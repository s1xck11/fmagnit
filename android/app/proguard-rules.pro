# Оставляем классы BRouter
-keep class btools.** { *; }
-keep class crosby.binary.** { *; }

# Оставляем классы OSMDroid
-keep class org.osmdroid.** { *; }
-dontwarn org.osmdroid.**

# Сохраняем имена методов, которые могут вызываться через рефлексию
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
