// Speech to text with whisper.cpp (DTW token timestamps) + Silero VAD for HushCast.
#include <jni.h>

#include <algorithm>
#include <string>
#include <vector>

#include "whisper.h"
#define DR_MP3_IMPLEMENTATION
#include "dr_mp3.h"

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

// ---------- Native NEON Polyphase Resampler ----------
#if defined(__aarch64__) || defined(_M_ARM64)
#include <arm_neon.h>
#define HUSHCAST_USE_NEON 1
#endif

namespace {

static int gcd_int(int a, int b) {
    while (b != 0) { int t = a % b; a = b; b = t; }
    return a;
}

static double bessel_i0(double x) {
    double sum = 1.0, term = 1.0;
    int k = 1;
    while (term > 1e-12 * sum) {
        double r = x / (2.0 * k);
        term *= r * r;
        sum += term;
        k++;
    }
    return sum;
}

struct NativeResampler {
    int in_rate;
    int out_rate;
    bool passthrough;
    double fc;
    int half;
    int taps;
    int g;
    int phases;
    double i0b;
    std::vector<std::vector<float>> bank;
    std::vector<float> hist;
    int64_t hist_base = 0;
    int64_t next_out = 0;
    std::vector<float> out_buf;

    double kernel(double d) const {
        if (std::abs(d) >= half) return 0.0;
        const double x = 2.0 * fc * d;
        const double sinc = (x == 0.0) ? 1.0 : std::sin(M_PI * x) / (M_PI * x);
        const double r = d / half;
        return 2.0 * fc * sinc * bessel_i0(9.0 * std::sqrt(std::max(0.0, 1.0 - r * r))) / i0b;
    }

    NativeResampler(int inR, int outR)
        : in_rate(inR), out_rate(outR), passthrough(inR == outR) {
        fc = 0.97 * std::min(in_rate, out_rate) / (2.0 * in_rate);
        half = (int) std::ceil(16.0 / (2.0 * fc));
        taps = 2 * half;
        g = gcd_int(in_rate, out_rate);
        phases = out_rate / g;
        i0b = bessel_i0(9.0);
        if (!passthrough && phases <= 4096) {
            bank.resize((size_t) phases);
            for (int p = 0; p < phases; p++) {
                double frac = (double) p * g / out_rate;
                bank[p].resize((size_t) taps);
                for (int j = 0; j < taps; j++) {
                    bank[p][j] = (float) kernel(j - half + 1 - frac);
                }
            }
        }
        hist.reserve(1 << 16);
        out_buf.reserve(1 << 14);
    }

    void produce(int64_t last_input) {
        out_buf.clear();
        const float * hptr = hist.data();
        const int64_t hist_len = (int64_t) hist.size();
        const bool have_bank = !bank.empty();
        while (true) {
            const int64_t num = next_out * in_rate;
            const int64_t center = num / out_rate;
            if (center >= last_input) break;
            if (center + half >= hist_base + hist_len) break;
            const int64_t rem = num % out_rate;
            const int base = (int) (center - half + 1 - hist_base);
            const int j0 = (base < 0) ? -base : 0;
            if (have_bank) {
                const float * b = bank[(size_t) (rem / g)].data();
                const float * x = hptr + base;
                int j = j0;
                float acc = 0.0f;
#ifdef HUSHCAST_USE_NEON
                float32x4_t vsum = vdupq_n_f32(0.0f);
                for (; j + 4 <= taps; j += 4) {
                    float32x4_t vx = vld1q_f32(x + j);
                    float32x4_t vb = vld1q_f32(b + j);
                    vsum = vfmaq_f32(vsum, vx, vb);
                }
                acc = vaddvq_f32(vsum);
#endif
                for (; j < taps; j++) {
                    acc += x[j] * b[j];
                }
                out_buf.push_back(acc);
            } else {
                double acc = 0.0;
                const double frac = (double) rem / out_rate;
                for (int j = j0; j < taps; j++) {
                    acc += (double) hptr[base + j] * kernel(j - half + 1 - frac);
                }
                out_buf.push_back((float) acc);
            }
            next_out++;
        }
        const int64_t keep_from = (next_out * in_rate) / out_rate - half - 1;
        const int64_t drop = keep_from - hist_base;
        if (drop > 0 && drop < (int64_t) hist.size()) {
            const size_t rem_len = hist.size() - (size_t) drop;
            std::memmove(hist.data(), hist.data() + drop, rem_len * sizeof(float));
            hist.resize(rem_len);
            hist_base += drop;
        }
    }
};

struct NativeFft {
    int n;
    std::vector<double> cos_t;
    std::vector<double> sin_t;
    std::vector<int> rev;

