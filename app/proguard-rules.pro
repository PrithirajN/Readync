# Readync's R8 rules. R8 makes the release build faster and smaller; these keep what's reached by
# name or from native code.

# Keep names readable: crash reports (Settings › Send crash report) stay easy to follow.
-dontobfuscate
-keepattributes SourceFile,LineNumberTable,*Annotation*,Signature,InnerClasses,EnclosingMethod

# Read by name (Backup checks which edition it is).
-keep class io.github.prithirajn.readync.BuildConfig { *; }

# The on-device AI: native code calls back into these classes.
-keep class com.google.ai.edge.litertlm.** { *; }
-dontwarn com.google.ai.edge.litertlm.**

# Background work is created by name.
-keep class * extends androidx.work.ListenableWorker { <init>(android.content.Context, androidx.work.WorkerParameters); }

# Things only some phones or library versions have.
-dontwarn org.jetbrains.annotations.**
-dontwarn javax.annotation.**
-dontwarn kotlinx.coroutines.debug.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn org.slf4j.**
