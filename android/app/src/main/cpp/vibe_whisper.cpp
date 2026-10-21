// Puente JNI entre Vibe IPTV y whisper.cpp (https://github.com/ggml-org/whisper.cpp, MIT).
// Sólo se compila con Whisper en arm64-v8a; en las demás arquitecturas queda un sustituto que responde "no disponible".
#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>
#ifndef VIBE_NO_WHISPER
#include "whisper.h"
#endif

#define TAG "VibeWhisper"

extern "C" {

JNIEXPORT jboolean JNICALL
Java_cl_iptv_player_data_WhisperNative_supported(JNIEnv *, jclass) {
#ifdef VIBE_NO_WHISPER
    return JNI_FALSE;
#else
    return JNI_TRUE;
#endif
}

JNIEXPORT jlong JNICALL
Java_cl_iptv_player_data_WhisperNative_init(JNIEnv *env, jclass, jstring path) {
#ifdef VIBE_NO_WHISPER
    return 0;
#else
    const char *p = env->GetStringUTFChars(path, nullptr);
    whisper_context_params cp = whisper_context_default_params();
    cp.use_gpu = false;
    cp.flash_attn = true;
    whisper_context *ctx = whisper_init_from_file_with_params(p, cp);
    env->ReleaseStringUTFChars(path, p);
    if (!ctx) __android_log_print(ANDROID_LOG_ERROR, TAG, "no se pudo cargar el modelo");
    return reinterpret_cast<jlong>(ctx);
#endif
}

JNIEXPORT void JNICALL
Java_cl_iptv_player_data_WhisperNative_free(JNIEnv *, jclass, jlong ctx) {
#ifndef VIBE_NO_WHISPER
    if (ctx) whisper_free(reinterpret_cast<whisper_context *>(ctx));
#endif
}

/**
 * Transcribe audio de 16 kHz mono (float, -1..1). Devuelve un texto por segmento con el formato
 * "inicio|fin|texto" (inicio y fin en centésimas de segundo desde el comienzo del trozo), o null si falló.
 */
JNIEXPORT jobjectArray JNICALL
Java_cl_iptv_player_data_WhisperNative_transcribe(JNIEnv *env, jclass, jlong ctxPtr, jfloatArray samples,
                                                  jstring lang, jint threads, jint audioCtx) {
#ifdef VIBE_NO_WHISPER
    return nullptr;
#else
    auto *ctx = reinterpret_cast<whisper_context *>(ctxPtr);
    if (!ctx) return nullptr;
    const char *l = env->GetStringUTFChars(lang, nullptr);
    std::string language(l);
    env->ReleaseStringUTFChars(lang, l);

    whisper_full_params wp = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    wp.print_realtime = false;
    wp.print_progress = false;
    wp.print_timestamps = false;
    wp.print_special = false;
    wp.translate = false;
    wp.language = language.c_str();
    wp.n_threads = threads;
    wp.no_context = true;         // sin el texto anterior como guía: evita que repita frases en bucle
    wp.single_segment = false;
    wp.no_timestamps = false;
    wp.suppress_blank = true;
    wp.suppress_nst = true;       // sin "[Música]", "(aplausos)", etc.
    wp.temperature = 0.0f;
    wp.temperature_inc = 0.0f;    // sin reintentos: más rápido y parejo
    wp.no_speech_thold = 0.6f;
    wp.audio_ctx = audioCtx;      // contexto acotado al largo del trozo: mucho más rápido en trozos cortos

    jsize n = env->GetArrayLength(samples);
    std::vector<float> pcm(n);
    env->GetFloatArrayRegion(samples, 0, n, pcm.data());
    if (whisper_full(ctx, wp, pcm.data(), (int) pcm.size()) != 0) return nullptr;

    const int segs = whisper_full_n_segments(ctx);
    jclass str = env->FindClass("java/lang/String");
    jobjectArray out = env->NewObjectArray(segs, str, nullptr);
    for (int i = 0; i < segs; i++) {
        std::string s = std::to_string(whisper_full_get_segment_t0(ctx, i)) + "|" +
                        std::to_string(whisper_full_get_segment_t1(ctx, i)) + "|" +
                        whisper_full_get_segment_text(ctx, i);
        jstring js = env->NewStringUTF(s.c_str());
        env->SetObjectArrayElement(out, i, js);
        env->DeleteLocalRef(js);
    }
    return out;
#endif
}

} // extern "C"
