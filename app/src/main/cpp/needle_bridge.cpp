// JNI bridge for the Cactus C++ engine (libneedle.a + needle.h).
//
// Real API (see downloaded needle.h):
//   needle_load(cact_bytes, n)      per .cact (buffers must stay alive)
//   needle_init(sys, tools_json, idx)
//   needle_set_audio(lang, kw, ts) / needle_complete(input, pcm, n, max, out, cap)
//   needle_transcribe(pcm, n, lang, kw, ts, out, cap)   (JSON via out buffer)
// Results cross JNI as UTF-8 byte[] and are parsed with org.json in Kotlin.
//
// Stub builds (ABI without a prebuilt engine): SPARK_STUB is defined by
// CMake; every entry returns failure so NeedleBridge reports
// nativeAvailable=false and VoiceService refuses to start.

#include <jni.h>

#include <cstdio>
#include <string>
#include <vector>

#ifndef SPARK_STUB
#include "needle.h"

namespace {

std::vector<unsigned char> g_whistle_cact;
std::vector<unsigned char> g_needle_cact;

bool readFile(const char *path, std::vector<unsigned char> &out) {
    FILE *f = std::fopen(path, "rb");
    if (!f) return false;
    std::fseek(f, 0, SEEK_END);
    long n = std::ftell(f);
    std::fseek(f, 0, SEEK_SET);
    if (n <= 0) {
        std::fclose(f);
        return false;
    }
    out.resize((size_t)n);
    size_t got = std::fread(out.data(), 1, (size_t)n, f);
    std::fclose(f);
    return got == (size_t)n;
}

jbyteArray toByteArray(JNIEnv *env, const char *data, int cap) {
    int len = 0;
    while (len < cap && data[len] != '\0') len++;
    jbyteArray arr = env->NewByteArray(len);
    if (arr && len > 0) env->SetByteArrayRegion(arr, 0, len, (const jbyte *)data);
    return arr;
}

}  // namespace
#endif  // SPARK_STUB

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_spark_assistant_engine_NeedleBridge_nativeLoad(JNIEnv *env, jobject /*thiz*/,
                                                     jstring whistlePath, jstring needlePath,
                                                     jstring toolsJson) {
#ifdef SPARK_STUB
    (void)env; (void)whistlePath; (void)needlePath; (void)toolsJson;
    return JNI_FALSE;
#else
    const char *w = env->GetStringUTFChars(whistlePath, nullptr);
    const char *n = env->GetStringUTFChars(needlePath, nullptr);
    const char *t = env->GetStringUTFChars(toolsJson, nullptr);
    bool ok = false;
    if (w && n && t && readFile(w, g_whistle_cact) && readFile(n, g_needle_cact)) {
        // Buffers stay alive in globals for the process lifetime (engine keeps them).
        if (needle_load(g_whistle_cact.data(), (unsigned long long)g_whistle_cact.size()) >= 0 &&
            needle_load(g_needle_cact.data(), (unsigned long long)g_needle_cact.size()) >= 0 &&
            (needle_models() & (NEEDLE_TEXT | NEEDLE_SPEECH)) == (NEEDLE_TEXT | NEEDLE_SPEECH) &&
            needle_init(nullptr, t, nullptr) >= 0) {
            ok = true;
        }
    }
    if (w) env->ReleaseStringUTFChars(whistlePath, w);
    if (n) env->ReleaseStringUTFChars(needlePath, n);
    if (t) env->ReleaseStringUTFChars(toolsJson, t);
    return ok ? JNI_TRUE : JNI_FALSE;
#endif
}

JNIEXPORT jbyteArray JNICALL
Java_com_spark_assistant_engine_NeedleBridge_nativeCompleteAudio(JNIEnv *env, jobject /*thiz*/,
                                                              jfloatArray samples, jstring language,
                                                              jstring keywords) {
#ifdef SPARK_STUB
    (void)env; (void)samples; (void)language; (void)keywords;
    return nullptr;
#else
    jsize len = env->GetArrayLength(samples);
    std::vector<float> pcm((size_t)len);
    env->GetFloatArrayRegion(samples, 0, len, pcm.data());
    const char *lang = language ? env->GetStringUTFChars(language, nullptr) : nullptr;
    // Newline-separated keyword biasing (see needle_set_audio).
    const char *kw = keywords ? env->GetStringUTFChars(keywords, nullptr) : nullptr;

    needle_set_audio(lang, kw, 0);
    std::string out(256 * 1024, '\0');
    int rc = needle_complete(nullptr, pcm.data(), (int)pcm.size(), 512, out.data(),
                             (int)out.size());
    if (language) env->ReleaseStringUTFChars(language, lang);
    if (keywords) env->ReleaseStringUTFChars(keywords, kw);
    if (rc < 0) return nullptr;
    return toByteArray(env, out.c_str(), (int)out.size());
#endif
}

JNIEXPORT jbyteArray JNICALL
Java_com_spark_assistant_engine_NeedleBridge_nativeTranscribe(JNIEnv *env, jobject /*thiz*/,
                                                           jfloatArray samples, jstring language,
                                                           jstring keywords) {
#ifdef SPARK_STUB
    (void)env; (void)samples; (void)language; (void)keywords;
    return nullptr;
#else
    jsize len = env->GetArrayLength(samples);
    std::vector<float> pcm((size_t)len);
    env->GetFloatArrayRegion(samples, 0, len, pcm.data());
    const char *lang = language ? env->GetStringUTFChars(language, nullptr) : nullptr;
    const char *kw = keywords ? env->GetStringUTFChars(keywords, nullptr) : nullptr;

    std::string out(64 * 1024, '\0');
    int rc = needle_transcribe(pcm.data(), (int)pcm.size(), lang, kw, 0, out.data(),
                               (int)out.size());
    if (language) env->ReleaseStringUTFChars(language, lang);
    if (keywords) env->ReleaseStringUTFChars(keywords, kw);
    if (rc < 0) return nullptr;
    return toByteArray(env, out.c_str(), (int)out.size());
#endif
}

}  // extern "C"
