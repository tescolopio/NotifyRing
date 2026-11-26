# Keep Android components declared in AndroidManifest.xml
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Service
-keep public class * extends android.service.notification.NotificationListenerService

# Keep AndroidX Core Telecom interfaces and callbacks
-keep class androidx.core.telecom.** { *; }
-dontwarn androidx.core.telecom.**

# Keep DataStore Preferences serialization helpers
-keep class androidx.datastore.preferences.** { *; }

# Strip verbose and debug logging in release builds for zero-leakage privacy
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
