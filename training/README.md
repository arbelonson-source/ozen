# Training

Teaches ivrit.ai's Whisper large-v3-turbo to hear across a room. Only the
encoder (the listening half) learns; the decoder (the writing half, which
holds punctuation, names and style) stays frozen. The main README's
[Training section](../README.md#training-a-model-that-hears-across-the-room)
has the why and the current status.

| Script | What it does |
| --- | --- |
| `download.py` | Fetches the base model, its CTranslate2 copy, the three ivrit.ai training sets, two ivrit.ai test sets and OpenSLR-28's rooms and noises into `hf/` and `rirs/`, about 33 GB. OpenSLR's own site was too slow from a rented machine, so the rooms come from a byte-identical copy on Hugging Face. |
| `build_exam.py` | Builds the fixed exam: 400 KAN validation clips and 300 FLEURS test sentences, each clean, through a real recorded room (OpenSLR-28 real room responses with that corpus's real noise) at 15 and 5 dB, under DEMAND kitchen, living-room and washing-machine noise at 5 dB, and under another Hebrew voice at 10 dB, plus 60 noise-only pieces. Saved as 16-bit audio so every machine scores the same sound. |
| `train_encoder.py` | The training. Each clip is heard clean, through one of OpenSLR-28's simulated rooms with point-source noise or hiss, under noise, or under another voice, at a random level, then through the home server's `speech_gain`, as at inference. Saves `step-N` folders; stops at `--steps`, `--minutes`, or when a file named `STOP` appears next to `--out`. |
| `score.sh` | Converts a saved step to CTranslate2 float16 and runs the exam on it. |
| `exam.py` | Scores one model on the exam the way the home server decodes (greedy with the 0.2 / 0.4 fallback, no previous-text context), with the fallback's random seed fixed per clip, so a model always gets the same score; unseeded, two runs of one model differed by 1.5 points. `EXAM_BEAM=5` scores it the way the server writes finished lines. Reports words wrong per source and condition, the share of read sentences that keep punctuation, and how many noise-only pieces come back with words, also counting only those the server's voice check lets through. |
| `exam_long.py` | Scores whole real recordings: ivrit.ai's 54 WhatsApp voice notes and 17 five-minute talks, as recorded and through a real room with real noise at 10 dB. |
| `compare.py` | Exam summaries side by side, with the change against the first. |

The rooms, noises and voice in the exam are recordings training never
uses: training gets OpenSLR-28's simulated rooms and point-source noises,
the exam its real recorded rooms and real room noise, DEMAND and a KAN TV
track.

## Running it

```sh
python3 -m venv venv && venv/bin/pip install torch transformers ctranslate2 faster-whisper \
    jiwer pyarrow scipy soundfile huggingface_hub nvidia-cublas-cu12 nvidia-cudnn-cu12
venv/bin/python download.py
venv/bin/python build_exam.py      # needs KAN, FLEURS he_il, DEMAND and OpenSLR-28 locally
venv/bin/python -u train_encoder.py \
    --data "hf/datasets--ivrit-ai--crowd-recital-whisper-training/snapshots/*/data/train-*.parquet@3" \
           "hf/datasets--ivrit-ai--whisper-training/snapshots/*/data/train-*.parquet" \
           "hf/datasets--ivrit-ai--crowd-transcribe-v5/snapshots/*/data/train-*.parquet" \
    --base hf/models--ivrit-ai--whisper-large-v3-turbo/snapshots/<hash> \
    --rirs rirs/RIRS_NOISES --out runs/a1 --batch 32 --steps 8000 --save-every 2000 --checkpointing
./score.sh runs/a1/step-2000 a1-2000
venv/bin/python compare.py base a1-2000
```

`@3` after a pattern means its files are read three times as often. The
first run used one RTX PRO 6000 (96 GB): batch 32 in bfloat16 with
gradient checkpointing took about 31 GB and heard about 18 clips a second.
