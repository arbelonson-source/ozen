#!/usr/bin/env python3
"""Try Ozen's cloud caption services on one recording, with your own keys.

Each service whose key is set in the environment gets the recording the way
the app sends it (same address, settings and names list), and the script
prints what each one wrote, how long it took and, given the words that were
really said, the share of words it got wrong.

    python3 scripts/cloud-trial/try_cloud_services.py talk.wav --language he \\
        --reference "what was really said" --names "Dana" "Dr. Cohen"

Keys: SONIOX_API_KEY, DEEPGRAM_API_KEY, OPENAI_API_KEY, GROQ_API_KEY,
ELEVENLABS_API_KEY, GEMINI_API_KEY, SPEECHMATICS_API_KEY, ASSEMBLYAI_API_KEY.
A service without a key is skipped. The live services (Soniox, Speechmatics,
AssemblyAI) need `pip install 'websockets>=13'` and get the audio at the pace it
was spoken unless --fast is given. OpenRouter is left out: the app sends it
a long prompt, and its results are already in the README.

The recording must be 16 kHz, mono, 16-bit WAV, as the app sends:
    ffmpeg -i input.m4a -ar 16000 -ac 1 -c:a pcm_s16le talk.wav
"""

import argparse
import asyncio
import base64
import json
import os
import sys
import time
import unicodedata
import urllib.error
import urllib.parse
import urllib.request
import uuid
import wave
from collections.abc import Callable
from dataclasses import dataclass
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "model-release"))
from wer import NIKUD, edit_distance, normalize  # noqa: E402

MAX_TERMS = 100
CHUNK_SECONDS = 0.1
GEMINI_TAGS = {
    "he": "he-IL", "en": "en-US", "ar": "ar-EG", "ru": "ru-RU", "am": "am-ET", "fr": "fr-FR",
    "es": "es-ES", "uk": "uk-UA", "de": "de-DE", "pt": "pt-PT", "zh": "cmn-Hans-CN", "hi": "hi-IN",
}


@dataclass
class Request:
    url: str
    headers: dict
    body: bytes
    method: str = "POST"


@dataclass
class Stream:
    url: str
    headers: dict
    start: str | None
    end_message: Callable[[int], str]
    waits_for_start: bool = False


@dataclass
class Recording:
    wav: bytes
    pcm: bytes
    seconds: float


def read_recording(path: Path) -> Recording:
    with wave.open(str(path), "rb") as audio:
        if audio.getframerate() != 16000 or audio.getnchannels() != 1 or audio.getsampwidth() != 2:
            raise ValueError(
                f"{audio.getframerate()} Hz, {audio.getnchannels()} channel(s), {8 * audio.getsampwidth()}-bit, "
                "not 16000 Hz, 1 channel, 16-bit; convert it with: "
                f"ffmpeg -i {path} -ar 16000 -ac 1 -c:a pcm_s16le out.wav"
            )
        pcm = audio.readframes(audio.getnframes())
        seconds = audio.getnframes() / 16000
    return Recording(wav=path.read_bytes(), pcm=pcm, seconds=seconds)


def terms(names: list[str], cap: int = MAX_TERMS) -> list[str]:
    """The names list as the app sends it: trimmed, no repeats (ignoring case
    and Hebrew vowel marks), 40 letters each, the first 100."""
    seen, kept = set(), []
    for name in names:
        clipped = name.strip().strip(",;\u060c").strip()[:40]
        key = NIKUD.sub("", unicodedata.normalize("NFC", clipped)).casefold()
        if key and key not in seen:
            seen.add(key)
            kept.append(clipped)
    return kept[:cap]


def multipart(fields: list[tuple[str, str]], wav: bytes) -> tuple[str, bytes]:
    boundary = uuid.uuid4().hex
    body = b""
    for name, value in fields:
        body += f'--{boundary}\r\nContent-Disposition: form-data; name="{name}"\r\n\r\n{value}\r\n'.encode()
    body += (
        f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="speech.wav"\r\n'
        "Content-Type: audio/wav\r\n\r\n"
    ).encode() + wav + f"\r\n--{boundary}--\r\n".encode()
    return f"multipart/form-data; boundary={boundary}", body


def strict_query(pairs: list[tuple[str, str]]) -> str:
    return "&".join(f"{name}={urllib.parse.quote(value, safe='')}" for name, value in pairs)


