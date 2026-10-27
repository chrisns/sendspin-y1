// y1-stt: speech to text for the Y1's voice search, on whisper.cpp. A song or artist name is a few words, so: one
// segment, greedy, no temperature fallback, at most 24 tokens, and the encoder window cut to the audio. Without those
// limits whisper on a short, noisy clip can loop for a thousand tokens (three minutes on this CPU).
//   y1-stt MODEL PCM_FILE    (16 kHz mono signed 16-bit little-endian; prints the text)
#include "whisper.h"
#include <cmath>
#include <cstdio>
#include <string>
#include <vector>

static void quiet(enum ggml_log_level, const char *, void *) {}

// Stop at the end of the first sentence, or as soon as the words start repeating: force end-of-text.
static void stop_early(whisper_context *ctx, whisper_state *, const whisper_token_data *tokens, int n, float *logits, void *) {
    bool stop = false;
    if (n > 0) {
        const char *t = whisper_token_to_str(ctx, tokens[n - 1].id);
        std::string s = t ? t : "";
        if (!s.empty() && (s.back() == '.' || s.back() == '!' || s.back() == '?')) stop = true;
    }
    for (int k = 1; !stop && 2 * k <= n; k++) {          // the last k tokens equal the k before them
        bool same = true;
        for (int i = 0; i < k && same; i++) same = tokens[n - 1 - i].id == tokens[n - 1 - k - i].id;
        if (same && (k > 1 || n > 2)) stop = true;
    }
    if (!stop) return;
    const int eot = whisper_token_eot(ctx);
    for (int i = 0; i < whisper_n_vocab(ctx); i++) if (i != eot) logits[i] = -INFINITY;
}

int main(int argc, char **argv) {
    if (argc < 3) { fprintf(stderr, "usage: y1-stt MODEL PCM_FILE\n"); return 2; }
    FILE *f = fopen(argv[2], "rb");
    if (!f) { perror(argv[2]); return 2; }
    std::vector<float> pcm;
    short buf[4096];
    size_t n;
    while ((n = fread(buf, 2, 4096, f)) > 0) for (size_t i = 0; i < n; i++) pcm.push_back(buf[i] / 32768.0f);
    fclose(f);
    if (pcm.size() < 1600) return 1;

    whisper_log_set(quiet, nullptr);
    whisper_context_params cp = whisper_context_default_params();
    cp.use_gpu = false;
    whisper_context *ctx = whisper_init_from_file_with_params(argv[1], cp);
    if (!ctx) { fprintf(stderr, "no model\n"); return 2; }

    whisper_full_params p = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    p.n_threads = 1;                 // MediaTek turns the second core off at will; ggml's threads spin on a missing one
    p.language = "en";
    p.translate = false;
    p.no_context = true;
    p.no_timestamps = true;
    p.single_segment = true;
    p.max_tokens = 24;
    p.temperature_inc = 0.0f;        // no fallback retries
    p.suppress_blank = true;
    p.suppress_nst = true;
    p.print_progress = false;
    p.print_realtime = false;
    p.print_timestamps = false;
    p.print_special = false;
    p.logits_filter_callback = stop_early;
    int secs50 = (int) (pcm.size() * 50 / 16000);          // encoder frames for this audio (1500 = 30 s)
    int ac = (secs50 + 15) / 16 * 16;                      // just the audio: trailing silence invites repeats
    p.audio_ctx = ac > 1500 ? 1500 : ac;
    if (whisper_full(ctx, p, pcm.data(), (int) pcm.size()) != 0) { whisper_free(ctx); return 1; }
    std::string out;
    for (int i = 0; i < whisper_full_n_segments(ctx); i++) out += whisper_full_get_segment_text(ctx, i);
    printf("%s\n", out.c_str());
    whisper_free(ctx);
    return 0;
}
