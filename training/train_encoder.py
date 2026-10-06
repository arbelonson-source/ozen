"""Teaches ivrit.ai's Whisper Turbo to hear through room echo and noise.

Only the encoder (the listening half) learns; the decoder (the writing half,
which holds punctuation, names and style) stays frozen. Each clip is heard
clean, from across a room, under household noise, or under another voice,
with rooms and noises kept apart from the exam's (build_exam.py): training
uses OpenSLR-28's simulated rooms and point-source noises, plus synthetic
hiss; the exam uses its real recorded rooms, real room noise and DEMAND.

    python train_encoder.py --data "<parquet glob>" --out <dir> [--minutes N]

Runs on Linux or Windows. Stops cleanly, saving, at --minutes or when a file
named STOP appears next to --out.
"""
import argparse, glob, io, json, math, os, random, re, sys, time
import numpy as np, soundfile as sf, torch, pyarrow.parquet as pq
from scipy.signal import fftconvolve
from transformers import WhisperForConditionalGeneration, WhisperProcessor

R = 16000
BIDI = re.compile("[\u200e\u200f\u202a-\u202e\u2066-\u2069]")
STAMP = re.compile(r"<\|[0-9.]+\|>")
TEXT_COLUMNS = ("sentence", "text", "transcript")
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "server"))
sys.path.insert(0, os.path.join(HERE, "..", "server"))
from ozen_server import speech_gain


def args_():
    p = argparse.ArgumentParser()
    p.add_argument("--data", required=True, nargs="+", help="parquet shard globs, each optionally glob@weight (seen weight times as often)")
    p.add_argument("--text", default="sentence")
    p.add_argument("--base", default="ivrit-ai/whisper-large-v3-turbo")
    p.add_argument("--rirs", default=os.path.expanduser("~/ozen-accuracy/rirs/RIRS_NOISES"))
    p.add_argument("--out", required=True)
    p.add_argument("--batch", type=int, default=32)
    p.add_argument("--lr", type=float, default=1e-5)
    p.add_argument("--warmup", type=int, default=300)
    p.add_argument("--steps", type=int, default=6000)
    p.add_argument("--minutes", type=float, default=0, help="stop and save after this long")
    p.add_argument("--save-every", type=int, default=1000)
    p.add_argument("--train-layers", type=int, default=0, help="0 = whole encoder, N = only its top N layers")
    p.add_argument("--workers", type=int, default=8)
    p.add_argument("--clean-share", type=float, default=0.3)
    p.add_argument("--checkpointing", action="store_true")
    p.add_argument("--seed", type=int, default=0)
    p.add_argument("--bench-loader", type=float, default=0, help="only time the data loader for this many seconds")
    return p.parse_args()


def rms(x):
    return float(np.sqrt(np.mean(np.square(x))) + 1e-9)


def mono16k(a, sr):
    a = a if a.ndim == 1 else a.mean(1)
    if sr != R:
        idx = np.arange(0, len(a) * R / sr) * sr / R
        a = np.interp(idx, np.arange(len(a)), a)
    return a.astype(np.float32)


def keep(row):
    extra = row.get("extra_data") or {}
    return not any(extra.get(k) for k in ("skipped", "unintelligible", "foreign_language", "too_long", "multiple_speakers"))


