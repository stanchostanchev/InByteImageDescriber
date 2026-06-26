#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <chrono>
#include <ctime>
#include <atomic>

#include "llama.h"
#include "mtmd.h"
#include "mtmd-helper.h"

#define TAG "InByteInference"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

struct InferenceContext {
    llama_model        * model    = nullptr;
    llama_context      * ctx      = nullptr;
    mtmd_context       * mtmd     = nullptr;
    llama_sampler      * sampler  = nullptr;
    const llama_vocab  * vocab    = nullptr;
    std::string          marker;
    uint32_t             n_ctx    = 2048;
    uint32_t             n_threads = 4;
    std::atomic<bool>    cancelled { false };
};

// NewStringUTF requires Modified UTF-8 which rejects 4-byte emoji sequences.
// Construct String(byte[], "UTF-8") instead to handle all Unicode correctly.
jstring newStringUtf8(JNIEnv* env, const std::string& s) {
    jbyteArray  bytes   = env->NewByteArray((jsize)s.size());
    env->SetByteArrayRegion(bytes, 0, (jsize)s.size(), (const jbyte*)s.data());
    jclass      strCls  = env->FindClass("java/lang/String");
    jmethodID   ctor    = env->GetMethodID(strCls, "<init>", "([BLjava/lang/String;)V");
    jstring     charset = env->NewStringUTF("UTF-8");
    jstring     result  = (jstring)env->NewObject(strCls, ctor, bytes, charset);
    env->DeleteLocalRef(bytes);
    env->DeleteLocalRef(strCls);
    env->DeleteLocalRef(charset);
    return result;
}

void invokeCallback(JNIEnv* env, jobject cb, const std::string& token) {
    jclass    cls = env->GetObjectClass(cb);
    jmethodID mid = env->GetMethodID(cls, "invoke", "(Ljava/lang/Object;)Ljava/lang/Object;");
    if (mid) {
        jstring jstr = newStringUtf8(env, token);
        env->CallObjectMethod(cb, mid, jstr);
        env->DeleteLocalRef(jstr);
    }
    env->DeleteLocalRef(cls);
}

} // namespace

