import asyncio
import base64
import io
import json
import sys
import tempfile
import types
import unittest
import wave
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))
import try_cloud_services as trial  # noqa: E402

FIXTURES = Path(__file__).resolve().parents[2] / "Tests" / "Fixtures" / "cloud"


def recording_file(folder: str, rate: int = 16000, channels: int = 1, frames: int = 16000) -> Path:
    path = Path(folder) / f"clip-{rate}-{channels}.wav"
    with wave.open(str(path), "wb") as audio:
        audio.setframerate(rate)
        audio.setnchannels(channels)
        audio.setsampwidth(2)
        audio.writeframes(b"\x01\x00" * frames * channels)
    return path


def sample() -> trial.Recording:
    return trial.Recording(wav=b"RIFFfake", pcm=b"\x00\x00" * 4800, seconds=0.3)


def lines(name: str) -> list[dict]:
    return [json.loads(line) for line in (FIXTURES / name).read_text().splitlines() if line.strip()]


def form_fields(request: trial.Request) -> list[tuple[str, str]]:
    boundary = request.headers["Content-Type"].split("boundary=")[1]
    fields = []
    for part in request.body.split(f"--{boundary}".encode())[1:-1]:
        head, _, value = part.partition(b"\r\n\r\n")
        name = head.decode().split('name="')[1].split('"')[0]
        fields.append((name, value[:-2].decode(errors="replace")))
    return fields


class RecordingTests(unittest.TestCase):
    def test_reads_the_format_the_app_sends(self):
        with tempfile.TemporaryDirectory() as folder:
            got = trial.read_recording(recording_file(folder, frames=8000))
        self.assertEqual(len(got.pcm), 16000)
        self.assertAlmostEqual(got.seconds, 0.5)
        self.assertTrue(got.wav.startswith(b"RIFF"))

    def test_refuses_other_formats_and_says_how_to_convert(self):
        with tempfile.TemporaryDirectory() as folder:
            for rate, channels in ((44100, 1), (16000, 2)):
                with self.assertRaises(ValueError) as caught:
                    trial.read_recording(recording_file(folder, rate, channels))
                self.assertIn("ffmpeg", str(caught.exception))
                self.assertIn("-ar 16000 -ac 1", str(caught.exception))

    def test_the_command_says_so_instead_of_a_traceback(self):
        with tempfile.TemporaryDirectory() as folder:
            for path in (recording_file(folder, 44100), Path(folder) / "missing.wav", Path(__file__)):
                with mock.patch.object(sys, "argv", ["try", str(path)]), mock.patch("sys.stderr", io.StringIO()) as said:
                    with self.assertRaises(SystemExit) as caught:
                        trial.main()
                self.assertEqual(caught.exception.code, 2)
                self.assertIn(path.name, said.getvalue())


class NamesTests(unittest.TestCase):
    def test_trimmed_without_repeats_or_list_commas(self):
        self.assertEqual(trial.terms(["  Avi, ", "avi", "Ruti;", "", "  "]), ["Avi", "Ruti"])

    def test_vowel_marks_do_not_make_a_new_name(self):
        self.assertEqual(trial.terms(["שָׁלוֹם", "שלום"]), ["שָׁלוֹם"])

    def test_clipped_to_40_letters_and_100_names(self):
        self.assertEqual(trial.terms(["x" * 50]), ["x" * 40])
        many = trial.terms([f"name{n}" for n in range(150)])
        self.assertEqual(len(many), 100)
        self.assertEqual(many[-1], "name99")

    def test_whisper_prompt_keeps_whole_names_within_224_bytes_with_its_leading_space(self):
        prompt = trial.whisper_prompt([f"שם{n:03d}" for n in range(60)])
        self.assertLessEqual(len((" " + prompt).encode()), 224)
        self.assertGreater(len((" " + prompt).encode()), 224 - 12)
        self.assertTrue(prompt.endswith("."))
        self.assertTrue(all(name.startswith("שם") and len(name) == 5 for name in prompt[:-1].split(", ")))
        name = "a" * 40
        self.assertEqual(trial.whisper_prompt([name, "b"], limit=len(" " + name + ".")), name + ".")
        self.assertIsNone(trial.whisper_prompt([name], limit=len(name + ".")))
        self.assertIsNone(trial.whisper_prompt([]))