def deepgram_terms(names: list[str], limit: int = 500) -> list[str]:
    """Whole names from the top within Deepgram's 500-token limit, counting
    each name's bytes and one more, as the app does."""
    kept, spent = [], 0
    for name in terms(names):
        spent += len(name.encode()) + 1
        if spent > limit:
            break
        kept.append(name)
    return kept


def deepgram(key: str, recording: Recording, language: str, names: list[str]) -> Request:
    pairs = [
        ("model", "nova-3"), ("language", language),
        ("punctuate", "true"), ("smart_format", "true"), ("utterances", "true"),
        ("diarize_model", "latest"), ("mip_opt_out", "true"),
    ]
    pairs += [("keyterm", term) for term in deepgram_terms(names)]
    return Request(
        url="https://api.deepgram.com/v1/listen?" + strict_query(pairs),
        headers={"Authorization": f"Token {key}", "Content-Type": "audio/wav"},
        body=recording.wav,
    )


def whisper_prompt(names: list[str], limit: int = 224) -> str | None:
    kept = []
    for name in terms(names):
        if len((" " + ", ".join(kept + [name]) + ".").encode()) > limit:
            break
        kept.append(name)
    return ", ".join(kept) + "." if kept else None


def openai_style(base: str, model: str, key: str, recording: Recording, language: str, names: list[str]) -> Request:
    fields = [("model", model), ("language", language), ("response_format", "json")]
    prompt = whisper_prompt(names)
    if prompt:
        fields.append(("prompt", prompt))
    content_type, body = multipart(fields, recording.wav)
    return Request(url=f"{base}/audio/transcriptions", headers={"Authorization": f"Bearer {key}", "Content-Type": content_type}, body=body)


def openai(key: str, recording: Recording, language: str, names: list[str]) -> Request:
    return openai_style("https://api.openai.com/v1", "gpt-4o-transcribe", key, recording, language, names)


def groq(key: str, recording: Recording, language: str, names: list[str]) -> Request:
    return openai_style("https://api.groq.com/openai/v1", "whisper-large-v3", key, recording, language, names)


def elevenlabs_terms(names: list[str]) -> list[str]:
    """Names ElevenLabs takes: five words at most, none of < > { } [ ] \\."""
    taken = [term for term in terms(names, cap=200) if len(term.split()) <= 5 and not set(term) & set("<>{}[]\\")]
    return taken[:MAX_TERMS]


def elevenlabs(key: str, recording: Recording, language: str, names: list[str]) -> Request:
    fields = [("model_id", "scribe_v2"), ("language_code", language), ("diarize", "true"), ("tag_audio_events", "false")]
    fields += [("keyterms", term) for term in elevenlabs_terms(names)]
    content_type, body = multipart(fields, recording.wav)
    return Request(url="https://api.elevenlabs.io/v1/speech-to-text", headers={"xi-api-key": key, "Content-Type": content_type}, body=body)


def gemini(key: str, recording: Recording, language: str, names: list[str]) -> Request:
    config = {}
    if language in GEMINI_TAGS:
        config["language_codes"] = [GEMINI_TAGS[language]]
    if terms(names):
        config["custom_vocabulary"] = terms(names)
    body = {
        "model": "gemini-3.5-transcribe",
        "input": [{"type": "audio", "mime_type": "audio/wav", "data": base64.b64encode(recording.wav).decode()}],
        "generation_config": {"transcription_config": config},
    }
    return Request(
        url="https://generativelanguage.googleapis.com/v1beta/interactions",
        headers={"x-goog-api-key": key, "Content-Type": "application/json"},
        body=json.dumps(body).encode(),
    )


def read_deepgram(reply: dict) -> str:
    utterances = reply.get("results", {}).get("utterances") or []
    if utterances:
        return " ".join(u["transcript"] for u in utterances if u.get("transcript"))
    channels = reply.get("results", {}).get("channels") or [{}]
    return (channels[0].get("alternatives") or [{}])[0].get("transcript", "")


def read_text(reply: dict) -> str:
    return reply.get("text", "")


def read_gemini(reply: dict) -> str:
    text = ""
    for step in reply.get("steps") or []:
        if step.get("type", "model_output") != "model_output":
            continue
        for part in step.get("content") or []:
            if part.get("type", "text") == "text":
                text += part.get("text", "")
    return text