extern "C" {

// ── Load model ──────────────────────────────────────────────────────────────

JNIEXPORT jlong JNICALL
Java_com_inbyte_imagedescriber_inference_LlamaEngine_nativeLoadModel(
    JNIEnv* env, jobject,
    jstring jModelPath, jstring jClipModelPath,
    jint nCtx, jint nThreads)
{
    llama_backend_init();
    llama_log_set(nullptr, nullptr);

    const char* modelPath = env->GetStringUTFChars(jModelPath, nullptr);
    const char* clipPath  = env->GetStringUTFChars(jClipModelPath, nullptr);

    auto* ic = new InferenceContext();

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0;
    ic->model = llama_load_model_from_file(modelPath, mparams);
    if (!ic->model) {
        LOGE("Failed to load model: %s", modelPath);
        env->ReleaseStringUTFChars(jModelPath, modelPath);
        env->ReleaseStringUTFChars(jClipModelPath, clipPath);
        delete ic;
        return 0;
    }

    ic->vocab     = llama_model_get_vocab(ic->model);
    ic->n_ctx     = static_cast<uint32_t>(nCtx);
    ic->n_threads = static_cast<uint32_t>(nThreads);

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx            = ic->n_ctx;
    cparams.n_threads        = ic->n_threads;
    cparams.n_threads_batch  = ic->n_threads;
    cparams.flash_attn_type  = LLAMA_FLASH_ATTN_TYPE_AUTO;
    ic->ctx = llama_new_context_with_model(ic->model, cparams);
    if (!ic->ctx) {
        LOGE("Failed to create llama context");
        llama_free_model(ic->model);
        env->ReleaseStringUTFChars(jModelPath, modelPath);
        env->ReleaseStringUTFChars(jClipModelPath, clipPath);
        delete ic;
        return 0;
    }

    if (clipPath && strlen(clipPath) > 0) {
        mtmd_context_params mparams_m = mtmd_context_params_default();
        mparams_m.use_gpu       = false;
        mparams_m.n_threads     = static_cast<int>(nThreads);
        mparams_m.print_timings = false;
        mparams_m.warmup        = false;
        ic->mtmd = mtmd_init_from_file(clipPath, ic->model, mparams_m);
        if (!ic->mtmd) {
            LOGE("Failed to init mtmd from: %s", clipPath);
        } else {
            ic->marker = mtmd_get_marker(ic->mtmd);
            LOGI("mtmd OK, marker=%s", ic->marker.c_str());
        }
    }

    LOGI("Model loaded (ctx=%d threads=%d)", nCtx, nThreads);
    env->ReleaseStringUTFChars(jModelPath, modelPath);
    env->ReleaseStringUTFChars(jClipModelPath, clipPath);
    return reinterpret_cast<jlong>(ic);
}

// ── Describe image (or plain text) ──────────────────────────────────────────

JNIEXPORT void JNICALL
Java_com_inbyte_imagedescriber_inference_LlamaEngine_nativeDescribeImage(
    JNIEnv* env, jobject,
    jlong handle, jbyteArray jImageBytes, jstring jPrompt,
    jint maxTokens, jfloat temperature, jobject tokenCb)
{
    auto* ic = reinterpret_cast<InferenceContext*>(handle);
    if (!ic || !ic->model) { LOGE("Invalid context"); return; }

    ic->cancelled.store(false);

    // Recreate context + sampler to guarantee a fully clean state between inferences
    if (ic->ctx) llama_free(ic->ctx);
    if (ic->sampler) llama_sampler_free(ic->sampler);
    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx           = ic->n_ctx;
    cparams.n_threads       = ic->n_threads;
    cparams.n_threads_batch = ic->n_threads;
    cparams.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_AUTO;
    ic->ctx = llama_new_context_with_model(ic->model, cparams);
    if (!ic->ctx) { LOGE("Failed to recreate context"); return; }

    // Recreate sampler with caller-supplied temperature; random seed for creative text
    bool isTextOnly = (jImageBytes == nullptr);
    uint32_t seed = (temperature > 0.3f)
        ? static_cast<uint32_t>(std::chrono::steady_clock::now().time_since_epoch().count())
        : LLAMA_DEFAULT_SEED;
    llama_sampler_chain_params sparams = llama_sampler_chain_default_params();
    ic->sampler = llama_sampler_chain_init(sparams);
    llama_sampler_chain_add(ic->sampler, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(ic->sampler, llama_sampler_init_top_p(0.9f, 1));
    llama_sampler_chain_add(ic->sampler, llama_sampler_init_temp(static_cast<float>(temperature)));
    llama_sampler_chain_add(ic->sampler, llama_sampler_init_dist(seed));

    const char* promptCStr = env->GetStringUTFChars(jPrompt, nullptr);
    std::string userPrompt(promptCStr);
    env->ReleaseStringUTFChars(jPrompt, promptCStr);

    bool hasImage = (jImageBytes != nullptr) && (ic->mtmd != nullptr);
    std::string fullPrompt = hasImage
        ? "<|im_start|>system\nYou are a visual image describer. "
          "You describe only what you see: people, animals, objects, shapes, colors, and scenes. "
          "You never read, transcribe, or analyze text. You never describe images as documents, letters, or manuscripts. "
          "If you see handwriting or scribbles in a drawing, ignore them and focus on the drawn figures and objects.\n<|im_end|>\n"
          "<|im_start|>User:" + ic->marker + "\nThis is a child's hand-drawn picture. "
          "Describe the figures, people, animals, plants, and objects the child drew. "
          "Describe colors and shapes. Ignore any handwriting or scribbles.\n"
          + userPrompt + "<|im_end|>\n<|im_start|>Assistant:\n"
        : "<|im_start|>system\nYou are a helpful assistant.\n<|im_end|>\n"
          "<|im_start|>User:\n" + userPrompt + "<|im_end|>\n<|im_start|>Assistant:\n";

    llama_pos n_past = 0;

    if (hasImage) {
        jsize  imgLen  = env->GetArrayLength(jImageBytes);
        jbyte* imgData = env->GetByteArrayElements(jImageBytes, nullptr);

        auto bmpWrapper = mtmd_helper_bitmap_init_from_buf(
            ic->mtmd,
            reinterpret_cast<const unsigned char*>(imgData),
            static_cast<size_t>(imgLen),
            /*placeholder=*/false);
        env->ReleaseByteArrayElements(jImageBytes, imgData, JNI_ABORT);

        if (!bmpWrapper.bitmap) {
            LOGE("Failed to decode image");
            invokeCallback(env, tokenCb, "[Error: could not decode image]");
            return;
        }

        mtmd_input_text inputText{ fullPrompt.c_str(), /*add_special=*/true, /*parse_special=*/true };
        const mtmd_bitmap* bitmaps[1] = { bmpWrapper.bitmap };
        mtmd_input_chunks* chunks = mtmd_input_chunks_init();

        int32_t tokErr = mtmd_tokenize(ic->mtmd, chunks, &inputText, bitmaps, 1);
        mtmd_bitmap_free(bmpWrapper.bitmap);

        if (tokErr != 0) {
            LOGE("mtmd_tokenize failed: %d", tokErr);
            mtmd_input_chunks_free(chunks);
            invokeCallback(env, tokenCb, "[Error: tokenization failed]");
            return;
        }

        auto tEval0 = std::chrono::steady_clock::now();
        int32_t evalErr = mtmd_helper_eval_chunks(
            ic->mtmd, ic->ctx, chunks,
            /*n_past=*/0, /*seq_id=*/0, /*n_batch=*/512,
            /*logits_last=*/true, &n_past);
        auto tEval1 = std::chrono::steady_clock::now();
        mtmd_input_chunks_free(chunks);
        LOGI("Image eval: n_past=%d time=%.1f ms", (int)n_past,
             std::chrono::duration<double, std::milli>(tEval1 - tEval0).count());

        if (evalErr != 0) {
            LOGE("mtmd_helper_eval_chunks failed: %d", evalErr);
            invokeCallback(env, tokenCb, "[Error: eval failed]");
            return;
        }
    } else {
        // Text-only path
        int n = llama_tokenize(ic->vocab, fullPrompt.c_str(), (int32_t)fullPrompt.size(),
                               nullptr, 0, true, true);
        std::vector<llama_token> tokens(-n);
        llama_tokenize(ic->vocab, fullPrompt.c_str(), (int32_t)fullPrompt.size(),
                       tokens.data(), (int32_t)tokens.size(), true, true);

        llama_batch batch = llama_batch_get_one(tokens.data(), (int32_t)tokens.size());
        llama_decode(ic->ctx, batch);
        n_past = static_cast<llama_pos>(tokens.size());
    }

    // Sample response tokens
    const llama_token eosId = llama_vocab_eos(ic->vocab);
    int    nGen  = 0;
    auto   tGen0 = std::chrono::steady_clock::now();
    for (int i = 0; i < maxTokens; i++) {
        if (ic->cancelled.load()) break;
        llama_token tok = llama_sampler_sample(ic->sampler, ic->ctx, -1);
        if (tok == eosId || tok < 0) break;

        char piece[256] = {};
        int len = llama_token_to_piece(ic->vocab, tok, piece, sizeof(piece), 0, false);
        if (len > 0) invokeCallback(env, tokenCb, std::string(piece, len));

        llama_batch batch = llama_batch_get_one(&tok, 1);
        if (llama_decode(ic->ctx, batch) != 0) break;
        n_past++;
        nGen++;

        if (nGen % 5 == 0) {
            auto tNow = std::chrono::steady_clock::now();
            double elapsed = std::chrono::duration<double, std::milli>(tNow - tGen0).count();
            LOGI("tok=%d  %.2f tok/s", nGen, nGen * 1000.0 / elapsed);
        }
    }
    auto tGen1 = std::chrono::steady_clock::now();
    double ms = std::chrono::duration<double, std::milli>(tGen1 - tGen0).count();
    LOGI("Generated %d tokens in %.1f ms → %.2f tok/s", nGen, ms, nGen * 1000.0 / ms);
}

JNIEXPORT void JNICALL
Java_com_inbyte_imagedescriber_inference_LlamaEngine_nativeGenerate(
    JNIEnv* env, jobject thiz,
    jlong handle, jstring jPrompt, jint maxTokens, jfloat temperature, jobject tokenCb)
{
    Java_com_inbyte_imagedescriber_inference_LlamaEngine_nativeDescribeImage(
        env, thiz, handle, nullptr, jPrompt, maxTokens, temperature, tokenCb);
}

// ── Cancel ───────────────────────────────────────────────────────────────────

JNIEXPORT void JNICALL
Java_com_inbyte_imagedescriber_inference_LlamaEngine_nativeCancel(
    JNIEnv*, jobject, jlong handle)
{
    auto* ic = reinterpret_cast<InferenceContext*>(handle);
    if (ic) ic->cancelled.store(true);
}

// ── Free ─────────────────────────────────────────────────────────────────────

JNIEXPORT void JNICALL
Java_com_inbyte_imagedescriber_inference_LlamaEngine_nativeFree(
    JNIEnv*, jobject, jlong handle)
{
    auto* ic = reinterpret_cast<InferenceContext*>(handle);
    if (!ic) return;
    if (ic->sampler) llama_sampler_free(ic->sampler);
    if (ic->mtmd)    mtmd_free(ic->mtmd);
    if (ic->ctx)     llama_free(ic->ctx);
    if (ic->model)   llama_free_model(ic->model);
    delete ic;
    llama_backend_free();
    LOGI("Resources freed");
}

} // extern "C"
