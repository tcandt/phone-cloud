# Keep main entry points and wrappers invoked via reflection
-keep class com.android.helper.CoreService {
    public static void main(java.lang.String[]);
}
-keep class com.android.helper.Options { *; }
-keep class com.android.helper.wrappers.** { *; }
-keep class com.android.helper.control.** { *; }
-keep class com.android.helper.device.** { *; }
-keep class com.android.helper.video.** { *; }
-keep class android.** { *; }