def soniox(key: str, language: str, names: list[str]) -> Stream:
    config = {
        "model": "stt-rt-v5", "audio_format": "pcm_s16le", "sample_rate": 16000, "num_channels": 1,
        "language_hints": [language], "enable_speaker_diarization": True, "enable_endpoint_detection": True,
    }
    if terms(names):
        config["context"] = {"terms": terms(names)}
    return Stream(
        url="wss://stt-rt.soniox.com/transcribe-websocket",
        headers={"Authorization": f"Bearer {key}"},
        start=json.dumps(config, sort_keys=True),
        end_message=lambda chunks: "",
    )


def speechmatics(key: str, language: str, names: list[str]) -> Stream:
    settings = {
        "language": "cmn" if language == "zh" else language, "operating_point": "enhanced",
        "enable_partials": True, "max_delay": 2.0, "diarization": "speaker",
        "conversation_config": {"end_of_utterance_silence_trigger": 0.7},
    }
    if terms(names):
        settings["additional_vocab"] = terms(names)
    start = {
        "message": "StartRecognition",
        "audio_format": {"type": "raw", "encoding": "pcm_s16le", "sample_rate": 16000},
        "transcription_config": settings,
    }
    return Stream(
        url="wss://eu.rt.speechmatics.com/v2",
        headers={"Authorization": f"Bearer {key}"},
        start=json.dumps(start, sort_keys=True),
        end_message=lambda chunks: json.dumps({"message": "EndOfStream", "last_seq_no": chunks}),
        waits_for_start=True,
    )


def assemblyai(key: str, language: str, names: list[str]) -> Stream:
    pairs = [
        ("sample_rate", "16000"), ("encoding", "pcm_s16le"), ("speech_model", "universal-3-6-pro"),
        ("language_codes", json.dumps([language])), ("speaker_labels", "true"),
    ]
    if terms(names):
        pairs.append(("keyterms_prompt", json.dumps(terms(names), ensure_ascii=False)))
    return Stream(
        url="wss://streaming.assemblyai.com/v3/ws?" + strict_query(pairs),
        headers={"Authorization": key},
        start=None,
        end_message=lambda chunks: json.dumps({"type": "Terminate"}),
    )


@dataclass
class Heard:
    """What a live service has said so far, final words only."""

    spaced: bool = True
    text: str = ""
    started: bool = False
    finished: bool = False
    error: str | None = None
    attach_next: bool = False
    line_ended: bool = False
    voice: str | None = None

    def add(self, piece: str, joins: bool = False) -> None:
        if self.text and self.spaced and not joins:
            self.text += " "
        self.text += piece.strip()


def read_soniox(frame: dict, heard: Heard) -> None:
    if frame.get("error_code"):
        heard.error = f"{frame['error_code']} {frame.get('error_message', '')}".strip()
        return
    for token in frame.get("tokens") or []:
        if not token.get("is_final"):
            continue
        if token.get("text") == "<end>":
            heard.line_ended = True
            continue
        voice = token.get("speaker")
        if heard.line_ended or (voice and heard.voice and voice != heard.voice):
            heard.add(token["text"])
        else:
            heard.text += token["text"]
        heard.line_ended = False
        heard.voice = voice or heard.voice
    heard.finished = heard.finished or bool(frame.get("finished"))


def read_speechmatics(frame: dict, heard: Heard) -> None:
    kind = frame.get("message")
    if kind == "RecognitionStarted":
        heard.started = True
    elif kind == "AddTranscript":
        for result in frame.get("results") or []:
            alternatives = result.get("alternatives") or [{}]
            attaches = result.get("attaches_to") or ("previous" if result.get("type") == "punctuation" else "none")
            heard.add(alternatives[0].get("content", ""), joins=heard.attach_next or attaches in ("previous", "both"))
            heard.attach_next = attaches in ("next", "both")
    elif kind == "EndOfTranscript":
        heard.finished = True
    elif kind == "Error":
        heard.error = f"{frame.get('type')} {frame.get('reason', '')}".strip()


def read_assemblyai(frame: dict, heard: Heard) -> None:
    if frame.get("error"):
        heard.error = frame["error"]
    elif frame.get("type") == "Turn" and frame.get("end_of_turn") and frame.get("turn_is_formatted", True):
        heard.add(frame.get("transcript", ""))
    elif frame.get("type") == "Termination":
        heard.finished = True


