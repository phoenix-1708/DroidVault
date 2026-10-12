# Droid Vault R8 rules — minimal keeps for root/installer/SMS paths
-keep class com.topjohnwu.superuser.** { *; }

# CRITICAL (verified device crash 2026-09-11): WorkManager opens its Room
# database reflectively at app start (androidx.startup -> WorkManagerInitializer
# -> Class.forName("...WorkDatabase_Impl").getDeclaredConstructor()). AGP 9
# full-mode R8 stripped the generated _Impl constructor despite the AAR's
# consumer rules, killing the app before any UI. Keep every RoomDatabase impl
# constructor, plus the reflection-driven startup and work internals.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep class androidx.startup.** { *; }
-keep class androidx.work.** { *; }

# Manifest-referenced entry points: workers are constructed reflectively by
# WorkManager; activities/receivers/services by the system. Without keeps R8
# may rename or strip classes that only appear by name in the manifest when
# shrinker analysis misses reflection paths (verified missing for workers).
-keep class com.phoenix.droidvault.utils.ConfigRunWorker { *; }
-keep class com.phoenix.droidvault.utils.RunCancelReceiver { *; }
-keep class com.phoenix.droidvault.utils.SmsHandbackReceiver { *; }
-keep class com.phoenix.droidvault.ScheduleTriggerActivity { *; }
-keep class com.phoenix.droidvault.ExternalInstallerActivity { *; }
-keep class com.phoenix.droidvault.utils.MessagesHandlersKt { *; }
-keep class com.phoenix.droidvault.utils.SmsReceiver { *; }
-keep class com.phoenix.droidvault.utils.MmsReceiver { *; }
-keep class com.phoenix.droidvault.utils.ComposeSmsActivity { *; }
-keep class com.phoenix.droidvault.utils.HeadlessSmsSendService { *; }
-keep class coil.** { *; }
-keep class androidx.compose.** { *; }
