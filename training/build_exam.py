"""Builds the fixed exam every candidate model is scored on.

Speech never used in training: 400 KAN validation clips (broadcast) and 300
FLEURS he test sentences (read speech, punctuated references). Conditions:
clean; far15 / far5 = a real recorded room response (OpenSLR-28 real RIRs)
plus that corpus's real recorded room noise at 15 / 5 dB; home5 = DEMAND
kitchen / living room / washing machine at 5 dB; tv10 = another Hebrew voice
(KAN train-00000 rows < 900, the old benches' TV track) at 10 dB. Plus 60
noise-only pieces to count lines read out of nothing. Saved as int16 so the
same audio is scored on this PC and on the rented machine.
"""
import glob, io, json, os, tarfile
import numpy as np, pyarrow.parquet as pq, soundfile as sf
from scipy.signal import fftconvolve

R = 16000
HOME = os.path.expanduser("~")
HUB = os.environ.get("OZEN_HUB", f"{HOME}/ozen-accuracy/hf/hub")
RIRS = os.environ.get("OZEN_RIRS", f"{HOME}/ozen-accuracy/rirs/RIRS_NOISES")
DEMAND = os.environ.get("OZEN_DEMAND", f"{HOME}/ozen-accuracy/demand")
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "exam")
rng = np.random.default_rng(20261006)


def rms(x):
    return float(np.sqrt(np.mean(np.square(x))) + 1e-9)


def mono16k(a, sr):
    a = a if a.ndim == 1 else a[:, 0]
    if sr != R:
        idx = np.arange(0, len(a) * R / sr) * sr / R
        a = np.interp(idx, np.arange(len(a)), a)
    return a.astype(np.float32)


def kan(name, rows):
    f = glob.glob(f"{HUB}/datasets--imvladikon--hebrew_speech_kan/snapshots/*/data/{name}")[0]
    t = pq.read_table(f, columns=["audio", "sentence"])
    for i, r in enumerate(t.slice(0, rows).to_pylist()):
        a, sr = sf.read(io.BytesIO(r["audio"]["bytes"]), dtype="float32")
        yield i, mono16k(a, sr), (r["sentence"] or "").strip()


speech = []
for i, a, text in kan("validation-*.parquet", 2000):
    if text and R < len(a) < 28 * R:
        speech.append({"source": "kan", "id": f"kan-val-{i}", "ref": text, "audio": a})
    if sum(s["source"] == "kan" for s in speech) == 400:
        break

fl = glob.glob(f"{HUB}/datasets--google--fleurs/snapshots/*/data/he_il")[0]
tar = tarfile.open(f"{fl}/audio/test.tar.gz")
members = {os.path.basename(m.name): m for m in tar.getmembers() if m.name.endswith(".wav")}
seen = set()
for line in open(f"{fl}/test.tsv", encoding="utf-8"):
    cols = line.rstrip("\n").split("\t")
    sid, wav, raw = cols[0], cols[1], cols[2].strip('"').replace('""', '"')
    if sid in seen or wav not in members:
        continue
    a, sr = sf.read(io.BytesIO(tar.extractfile(members[wav]).read()), dtype="float32")
    a = mono16k(a, sr)
    if R < len(a) < 28 * R:
        seen.add(sid)
        speech.append({"source": "fleurs", "id": f"fleurs-{wav[:-4]}", "ref": raw, "audio": a})
    if len(seen) == 300:
        break

tv = np.concatenate([a for i, a, _ in kan("train-00000-*.parquet", 900)])
base = f"{RIRS}/real_rirs_isotropic_noises"
rir_files = sorted(f for f in glob.glob(f"{base}/*.wav") if "_rir_" in os.path.basename(f))
noise_files = sorted(f for f in glob.glob(f"{base}/*.wav") if "noise" in os.path.basename(f).lower())
demand = sorted(glob.glob(f"{DEMAND}/*/ch*.wav"))
noises = [mono16k(*sf.read(f, dtype="float32")) for f in noise_files]
homes = [mono16k(*sf.read(f, dtype="float32")) for f in demand]
print(len(speech), "speech clips,", len(rir_files), "rooms,", len(noises), "room noises,", len(homes), "home noises", flush=True)


def piece(track, n):
    if len(track) <= n:
        track = np.tile(track, n // len(track) + 1)
    o = rng.integers(0, len(track) - n)
    return track[o:o + n].copy()


def mix(clean, noise, snr):
    return clean + noise * (rms(clean) / (rms(noise) * 10 ** (snr / 20)))


def level(x, dbfs=-30.0):
    x = x * (10 ** (dbfs / 20) / rms(x))
    return np.clip(x, -1, 1)


items, audio = [], {}
for s in speech:
    a = s["audio"]
    h = mono16k(*sf.read(rir_files[rng.integers(len(rir_files))], dtype="float32"))
    h = h[np.argmax(np.abs(h)):]
    wet = fftconvolve(a, h)[: len(a)].astype(np.float32)
    room = piece(noises[rng.integers(len(noises))], len(a))
    conds = {
        "clean": a,
        "far15": mix(wet, room, 15),
        "far5": mix(wet, room, 5),
        "home5": mix(a, piece(homes[rng.integers(len(homes))], len(a)), 5),
        "tv10": mix(a, piece(tv, len(a)), 10),
    }
    for cond, x in conds.items():
        key = f"{s['id']}/{cond}"
        audio[key] = (level(x) * 32767).astype(np.int16)
        items.append({"key": key, "source": s["source"], "cond": cond, "ref": s["ref"], "seconds": len(a) / R})

for i in range(60):
    track = homes[rng.integers(len(homes))] if i < 30 else noises[rng.integers(len(noises))]
    key = f"noise-{i}/noise"
    audio[key] = (level(piece(track, 6 * R)) * 32767).astype(np.int16)
    items.append({"key": key, "source": "noise", "cond": "noise", "ref": "", "seconds": 6.0})

os.makedirs(OUT, exist_ok=True)
np.savez(f"{OUT}/exam.npz", **{k.replace("/", "|"): v for k, v in audio.items()})
json.dump(items, open(f"{OUT}/exam.json", "w", encoding="utf-8"), ensure_ascii=False)
minutes = sum(i["seconds"] for i in items) / 60
print(len(items), "items,", round(minutes, 1), "minutes of audio ->", OUT, flush=True)