    explicit NativeFft(int size) : n(size) {
        int bits = 0;
        while ((1 << bits) < n) bits++;
        cos_t.resize((size_t) n / 2);
        sin_t.resize((size_t) n / 2);
        for (int i = 0; i < n / 2; i++) {
            double ang = 2.0 * M_PI * i / n;
            cos_t[i] = std::cos(ang);
            sin_t[i] = std::sin(ang);
        }
        rev.resize((size_t) n);
        for (int i = 0; i < n; i++) {
            int r = 0, v = i;
            for (int b = 0; b < bits; b++) {
                r = (r << 1) | (v & 1);
                v >>= 1;
            }
            rev[i] = r;
        }
    }

    void transform(double * re, double * im, bool inverse) const {
        for (int i = 0; i < n; i++) {
            const int j = rev[i];
            if (j > i) {
                std::swap(re[i], re[j]);
                std::swap(im[i], im[j]);
            }
        }
        const double sgn = inverse ? 1.0 : -1.0;
        for (int size = 2; size <= n; size <<= 1) {
            const int half = size >> 1;
            const int step = n / size;
            for (int start = 0; start < n; start += size) {
                int k = 0;
                for (int j = start; j < start + half; j++) {
                    const int l = j + half;
                    const double c = cos_t[k];
                    const double s = sgn * sin_t[k];
                    const double tr = re[l] * c - im[l] * s;
                    const double ti = re[l] * s + im[l] * c;
                    re[l] = re[j] - tr;
                    im[l] = im[j] - ti;
                    re[j] += tr;
                    im[j] += ti;
                    k += step;
                }
            }
        }
    }
};

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_app_hushcast_automute_nativebridge_WhisperJni_resamplerNew(JNIEnv *, jclass, jint inRate, jint outRate) {
    return reinterpret_cast<jlong>(new NativeResampler(inRate, outRate));
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_app_hushcast_automute_nativebridge_WhisperJni_resamplerProcess(
        JNIEnv * env, jclass, jlong h, jfloatArray jinput, jint offset, jint count) {
    auto * r = reinterpret_cast<NativeResampler *>(h);
    if (r->passthrough) {
        jfloatArray out = env->NewFloatArray(count);
        if (count > 0) {
            r->out_buf.resize((size_t) count);
            env->GetFloatArrayRegion(jinput, offset, count, r->out_buf.data());
            env->SetFloatArrayRegion(out, 0, count, r->out_buf.data());
        }
        return out;
    }
    const size_t old_sz = r->hist.size();
    r->hist.resize(old_sz + (size_t) count);
    if (count > 0) {
        env->GetFloatArrayRegion(jinput, offset, count, r->hist.data() + old_sz);
    }
    r->produce(INT64_MAX);
    const jsize n_out = (jsize) r->out_buf.size();
    jfloatArray out = env->NewFloatArray(n_out);
    if (n_out > 0) {
        env->SetFloatArrayRegion(out, 0, n_out, r->out_buf.data());
    }
    return out;
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_app_hushcast_automute_nativebridge_WhisperJni_resamplerFinish(JNIEnv * env, jclass, jlong h) {
    auto * r = reinterpret_cast<NativeResampler *>(h);
    if (r->passthrough) return env->NewFloatArray(0);
    const int64_t end = r->hist_base + (int64_t) r->hist.size();
    const size_t pad = (size_t) (r->half + 1);
    r->hist.resize(r->hist.size() + pad, 0.0f);
    r->produce(end);
    const jsize n_out = (jsize) r->out_buf.size();
    jfloatArray out = env->NewFloatArray(n_out);
    if (n_out > 0) {
        env->SetFloatArrayRegion(out, 0, n_out, r->out_buf.data());
    }
    return out;
}

extern "C" JNIEXPORT void JNICALL
Java_app_hushcast_automute_nativebridge_WhisperJni_resamplerFree(JNIEnv *, jclass, jlong h) {
    delete reinterpret_cast<NativeResampler *>(h);
}

extern "C" JNIEXPORT jlong JNICALL
Java_app_hushcast_automute_nativebridge_WhisperJni_fftNew(JNIEnv *, jclass, jint n) {
    return reinterpret_cast<jlong>(new NativeFft(n));
}

extern "C" JNIEXPORT void JNICALL
Java_app_hushcast_automute_nativebridge_WhisperJni_fftTransform(
        JNIEnv * env, jclass, jlong h, jdoubleArray jre, jdoubleArray jim, jboolean inverse) {
    auto * f = reinterpret_cast<NativeFft *>(h);
    jdouble * re = (jdouble *) env->GetPrimitiveArrayCritical(jre, nullptr);
    jdouble * im = (jdouble *) env->GetPrimitiveArrayCritical(jim, nullptr);
    if (re && im) {
        f->transform(re, im, inverse);
    }
    if (im) env->ReleasePrimitiveArrayCritical(jim, im, 0);
    if (re) env->ReleasePrimitiveArrayCritical(jre, re, 0);
}

extern "C" JNIEXPORT void JNICALL
Java_app_hushcast_automute_nativebridge_WhisperJni_fftFree(JNIEnv *, jclass, jlong h) {
    delete reinterpret_cast<NativeFft *>(h);
}

struct NativeMp3Decoder {
    drmp3 mp3;
    std::vector<float> interleaved;
    std::vector<float> mid;
    std::vector<float> side;
};

extern "C" JNIEXPORT jlong JNICALL
Java_app_hushcast_automute_nativebridge_WhisperJni_mp3Open(JNIEnv * env, jclass, jstring jpath) {
    const char * path = env->GetStringUTFChars(jpath, nullptr);
    auto * dec = new NativeMp3Decoder();
    if (!drmp3_init_file(&dec->mp3, path, nullptr)) {
        env->ReleaseStringUTFChars(jpath, path);
        delete dec;
        return 0L;
    }
    env->ReleaseStringUTFChars(jpath, path);
    return reinterpret_cast<jlong>(dec);
}

extern "C" JNIEXPORT jint JNICALL
Java_app_hushcast_automute_nativebridge_WhisperJni_mp3SampleRate(JNIEnv *, jclass, jlong h) {
    return (jint) reinterpret_cast<NativeMp3Decoder *>(h)->mp3.sampleRate;
}

extern "C" JNIEXPORT jint JNICALL
Java_app_hushcast_automute_nativebridge_WhisperJni_mp3Channels(JNIEnv *, jclass, jlong h) {
    return (jint) reinterpret_cast<NativeMp3Decoder *>(h)->mp3.channels;
}

extern "C" JNIEXPORT jlong JNICALL
Java_app_hushcast_automute_nativebridge_WhisperJni_mp3TotalFrames(JNIEnv *, jclass, jlong h) {
    return (jlong) drmp3_get_pcm_frame_count(&reinterpret_cast<NativeMp3Decoder *>(h)->mp3);
}

extern "C" JNIEXPORT jint JNICALL
Java_app_hushcast_automute_nativebridge_WhisperJni_mp3ReadChunk(
        JNIEnv * env, jclass, jlong h, jfloatArray jmid, jfloatArray jside) {
    auto * dec = reinterpret_cast<NativeMp3Decoder *>(h);
    const jsize maxFrames = env->GetArrayLength(jmid);
    const drmp3_uint32 ch = dec->mp3.channels;
    if (maxFrames <= 0 || ch == 0) return 0;

    dec->interleaved.resize((size_t) maxFrames * ch);
    const drmp3_uint64 n = drmp3_read_pcm_frames_f32(&dec->mp3, (drmp3_uint64) maxFrames, dec->interleaved.data());
    if (n == 0) return 0;

    dec->mid.resize((size_t) n);
    const float * src = dec->interleaved.data();
    float * mid = dec->mid.data();
    if (ch >= 2) {
        dec->side.resize((size_t) n);
        float * side = dec->side.data();
        for (drmp3_uint64 i = 0; i < n; i++) {
            const float l = src[i * ch];
            const float r = src[i * ch + 1];
            mid[i] = (l + r) * 0.5f;
            side[i] = (l - r) * 0.5f;
        }
        if (jside != nullptr) {
            env->SetFloatArrayRegion(jside, 0, (jsize) n, side);
        }
    } else {
        std::copy(src, src + n, mid);
    }
    env->SetFloatArrayRegion(jmid, 0, (jsize) n, mid);
    return (jint) n;
}

extern "C" JNIEXPORT void JNICALL
Java_app_hushcast_automute_nativebridge_WhisperJni_mp3Close(JNIEnv *, jclass, jlong h) {
    auto * dec = reinterpret_cast<NativeMp3Decoder *>(h);
    drmp3_uninit(&dec->mp3);
    delete dec;
}

