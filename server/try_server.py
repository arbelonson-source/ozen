import asyncio
import json
import re
import sys
import time

import numpy as np
import soundfile as sf
import websockets

RATE = 16_000
CHUNK = 688
USAGE = "usage: python try_server.py ws://localhost:8765 CODE speech.wav [reference.txt]"


def normalized(text):
    text = re.sub("[\u0591-\u05c7]", "", text).replace("\u05be", " ").replace("-", " ")
    return " ".join(re.sub(r"[^\w\s]", " ", text).lower().split())


def words_wrong(reference, hypothesis):
    said, heard = normalized(reference).split(), normalized(hypothesis).split()
    row = list(range(len(heard) + 1))
    for i, word in enumerate(said, 1):
        previous, row[0] = row[0], i
        for j, other in enumerate(heard, 1):
            previous, row[j] = row[j], min(row[j] + 1, row[j - 1] + 1, previous + (word != other))
    return row[-1] / max(len(said), 1)

async def main(url, token, wav, reference=None):
    audio, rate = sf.read(wav, dtype="float32")
    if audio.ndim > 1:
        audio = audio.mean(1)
    if rate != RATE:
        raise SystemExit(f"{wav} is {rate} Hz; this needs 16 kHz mono")
    finals, first_words, delays = [], [], []
    live = 0
    async with websockets.connect(url, max_size=2**22) as ws:
        await ws.send(json.dumps({"type": "hello", "version": 1, "token": token, "language": "he", "vocabulary": []}))
        reply = json.loads(await ws.recv())
        if reply.get("type") != "ready":
            raise SystemExit(f"server refused: {reply}")
        print("connected:", reply.get("model"))
        start = time.monotonic()

        async def send():
            for i in range(0, len(audio), CHUNK):
                chunk = np.clip(audio[i:i + CHUNK], -1, 1)
                await ws.send((chunk * 32767).astype("<i2").tobytes())
                await asyncio.sleep(max(0.0, start + (i + CHUNK) / RATE - time.monotonic()))
            await ws.send(json.dumps({"type": "end"}))

        sender = asyncio.create_task(send())
        seen, shown = set(), {}
        async for message in ws:
            msg = json.loads(message)
            if msg.get("type") != "text":
                continue
            now = time.monotonic()
            lag = now - (start + msg.get("end_s", 0))
            number = msg["utterance"]
            text = msg["text"].strip() or shown.get(number, "")
            if msg["final"]:
                shown.pop(number, None)
            else:
                shown[number] = text
            if not text:
                continue
            if number not in seen:
                seen.add(number)
                first_words.append(lag)
            if msg["final"]:
                finals.append(text)
                delays.append(lag)
                print(f"  [{len(finals):3d}] {lag:5.2f}s  {text}")
            else:
                live += 1
        await sender

    print(f"\n{len(finals)} lines, {live} live updates")
    if delays:
        print(f"finished line on screen after it was said: median {np.median(delays):.2f}s, worst {max(delays):.2f}s")
        print(f"first words of a line on screen: median {np.median(first_words):.2f}s behind the audio")
    if reference:
        with open(reference, encoding="utf-8") as f:
            print(f"words wrong: {words_wrong(f.read(), ' '.join(finals)) * 100:.1f}%")


if __name__ == "__main__":
    if not 4 <= len(sys.argv) <= 5:
        raise SystemExit(USAGE)
    asyncio.run(main(*sys.argv[1:]))