class RequestTests(unittest.TestCase):
    def test_deepgram_spells_plus_signs_so_they_arrive(self):
        request = trial.deepgram("k", sample(), "en", ["C++", "Dana & Avi"])
        self.assertTrue(request.url.startswith("https://api.deepgram.com/v1/listen?model=nova-3&language=en&"))
        self.assertIn("keyterm=C%2B%2B", request.url)
        self.assertIn("keyterm=Dana%20%26%20Avi", request.url)
        self.assertIn("diarize_model=latest", request.url)
        self.assertIn("mip_opt_out=true", request.url)
        self.assertEqual(request.headers["Authorization"], "Token k")
        self.assertEqual(request.body, b"RIFFfake")

    def test_deepgram_chinese_goes_to_nova_3_with_names(self):
        request = trial.deepgram("k", sample(), "zh", ["Dana"])
        self.assertIn("model=nova-3&language=zh&", request.url)
        self.assertIn("diarize_model=latest", request.url)
        self.assertIn("keyterm=Dana", request.url)

    def test_deepgram_names_stay_within_its_limit(self):
        long = [letter * 40 for letter in "abcdefghijkl"]
        self.assertEqual(trial.deepgram_terms(long + ["z" * 7, "after"]), long + ["z" * 7])
        self.assertEqual(trial.deepgram_terms(long + ["z" * 8, "after"]), long)
        request = trial.deepgram("k", sample(), "en", long + ["z" * 8])
        self.assertEqual(request.url.count("keyterm="), 12)

    def test_openai_and_groq_send_the_names_as_a_prompt(self):
        for build, url, model in (
            (trial.openai, "https://api.openai.com/v1/audio/transcriptions", "gpt-4o-transcribe"),
            (trial.groq, "https://api.groq.com/openai/v1/audio/transcriptions", "whisper-large-v3"),
        ):
            request = build("k", sample(), "he", ["Avi", "Ruti"])
            self.assertEqual(request.url, url)
            self.assertEqual(request.headers["Authorization"], "Bearer k")
            self.assertEqual(
                form_fields(request),
                [("model", model), ("language", "he"), ("response_format", "json"), ("prompt", "Avi, Ruti."), ("file", "RIFFfake")],
            )
        self.assertNotIn("prompt", dict(form_fields(trial.groq("k", sample(), "he", []))))

    def test_elevenlabs_sends_each_name_as_its_own_field(self):
        request = trial.elevenlabs("k", sample(), "he", ["Avi", "Ruti"])
        self.assertEqual(request.headers["xi-api-key"], "k")
        self.assertEqual(
            form_fields(request),
            [("model_id", "scribe_v2"), ("language_code", "he"), ("diarize", "true"), ("tag_audio_events", "false"),
             ("keyterms", "Avi"), ("keyterms", "Ruti"), ("file", "RIFFfake")],
        )

    def test_elevenlabs_leaves_out_names_it_would_refuse(self):
        refused = [f"{name}{mark}x" for name, mark in zip(["Avi", "Ben", "Chen", "Dana", "Eli", "Gal", "Hila"], "<>{}[]\\", strict=True)]
        names = ["one two three four five", "one two three four five six"] + refused + ["Dana"] + [f"n{n}" for n in range(1, 101)]
        taken = trial.elevenlabs_terms(names)
        self.assertEqual(taken[:2], ["one two three four five", "Dana"])
        self.assertEqual(len(taken), 100)
        self.assertEqual(taken[-1], "n98")

    def test_gemini_names_the_language_by_region(self):
        request = trial.gemini("k", sample(), "zh", ["Dana"])
        body = json.loads(request.body)
        self.assertEqual(request.headers["x-goog-api-key"], "k")
        self.assertEqual(body["model"], "gemini-3.5-transcribe")
        self.assertEqual(base64.b64decode(body["input"][0]["data"]), b"RIFFfake")
        self.assertEqual(
            body["generation_config"]["transcription_config"], {"language_codes": ["cmn-Hans-CN"], "custom_vocabulary": ["Dana"]}
        )
        self.assertEqual(json.loads(trial.gemini("k", sample(), "xx", []).body)["generation_config"]["transcription_config"], {})


