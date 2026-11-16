// Speech to text with whisper.cpp (DTW token timestamps) + Silero VAD for HushCast.
#include <jni.h>

#include <algorithm>
#include <string>
#include <vector>

#include "whisper.h"

namespace {

void fail(JNIEnv * env, const std::string & msg) {
    env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), msg.c_str());
}

void quiet_log(ggml_log_level level, const char * text, void *) {
    (void) level; (void) text;
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_app_hushcast_automute_nativebridge_WhisperJni_load(
        JNIEnv * env, jclass, jstring jpath, jboolean useDtw, jboolean useFlashAttn, jboolean isTiny) {
    whisper_log_set(quiet_log, nullptr);
    const char * path = env->GetStringUTFChars(jpath, nullptr);
    whisper_context_params cp = whisper_context_default_params();
    cp.use_gpu = false;
    if (useDtw) {
        cp.dtw_token_timestamps = true;
        cp.dtw_aheads_preset = isTiny ? WHISPER_AHEADS_TINY_EN : WHISPER_AHEADS_BASE_EN;
    } else if (useFlashAttn) {
        cp.flash_attn = true;
    }
    whisper_context * ctx = whisper_init_from_file_with_params(path, cp);
    env->ReleaseStringUTFChars(jpath, path);
    if (!ctx) {
        fail(env, "could not load the whisper model");
        return 0;
    }
    return reinterpret_cast<jlong>(ctx);
}

// The audio buffer (PcmBuffer): 16 kHz mono float32 in native memory, off the Java heap.
extern "C" JNIEXPORT jlong JNICALL
Java_app_hushcast_automute_nativebridge_WhisperJni_pcmNew(JNIEnv *, jclass, jint capacity) {
    auto * v = new std::vector<float>();
    v->reserve(capacity > 0 ? (size_t) capacity : 0);
    return reinterpret_cast<jlong>(v);
}

extern "C" JNIEXPORT void JNICALL
Java_app_hushcast_automute_nativebridge_WhisperJni_pcmAppend(
        JNIEnv * env, jclass, jlong pcm, jfloatArray x, jint offset, jint count) {
    auto * v = reinterpret_cast<std::vector<float> *>(pcm);
    const size_t at = v->size();
    v->resize(at + (size_t) count);
    env->GetFloatArrayRegion(x, offset, count, v->data() + at);
}

extern "C" JNIEXPORT jint JNICALL
Java_app_hushcast_automute_nativebridge_WhisperJni_pcmSize(JNIEnv *, jclass, jlong pcm) {
    auto * v = reinterpret_cast<std::vector<float> *>(pcm);
    return v ? static_cast<jint>(v->size()) : 0;
}

extern "C" JNIEXPORT void JNICALL
Java_app_hushcast_automute_nativebridge_WhisperJni_pcmFree(JNIEnv *, jclass, jlong pcm) {
    delete reinterpret_cast<std::vector<float> *>(pcm);
}

struct WhisperProgressCtx {
    JNIEnv * env;
    jobject cb;
    jmethodID method;
    int last_pct;
};

static void whisper_jni_progress(struct whisper_context *, struct whisper_state *, int progress, void * user_data) {
    auto * c = reinterpret_cast<WhisperProgressCtx *>(user_data);
    if (!c || !c->cb || !c->method) return;
    if (progress > c->last_pct) {
        c->last_pct = progress;
        c->env->CallVoidMethod(c->cb, c->method, (jint) progress);
    }
}

// Transcribes [startSample, endSample) (or full buffer if endSample < 0).
// Returns Object[] { float[] (segment, start, end per word, seconds), byte[][] (UTF-8 word text) }.
extern "C" JNIEXPORT jobjectArray JNICALL
Java_app_hushcast_automute_nativebridge_WhisperJni_transcribeRange(
        JNIEnv * env, jclass, jlong h, jlong pcm, jint startSample, jint endSample,
        jint threads, jstring jlang, jint beam_size, jstring jvadPath, jobject jcb) {
    auto * ctx = reinterpret_cast<whisper_context *>(h);
    auto * audio = reinterpret_cast<std::vector<float> *>(pcm);
    const int total = (int) audio->size();
    const int s0 = std::max(0, std::min(startSample, total));
    const int s1 = (endSample < 0) ? total : std::max(s0, std::min(endSample, total));
    const float * samples = audio->data() + s0;
    const int n_samples = s1 - s0;
    const float offset_sec = s0 / 16000.0f;

    const char * lang = env->GetStringUTFChars(jlang, nullptr);
    const char * vadPath = jvadPath ? env->GetStringUTFChars(jvadPath, nullptr) : nullptr;
    whisper_full_params p = whisper_full_default_params(
        beam_size > 1 ? WHISPER_SAMPLING_BEAM_SEARCH : WHISPER_SAMPLING_GREEDY);
    p.beam_search.beam_size = beam_size;
    p.n_threads = threads;
    p.language = lang;
    p.translate = false;
    p.no_context = true;
    p.token_timestamps = true;
    // Disable temperature fallback retries on music/crosstalk to avoid multi-pass stalls
    p.temperature_inc = 0.0f;
    p.print_progress = false;
    p.print_realtime = false;
    p.print_timestamps = false;
    p.print_special = false;

    if (vadPath && *vadPath) {
        p.vad = true;
        p.vad_model_path = vadPath;
        p.vad_params = whisper_vad_default_params();
        p.vad_params.threshold = 0.35f;
        p.vad_params.min_silence_duration_ms = 1000;
    }

    WhisperProgressCtx pctx { env, jcb, nullptr, -1 };
    if (jcb) {
        jclass cls = env->GetObjectClass(jcb);
        pctx.method = env->GetMethodID(cls, "onProgress", "(I)V");
        env->DeleteLocalRef(cls);
        if (pctx.method) {
            p.progress_callback = whisper_jni_progress;
            p.progress_callback_user_data = &pctx;
        }
    }

    const int rc = whisper_full(ctx, p, samples, n_samples);
    env->ReleaseStringUTFChars(jlang, lang);
    if (vadPath) env->ReleaseStringUTFChars(jvadPath, vadPath);
    if (rc != 0) {
        fail(env, "whisper_full failed");
        return nullptr;
    }

    const whisper_token eot = whisper_token_eot(ctx);
    std::vector<float> times;
    std::vector<std::string> words;
    const int n_seg = whisper_full_n_segments(ctx);
    for (int s = 0; s < n_seg; s++) {
        std::string cur;
        float t0 = 0, t1 = 0;
        const int n_tok = whisper_full_n_tokens(ctx, s);
        for (int t = 0; t < n_tok; t++) {
            const whisper_token_data d = whisper_full_get_token_data(ctx, s, t);
            if (d.id >= eot) continue;
            const char * text = whisper_full_get_token_text(ctx, s, t);
            if (!text || !*text) continue;
            // Prefer DTW timestamp when available (t_dtw >= 0), otherwise token t0/t1
            const float tok_t0 = (d.t_dtw >= 0) ? (d.t_dtw / 100.0f) : (d.t0 / 100.0f);
            const float tok_t1 = (d.t1 >= d.t0) ? (d.t1 / 100.0f) : tok_t0 + 0.05f;
            if (text[0] == ' ' || cur.empty()) {
                if (!cur.empty()) {
                    times.insert(times.end(), {(float) s, offset_sec + t0, offset_sec + t1});
                    words.push_back(cur);
                }
                cur = text;
                t0 = tok_t0;
            } else {
                cur += text;
            }
            t1 = std::max(t0, tok_t1);
        }
        if (!cur.empty()) {
            times.insert(times.end(), {(float) s, offset_sec + t0, offset_sec + t1});
            words.push_back(cur);
        }
    }

    jfloatArray jtimes = env->NewFloatArray((jsize) times.size());
    env->SetFloatArrayRegion(jtimes, 0, (jsize) times.size(), times.data());
    jclass bytes_class = env->FindClass("[B");
    jobjectArray jwords = env->NewObjectArray((jsize) words.size(), bytes_class, nullptr);
    for (size_t i = 0; i < words.size(); i++) {
        jbyteArray b = env->NewByteArray((jsize) words[i].size());
        env->SetByteArrayRegion(b, 0, (jsize) words[i].size(), reinterpret_cast<const jbyte *>(words[i].data()));
        env->SetObjectArrayElement(jwords, (jsize) i, b);
        env->DeleteLocalRef(b);
    }
    jobjectArray result = env->NewObjectArray(2, env->FindClass("java/lang/Object"), nullptr);
    env->SetObjectArrayElement(result, 0, jtimes);
    env->SetObjectArrayElement(result, 1, jwords);
    return result;
}

// Runs ggml Silero VAD on the PcmBuffer and returns float[] { start0, end0, start1, end1, ... } in seconds.
extern "C" JNIEXPORT jfloatArray JNICALL
Java_app_hushcast_automute_nativebridge_WhisperJni_vadDetectSpeech(
        JNIEnv * env, jclass, jstring jvadPath, jlong pcm, jint threads,
        jfloat threshold, jint minSilenceDurationMs) {
    whisper_log_set(quiet_log, nullptr);
    const char * vadPath = env->GetStringUTFChars(jvadPath, nullptr);
    whisper_vad_context_params vcp = whisper_vad_default_context_params();
    vcp.n_threads = threads;
    vcp.use_gpu = false;
    whisper_vad_context * vctx = whisper_vad_init_from_file_with_params(vadPath, vcp);
    env->ReleaseStringUTFChars(jvadPath, vadPath);
    if (!vctx) {
        fail(env, "could not load Silero VAD model");
        return nullptr;
    }

    auto * audio = reinterpret_cast<std::vector<float> *>(pcm);
    whisper_vad_params vp = whisper_vad_default_params();
    vp.threshold = threshold;
    vp.min_silence_duration_ms = minSilenceDurationMs;

    std::vector<float> spans;
    whisper_vad_segments * segs = whisper_vad_segments_from_samples(
        vctx, vp, audio->data(), (int) audio->size());
    if (segs) {
        const int n = whisper_vad_segments_n_segments(segs);
        spans.reserve((size_t) n * 2);
        for (int i = 0; i < n; i++) {
            spans.push_back(whisper_vad_segments_get_segment_t0(segs, i));
            spans.push_back(whisper_vad_segments_get_segment_t1(segs, i));
        }
        whisper_vad_free_segments(segs);
    }
    whisper_vad_free(vctx);

    jfloatArray out = env->NewFloatArray((jsize) spans.size());
    if (!spans.empty()) {
        env->SetFloatArrayRegion(out, 0, (jsize) spans.size(), spans.data());
    }
    return out;
}

extern "C" JNIEXPORT void JNICALL
Java_app_hushcast_automute_nativebridge_WhisperJni_free(JNIEnv *, jclass, jlong h) {
    whisper_free(reinterpret_cast<whisper_context *>(h));
}