class Clips(torch.utils.data.IterableDataset):
    """Rows from the shards in random order, each heard one of four ways."""

    def __init__(self, a, features):
        self.files = []
        for spec in a.data:
            pattern, _, weight = spec.partition("@")
            self.files += sorted(glob.glob(pattern)) * int(weight or 1)
        self.text, self.rirs_dir, self.clean_share, self.seed = a.text, a.rirs, a.clean_share, a.seed
        self.features = features

    def __iter__(self):
        info = torch.utils.data.get_worker_info()
        wid, n = (info.id, info.num_workers) if info else (0, 1)
        rng = random.Random(self.seed * 1000 + wid + int(time.time()))
        rirs = sorted(glob.glob(os.path.join(self.rirs_dir, "simulated_rirs", "*", "*", "*.wav")))
        noises = sorted(glob.glob(os.path.join(self.rirs_dir, "pointsource_noises", "*.wav")))
        others = []
        while True:
            files = self.files[:]
            rng.shuffle(files)
            for f in files[wid::n] or files:
                pf = pq.ParquetFile(f)
                groups = list(range(pf.num_row_groups))
                rng.shuffle(groups)
                for g in groups:
                    rows = pf.read_row_group(g).to_pylist()
                    rng.shuffle(rows)
                    for row in rows:
                        raw = next((row[c] for c in (self.text,) + TEXT_COLUMNS if row.get(c)), "")
                        text = " ".join(STAMP.sub(" ", BIDI.sub("", raw)).split())
                        if not text or not keep(row):
                            continue
                        try:
                            a = mono16k(*sf.read(io.BytesIO(row["audio"]["bytes"]), dtype="float32"))
                        except Exception:
                            continue
                        if not (0.5 * R < len(a) <= 30 * R):
                            continue
                        others = (others + [a])[-32:]
                        x = self.hear(a, rng, rirs, noises, others)
                        yield self.features(x, sampling_rate=R, return_tensors="np").input_features[0], text

    def hear(self, a, rng, rirs, noises, others):
        roll = rng.random()
        if roll < self.clean_share:
            x = a
        elif roll < self.clean_share + (1 - self.clean_share) * 0.5 and rirs:
            h = mono16k(*sf.read(rirs[rng.randrange(len(rirs))], dtype="float32"))
            wet = fftconvolve(a, h[np.argmax(np.abs(h)):])[: len(a)].astype(np.float32)
            x = mix(wet, noise(len(a), rng, noises), rng.uniform(0, 20))
        elif roll < self.clean_share + (1 - self.clean_share) * 0.8:
            x = mix(a, noise(len(a), rng, noises), rng.uniform(0, 15))
        else:
            other = others[rng.randrange(len(others))]
            x = mix(a, np.resize(other, len(a)), rng.uniform(5, 20)) if other is not a else a
        x = x * (10 ** (rng.uniform(-45, -20) / 20) / rms(x))
        return speech_gain(np.clip(x, -1, 1).astype(np.float32))


def noise(n, rng, noises):
    if noises and rng.random() < 0.7:
        x = mono16k(*sf.read(noises[rng.randrange(len(noises))], dtype="float32"))
        if len(x) < n:
            x = np.resize(x, n)
        o = rng.randrange(len(x) - n + 1)
        return x[o:o + n]
    w = np.random.default_rng(rng.randrange(1 << 30)).standard_normal(n).astype(np.float32)
    if rng.random() < 0.5:
        f = np.fft.rfft(w)
        f /= np.sqrt(np.arange(1, len(f) + 1))
        w = np.fft.irfft(f, n).astype(np.float32)
    return w


def mix(clean, n, snr):
    return clean + n * (rms(clean) / (rms(n) * 10 ** (snr / 20)))


class Collate:
    def __init__(self, proc, start):
        self.proc, self.start = proc, start

    def __call__(self, batch):
        feats, texts = zip(*batch)
        feats = torch.from_numpy(np.stack(feats))
        labels = self.proc.tokenizer([" " + t for t in texts], return_tensors="pt", padding=True).input_ids
        labels = labels.masked_fill(labels == self.proc.tokenizer.pad_token_id, -100)
        if (labels[:, 0] == self.start).all():
            labels = labels[:, 1:]
        return feats, labels[:, :440]


