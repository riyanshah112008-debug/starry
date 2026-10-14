# Buddy — R8 / ProGuard rules for release builds.
# Keep Gson model classes (reflection-based serialization/deserialization).
-keep class com.example.buddy.ai.** { *; }
-keep class com.example.buddy.data.entity.** { *; }

# Keep the AccessibilityService entry point referenced only from the manifest.
-keep class com.example.buddy.AutoService { *; }
-keep class com.example.buddy.AiAgentApp { *; }

# Keep Gson internals that rely on generic signatures.
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken

# OkHttp / Okio
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Room generated code
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-dontwarn androidx.room.paging.**
