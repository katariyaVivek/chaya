# Keep WebView JavaScript interface methods
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Keep Room entities
-keep class com.chaya.app.database.** { *; }

# Called from Python (Chaquopy looks it up by name): yt-dlp's YouTube challenge solver runs through it.
-keep class com.chaya.app.platform.JsSolver { *; }

# whisper.cpp's bridge (src/main/cpp/chaya_whisper.c) calls back into Kotlin by name: R8 renaming
# Whisper.Listener.heard made "Make subtitles" close the app in release builds (NoSuchMethodError).
# NativeCallbacksTest checks that every method the C code looks up is kept here.
-keep interface com.chaya.app.speech.Whisper$Listener {
    void heard(int);
}
-keepclasseswithmembernames class com.chaya.app.speech.Whisper {
    native <methods>;
}

# sherpa-onnx's JNI library finds its Kotlin classes and reads their fields by name (src/main/java/com/k2fsa/sherpa/onnx):
# kept whole, so R8 renames nothing it looks up.
-keep class com.k2fsa.sherpa.onnx.** { *; }