class StreamSettingsTests(unittest.TestCase):
    def test_soniox_settings_message(self):
        stream = trial.soniox("k", "he", ["Avi"])
        settings = json.loads(stream.start)
        self.assertEqual(settings["model"], "stt-rt-v5")
        self.assertEqual(settings["language_hints"], ["he"])
        self.assertEqual(settings["context"], {"terms": ["Avi"]})
        self.assertNotIn("context", json.loads(trial.soniox("k", "he", []).start))
        self.assertEqual(stream.end_message(7), "")
        self.assertFalse(stream.waits_for_start)

    def test_speechmatics_waits_for_start_and_counts_chunks(self):
        stream = trial.speechmatics("k", "zh", ["Dana"])
        settings = json.loads(stream.start)["transcription_config"]
        self.assertEqual(settings["language"], "cmn")
        self.assertEqual(settings["additional_vocab"], ["Dana"])
        self.assertEqual(settings["conversation_config"], {"end_of_utterance_silence_trigger": 0.7})
        self.assertTrue(stream.waits_for_start)
        self.assertEqual(json.loads(stream.end_message(7)), {"message": "EndOfStream", "last_seq_no": 7})

    def test_assemblyai_puts_its_settings_in_the_address(self):
        stream = trial.assemblyai("k", "he", ["C++", "אבי"])
        self.assertTrue(stream.url.startswith("wss://streaming.assemblyai.com/v3/ws?sample_rate=16000&encoding=pcm_s16le&"))
        self.assertIn("language_codes=%5B%22he%22%5D", stream.url)
        self.assertIn("keyterms_prompt=%5B%22C%2B%2B%22%2C%20%22%D7%90%D7%91%D7%99%22%5D", stream.url)
        self.assertEqual(stream.headers, {"Authorization": "k"})
        self.assertIsNone(stream.start)
        self.assertEqual(json.loads(stream.end_message(3)), {"type": "Terminate"})


