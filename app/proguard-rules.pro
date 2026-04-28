# KumaCheck ProGuard / R8 rules.
# Keeps reflection-using internals (Socket.IO, Moshi) intact under R8.

# --- Socket.IO + Engine.IO ---
# CI2: scope the keep rules to the actual subpackages we link to
# (io.socket:socket.io-client:2.1.2 ships `client` and `engineio.client`),
# rather than blanket-keeping io.socket.**. The legacy
# `com.github.nkzawa.**` rule used to cover the pre-2.x package; since this
# project compiles only against the modern coordinate it is unreferenced
# and was preventing R8 from shrinking the package. The targeted rules
# below keep the reflectively-invoked Socket / IO / Ack / EngineIO public
# API while letting R8 strip implementation classes the app never names.
-keep class io.socket.client.** { *; }
-keep class io.socket.engineio.client.** { *; }
-dontwarn io.socket.**

# OkHttp / Okio (transitive via Socket.IO).
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# --- Moshi (codegen-generated *_JsonAdapter classes) ---
-keep class **JsonAdapter { *; }
-keepclasseswithmembers class * {
    @com.squareup.moshi.Json <fields>;
}
-keep class com.squareup.moshi.** { *; }
-dontwarn com.squareup.moshi.**

# --- Kotlin coroutines ---
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# --- Compose / AndroidX ---
-dontwarn androidx.compose.**

# --- KumaCheck data models ---
# Referenced by name in JSON parsing; keep their public shape.
-keep class app.kumacheck.data.model.** { *; }

# --- Strip debug/info/verbose Log calls in release builds ---
# Some of these print server responses or socket payloads; we want them gone
# in shipped APKs. R8 only applies these rules when minification is enabled
# (release), so debug builds keep the logs.
#
# `w` (warning) is intentionally NOT stripped: it's how degraded states surface
# in production (e.g. TokenCrypto's keystore-unavailable plaintext fallback).
# `e` (error) is also kept.
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** i(...);
    public static *** v(...);
}
