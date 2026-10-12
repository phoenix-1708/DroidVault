package com.phoenix.droidvault.models

/**
 * Lines the transfer engine (`engine/src/transfer.rs`) prints while it works.
 *
 * The app backup engine speaks `ACTION:BACKUP_START` and friends; the transfer
 * engine speaks its own verbs because the work is different — byte progress
 * through one archive rather than per-app part states. Parsed out of
 * [EngineEvent.RawLog] by the collector, so a line the engine never sent can
 * never move the UI.
 *
 * Pure, so the contract is pinned by tests rather than by hoping both sides
 * spell the keys the same way. A field the engine stops sending reads as null
 * here, never as a crash mid-run.
 */
sealed interface EngineTransferEvent {
    data class Progress(val op: String, val done: Long, val total: Long, val pct: Int) :
        EngineTransferEvent

    data class FilePlaced(val name: String, val state: String) : EngineTransferEvent

    data class ExportDone(val path: String, val bytes: Long) : EngineTransferEvent

    data class ExtractDone(val bytes: Long) : EngineTransferEvent

    data class PlaceDone(val added: Long, val kept: Long, val failed: Long) : EngineTransferEvent

    data class SplitDone(val parts: Long, val bytes: Long) : EngineTransferEvent

    data class SurveyDone(val bytes: Long) : EngineTransferEvent
}

object TransferEngine {

    /**
     * Parse one engine stdout line. Null for anything that is not a transfer
     * line — the collector ignores those, because transfer output shares the
     * stream with nothing else but must still never choke on it.
     */
    fun parse(line: String): EngineTransferEvent? {
        if (!line.startsWith("ACTION:TRANSFER")) return null
        val fields = line.split('|').mapNotNull { segment ->
            val idx = segment.indexOf(':')
            if (idx <= 0) null
            else segment.substring(0, idx) to segment.substring(idx + 1)
        }.toMap()
        return when (fields["ACTION"]) {
            "TRANSFER" -> EngineTransferEvent.Progress(
                op = fields["OP"] ?: return null,
                done = fields["DONE"]?.toLongOrNull() ?: return null,
                total = fields["TOTAL"]?.toLongOrNull() ?: return null,
                pct = fields["PCT"]?.toIntOrNull() ?: return null
            )
            "TRANSFER_FILE" -> EngineTransferEvent.FilePlaced(
                name = fields["NAME"] ?: return null,
                state = fields["STATE"] ?: return null
            )
            "TRANSFER_DONE" -> when (fields["OP"]) {
                "EXPORT" -> EngineTransferEvent.ExportDone(
                    path = fields["PATH"] ?: return null,
                    bytes = fields["BYTES"]?.toLongOrNull() ?: return null
                )
                "EXTRACT" -> EngineTransferEvent.ExtractDone(
                    bytes = fields["BYTES"]?.toLongOrNull() ?: return null
                )
                "PLACE" -> EngineTransferEvent.PlaceDone(
                    added = fields["ADDED"]?.toLongOrNull() ?: return null,
                    kept = fields["KEPT"]?.toLongOrNull() ?: return null,
                    failed = fields["FAILED"]?.toLongOrNull() ?: return null
                )
                "SPLIT" -> EngineTransferEvent.SplitDone(
                    parts = fields["PARTS"]?.toLongOrNull() ?: return null,
                    bytes = fields["BYTES"]?.toLongOrNull() ?: return null
                )
                "SURVEY" -> EngineTransferEvent.SurveyDone(
                    bytes = fields["BYTES"]?.toLongOrNull() ?: return null
                )
                else -> null
            }
            else -> null
        }
    }
}
