import glob, io, json, os, re, sys, time, zlib
import ctranslate2
import numpy as np, jiwer, pyarrow.parquet as pq, soundfile as sf
from scipy.signal import fftconvolve

R = 16000
HOME = os.path.expanduser("~")
HERE = os.path.dirname(os.path.abspath(__file__))
HUB = os.environ.get("OZEN_HUB", f"{HOME}/ozen-accuracy/hf/hub")
RIRS = os.environ.get("OZEN_RIRS", f"{HOME}/ozen-accuracy/rirs/RIRS_NOISES")

if os.name == "nt":
    import importlib
    for m in ("nvidia.cublas", "nvidia.cudnn"):
        try:
            d = os.path.join(list(importlib.import_module(m).__path__)[0], "bin")
            os.add_dll_directory(d)
            os.environ["PATH"] = d + os.pathsep + os.environ["PATH"]
        except Exception:
            pass
NIQQUD = re.compile("[֑-ׇ]")


def norm(t):
    t = NIQQUD.sub("", t).replace("־", " ").replace("-", " ")
    t = re.sub(r"[^\w\s]", " ", t)
    return " ".join(t.lower().split())


def mono16k(a, sr):
    a = a if a.ndim == 1 else a.mean(1)
    if sr != R:
        idx = np.arange(0, len(a) * R / sr) * sr / R
        a = np.interp(idx, np.arange(len(a)), a)
    return a.astype(np.float32)


def rms(x):
    return float(np.sqrt(np.mean(np.square(x))) + 1e-9)


from faster_whisper import WhisperModel

model_path, label = sys.argv[1], sys.argv[2]
rng = np.random.default_rng(7)
base = f"{RIRS}/real_rirs_isotropic_noises"
rirs = sorted(f for f in glob.glob(f"{base}/*.wav") if "_rir_" in os.path.basename(f))
noises = sorted(f for f in glob.glob(f"{base}/*.wav") if "noise" in os.path.basename(f).lower())
model = WhisperModel(model_path, device="cuda", compute_type="float16")
rows, started = [], time.time()
for name in ("eval-whatsapp", "eval-d1"):
    f = glob.glob(f"{HUB}/datasets--ivrit-ai--{name}/snapshots/*/data/*.parquet")[0]
    for i, r in enumerate(pq.read_table(f).to_pylist()):
        a = mono16k(*sf.read(io.BytesIO(r["audio"]["bytes"]), dtype="float32"))
        h = mono16k(*sf.read(rirs[rng.integers(len(rirs))], dtype="float32"))
        n = mono16k(*sf.read(noises[rng.integers(len(noises))], dtype="float32"))
        wet = fftconvolve(a, h[np.argmax(np.abs(h)):])[: len(a)].astype(np.float32)
        n = np.resize(n, len(a))
        far = wet + n * (rms(wet) / (rms(n) * 10 ** (10 / 20)))
        far = np.clip(far * (0.1 / rms(far)), -1, 1).astype(np.float32)
        for cond, x in (("as recorded", a), ("far10", far)):
            ctranslate2.set_random_seed(zlib.crc32(f"{name}/{i}/{cond}".encode()))
            segs, _ = model.transcribe(x, language="he", beam_size=1, temperature=[0.0, 0.2, 0.4],
                                       condition_on_previous_text=False, vad_filter=True,
                                       compression_ratio_threshold=2.4, log_prob_threshold=-1.0)
            rows.append({"set": name, "i": i, "cond": cond, "ref": r["text"], "hyp": " ".join(s.text.strip() for s in segs)})
        print(name, i, f"{time.time() - started:.0f}s", flush=True)
os.makedirs(f"{HERE}/results", exist_ok=True)
with open(f"{HERE}/results/{label}.long.jsonl", "w", encoding="utf-8") as out:
    for r in rows:
        out.write(json.dumps(r, ensure_ascii=False) + "\n")
summary = {"label": label, "model": model_path}
for name in ("eval-whatsapp", "eval-d1"):
    for cond in ("as recorded", "far10"):
        sel = [r for r in rows if r["set"] == name and r["cond"] == cond]
        summary[f"{name}/{cond}"] = round(100 * jiwer.wer([norm(r["ref"]) for r in sel], [norm(r["hyp"]) for r in sel]), 2)
json.dump(summary, open(f"{HERE}/results/{label}.long.summary.json", "w"), indent=1, ensure_ascii=False)
for k, v in summary.items():
    print(f"{k:28s} {v}")