HTTP = {
    "deepgram": (deepgram, read_deepgram, "DEEPGRAM_API_KEY"),
    "openai": (openai, read_text, "OPENAI_API_KEY"),
    "groq": (groq, read_text, "GROQ_API_KEY"),
    "elevenlabs": (elevenlabs, read_text, "ELEVENLABS_API_KEY"),
    "gemini": (gemini, read_gemini, "GEMINI_API_KEY"),
}
LIVE = {
    "soniox": (soniox, read_soniox, "SONIOX_API_KEY"),
    "speechmatics": (speechmatics, read_speechmatics, "SPEECHMATICS_API_KEY"),
    "assemblyai": (assemblyai, read_assemblyai, "ASSEMBLYAI_API_KEY"),
}


def send(request: Request) -> str:
    message = urllib.request.Request(request.url, data=request.body, headers=request.headers, method=request.method)
    try:
        with urllib.request.urlopen(message, timeout=120) as answer:
            return answer.read().decode()
    except urllib.error.HTTPError as error:
        raise RuntimeError(f"HTTP {error.code}: {error.read().decode(errors='replace')[:300]}") from None


async def listen(stream: Stream, reader, recording: Recording, spaced: bool, paced: bool) -> str:
    from websockets.asyncio.client import connect

    heard = Heard(spaced=spaced)
    async with connect(stream.url, additional_headers=stream.headers, max_size=1 << 22) as socket:
        if stream.start:
            await socket.send(stream.start)

        async def talk() -> None:
            while stream.waits_for_start and not heard.started:
                await asyncio.sleep(0.05)
            size = int(16000 * 2 * CHUNK_SECONDS)
            chunks = 0
            for offset in range(0, len(recording.pcm), size):
                await socket.send(recording.pcm[offset:offset + size])
                chunks += 1
                if paced:
                    await asyncio.sleep(CHUNK_SECONDS)
            await socket.send(stream.end_message(chunks))

        sender = asyncio.create_task(talk())
        async for frame in socket:
            if isinstance(frame, str):
                reader(json.loads(frame), heard)
            if heard.error or heard.finished:
                break
        sender.cancel()
        await asyncio.gather(sender, return_exceptions=True)
    if heard.error:
        raise RuntimeError(heard.error)
    return heard.text


def words_wrong(said: str, reference: str) -> float:
    truth = normalize(reference)
    return 100 * edit_distance(normalize(said), truth) / max(1, len(truth))


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("recording", type=Path)
    parser.add_argument(
        "--language", default="he", help="caption language code, as in Ozen (he, en, ar, ru, am, fr, es, uk, de, pt, zh, hi)"
    )
    parser.add_argument("--reference", help="the words really said, to count the share wrong")
    parser.add_argument("--names", nargs="*", default=[], help="names and important words, as in Ozen's lists")
    parser.add_argument("--only", nargs="*", help="services to try (default: every one with a key)")
    parser.add_argument("--fast", action="store_true", help="send audio to live services as fast as possible")
    options = parser.parse_args()
    try:
        recording = read_recording(options.recording)
    except (OSError, EOFError, ValueError, wave.Error) as problem:
        parser.error(f"{options.recording}: {problem}")
    print(f"{options.recording.name}: {recording.seconds:.1f} s, language {options.language}\n")
    for name, (build, reader, variable) in {**HTTP, **LIVE}.items():
        if options.only and name not in options.only:
            continue
        key = os.environ.get(variable, "").strip()
        if not key:
            print(f"{name}: skipped, no {variable}")
            continue
        started = time.monotonic()
        try:
            if name in HTTP:
                text = reader(json.loads(send(build(key, recording, options.language, options.names))))
            else:
                stream = build(key, options.language, options.names)
                text = asyncio.run(listen(stream, reader, recording, options.language != "zh", not options.fast))
        except ModuleNotFoundError:
            print(f"{name}: needs `pip install 'websockets>=13'`")
            continue
        except Exception as error:  # every failure is reported and the next service tried
            print(f"{name}: failed after {time.monotonic() - started:.1f} s: {error}")
            continue
        score = f", {words_wrong(text, options.reference):.1f}% of words wrong" if options.reference else ""
        print(f"{name}: {time.monotonic() - started:.1f} s{score}\n  {text}")


if __name__ == "__main__":
    main()
