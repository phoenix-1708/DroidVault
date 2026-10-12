package com.phoenix.droidvault.utils

import android.app.Activity
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.telephony.SmsMessage
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

/**
 * Default-SMS-app receivers. Droid Vault holds the role only for the duration
 * of a Messages restore — during that window the platform delivers EVERY
 * incoming SMS/MMS to THIS process first, and whatever we fail to persist
 * here is lost to the system for good (the real messaging app is no longer
 * the default and never sees the broadcast).
 *
 * Contract: one content-provider call per message, issued as a single root
 * shell invocation, because this process can be killed mid-restore by the
 * role-permission revocation ("permissions revoked" — verified on device).
 * The su daemon completes the insert even if we die. DO NOT touch the UI
 * from here; a receiver crash makes the platform fall back to its own
 * persistence path, risking duplicates once the role is handed back.
 *
 * Persistence runs off the main thread via goAsync (audit A-13): a cold su
 * session can blow the synchronous receiver budget. `abortBroadcast()` is
 * deliberately NOT called (audit A-03): only the default SMS app ever
 * receives SMS_DELIVER, so aborting can only prevent our own fallback path —
 * if the insert failed, aborting would drop the message entirely.
 */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != android.provider.Telephony.Sms.Intents.SMS_DELIVER_ACTION) return
        // Parse while the platform still owns the intent extras.
        val parsed = try {
            parseIncomingSms(intent)
        } catch (e: Exception) {
            android.util.Log.w("SmsReceiver", "SMS parse failed (message at risk): ${e.message}")
            null
        } ?: return
        DroidVaultLogger.i("Sms", "Incoming SMS received — persisting")
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                persistIncomingSms(parsed.address, parsed.body, parsed.timestamp, parsed.subId)
            } catch (e: Exception) {
                android.util.Log.w("SmsReceiver", "SMS persistence failed (message at risk): ${e.message}")
                DroidVaultLogger.w("Sms", "SMS persistence failed — message at risk")
            } finally {
                pending.finish()
            }
        }
    }
}

private class ParsedSms(
    val address: String,
    val body: String,
    val timestamp: Long,
    val subId: Int
)

/** Multi-part SMS arrives as several PDUs reassembled into one logical message. */
private fun parseIncomingSms(intent: Intent): ParsedSms? {
    val messages: Array<SmsMessage?>? =
        android.provider.Telephony.Sms.Intents.getMessagesFromIntent(intent)
    if (messages.isNullOrEmpty()) return null
    val fullBody = messages.mapNotNull { it?.messageBody }.joinToString("")
    val address = messages.firstNotNullOfOrNull { it?.originatingAddress }
    val timestamp = messages.firstNotNullOfOrNull { sms ->
        sms?.timestampMillis?.takeIf { it > 0 }
    } ?: System.currentTimeMillis()
    if (address.isNullOrEmpty() || fullBody.isEmpty()) return null
    // Platform extra for the delivering SIM (pre-31 name is "subscription").
    val subId = intent.getIntExtra(
        "android.telephony.extra.SUBSCRIPTION_INDEX",
        intent.getIntExtra("subscription", -1)
    )
    return ParsedSms(address, fullBody, timestamp, subId)
}

class MmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != android.provider.Telephony.Sms.Intents.WAP_PUSH_DELIVER_ACTION) return
        // MMS PDU reassembly/persistence needs the telephony provider's own
        // pipeline, which is fragile to re-implement here. Park the raw PDU
        // under /data/local/tmp so nothing is silently destroyed; the
        // pending-handoff heal or the user's messaging app picks it up
        // after the role returns.
        val pdu = try {
            @Suppress("DEPRECATION")
            (intent.getSerializableExtra("pdus") as? Array<*>)?.firstOrNull() as? ByteArray
        } catch (e: Exception) {
            android.util.Log.w("MmsReceiver", "MMS parse failed (message at risk): ${e.message}")
            null
        } ?: return
        DroidVaultLogger.i("Sms", "Incoming MMS received — parking raw PDU")
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                parkMms(context, pdu)
            } catch (e: Exception) {
                android.util.Log.w("MmsReceiver", "MMS parking failed (message at risk): ${e.message}")
            } finally {
                pending.finish()
            }
        }
    }
}

/**
 * Park one raw MMS PDU under /data/local/tmp through the root shell.
 *
 * The previous implementation wrote with java.io into a root-owned 0771 dir:
 * the app uid lands in "other" and the write could never succeed, so every
 * MMS received during the restore window was destroyed (audit A-03). The
 * app-owned cache write always works; the root shell does the privileged
 * copy — and it completes even if this process is killed mid-restore.
 */
private fun parkMms(context: Context, pdu: ByteArray): Boolean {
    val dir = "/data/local/tmp/droidvault_incoming_mms"
    val name = "mms_${System.currentTimeMillis()}.bin"
    val cacheFile = File(context.cacheDir, name)
    cacheFile.outputStream().use { stream -> stream.write(pdu) }
    val res = try {
        Shell.cmd(
            "mkdir -p ${ShellQuote.q(dir)} && chmod 771 ${ShellQuote.q(dir)} && " +
                "cp ${ShellQuote.q(cacheFile.absolutePath)} ${ShellQuote.q("$dir/$name")} && " +
                "chmod 600 ${ShellQuote.q("$dir/$name")}"
        ).exec()
    } finally {
        cacheFile.delete()
    }
    if (!res.isSuccess) {
        android.util.Log.w("MmsReceiver", "root parking failed: ${res.err.joinToString(" ")}")
        DroidVaultLogger.w("Sms", "MMS root parking failed — heal path will retry")
    }
    return res.isSuccess
}

/**
 * Insert one incoming SMS via a single root `content insert` call: the su
 * daemon completes it even if this process is killed by the role revocation
 * mid-restore, so the message lands in telephony's provider no matter what.
 * type=1 (MESSAGE_TYPE_INBOX), read/seen=0 → the messaging app shows it as a
 * fresh unread message after the role is handed back. Returns true when the
 * insert succeeded.
 */
private fun persistIncomingSms(address: String, body: String, timestamp: Long, subscribedId: Int): Boolean {
    val sub = if (subscribedId > 0) subscribedId.toString() else "-1"
    val escapedAddress = address.replace("'", "'\\''")
    val escapedBody = body.replace("'", "'\\''")
    val cmd = "content insert --uri content://sms/inbox" +
        " --bind address:s:'$escapedAddress'" +
        " --bind body:s:'$escapedBody'" +
        " --bind date:l:$timestamp" +
        " --bind type:i:1" +
        " --bind read:i:0" +
        " --bind seen:i:0" +
        " --bind sub_id:i:$sub"
    val res = Shell.cmd(cmd).exec()
    if (!res.isSuccess) {
        android.util.Log.w("SmsReceiver", "content insert failed: ${res.err.joinToString(" ")}")
    }
    return res.isSuccess
}

class ComposeSmsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        finish()
    }
}

class HeadlessSmsSendService : Service() {
    override fun onBind(intent: Intent): IBinder? {
        return null
    }
}
