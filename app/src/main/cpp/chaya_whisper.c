// The bridge between Kotlin (com.chaya.app.speech.Whisper) and whisper.cpp: listens to 16 kHz mono samples with a
// model file and answers with the JSON whisper-cli writes with -ojf (its "result" and "transcription"), so the phone
// reads it with the same rules, and the same recorded tests, as Chaya for Windows. Word times come from DTW, as on
// Windows (-dtw), which needs flash attention off. One transcription at a time; Whisper.stop() ends it early.

#include <jni.h>
#include <math.h>
#include <stdatomic.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "whisper.h"

static atomic_bool stopping = false;

// A growing buffer of bytes.
typedef struct {
    char *bytes;
    size_t length;
    size_t room;
    int failed;
} Out;

static void put(Out *out, const char *text, size_t n) {
    if (out->failed) return;
    if (out->length + n + 1 > out->room) {
        size_t room = out->room ? out->room * 2 : 65536;
        while (room < out->length + n + 1) room *= 2;
        char *bigger = realloc(out->bytes, room);
        if (!bigger) {
            out->failed = 1;
            return;
        }
        out->bytes = bigger;
        out->room = room;
    }
    memcpy(out->bytes + out->length, text, n);
    out->length += n;
    out->bytes[out->length] = 0;
}

static void puts_(Out *out, const char *text) { put(out, text, strlen(text)); }

static void putf(Out *out, const char *format, long long a, long long b) {
    char buffer[96];
    int n = snprintf(buffer, sizeof buffer, format, a, b);
    if (n > 0) put(out, buffer, (size_t) n);
}

// A JSON string: quotes, backslashes and control characters escaped; other bytes as they are (UTF-8).
static void put_string(Out *out, const char *text) {
    puts_(out, "\"");
    for (const unsigned char *c = (const unsigned char *) text; c && *c; c++) {
        if (*c == '"') puts_(out, "\\\"");
        else if (*c == '\\') puts_(out, "\\\\");
        else if (*c < 0x20) {
            char escaped[8];
            snprintf(escaped, sizeof escaped, "\\u%04x", *c);
            puts_(out, escaped);
        } else put(out, (const char *) c, 1);
    }
    puts_(out, "\"");
}

static void put_number(Out *out, float value) {
    if (!isfinite(value)) {
        puts_(out, "null");
        return;
    }
    char buffer[32];
    int n = snprintf(buffer, sizeof buffer, "%.4f", value);
    if (n > 0) put(out, buffer, (size_t) n);
}

typedef struct {
    JNIEnv *env;
    jobject listener;
    jmethodID heard;
} Progress;

static void on_progress(struct whisper_context *ctx, struct whisper_state *state, int progress, void *data) {
    (void) ctx;
    (void) state;
    Progress *p = data;
    if (p->listener) (*p->env)->CallVoidMethod(p->env, p->listener, p->heard, (jint) progress);
}

static bool on_abort(void *data) {
    (void) data;
    return atomic_load(&stopping);
}

static enum whisper_alignment_heads_preset heads(const char *dtw) {
    if (strcmp(dtw, "tiny") == 0) return WHISPER_AHEADS_TINY;
    if (strcmp(dtw, "base") == 0) return WHISPER_AHEADS_BASE;
    if (strcmp(dtw, "small") == 0) return WHISPER_AHEADS_SMALL;
    return WHISPER_AHEADS_NONE;
}

JNIEXPORT void JNICALL Java_com_chaya_app_speech_Whisper_stopNative(JNIEnv *env, jclass type) {
    (void) env;
    (void) type;
    atomic_store(&stopping, true);
}