class ReaderTests(unittest.TestCase):
    def test_file_replies(self):
        deepgram = json.loads((FIXTURES / "deepgram-two-speakers.json").read_text())
        self.assertEqual(trial.read_deepgram(deepgram), "מה שלומך? טוב, תודה. ואתה?")
        self.assertEqual(trial.read_deepgram({"results": {"channels": [{"alternatives": [{"transcript": "hi"}]}]}}), "hi")
        self.assertEqual(trial.read_text(json.loads((FIXTURES / "elevenlabs-two-speakers.json").read_text())), "מה שלומך? טוב, תודה. ואתה?")
        self.assertEqual(trial.read_gemini(json.loads((FIXTURES / "gemini-transcribe.json").read_text())), "מה שלומך? טוב, תודה.")

    def test_deepgram_reads_the_speakers_turns_before_the_whole(self):
        reply = {"results": {"utterances": [{"transcript": "one"}, {"transcript": ""}, {"transcript": "two"}],
                             "channels": [{"alternatives": [{"transcript": "whole"}]}]}}
        self.assertEqual(trial.read_deepgram(reply), "one two")

    def test_gemini_keeps_only_the_text_it_wrote(self):
        reply = {"steps": [{"type": "thought", "content": [{"type": "text", "text": "thinking"}]},
                           {"content": [{"text": "a "}, {"type": "audio", "text": "x"}]},
                           {"type": "model_output", "content": [{"type": "text", "text": "b"}]}]}
        self.assertEqual(trial.read_gemini(reply), "a b")

    def live(self, reader, name: str) -> trial.Heard:
        heard = trial.Heard()
        for frame in lines(name):
            reader(frame, heard)
        return heard

    def test_live_replies_keep_the_final_words_with_a_space_between_lines(self):
        for reader, name, started in (
            (trial.read_soniox, "soniox-two-speakers.jsonl", False),
            (trial.read_speechmatics, "speechmatics-two-speakers.jsonl", True),
        ):
            heard = self.live(reader, name)
            self.assertEqual(heard.text, "מה שלומך? טוב, תודה. ואתה? מצוין", name)
            self.assertTrue(heard.finished, name)
            self.assertEqual(heard.started, started, name)
        heard = self.live(trial.read_assemblyai, "assemblyai-two-speakers.jsonl")
        self.assertEqual(heard.text, "מה שלומך? טוב, תודה. ואתה? מצוין.")
        self.assertTrue(heard.finished)

    def test_a_line_end_from_the_same_voice_still_parts_the_words(self):
        heard = trial.Heard()
        trial.read_soniox({"tokens": [{"text": "שלום", "is_final": True, "speaker": "1"}, {"text": "<end>", "is_final": True}]}, heard)
        then = [{"text": "מה", "is_final": True, "speaker": "1"}, {"text": " נשמע", "is_final": True, "speaker": "1"}]
        trial.read_soniox({"tokens": then}, heard)
        self.assertEqual(heard.text, "שלום מה נשמע")

    def test_speechmatics_marks_that_open_a_word_join_the_next_one(self):
        heard = trial.Heard()
        trial.read_speechmatics({"message": "AddTranscript", "results": [
            {"type": "word", "alternatives": [{"content": "Hola"}]},
            {"type": "punctuation", "attaches_to": "next", "alternatives": [{"content": "¿"}]},
            {"type": "word", "alternatives": [{"content": "qué"}]},
            {"type": "punctuation", "alternatives": [{"content": "?"}]},
        ]}, heard)
        self.assertEqual(heard.text, "Hola ¿qué?")

    def test_assemblyai_waits_for_the_punctuated_turn(self):
        heard = trial.Heard()
        trial.read_assemblyai({"type": "Turn", "end_of_turn": True, "turn_is_formatted": False, "transcript": "hello there"}, heard)
        trial.read_assemblyai({"type": "Turn", "end_of_turn": True, "turn_is_formatted": True, "transcript": "Hello there."}, heard)
        self.assertEqual(heard.text, "Hello there.")

    def test_chinese_lines_join_without_a_space(self):
        heard = trial.Heard(spaced=False)
        trial.read_soniox({"tokens": [{"text": "你好", "is_final": True, "speaker": "1"}, {"text": "<end>", "is_final": True}]}, heard)
        trial.read_soniox({"tokens": [{"text": "谢谢", "is_final": True, "speaker": "2"}, {"text": "再见", "is_final": False}]}, heard)
        self.assertEqual(heard.text, "你好谢谢")

    def test_errors_are_kept(self):
        heard = trial.Heard()
        trial.read_soniox({"error_code": 401, "error_message": "Invalid API key."}, heard)
        self.assertEqual(heard.error, "401 Invalid API key.")
        heard = trial.Heard()
        trial.read_speechmatics({"message": "Error", "type": "not_authorised", "reason": "bad key"}, heard)
        self.assertEqual(heard.error, "not_authorised bad key")
        heard = trial.Heard()
        trial.read_assemblyai({"error": "Insufficient balance"}, heard)
        self.assertEqual(heard.error, "Insufficient balance")


