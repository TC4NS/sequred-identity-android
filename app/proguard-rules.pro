# ─── UniFFI / JNA glue ───────────────────────────────────────────────────────
# UniFFI generates Kotlin types instantiated reflectively from JNA pointers.
# JNA itself resolves types by name. Strip either and the FFI throws
# ClassNotFoundException at first call.
-keep class uniffi.sequred_core.** { *; }
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.** { *; }
-keepclassmembers class * implements com.sun.jna.Callback { *; }
# JNA has optional java.awt fall-back classes for desktop. Android has no
# AWT, so dontwarn them — they're behind runtime feature-detect guards.
-dontwarn java.awt.**
-dontwarn com.sun.jna.Native$AWT

# ─── kotlinx.serialization ───────────────────────────────────────────────────
# kotlinx-serialization synthesises a companion `$serializer` for every
# @Serializable class and looks it up by name. R8 doesn't know about that
# convention.
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
    static **$$serializer INSTANCE;
}
-keepclasseswithmembers class **$$serializer {
    *;
}
-keepclassmembers class **$Companion {
    kotlinx.serialization.KSerializer serializer(...);
}
# Keep the data classes used in our wire format so reflection-based serdes
# (Apple JSON, base64, UUID custom serializers) keep working.
-keep,allowobfuscation,allowshrinking class kotlinx.serialization.** { *; }
-keep,includedescriptorclasses class co.sequred.identity.data.** { *; }

# ─── Coroutines ──────────────────────────────────────────────────────────────
# Service loaders for the main-thread dispatcher resolution.
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# ─── Splash screen ───────────────────────────────────────────────────────────
-keep class androidx.core.splashscreen.** { *; }

# ─── EncryptedSharedPreferences ──────────────────────────────────────────────
# Tink (under the hood) uses reflection for its keyset factory.
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**
-keep class * extends com.google.crypto.tink.proto.KeyData { *; }

# ─── BouncyCastle (transitive via biometric/security) ────────────────────────
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**

# ─── ZXing ───────────────────────────────────────────────────────────────────
-keep class com.journeyapps.barcodescanner.** { *; }
-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**

# ─── Compose ─────────────────────────────────────────────────────────────────
# R8 + Compose are generally well-aligned but the Composer can be stripped on
# aggressive shrinking. Keep symbols around for stack traces in crash reports.
-keepattributes Signature, *Annotation*, EnclosingMethod, InnerClasses
-keepclassmembers class androidx.compose.runtime.** { *; }

# ─── Strip Log.v / Log.d in release ──────────────────────────────────────────
# Audit L1: defensive stripping so accidental debug-only Log.d calls don't
# leak into release. Log.e/w/i preserved so crash diagnostics still surface.
-assumenosideeffects class android.util.Log {
    public static *** v(...);
    public static *** d(...);
}

# Keep generic crash-trace usefulness.
-keepattributes SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile
