// JNI wrapper over llama.cpp for on-device text rewriting: load a GGUF model, run one chat turn, stream the text back.
// Built by tools/build-llm.sh into libwisp_llm.so (llama.cpp is linked in statically). Used only from the voice process.
#include <jni.h>
#include <android/log.h>
#include <atomic>
#include <string>
#include <vector>
#include "llama.h"

#define TAG "WispLlm"

namespace {
struct Llm {
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    std::atomic<bool> cancel{false};
};

void quiet_log(enum ggml_log_level level, const char * text, void *) {
    if (level == GGML_LOG_LEVEL_ERROR) __android_log_print(ANDROID_LOG_ERROR, TAG, "%s", text);
}

// How many bytes at the start of s form complete UTF-8 characters (a token can end in the middle of one).
size_t complete_utf8(const std::string & s) {
    size_t n = s.size();
    for (size_t back = 1; back <= 3 && back <= n; back++) {
        unsigned char c = (unsigned char) s[n - back];
        if ((c & 0xC0) == 0x80) continue;           // continuation byte: keep looking for the start
        size_t need = (c >= 0xF0) ? 4 : (c >= 0xE0) ? 3 : (c >= 0xC0) ? 2 : 1;
        return back >= need ? n : n - back;
    }
    return n;
}
}

extern "C" {

JNIEXPORT jlong JNICALL Java_app_wisp_keyboard_voice_llm_LlamaBridge_load(JNIEnv * env, jclass, jstring jpath, jint threads, jint nctx) {
    const char * path = env->GetStringUTFChars(jpath, nullptr);
    llama_log_set(quiet_log, nullptr);
    llama_backend_init();
    llama_model_params mp = llama_model_default_params();
    // default load mode: the weights are memory-mapped from the file where the system allows it
    llama_model * model = llama_model_load_from_file(path, mp);
    env->ReleaseStringUTFChars(jpath, path);
    if (!model) return 0;
    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = nctx; cp.n_batch = 512; cp.n_threads = threads; cp.n_threads_batch = threads;
    llama_context * ctx = llama_init_from_model(model, cp);
    if (!ctx) { llama_model_free(model); return 0; }
    auto * l = new Llm();
    l->model = model; l->ctx = ctx;
    return (jlong) l;
}

JNIEXPORT void JNICALL Java_app_wisp_keyboard_voice_llm_LlamaBridge_free(JNIEnv *, jclass, jlong h) {
    auto * l = (Llm *) h;
    if (!l) return;
    llama_free(l->ctx); llama_model_free(l->model);
    delete l;
}

JNIEXPORT void JNICALL Java_app_wisp_keyboard_voice_llm_LlamaBridge_cancel(JNIEnv *, jclass, jlong h) {
    if (h) ((Llm *) h)->cancel = true;
}

// One chat turn. The pieces of the answer are passed to cb.onToken(String) as they are produced; returns the full answer, or null on failure.
JNIEXPORT jstring JNICALL Java_app_wisp_keyboard_voice_llm_LlamaBridge_chat(JNIEnv * env, jclass, jlong h, jstring jsystem, jstring juser, jint maxTokens, jfloat temp, jobject cb) {
    auto * l = (Llm *) h;
    if (!l) return nullptr;
    l->cancel = false;
    const char * sys = env->GetStringUTFChars(jsystem, nullptr);
    const char * usr = env->GetStringUTFChars(juser, nullptr);
    std::string system_text = sys, user_text = usr;
    env->ReleaseStringUTFChars(jsystem, sys); env->ReleaseStringUTFChars(juser, usr);

    const llama_vocab * vocab = llama_model_get_vocab(l->model);
    const char * tmpl = llama_model_chat_template(l->model, nullptr);
    std::vector<llama_chat_message> msgs = { {"system", system_text.c_str()}, {"user", user_text.c_str()} };
    std::vector<char> buf(system_text.size() + user_text.size() + 1024);
    int n = llama_chat_apply_template(tmpl ? tmpl : "chatml", msgs.data(), msgs.size(), true, buf.data(), buf.size());
    if (n > (int) buf.size()) { buf.resize(n); n = llama_chat_apply_template(tmpl ? tmpl : "chatml", msgs.data(), msgs.size(), true, buf.data(), buf.size()); }
    if (n < 0) return nullptr;
    std::string prompt(buf.data(), n);

    int ntok = -llama_tokenize(vocab, prompt.c_str(), prompt.size(), nullptr, 0, true, true);
    std::vector<llama_token> tokens(ntok);
    if (llama_tokenize(vocab, prompt.c_str(), prompt.size(), tokens.data(), tokens.size(), true, true) < 0) return nullptr;
    if ((int) tokens.size() + maxTokens > (int) llama_n_ctx(l->ctx)) return nullptr;       // does not fit: the caller shows a message

    llama_memory_clear(llama_get_memory(l->ctx), true);
    llama_sampler * smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.9f, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(temp));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    jclass cls = env->GetObjectClass(cb);
    jmethodID onToken = env->GetMethodID(cls, "onToken", "(Ljava/lang/String;)V");
    std::string answer, pending;
    llama_batch batch = llama_batch_get_one(tokens.data(), tokens.size());
    bool failed = false;
    for (int i = 0; i < maxTokens && !l->cancel; i++) {
        if (llama_decode(l->ctx, batch) != 0) { failed = true; break; }
        llama_token id = llama_sampler_sample(smpl, l->ctx, -1);
        if (llama_vocab_is_eog(vocab, id)) break;
        char piece[256];
        int len = llama_token_to_piece(vocab, id, piece, sizeof(piece), 0, true);
        if (len < 0) { failed = true; break; }
        pending.append(piece, len);
        size_t ok = complete_utf8(pending);
        if (ok > 0) {
            std::string chunk = pending.substr(0, ok); pending.erase(0, ok);
            answer += chunk;
            jstring js = env->NewStringUTF(chunk.c_str());
            env->CallVoidMethod(cb, onToken, js);
            env->DeleteLocalRef(js);
        }
        batch = llama_batch_get_one(&id, 1);
    }
    llama_sampler_free(smpl);
    if (failed || l->cancel) return nullptr;
    return env->NewStringUTF(answer.c_str());
}

}