class FakeSocket:
    """Says its first frame at once and the rest only after the audio's end
    message, as a service finishes after it has heard everything."""

    def __init__(self, frames: list[str], opening_turns: int = 0):
        self.frames = frames
        self.opening_turns = opening_turns
        self.sent: list = []
        self.sent_before_first_frame = None

    async def __aenter__(self):
        return self

    async def __aexit__(self, *problem):
        return False

    async def send(self, data):
        self.sent.append(data)

    def ended(self) -> bool:
        return any(isinstance(item, bytes) for item in self.sent) and isinstance(self.sent[-1], str)

    async def __aiter__(self):
        for _ in range(self.opening_turns):
            await asyncio.sleep(0)
        self.sent_before_first_frame = list(self.sent)
        for number, frame in enumerate(self.frames):
            while number > 0 and not self.ended():
                await asyncio.sleep(0)
            yield frame


class ListenTests(unittest.TestCase):
    def setUp(self):
        self.opened = []
        client = types.ModuleType("websockets.asyncio.client")

        def connect(url, additional_headers=None, max_size=None):
            self.opened.append((url, additional_headers))
            return self.socket

        client.connect = connect
        names = ("websockets", "websockets.asyncio", "websockets.asyncio.client")
        self.modules = {name: sys.modules.get(name) for name in names}
        sys.modules.update(dict(zip(names, (types.ModuleType(names[0]), types.ModuleType(names[1]), client), strict=True)))

    def tearDown(self):
        for name, module in self.modules.items():
            if module is None:
                sys.modules.pop(name, None)
            else:
                sys.modules[name] = module

    def listen(self, stream, reader):
        return asyncio.run(asyncio.wait_for(trial.listen(stream, reader, sample(), spaced=True, paced=False), 5))

    def test_speechmatics_hears_the_whole_recording_after_it_starts(self):
        self.socket = FakeSocket((FIXTURES / "speechmatics-two-speakers.jsonl").read_text().splitlines(), opening_turns=20)
        stream = trial.speechmatics("k", "he", [])
        self.assertEqual(self.listen(stream, trial.read_speechmatics), "מה שלומך? טוב, תודה. ואתה? מצוין")
        self.assertEqual(self.opened, [("wss://eu.rt.speechmatics.com/v2", {"Authorization": "Bearer k"})])
        self.assertEqual(self.socket.sent_before_first_frame, [stream.start])
        audio = self.socket.sent[1:-1]
        self.assertEqual(b"".join(audio), sample().pcm)
        self.assertEqual(len(audio), 3)
        self.assertEqual(json.loads(self.socket.sent[-1]), {"message": "EndOfStream", "last_seq_no": 3})

    def test_speechmatics_sends_no_audio_before_it_starts(self):
        self.socket = FakeSocket([json.dumps({"message": "Error", "type": "not_authorised", "reason": "bad key"})])
        with self.assertRaisesRegex(RuntimeError, "not_authorised"):
            self.listen(trial.speechmatics("k", "he", []), trial.read_speechmatics)
        self.assertEqual(len(self.socket.sent), 1)

    def test_assemblyai_sends_audio_then_terminate(self):
        frames = [json.dumps({"type": "Begin"})] + (FIXTURES / "assemblyai-two-speakers.jsonl").read_text().splitlines()
        self.socket = FakeSocket(frames, opening_turns=20)
        self.assertEqual(self.listen(trial.assemblyai("k", "he", []), trial.read_assemblyai), "מה שלומך? טוב, תודה. ואתה? מצוין.")
        self.assertEqual(json.loads(self.socket.sent[-1]), {"type": "Terminate"})
        self.assertTrue(all(isinstance(item, bytes) for item in self.socket.sent[:-1]))
        early_audio = [item for item in self.socket.sent_before_first_frame if isinstance(item, bytes)]
        self.assertEqual(b"".join(early_audio), sample().pcm)


class ScoreTests(unittest.TestCase):
    def test_share_of_words_wrong(self):
        self.assertEqual(trial.words_wrong("מה שלומך? טוב, תודה.", "מה שלומך טוב תודה"), 0)
        self.assertEqual(trial.words_wrong("מה שלומך", "מה שלומך טוב תודה"), 50)
        self.assertEqual(trial.words_wrong("anything", ""), 100)


if __name__ == "__main__":
    unittest.main()
