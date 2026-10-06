import os, time, zipfile
from huggingface_hub import hf_hub_download, snapshot_download

HERE = os.path.dirname(os.path.abspath(__file__))
HUB = os.path.join(HERE, "hf")
started = time.time()


def get(repo, patterns, kind="dataset"):
    p = snapshot_download(repo, repo_type=kind, allow_patterns=patterns, cache_dir=HUB, max_workers=16)
    print(f"{repo}: {p} ({time.time() - started:.0f}s)", flush=True)


get("ivrit-ai/whisper-large-v3-turbo", ["*.json", "*.safetensors", "*.txt"], "model")
get("ivrit-ai/whisper-large-v3-turbo-ct2", ["*"], "model")
get("ivrit-ai/eval-whatsapp", ["data/*"])
get("ivrit-ai/eval-d1", ["data/*"])
get("ivrit-ai/crowd-recital-whisper-training", ["data/train-*"])
get("ivrit-ai/whisper-training", ["data/train-*"])
get("ivrit-ai/crowd-transcribe-v5", ["data/train-*"])
rirs = os.path.join(HERE, "rirs")
if not os.path.isdir(os.path.join(rirs, "RIRS_NOISES")):
    z = hf_hub_download("EaseZh/rirs_noises", "rirs_noises.zip", repo_type="dataset", cache_dir=HUB)
    zipfile.ZipFile(z).extractall(rirs)
print(f"ALL DONE in {time.time() - started:.0f}s", flush=True)
