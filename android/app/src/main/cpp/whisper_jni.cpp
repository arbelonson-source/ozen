#include <jni.h>

#include <cmath>
#include <string>

#include "whisper.h"

namespace {

whisper_context *context(jlong handle) {
    return reinterpret_cast<whisper_context *>(handle);
}

std::string text(JNIEnv *env, jstring value) {
    if (value == nullptr) return "";
    const char *chars = env->GetStringUTFChars(value, nullptr);
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_arbelonson_ozen_whisper_WhisperCpp_load(JNIEnv *env, jobject, jstring path) {
    whisper_context_params params = whisper_context_default_params();
    params.use_gpu = false;
    return reinterpret_cast<jlong>(whisper_init_from_file_with_params(text(env, path).c_str(), params));
}

extern "C" JNIEXPORT void JNICALL
Java_com_arbelonson_ozen_whisper_WhisperCpp_free(JNIEnv *, jobject, jlong handle) {
    whisper_free(context(handle));
}

extern "C" JNIEXPORT jint JNICALL
Java_com_arbelonson_ozen_whisper_WhisperCpp_transcribe(
        JNIEnv *env, jobject, jlong handle, jfloatArray audio, jstring language, jstring prompt,
        jint threads, jint beam, jboolean finished) {
    whisper_full_params params = whisper_full_default_params(
            beam > 1 ? WHISPER_SAMPLING_BEAM_SEARCH : WHISPER_SAMPLING_GREEDY);
    std::string languageCode = text(env, language);
    std::string promptText = text(env, prompt);
    params.language = languageCode.c_str();
    params.initial_prompt = promptText.empty() ? nullptr : promptText.c_str();
    params.n_threads = threads;
    params.beam_search.beam_size = beam;
    params.no_context = true;
    params.no_timestamps = true;
    params.print_progress = false;
    params.print_realtime = false;
    params.print_special = false;
    params.print_timestamps = false;
    params.temperature = 0.0f;
    params.temperature_inc = finished ? 0.2f : 0.0f;
    params.logprob_thold = -1.0f;
    params.no_speech_thold = 0.6f;

    jsize count = env->GetArrayLength(audio);
    jfloat *samples = env->GetFloatArrayElements(audio, nullptr);
    int result = whisper_full(context(handle), params, samples, count);
    env->ReleaseFloatArrayElements(audio, samples, JNI_ABORT);
    return result == 0 ? whisper_full_n_segments(context(handle)) : -1;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_arbelonson_ozen_whisper_WhisperCpp_segmentText(JNIEnv *env, jobject, jlong handle, jint segment) {
    const char *value = whisper_full_get_segment_text(context(handle), segment);
    jsize length = static_cast<jsize>(std::char_traits<char>::length(value));
    jbyteArray bytes = env->NewByteArray(length);
    env->SetByteArrayRegion(bytes, 0, length, reinterpret_cast<const jbyte *>(value));
    return bytes;
}

extern "C" JNIEXPORT jfloat JNICALL
Java_com_arbelonson_ozen_whisper_WhisperCpp_segmentNoSpeech(JNIEnv *, jobject, jlong handle, jint segment) {
    return whisper_full_get_segment_no_speech_prob(context(handle), segment);
}

extern "C" JNIEXPORT jfloat JNICALL
Java_com_arbelonson_ozen_whisper_WhisperCpp_segmentLogprob(JNIEnv *, jobject, jlong handle, jint segment) {
    whisper_context *ctx = context(handle);
    int tokens = whisper_full_n_tokens(ctx, segment);
    double sum = 0;
    int counted = 0;
    for (int i = 0; i < tokens; ++i) {
        whisper_token_data data = whisper_full_get_token_data(ctx, segment, i);
        if (data.id >= whisper_token_eot(ctx)) continue;
        sum += std::log(std::fmax(data.p, 1e-10f));
        ++counted;
    }
    return counted == 0 ? 0.0f : static_cast<jfloat>(sum / counted);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_arbelonson_ozen_whisper_WhisperCpp_systemInfo(JNIEnv *env, jobject) {
    return env->NewStringUTF(whisper_print_system_info());
}