def main():
    a = args_()
    torch.manual_seed(a.seed)
    os.makedirs(a.out, exist_ok=True)
    stop_file = os.path.join(os.path.dirname(os.path.abspath(a.out)), "STOP")
    proc = WhisperProcessor.from_pretrained(a.base)
    proc.tokenizer.set_prefix_tokens(language="he", task="transcribe", predict_timestamps=False)
    model = WhisperForConditionalGeneration.from_pretrained(a.base, dtype=torch.float32)
    model.config.forced_decoder_ids = None
    model.generation_config.forced_decoder_ids = None
    for q in model.parameters():
        q.requires_grad = False
    enc = model.model.encoder
    train = list(enc.layers[-a.train_layers:]) + [enc.layer_norm] if a.train_layers else [enc]
    for m in train:
        for q in m.parameters():
            q.requires_grad = True
    if a.checkpointing:
        model.gradient_checkpointing_enable()
        model.config.use_cache = False
    trainable = [q for q in model.parameters() if q.requires_grad]
    print(f"training {sum(q.numel() for q in trainable) / 1e6:.0f}M of {sum(q.numel() for q in model.parameters()) / 1e6:.0f}M weights", flush=True)
    model.cuda().train()
    bf16 = torch.cuda.is_bf16_supported() and torch.cuda.get_device_capability()[0] >= 8
    dtype = torch.bfloat16 if bf16 else torch.float16
    scaler = torch.amp.GradScaler("cuda", enabled=not bf16)
    opt = torch.optim.AdamW(trainable, lr=a.lr, weight_decay=0.0)
    sched = torch.optim.lr_scheduler.LambdaLR(opt, lambda s: min(1, (s + 1) / a.warmup) * max(0.05, 0.5 * (1 + math.cos(math.pi * min(s, a.steps) / a.steps))))
    loader = torch.utils.data.DataLoader(Clips(a, proc.feature_extractor), batch_size=a.batch, num_workers=a.workers,
                                         collate_fn=Collate(proc, model.config.decoder_start_token_id),
                                         persistent_workers=a.workers > 0, prefetch_factor=4 if a.workers else None)
    if a.bench_loader:
        t0, n = time.time(), 0
        for feats, _ in loader:
            n += len(feats)
            if time.time() - t0 > a.bench_loader:
                break
        print(f"loader alone: {n / (time.time() - t0):.1f} clips/s with {a.workers} workers", flush=True)
        return
    log = open(os.path.join(a.out, "log.jsonl"), "a")
    started, seen, losses = time.time(), 0, []

    def save(step):
        dest = os.path.join(a.out, f"step-{step}")
        model.save_pretrained(dest)
        proc.save_pretrained(dest)
        proc.feature_extractor.to_json_file(os.path.join(dest, "preprocessor_config.json"))
        print("saved", dest, flush=True)

    step = 0
    for feats, labels in loader:
        step += 1
        with torch.autocast("cuda", dtype=dtype):
            loss = model(input_features=feats.cuda(non_blocking=True), labels=labels.cuda(non_blocking=True)).loss
        scaler.scale(loss).backward()
        scaler.unscale_(opt)
        torch.nn.utils.clip_grad_norm_(trainable, 1.0)
        scaler.step(opt)
        scaler.update()
        opt.zero_grad(set_to_none=True)
        sched.step()
        seen += len(feats)
        losses.append(loss.item())
        elapsed = time.time() - started
        if step % 25 == 0:
            rec = {"step": step, "loss": round(sum(losses) / len(losses), 4), "clips": seen, "minutes": round(elapsed / 60, 1),
                   "clips_per_s": round(seen / elapsed, 1), "lr": opt.param_groups[0]["lr"]}
            losses = []
            log.write(json.dumps(rec) + "\n")
            log.flush()
            print(rec, flush=True)
        halt = step >= a.steps or os.path.exists(stop_file) or (a.minutes and elapsed > a.minutes * 60)
        if step % a.save_every == 0 or halt:
            save(step)
        if halt:
            print("stopping at step", step, flush=True)
            break


if __name__ == "__main__":
    main()
