import json, os, re, sys, time
import zlib
import numpy as np, jiwer, ctranslate2

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "server"))
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "server"))

if os.name == "nt":
    import importlib
    for m in ("nvidia.cublas", "nvidia.cudnn"):
        try:
            d = os.path.join(list(importlib.import_module(m).__path__)[0], "bin")
            os.add_dll_directory(d)
            os.environ["PATH"] = d + os.pathsep + os.environ["PATH"]
        except Exception:
            pass
from ozen_server import speech_gain, lacks_voice
from faster_whisper import WhisperModel

HERE = os.path.dirname(os.path.abspath(__file__))
NIQQUD = re.compile("[֑-ׇ]")
PUNCT = re.compile(r"[.,?!:;]")


def norm(t):
    t = NIQQUD.sub("", t).replace("־", " ").replace("-", " ")
    t = re.sub(r"[^\w\s]", " ", t)
    return " ".join(t.lower().split())


model_path, label = sys.argv[1], sys.argv[2]
limit = int(os.environ.get("EXAM_LIMIT", "0"))
beam = int(os.environ.get("EXAM_BEAM", "1"))
items = json.load(open(f"{HERE}/exam/exam.json", encoding="utf-8"))
if limit:
    keep = {i["key"].split("/")[0] for i in items if i["source"] != "noise"}
    keep = set(sorted(keep)[:limit])
    items = [i for i in items if i["key"].split("/")[0] in keep or i["source"] == "noise"]
audio = np.load(f"{HERE}/exam/exam.npz")
model = WhisperModel(model_path, device="cuda", compute_type="float16")
os.makedirs(f"{HERE}/results", exist_ok=True)
out = open(f"{HERE}/results/{label}.jsonl", "w", encoding="utf-8")
started = time.time()
for n, it in enumerate(items):
    x = audio[it["key"].replace("/", "|")].astype(np.float32) / 32767
    ctranslate2.set_random_seed(zlib.crc32(it["key"].encode()))
    segs, _ = model.transcribe(
        speech_gain(x), language="he", task="transcribe", beam_size=beam,
        temperature=[0.0, 0.2, 0.4], condition_on_previous_text=False, without_timestamps=True,
        vad_filter=False, compression_ratio_threshold=2.4, log_prob_threshold=-1.0,
    )
    it = dict(it, hyp=" ".join(s.text.strip() for s in segs).strip(), gated=bool(lacks_voice(x, 0.05)))
    out.write(json.dumps(it, ensure_ascii=False) + "\n")
    if n % 500 == 0:
        print(f"{n}/{len(items)} {time.time() - started:.0f}s", flush=True)
out.close()

rows = [json.loads(l) for l in open(f"{HERE}/results/{label}.jsonl", encoding="utf-8")]
summary = {"label": label, "model": model_path, "items": len(rows), "seconds": round(time.time() - started)}
for source in ("kan", "fleurs"):
    for cond in ("clean", "far15", "far5", "home5", "tv10"):
        sel = [r for r in rows if r["source"] == source and r["cond"] == cond]
        refs, hyps = [norm(r["ref"]) for r in sel], [norm(r["hyp"]) for r in sel]
        summary[f"{source}/{cond}"] = round(100 * jiwer.wer(refs, hyps), 2)
fl = [r for r in rows if r["source"] == "fleurs" and r["cond"] == "clean" and r["hyp"]]
summary["fleurs/clean punctuated %"] = round(100 * sum(bool(PUNCT.search(r["hyp"])) for r in fl) / max(len(fl), 1), 1)
noise = [r for r in rows if r["source"] == "noise"]
summary["noise pieces with words"] = sum(bool(norm(r["hyp"])) for r in noise)
summary["noise pieces past the voice check"] = sum(not r["gated"] for r in noise)
summary["...of those with words"] = sum(bool(norm(r["hyp"])) and not r["gated"] for r in noise)
json.dump(summary, open(f"{HERE}/results/{label}.summary.json", "w"), indent=1, ensure_ascii=False)
for k, v in summary.items():
    print(f"{k:28s} {v}")