// The JSON for what was heard in samples (16 kHz mono), as UTF-8 bytes; null when the model would not load or
// listening failed or was stopped.
JNIEXPORT jbyteArray JNICALL Java_com_chaya_app_speech_Whisper_transcribeNative(
    JNIEnv *env, jclass type, jstring model_path, jstring dtw_preset, jfloatArray samples, jstring language,
    jint threads, jobject listener) {
    (void) type;
    atomic_store(&stopping, false);
    const char *path = (*env)->GetStringUTFChars(env, model_path, NULL);
    const char *dtw = (*env)->GetStringUTFChars(env, dtw_preset, NULL);
    struct whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;
    cparams.flash_attn = false;
    cparams.dtw_aheads_preset = heads(dtw);
    cparams.dtw_token_timestamps = cparams.dtw_aheads_preset != WHISPER_AHEADS_NONE;
    struct whisper_context *ctx = whisper_init_from_file_with_params(path, cparams);
    (*env)->ReleaseStringUTFChars(env, dtw_preset, dtw);
    (*env)->ReleaseStringUTFChars(env, model_path, path);
    if (!ctx) return NULL;

    const char *lang = language ? (*env)->GetStringUTFChars(env, language, NULL) : NULL;
    struct whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads = threads > 0 ? threads : 4;
    params.language = lang ? lang : "auto";
    params.translate = false;
    params.token_timestamps = true;
    params.print_progress = false;
    params.print_realtime = false;
    params.print_timestamps = false;
    params.print_special = false;
    Progress progress = {env, listener, NULL};
    if (listener) {
        jclass listener_class = (*env)->GetObjectClass(env, listener);
        progress.heard = (*env)->GetMethodID(env, listener_class, "heard", "(I)V");
        if (!progress.heard) progress.listener = NULL;
    }
    params.progress_callback = on_progress;
    params.progress_callback_user_data = &progress;
    params.abort_callback = on_abort;

    jsize count = (*env)->GetArrayLength(env, samples);
    jfloat *pcm = (*env)->GetFloatArrayElements(env, samples, NULL);
    int failed = whisper_full(ctx, params, pcm, count);
    (*env)->ReleaseFloatArrayElements(env, samples, pcm, JNI_ABORT);
    if (lang) (*env)->ReleaseStringUTFChars(env, language, lang);
    if (failed || atomic_load(&stopping)) {
        whisper_free(ctx);
        return NULL;
    }

    Out out = {0};
    puts_(&out, "{\"result\": {\"language\": ");
    put_string(&out, whisper_lang_str(whisper_full_lang_id(ctx)));
    puts_(&out, "}, \"transcription\": [");
    int segments = whisper_full_n_segments(ctx);
    for (int i = 0; i < segments; i++) {
        if (i) puts_(&out, ",");
        // whisper.cpp counts in hundredths of a second
        putf(&out, "{\"offsets\": {\"from\": %lld, \"to\": %lld}, \"text\": ",
             (long long) whisper_full_get_segment_t0(ctx, i) * 10, (long long) whisper_full_get_segment_t1(ctx, i) * 10);
        put_string(&out, whisper_full_get_segment_text(ctx, i));
        puts_(&out, ", \"tokens\": [");
        int tokens = whisper_full_n_tokens(ctx, i);
        for (int j = 0; j < tokens; j++) {
            whisper_token_data token = whisper_full_get_token_data(ctx, i, j);
            if (j) puts_(&out, ",");
            puts_(&out, "{\"text\": ");
            put_string(&out, whisper_full_get_token_text(ctx, i, j));
            putf(&out, ", \"offsets\": {\"from\": %lld, \"to\": %lld}", (long long) token.t0 * 10, (long long) token.t1 * 10);
            putf(&out, ", \"id\": %lld, \"t_dtw\": %lld, \"p\": ", (long long) token.id, (long long) token.t_dtw);
            put_number(&out, token.p);
            puts_(&out, "}");
        }
        puts_(&out, "]}");
    }
    puts_(&out, "]}");
    whisper_free(ctx);
    if (out.failed || !out.bytes) {
        free(out.bytes);
        return NULL;
    }
    jbyteArray json = (*env)->NewByteArray(env, (jsize) out.length);
    if (json) (*env)->SetByteArrayRegion(env, json, 0, (jsize) out.length, (const jbyte *) out.bytes);
    free(out.bytes);
    return json;
}
