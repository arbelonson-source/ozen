#include <jni.h>
#include <android/log.h>

#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdlib>
#include <string>
#include <vector>

#include "ggml-backend.h"
#include "ggml.h"
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

// A stop stays asked for until the next pass begins: WhisperModel.beginPass
// clears it, under the same lock as the stop, and only for a pass whose
// stream still runs. Clearing it here, as the pass started, lost a stop that
// landed just before, and that pass then ran to its end.
void stop_when_asked(jlong handle, whisper_full_params &params) {
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

extern "C" JNIEXPORT void JNICALL
Java_com_arbelonson_ozen_whisper_WhisperCpp_resume(JNIEnv *, jobject, jlong handle) {
    model(handle)->stop = false;
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
    model(handle)->stop = false;
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
        jint audioContext, jboolean reuseEncoding) {
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
    params.reuse_encoding = reuseEncoding;
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

// The speed of the work a Whisper pass is made of: Q5_0 weights the size of
// Turbo's feed-forward layer times a few hundred audio frames, on the same
// backend and thread count the engine runs with. Repeats for about
// [millis] and answers billions of operations per second, 0 on failure.
extern "C" JNIEXPORT jdouble JNICALL
Java_com_arbelonson_ozen_whisper_WhisperCpp_benchMatMul(JNIEnv *, jobject, jint threads, jint millis) {
    const int64_t k = 1280, rows = 5120, frames = 300;
    const size_t bytes = 24u << 20;
    ggml_init_params init = {bytes, nullptr, false};
    ggml_context *ctx = ggml_init(init);
    if (ctx == nullptr) return 0;
    ggml_tensor *weights = ggml_new_tensor_2d(ctx, GGML_TYPE_Q5_0, k, rows);
    ggml_tensor *input = ggml_new_tensor_2d(ctx, GGML_TYPE_F32, k, frames);
    ggml_tensor *product = ggml_mul_mat(ctx, weights, input);
    ggml_cgraph *graph = ggml_new_graph(ctx);
    ggml_build_forward_expand(graph, product);
    std::vector<float> plain(static_cast<size_t>(k) * rows);
    for (size_t i = 0; i < plain.size(); ++i) plain[i] = static_cast<float>((i * 2654435761u) % 2001) / 1000.0f - 1.0f;
    ggml_quantize_chunk(GGML_TYPE_Q5_0, plain.data(), weights->data, 0, rows, k, nullptr);
    float *in = static_cast<float *>(input->data);
    for (int64_t i = 0; i < k * frames; ++i) in[i] = static_cast<float>((i * 40503u) % 1999) / 1000.0f - 1.0f;

    ggml_backend_t backend = ggml_backend_init_by_type(GGML_BACKEND_DEVICE_TYPE_CPU, nullptr);
    double result = 0;
    if (backend != nullptr) {
        auto *reg = ggml_backend_dev_backend_reg(ggml_backend_get_device(backend));
        auto setThreads = reinterpret_cast<ggml_backend_set_n_threads_t>(ggml_backend_reg_get_proc_address(reg, "ggml_backend_set_n_threads"));
        if (setThreads != nullptr) setThreads(backend, threads);
        if (ggml_backend_graph_compute(backend, graph) == GGML_STATUS_SUCCESS) {
            using clock = std::chrono::steady_clock;
            const auto start = clock::now();
            const auto end = start + std::chrono::milliseconds(millis);
            int runs = 0;
            do {
                if (ggml_backend_graph_compute(backend, graph) != GGML_STATUS_SUCCESS) break;
                ++runs;
            } while (clock::now() < end);
            const double seconds = std::chrono::duration<double>(clock::now() - start).count();
            if (runs > 0 && seconds > 0) result = 2.0 * k * rows * frames * runs / seconds * 1e-9;
        }
        ggml_backend_free(backend);
    }
    ggml_free(ctx);
    return result;
}
