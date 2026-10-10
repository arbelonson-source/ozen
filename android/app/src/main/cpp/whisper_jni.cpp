#include <jni.h>
#include <android/log.h>

#include <atomic>
#include <cmath>
#include <string>
#include <vector>

#include "ggml-backend.h"
#include "whisper.h"

namespace {

struct Model {
    whisper_context *context;
    std::atomic<bool> stop{false};
};

Model *model(jlong handle) {
    return reinterpret_cast<Model *>(handle);
}

whisper_context *context(jlong handle) {
    return model(handle)->context;
}

// Each pass starts listening for a stop of its own: one asked for after
// the last pass ended must not cut the next stream's first pass short.
void stop_when_asked(jlong handle, whisper_full_params &params) {
    model(handle)->stop = false;
    params.abort_callback = [](void *stop) { return static_cast<std::atomic<bool> *>(stop)->load(); };
    params.abort_callback_user_data = &model(handle)->stop;
}

std::string text(JNIEnv *env, jstring value) {
    if (value == nullptr) return "";
    const char *chars = env->GetStringUTFChars(value, nullptr);
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

void log_to_logcat(ggml_log_level level, const char *message, void *) {
    int priority = ANDROID_LOG_INFO;
    if (level == GGML_LOG_LEVEL_ERROR) priority = ANDROID_LOG_ERROR;
    else if (level == GGML_LOG_LEVEL_WARN) priority = ANDROID_LOG_WARN;
    else if (level == GGML_LOG_LEVEL_DEBUG) return;
    __android_log_print(priority, "OzenGgml", "%s", message);
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_arbelonson_ozen_whisper_WhisperCpp_load(JNIEnv *env, jobject, jstring path) {
    whisper_context_params params = whisper_context_default_params();
    params.use_gpu = false;
    whisper_context *loaded = whisper_init_from_file_with_params(text(env, path).c_str(), params);
    if (loaded == nullptr) return 0;
    return reinterpret_cast<jlong>(new Model{loaded});
}

extern "C" JNIEXPORT void JNICALL
Java_com_arbelonson_ozen_whisper_WhisperCpp_free(JNIEnv *, jobject, jlong handle) {
    whisper_free(context(handle));
    delete model(handle);
}

extern "C" JNIEXPORT void JNICALL
Java_com_arbelonson_ozen_whisper_WhisperCpp_abort(JNIEnv *, jobject, jlong handle) {
    model(handle)->stop = true;
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
    stop_when_asked(handle, params);

    jsize count = env->GetArrayLength(audio);
    jfloat *samples = env->GetFloatArrayElements(audio, nullptr);
    int result = whisper_full(context(handle), params, samples, count);
    env->ReleaseFloatArrayElements(audio, samples, JNI_ABORT);
    return result == 0 ? whisper_full_n_segments(context(handle)) : -1;
}

// One decode at one temperature, as WhisperKit makes each of its attempts:
// the retries at higher temperatures, and whether a stretch was silence,
// are decided in Kotlin from the tokens, so whisper.cpp's own are off.
extern "C" JNIEXPORT jint JNICALL
Java_com_arbelonson_ozen_whisper_WhisperCpp_pass(
        JNIEnv *env, jobject, jlong handle, jfloatArray audio, jstring language, jintArray prompt,
        jint maxTokens, jfloat temperature, jint threads, jboolean suppressBlank, jboolean noTimestamps,
        jint audioContext) {
    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    std::string languageCode = text(env, language);
    params.language = languageCode.c_str();
    params.n_threads = threads;
    params.no_context = true;
    params.no_timestamps = noTimestamps;
    params.single_segment = true;
    params.print_progress = false;
    params.print_realtime = false;
    params.print_special = false;
    params.print_timestamps = false;
    params.suppress_blank = suppressBlank;
    params.max_tokens = maxTokens;
    params.temperature = temperature;
    params.temperature_inc = 0.0f;
    params.greedy.best_of = 1;
    params.no_speech_thold = 1.0f;
    params.audio_ctx = audioContext;
    stop_when_asked(handle, params);

    std::vector<whisper_token> promptTokens;
    if (prompt != nullptr) {
        const jsize length = env->GetArrayLength(prompt);
        promptTokens.resize(length);
        env->GetIntArrayRegion(prompt, 0, length, reinterpret_cast<jint *>(promptTokens.data()));
        params.prompt_tokens = promptTokens.data();
        params.prompt_n_tokens = static_cast<int>(promptTokens.size());
    }

    jsize count = env->GetArrayLength(audio);
    jfloat *samples = env->GetFloatArrayElements(audio, nullptr);
    int result = whisper_full(context(handle), params, samples, count);
    env->ReleaseFloatArrayElements(audio, samples, JNI_ABORT);
    return result == 0 ? whisper_full_n_segments(context(handle)) : -1;
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_arbelonson_ozen_whisper_WhisperCpp_segmentTokenIds(JNIEnv *env, jobject, jlong handle, jint segment) {
    whisper_context *ctx = context(handle);
    const int count = whisper_full_n_tokens(ctx, segment);
    std::vector<jint> ids(count);
    for (int i = 0; i < count; ++i) ids[i] = whisper_full_get_token_id(ctx, segment, i);
    jintArray result = env->NewIntArray(count);
    env->SetIntArrayRegion(result, 0, count, ids.data());
    return result;
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_arbelonson_ozen_whisper_WhisperCpp_segmentTokenProbabilities(JNIEnv *env, jobject, jlong handle, jint segment) {
    whisper_context *ctx = context(handle);
    const int count = whisper_full_n_tokens(ctx, segment);
    std::vector<jfloat> chances(count);
    for (int i = 0; i < count; ++i) chances[i] = whisper_full_get_token_p(ctx, segment, i);
    jfloatArray result = env->NewFloatArray(count);
    env->SetFloatArrayRegion(result, 0, count, chances.data());
    return result;
}

// The token's own bytes: a Hebrew letter is two bytes and a token can end
// between them, so they are put back together before being read as text.
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_arbelonson_ozen_whisper_WhisperCpp_tokenPiece(JNIEnv *env, jobject, jlong handle, jint token) {
    const char *value = whisper_token_to_str(context(handle), token);
    if (value == nullptr) value = "";
    jsize length = static_cast<jsize>(std::char_traits<char>::length(value));
    jbyteArray bytes = env->NewByteArray(length);
    env->SetByteArrayRegion(bytes, 0, length, reinterpret_cast<const jbyte *>(value));
    return bytes;
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_arbelonson_ozen_whisper_WhisperCpp_tokenize(JNIEnv *env, jobject, jlong handle, jstring value) {
    whisper_context *ctx = context(handle);
    std::string words = text(env, value);
    std::vector<whisper_token> tokens(words.size() + 8);
    int count = whisper_tokenize(ctx, words.c_str(), tokens.data(), static_cast<int>(tokens.size()));
    if (count < 0) {
        tokens.resize(-count);
        count = whisper_tokenize(ctx, words.c_str(), tokens.data(), static_cast<int>(tokens.size()));
    }
    if (count < 0) return nullptr;
    jintArray result = env->NewIntArray(count);
    env->SetIntArrayRegion(result, 0, count, reinterpret_cast<const jint *>(tokens.data()));
    return result;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_arbelonson_ozen_whisper_WhisperCpp_tokenEot(JNIEnv *, jobject, jlong handle) {
    return whisper_token_eot(context(handle));
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

extern "C" JNIEXPORT jlong JNICALL
Java_com_arbelonson_ozen_whisper_WhisperCpp_vadLoad(JNIEnv *env, jobject, jstring path) {
    whisper_vad_context_params params = whisper_vad_default_context_params();
    params.n_threads = 1;
    params.use_gpu = false;
    whisper_vad_context *vad = whisper_vad_init_from_file_with_params(text(env, path).c_str(), params);
    // Loading leaves Silero's memory as whatever was in it before: without
    // this the first chunks of hiss scored as a voice on the emulator.
    if (vad != nullptr) whisper_vad_reset_state(vad);
    return reinterpret_cast<jlong>(vad);
}

// Silero's memory runs on from one call to the next, as it does across
// the chunks of a live stream; one probability per 512-sample frame.
extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_arbelonson_ozen_whisper_WhisperCpp_vadFrames(JNIEnv *env, jobject, jlong handle, jfloatArray samples) {
    auto *vad = reinterpret_cast<whisper_vad_context *>(handle);
    const jsize count = env->GetArrayLength(samples);
    jfloat *audio = env->GetFloatArrayElements(samples, nullptr);
    const bool ok = whisper_vad_detect_speech_no_reset(vad, audio, count);
    env->ReleaseFloatArrayElements(samples, audio, JNI_ABORT);
    if (!ok) return nullptr;
    const int frames = whisper_vad_n_probs(vad);
    jfloatArray result = env->NewFloatArray(frames);
    env->SetFloatArrayRegion(result, 0, frames, whisper_vad_probs(vad));
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_arbelonson_ozen_whisper_WhisperCpp_vadReset(JNIEnv *, jobject, jlong handle) {
    whisper_vad_reset_state(reinterpret_cast<whisper_vad_context *>(handle));
}

extern "C" JNIEXPORT void JNICALL
Java_com_arbelonson_ozen_whisper_WhisperCpp_vadFree(JNIEnv *, jobject, jlong handle) {
    whisper_vad_free(reinterpret_cast<whisper_vad_context *>(handle));
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_arbelonson_ozen_whisper_WhisperCpp_systemInfo(JNIEnv *env, jobject) {
    return env->NewStringUTF(whisper_print_system_info());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_arbelonson_ozen_whisper_WhisperCpp_loadBackends(JNIEnv *env, jobject, jstring folder) {
    ggml_log_set(log_to_logcat, nullptr);
    ggml_backend_load_all_from_path(text(env, folder).c_str());
    return ggml_backend_dev_by_type(GGML_BACKEND_DEVICE_TYPE_CPU) != nullptr;
}
