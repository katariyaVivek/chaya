# Keep WebView JavaScript interface methods
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Keep Room entities
-keep class com.chaya.app.database.** { *; }

# Called from Python (Chaquopy looks it up by name): yt-dlp's YouTube challenge solver runs through it.
-keep class com.chaya.app.platform.JsSolver { *; }
