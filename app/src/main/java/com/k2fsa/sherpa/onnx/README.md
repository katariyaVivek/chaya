# sherpa-onnx's Kotlin API (vendored)

These files are sherpa-onnx's own (`sherpa-onnx/kotlin-api/`, tag `v1.12.21`, commit
`a9ff1700dc1604fce987386b1b5b5523b04d454e`, https://github.com/k2-fsa/sherpa-onnx), copied unchanged (but for one `@file:Suppress("UNCHECKED_CAST")` line at the top of `OfflineRecognizer.kt`, for its own
cast of the JNI result, as this project fails on warnings), under the
Apache License 2.0. Its native library (`libsherpa-onnx-jni.so`, fetched by `app/build.gradle.kts`'s
`fetchSherpaOnnx` from the same release, pinned by SHA-256) finds these classes and reads their fields **by name**, so
they must stay exactly as released and are kept whole from R8 (`app/proguard-rules.pro`). Upgrade them together with
the library: copy the new release's files here and change the pins in `app/build.gradle.kts`.

Chaya uses only the offline recognizer (`OfflineRecognizer`, `OfflineStream` and the configs they need), for
*Make subtitles* with NVIDIA's Parakeet TDT 0.6B v3 (`com.chaya.app.speech.Parakeet`).
