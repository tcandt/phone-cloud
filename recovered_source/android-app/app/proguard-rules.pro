# Shizuku rules
-keep class rikka.shizuku.** { *; }
-keep interface rikka.shizuku.** { *; }
-keep class dev.rikka.shizuku.** { *; }

# Gson rules
-keepattributes Signature
-keepattributes *Annotation*
-dontwarn sun.misc.**
-keep class com.google.gson.** { *; }
-keep class com.cloudphone.app.model.** { *; }

# OkHttp rules
-dontwarn okhttp3.**
-dontwarn okio.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# Coroutines rules
-keepclassmembers class kotlinx.coroutines.internal.MainDispatcherFactory {
    public <init>();
}
-keepclassmembers class kotlinx.coroutines.CoroutineExceptionHandler {
    public <init>();
}
