import asyncio
import json
import logging
import os
import shutil
import sys
import tempfile
import threading
import time
import types
import unittest
from unittest import mock

import numpy as np

fake = types.ModuleType("faster_whisper")
fake.WhisperModel = object
fake_utils = types.ModuleType("faster_whisper.utils")
fake_utils.download_model = lambda name, **kw: name
fake_vad = types.ModuleType("faster_whisper.vad")
fake_vad.VadOptions = lambda **kw: kw
fake_vad.get_speech_timestamps = lambda *a, **kw: []
sys.modules.setdefault("faster_whisper", fake)
sys.modules.setdefault("faster_whisper.vad", fake_vad)
sys.modules.setdefault("faster_whisper.utils", fake_utils)
sys.modules.setdefault("websockets", types.ModuleType("websockets"))
if not hasattr(sys.modules["websockets"], "ConnectionClosed"):
    sys.modules["websockets"].ConnectionClosed = type("ConnectionClosed", (Exception,), {})
sys.modules.setdefault("onnxruntime", types.ModuleType("onnxruntime"))

import enhance as E
import ozen_server as S


class Model:
    def __init__(self):
        self.beams = []
        self.calls = []

    def transcribe(self, audio, **kw):
        self.beams.append(kw["beam_size"])
        self.calls.append(kw)
        return [], None


class Beam(unittest.TestCase):
    def test_only_a_sensible_beam_from_the_phone_counts(self):
        self.assertEqual(S.requested_beam(1), 1)
        self.assertEqual(S.requested_beam(2), 2)
        self.assertEqual(S.requested_beam(10), 10)
        for junk in (None, 0, 11, -1, "3", 2.5, True):
            self.assertIsNone(S.requested_beam(junk), junk)

    def test_finished_lines_use_the_phones_beam_and_live_passes_stay_greedy(self):
        t = S.Transcriber.__new__(S.Transcriber)
        t.model = t.final_model = Model()
        t.beam, t.context, t.speech_gate = 5, 0, 0.0
        audio = np.zeros(1600, dtype=np.float32)
        t._run(audio, "he", None, True, beam=2)
        t._run(audio, "he", None, True)
        t._run(audio, "he", None, False, beam=2)
        self.assertEqual(t.model.beams, [2, 5, 1])

    def test_the_names_reach_the_model_as_the_prompt_and_as_hotwords(self):
        t = S.Transcriber.__new__(S.Transcriber)
        t.model = t.final_model = Model()
        t.beam, t.context, t.speech_gate = 5, 0, 0.0
        audio = np.zeros(1600, dtype=np.float32)
        t._run(audio, "he", "Noa, Itai.", True, hotwords="Noa, Itai")
        t._run(audio, "he", "", False, hotwords="")
        named, unnamed = t.model.calls
        self.assertEqual((named["initial_prompt"], named["hotwords"]), ("Noa, Itai.", "Noa, Itai"))
        self.assertEqual((unnamed["initial_prompt"], unnamed["hotwords"]), (None, None))


def voice(*stretches):
    """Silero's answer for a line: voice over these (start, seconds)."""
    stamps = [{"start": int(start * S.RATE), "end": int((start + seconds) * S.RATE)} for start, seconds in stretches]
    return mock.patch.object(S, "get_speech_timestamps", return_value=stamps)


class VoiceGate(unittest.TestCase):
    def line(self, seconds):
        return np.zeros(int(seconds * S.RATE), dtype=np.float32)

    def test_a_line_with_almost_no_voice_is_dropped(self):
        with voice():
            self.assertTrue(S.lacks_voice(self.line(10), 0.05))
        with voice((4, 0.1)):
            self.assertTrue(S.lacks_voice(self.line(10), 0.05))

    def test_a_short_sentence_that_clatter_kept_open_for_half_a_minute_is_kept(self):
        with voice((10, 0.15), (11, 0.15)):
            self.assertFalse(S.lacks_voice(self.line(30), 0.05))

    def test_a_one_word_answer_alone_on_its_line_is_kept(self):
        with voice((0.4, 0.1)):
            self.assertFalse(S.lacks_voice(self.line(1), 0.05))

    def test_a_dropped_line_never_reaches_the_model_and_a_gate_of_zero_drops_nothing(self):
        t = S.Transcriber.__new__(S.Transcriber)
        t.model = t.final_model = Model()
        t.beam, t.context = 5, 0
        with voice() as detector:
            t.speech_gate = 0.05
            self.assertEqual(t._run(self.line(2), "he", None, True), ("", None, []))
            self.assertEqual(t.model.beams, [])
            detector.reset_mock()
            t.speech_gate = 0.0
            t._run(self.line(2), "he", None, True)
            self.assertEqual(t.model.beams, [5])
            detector.assert_not_called()


class OddScores:
    def transcribe(self, audio, **kw):
        segment = types.SimpleNamespace(text=" שלום לך", no_speech_prob=float("nan"),
                                        avg_logprob=float("nan"), compression_ratio=1.2)
        silence = types.SimpleNamespace(text=" תודה", no_speech_prob=0.9,
                                        avg_logprob=float("-inf"), compression_ratio=1.0)
        return [segment, silence], None


class OddNumbers(unittest.TestCase):
    def test_a_score_json_cannot_hold_still_leaves_a_frame_the_phone_can_read(self):
        t = S.Transcriber.__new__(S.Transcriber)
        t.model = t.final_model = OddScores()
        t.beam, t.context, t.speech_gate = 5, 0, 0.0
        text, confidence, pieces = t._run(np.zeros(1600, dtype=np.float32), "he", None, True)

        def refuse(constant):
            raise ValueError(constant)

        frame = json.dumps({"text": text, "confidence": confidence, "segments": pieces}, ensure_ascii=False)
        parsed = json.loads(frame, parse_constant=refuse)
        self.assertEqual(parsed["text"], "שלום לך")
        self.assertLess(parsed["segments"][1]["logprob"], -1.0)


class MixedSegments:
    def transcribe(self, audio, **kw):
        return [
            types.SimpleNamespace(text=" שלום", no_speech_prob=0.01, avg_logprob=-0.1, compression_ratio=1.2),
            types.SimpleNamespace(text=" אחת שתיים אחת שתיים אחת שתיים אחת שתיים", no_speech_prob=0.0,
                                  avg_logprob=-0.05, compression_ratio=2.9),
            types.SimpleNamespace(text="‏ מה נשמע", no_speech_prob=0.02, avg_logprob=-0.3, compression_ratio=1.1),
            types.SimpleNamespace(text="  ", no_speech_prob=0.0, avg_logprob=-0.2, compression_ratio=1.0),
        ], None


class PassText(unittest.TestCase):
    def test_the_line_skips_a_repeated_segment_has_one_space_after_a_direction_mark_and_its_own_confidence(self):
        import math
        t = S.Transcriber.__new__(S.Transcriber)
        t.model = t.final_model = MixedSegments()
        t.beam, t.context, t.speech_gate = 5, 0, 0.0
        text, confidence, pieces = t._run(np.zeros(1600, dtype=np.float32), "he", None, True)
        self.assertEqual(text, "שלום מה נשמע")
        self.assertAlmostEqual(confidence, math.exp(-0.2), places=6)
        self.assertEqual([p["text"] for p in pieces], ["שלום", "אחת שתיים אחת שתיים אחת שתיים אחת שתיים", "מה נשמע"])
        self.assertEqual(pieces[1]["compression"], 2.9)


class BrokenGPU:
    def transcribe(self, audio, **kw):
        raise RuntimeError("CUDA error: an illegal memory access was encountered")


class NowAndThenGPU:
    """Every other pass fails, the rest finish."""

    def __init__(self):
        self.passes = 0

    def transcribe(self, audio, **kw):
        self.passes += 1
        if self.passes % 2:
            raise RuntimeError("CUDA error: out of memory")
        return iter([]), None


class ModelsFromDisk(unittest.TestCase):
    """With the internet down and the home network up, asking the Hub
    about a model already on disk took 135 s per model to give up."""

    def folder(self, *files):
        path = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, path)
        for name in files:
            open(os.path.join(path, name), "w").close()
        return path

    def test_a_downloaded_model_loads_without_asking_the_hub(self):
        complete = self.folder("model.bin", "config.json", "tokenizer.json", "vocabulary.json")
        asked = []

        def download(name, local_files_only=False):
            asked.append((name, local_files_only))
            if not local_files_only:
                raise AssertionError("asked the network")
            return complete

        with mock.patch.object(S, "download_model", download):
            self.assertEqual(S.model_path("ivrit-ai/whisper-large-v3-ct2"), complete)
        self.assertEqual(asked, [("ivrit-ai/whisper-large-v3-ct2", True)])

    def test_a_model_not_downloaded_yet_is_fetched_as_before(self):
        def download(name, local_files_only=False):
            raise LookupError("not in the cache")

        with mock.patch.object(S, "download_model", download):
            self.assertEqual(S.model_path("ivrit-ai/whisper-large-v3-ct2"), "ivrit-ai/whisper-large-v3-ct2")

    def test_a_first_download_cut_off_before_the_weights_is_fetched_again(self):
        # The Hub library hands back the folder of a download stopped part
        # way; loading it failed at every start, never finishing it.
        cut_off = self.folder("config.json", "tokenizer.json")
        with mock.patch.object(S, "download_model", lambda name, local_files_only=False: cut_off):
            self.assertEqual(S.model_path("ivrit-ai/whisper-large-v3-ct2"), "ivrit-ai/whisper-large-v3-ct2")

    def test_both_models_load_from_disk(self):
        live, final = self.folder("model.bin", "config.json", "tokenizer.json"), self.folder("model.bin", "config.json", "tokenizer.json")
        loaded = []

        class Recorder:
            def __init__(self, path, device, compute_type):
                loaded.append(path)

        with mock.patch.object(S, "download_model", lambda name, local_files_only=False: {"live": live, "final": final}[name]), \
                mock.patch.object(S, "WhisperModel", Recorder):
            S.Transcriber("live", "cuda", "int8_float16", 5, 0, final_model="final")
        self.assertEqual(loaded, [live, final])

    def test_the_phone_is_told_the_model_and_the_finished_line_model_when_there_is_one(self):
        with mock.patch.object(S, "download_model", lambda name, local_files_only=False: name), \
                mock.patch.object(S, "WhisperModel", lambda path, device, compute_type: object()):
            self.assertEqual(S.Transcriber("live", "cuda", "int8_float16", 5, 0).name, "live")
            self.assertEqual(S.Transcriber("live", "cuda", "int8_float16", 5, 0, final_model="final").name, "live + final")


class Restart(unittest.TestCase):
    def test_passes_the_voice_gate_drops_do_not_hide_a_broken_card(self):
        t = S.Transcriber.__new__(S.Transcriber)
        t.model = t.final_model = BrokenGPU()
        t.beam, t.context, t.speech_gate = 5, 0, 0.05
        t.failures, t.failures_before_exit = 0, 3
        exits = []
        speech = np.full(1600, 0.1, dtype=np.float32)
        noise = np.zeros(1600, dtype=np.float32)
        real_gate, real_exit, real_shutdown = S.lacks_voice, S.os._exit, S.logging.shutdown
        S.lacks_voice = lambda audio, gate: not audio.any()
        S.os._exit = exits.append
        S.logging.shutdown = lambda: None
        try:
            async def evening():
                t.lock = asyncio.Lock()
                for audio in (speech, noise, speech, noise, speech):
                    try:
                        await t.transcribe(audio, "he", None, True)
                    except RuntimeError:
                        pass
            asyncio.run(evening())
        finally:
            S.lacks_voice, S.os._exit, S.logging.shutdown = real_gate, real_exit, real_shutdown
        self.assertEqual(exits, [3])

    def test_a_pass_that_fails_now_and_then_does_not_restart_the_server(self):
        t = S.Transcriber.__new__(S.Transcriber)
        t.model = t.final_model = NowAndThenGPU()
        t.beam, t.context, t.speech_gate = 5, 0, 0.0
        t.failures, t.failures_before_exit = 0, 3
        exits = []
        speech = np.full(1600, 0.1, dtype=np.float32)
        real_exit, real_shutdown = S.os._exit, S.logging.shutdown
        S.os._exit = exits.append
        S.logging.shutdown = lambda: None
        try:
            async def evening():
                t.lock = asyncio.Lock()
                for _ in range(6):
                    try:
                        await t.transcribe(speech, "he", None, True)
                    except RuntimeError:
                        pass
            asyncio.run(evening())
        finally:
            S.os._exit, S.logging.shutdown = real_exit, real_shutdown
        self.assertEqual(exits, [])
        self.assertEqual(t.failures, 0)


class HungUpMidPass(unittest.TestCase):
    def test_a_phone_that_hangs_up_mid_pass_does_not_let_the_next_pass_share_the_card(self):
        t = S.Transcriber.__new__(S.Transcriber)
        t.speech_gate, t.beam, t.context = 0.0, 5, False
        t.failures, t.failures_before_exit, t.model_ran = 0, 3, False
        release = threading.Event()
        guard = threading.Lock()
        state = {"running": 0, "peak": 0}

        def run(*args):
            with guard:
                state["running"] += 1
                state["peak"] = max(state["peak"], state["running"])
            release.wait(2)
            with guard:
                state["running"] -= 1
            return "", None, []

        t._run = run
        audio = np.zeros(1600, dtype=np.float32)

        async def scenario():
            t.lock = asyncio.Lock()
            first = asyncio.create_task(t.transcribe(audio, "he", None, True))
            await asyncio.sleep(0.1)
            first.cancel()
            await asyncio.sleep(0.05)
            second = asyncio.create_task(t.transcribe(audio, "he", None, True))
            await asyncio.sleep(0.2)
            release.set()
            await second
            with self.assertRaises(asyncio.CancelledError):
                await first

        asyncio.run(scenario())
        self.assertEqual(state["peak"], 1, "two passes were on the card at once")


class SlowGPU:
    """Stands in for the Transcriber: each pass takes a while, as on a GPU."""
    context = False

    def __init__(self, live_seconds):
        self.live_seconds = live_seconds
        self.passes = []

    async def transcribe(self, audio, language, prompt, final, hotwords=None, gate=True, beam=None):
        self.passes.append((time.monotonic(), final))
        await asyncio.sleep(0.05 if final else self.live_seconds)
        return ("שלום" if final else "של"), 0.9, []


class Socket:
    def __init__(self):
        self.sent = []

    async def send(self, text):
        self.sent.append(text)


def pcm(seconds, level):
    n = int(seconds * S.RATE)
    wave = level * np.sin(np.arange(n) * 2 * np.pi * 300 / S.RATE)
    return (wave * 32767).astype("<i2").tobytes()


class PauseEnd(unittest.TestCase):
    def play(self):
        gpu = SlowGPU(live_seconds=0.25)
        session = S.Session(Socket(), gpu, "he", [], live_interval=0.3)

        async def feed():
            worker = asyncio.create_task(session.run())
            for seconds, level in ((0.6, 0.0005), (1.5, 0.3), (1.6, 0.0005)):
                chunk = pcm(seconds, level)
                step = int(0.1 * S.RATE) * 2
                for i in range(0, len(chunk), step):
                    session.add_audio(chunk[i:i + step])
                    await asyncio.sleep(0.1)
            session.finished = True
            session.changed.set()
            await asyncio.wait_for(worker, 5)

        asyncio.run(feed())
        return gpu, session

    def test_no_live_pass_reads_only_silence_and_the_final_starts_at_the_pause(self):
        gpu, session = self.play()
        finals = [t for t, final in gpu.passes if final]
        self.assertEqual(len(finals), 1)
        speech_over = [t for t, final in gpu.passes if not final]
        # One live pass may read the last words; none may only reread them.
        after_last_words = [t for t in speech_over if t > finals[0] - 0.7]
        self.assertLessEqual(len(after_last_words), 1, gpu.passes)
        self.assertLess(session.final_lag_seconds[0], 0.15, session.final_lag_seconds)
        # 0.6 s of quiet, 1.5 s of speech and the 0.3 s pad after it.
        final_end = [json.loads(m)["end_s"] for m in session.ws.sent if json.loads(m)["final"]]
        self.assertEqual(len(final_end), 1, final_end)
        self.assertAlmostEqual(final_end[0], 2.4, delta=0.06)


class PreviousLine(unittest.TestCase):
    def test_the_line_before_goes_into_the_prompt_only_when_the_server_keeps_context(self):
        session = S.Session(Socket(), SlowGPU(live_seconds=0.01), "he", [], live_interval=0.3)
        session.previous_text = "the line before"
        self.assertIsNone(session.prompt())
        session.t.context = True
        self.assertEqual(session.prompt(), "the line before")


class SlowFinalGPU(SlowGPU):
    """Finished passes take half a second, live ones a moment."""

    async def transcribe(self, audio, language, prompt, final, hotwords=None, gate=True, beam=None):
        self.passes.append((len(audio), final))
        await asyncio.sleep(0.5 if final else 0.01)
        return ("שלום" if final else "של"), 0.9, []


class StopWithALineWaiting(unittest.TestCase):
    def test_a_phone_that_stops_during_a_slow_finished_pass_still_gets_the_sentence_after_it(self):
        gpu = SlowFinalGPU(live_seconds=0.01)
        session = S.Session(Socket(), gpu, "he", [], live_interval=0.3)

        async def feed():
            worker = asyncio.create_task(session.run())
            for seconds, level in ((1.0, 0.3), (0.8, 0.0005), (1.0, 0.3), (0.2, 0.0005)):
                chunk = pcm(seconds, level)
                step = int(0.1 * S.RATE) * 2
                for i in range(0, len(chunk), step):
                    session.add_audio(chunk[i:i + step])
                    await asyncio.sleep(0.01)
            session.finished = True
            session.changed.set()
            await asyncio.wait_for(worker, 5)

        asyncio.run(feed())
        finals = [length for length, final in gpu.passes if final]
        self.assertEqual(len(finals), 2, gpu.passes)
        # Only the first line ended at a pause; the stop ended the second,
        # and has no pause to time it from.
        self.assertEqual(len(session.final_lag_seconds), 1, session.final_lag_seconds)


class OneBigChunk(unittest.TestCase):
    def test_a_pause_and_the_end_of_speech_are_found_inside_one_chunk(self):
        session = S.Session(Socket(), SlowGPU(live_seconds=0.01), "he", [], live_interval=0.3)
        session.add_audio(pcm(1.0, 0.3) + pcm(1.0, 0.0005) + pcm(1.0, 0.3) + pcm(0.5, 0.0005))
        self.assertEqual(len(session.pauses), 1, session.pauses)
        self.assertTrue(S.RATE <= session.pauses[0] <= S.RATE + 688, session.pauses)
        self.assertTrue(3 * S.RATE <= session.last_speech_end <= 3 * S.RATE + 688, session.last_speech_end)


class ThreeSentencesInOneSlowPass(unittest.TestCase):
    def test_three_sentences_said_during_one_slow_live_pass_come_back_as_three_lines(self):
        gpu = WindowGPU(live_seconds=2.0)
        session = S.Session(Socket(), gpu, "he", [], live_interval=0.3)

        async def feed():
            worker = asyncio.create_task(session.run())
            for seconds, level in ((0.6, 0.0005), (1.0, 0.3), (1.0, 0.0005), (1.0, 0.3), (1.0, 0.0005),
                                   (1.0, 0.3), (1.6, 0.0005)):
                chunk = pcm(seconds, level)
                step = int(0.1 * S.RATE) * 2
                for i in range(0, len(chunk), step):
                    session.add_audio(chunk[i:i + step])
                    await asyncio.sleep(0.02)
            session.finished = True
            session.changed.set()
            await asyncio.wait_for(worker, 10)

        asyncio.run(feed())
        finals = [n / S.RATE for n, final in gpu.passes if final]
        self.assertEqual(len(finals), 3, gpu.passes)
        self.assertTrue(all(seconds <= 2.5 for seconds in finals), finals)
        # The first line waited for the slow pass long after its pause.
        self.assertGreater(session.final_lag_seconds[0], 0.5, session.final_lag_seconds)


class LiveCadence(unittest.TestCase):
    def test_a_fast_card_still_waits_a_live_interval_of_new_audio_between_live_passes(self):
        gpu = SlowGPU(live_seconds=0.01)
        session = S.Session(Socket(), gpu, "he", [], live_interval=0.5)

        async def feed():
            worker = asyncio.create_task(session.run())
            chunk = pcm(2.0, 0.3)
            step = int(0.1 * S.RATE) * 2
            for i in range(0, len(chunk), step):
                session.add_audio(chunk[i:i + step])
                await asyncio.sleep(0.05)
            session.finished = True
            session.changed.set()
            await asyncio.wait_for(worker, 5)

        asyncio.run(feed())
        live = [t for t, final in gpu.passes if not final]
        self.assertTrue(2 <= len(live) <= 5, gpu.passes)


class WindowGPU(SlowGPU):
    """Remembers how much audio each pass was given."""

    async def transcribe(self, audio, language, prompt, final, hotwords=None, gate=True, beam=None):
        self.passes.append((len(audio), final))
        await asyncio.sleep(0.05 if final else self.live_seconds)
        return ("שלום" if final else "של"), 0.9, []


class GateGPU(SlowGPU):
    """Writes a line where there was a real stretch of voice, after a
    while; a short click comes back empty at once, as the voice gate
    returns it without the model."""

    async def transcribe(self, audio, language, prompt, final, hotwords=None, gate=True, beam=None):
        voiced = np.count_nonzero(np.abs(audio) > 0.1) / S.RATE
        if final and voiced < 0.5:
            return "", None, []
        await asyncio.sleep(0.2 if final else 0.01)
        return "hello", 0.9, []


class RepeatGPU(SlowGPU):
    """Writes a sentence said twice: one segment this server drops for
    its compression ratio, sent on for the phone to judge."""

    async def transcribe(self, audio, language, prompt, final, hotwords=None, gate=True, beam=None):
        await asyncio.sleep(0.01)
        return "", None, [{"text": "a b c d e. a b c d e.", "no_speech": 0.0, "logprob": -0.01, "compression": 2.9}]


class DigitalSilence(unittest.TestCase):
    def test_a_dropout_of_zeros_leaves_a_hum_a_hum(self):
        detector = S.EnergyVoiceDetector()
        hum = 0.007 * np.sin(np.arange(1024) * 0.3)
        for _ in range(235):
            detector.is_speech(hum)
        floor = detector.floor
        for _ in range(8):
            detector.is_speech(np.zeros(1024))
        for _ in range(30):
            detector.is_speech(np.zeros(0))
        self.assertEqual(detector.floor, floor)
        self.assertEqual(sum(detector.is_speech(hum) for _ in range(50)), 0)
        self.assertTrue(detector.is_speech(0.05 * np.sin(np.arange(1024) * 0.3)))


def tone(amplitude, count=1024):
    return amplitude * np.sin(np.arange(count) * 0.3)


class Detector(unittest.TestCase):
    """The cases of Tests/OzenKitTests/EnergyVoiceDetectorTests.swift that
    PCM16 can carry, with the same numbers, so the port can't drift from
    the phone's detector unnoticed."""

    def test_silence_is_not_speech_and_talk_across_the_table_is(self):
        detector = S.EnergyVoiceDetector()
        self.assertFalse(detector.is_speech(np.zeros(1024)))
        self.assertFalse(detector.is_speech(tone(0.0008)))
        self.assertTrue(detector.is_speech(tone(0.0025)))
        self.assertTrue(detector.is_speech(tone(0.05)))

    def test_a_long_stretch_of_speech_with_the_gaps_speech_has_stays_speech(self):
        detector = S.EnergyVoiceDetector()
        words = heard = 0
        for index in range(3_000):
            if index % 7 < 5:
                words += 1
                heard += detector.is_speech(tone(0.01))
            else:
                detector.is_speech(tone(0.0005))
        self.assertEqual(heard, words)
        self.assertLess(detector.floor, 0.001)

    def test_a_steady_hum_stops_counting_as_speech_within_seconds(self):
        detector = S.EnergyVoiceDetector()
        hum = tone(0.009)
        self.assertTrue(detector.is_speech(hum))
        for _ in range(156):
            detector.is_speech(hum)
        self.assertFalse(detector.is_speech(hum))
        self.assertTrue(detector.is_speech(tone(0.05)))

    def test_without_the_recent_minimum_a_steady_hum_stays_speech(self):
        detector = S.EnergyVoiceDetector()
        detector.window = 10**12
        hum = tone(0.009)
        for _ in range(156):
            detector.is_speech(hum)
        self.assertTrue(detector.is_speech(hum))

    def test_steady_noise_lowers_the_margin_to_6_db_and_swinging_noise_keeps_8(self):
        steady = S.EnergyVoiceDetector()
        self.assertAlmostEqual(steady._ratio_now(), 2.5, delta=0.01)
        for _ in range(600):
            steady.is_speech(tone(0.002, 1600))
        self.assertLess(steady.swing, 0.5)
        self.assertLess(steady._ratio_now(), 2.15)
        self.assertTrue(steady.is_speech(tone(0.0042, 1600)))

        swinging = S.EnergyVoiceDetector()
        for i in range(600):
            decibels = (i * 7) % 13 - 6
            swinging.is_speech(tone(0.002 * 10 ** (decibels / 20), 1600))
        self.assertGreater(swinging.swing, 2)
        self.assertAlmostEqual(swinging._ratio_now(), 2.5, delta=0.01)

    def test_the_servers_line_hears_a_voice_5_db_over_a_hum_that_the_default_misses(self):
        hum = tone(0.002, 1600)
        quiet_voice = tone(0.002 * 10 ** (5 / 20), 1600)
        standard = S.EnergyVoiceDetector()
        server = S.Session(Socket(), GateGPU(0.01), "he", [], live_interval=0.3).detector
        for _ in range(600):
            standard.is_speech(hum)
            server.is_speech(hum)
        self.assertFalse(standard.is_speech(quiet_voice))
        self.assertTrue(server.is_speech(quiet_voice))
        self.assertFalse(server.is_speech(hum))

    def test_a_window_only_a_few_chunks_long_keeps_the_margin_cautious(self):
        detector = S.EnergyVoiceDetector()
        detector.window = 3_200
        for i in range(600):
            detector.is_speech(tone(0.001 if i % 2 == 0 else 0.004, 1600))
        self.assertAlmostEqual(detector._ratio_now(), 2.5, delta=0.01)

    def test_the_floor_is_capped_so_a_loud_fan_cannot_hide_a_voice_over_it(self):
        detector = S.EnergyVoiceDetector()
        for _ in range(1_000):
            detector.is_speech(tone(0.1))
        self.assertEqual(detector.floor, detector.max_floor)
        self.assertTrue(detector.is_speech(tone(0.15)))

    def test_the_floor_falls_quickly_when_the_room_gets_quieter(self):
        detector = S.EnergyVoiceDetector()
        detector.floor = 0.02
        for _ in range(20):
            detector.is_speech(tone(0.0002))
        self.assertLess(detector.floor, 0.001)

    def test_the_floor_creeps_up_to_a_steady_sound_under_the_line_and_never_past_it(self):
        detector = S.EnergyVoiceDetector()
        sound = tone(0.001)
        level = float(np.sqrt(np.mean(np.square(sound))))
        highest = 0.0
        for _ in range(500):
            self.assertFalse(detector.is_speech(sound))
            highest = max(highest, detector.floor)
        self.assertLessEqual(highest, level * 1.0001)
        self.assertGreater(highest, 0.9 * level)


def alternating(count, gap=range(0)):
    samples = np.where(np.arange(count) % 2 == 0, 0.5, -0.5)
    samples[gap.start:gap.stop] = 0
    return samples


class QuietestPoint(unittest.TestCase):
    """The cases of Tests/OzenKitTests/UtteranceCutTests.swift."""

    def test_the_cut_goes_into_the_quiet_between_words(self):
        cut = S.quietest_point(alternating(16_000, range(12_000, 12_800)), 16_000, 8_000, 400)
        # The middle of the last frame that is all silence.
        self.assertEqual(cut, 12_600)

    def test_a_gap_older_than_the_look_back_is_not_reached_for(self):
        cut = S.quietest_point(alternating(16_000, range(1_000, 1_800)), 16_000, 4_000, 400)
        self.assertGreaterEqual(cut, 12_000)

    def test_with_nothing_quieter_anywhere_the_latest_point_wins(self):
        cut = S.quietest_point(alternating(16_000), 16_000, 4_000, 400)
        self.assertTrue(15_000 <= cut <= 16_000, cut)

    def test_too_little_audio_or_nonsense_arguments_leave_the_cut_where_it_was_asked_for(self):
        self.assertEqual(S.quietest_point(alternating(100), 100, 4_000, 400), 100)
        self.assertEqual(S.quietest_point(np.zeros(0), 50, 10, 4), 0)
        self.assertEqual(S.quietest_point(alternating(1_000), 5_000, 1_000, 0), 1_000)
        self.assertEqual(S.quietest_point(alternating(1_000), -3, 1_000, 100), 0)


class UnchangedSpectrum:
    """GTCRN's inputs and outputs, with the spectrum handed back as it came."""

    def __init__(self, *args, **kwargs):
        pass

    def run(self, names, inputs):
        return inputs["mix"], inputs["conv_cache"], inputs["tra_cache"], inputs["inter_cache"]


class Cleaner(unittest.TestCase):
    def cleaner(self, mix):
        with mock.patch.object(E.onnxruntime, "InferenceSession", UnchangedSpectrum, create=True):
            return E.StreamingEnhancer("gtcrn_simple.onnx", mix)

    def test_with_a_model_that_changes_nothing_the_mix_is_the_input_a_hop_late(self):
        cleaner = self.cleaner(0.3)
        audio = (0.3 * np.sin(np.arange(16_000) * 0.05)).astype(np.float32)
        out = np.concatenate([cleaner.process(audio[i:i + 100]) for i in range(0, len(audio), 100)])
        self.assertEqual(len(out), 16_000 // E.HOP * E.HOP)
        self.assertTrue(np.allclose(out[:E.HOP], 0))
        self.assertTrue(np.allclose(out[E.HOP:], audio[:len(out) - E.HOP], atol=1e-4))

    def test_each_full_hop_comes_out_as_soon_as_it_is_in(self):
        cleaner = self.cleaner(0.5)
        self.assertEqual(len(cleaner.process(np.zeros(E.HOP - 1, np.float32))), 0)
        self.assertEqual(len(cleaner.process(np.zeros(1, np.float32))), E.HOP)

    def test_frames_fade_in_and_out_as_the_model_was_trained_on(self):
        self.assertEqual(E.WINDOW[0], 0)
        self.assertAlmostEqual(float(E.WINDOW[E.N_FFT // 2]), 1.0, places=6)


def slow_wave(peak, count=16_000):
    return (peak * np.sin(np.arange(count) * 0.05)).astype(np.float32)


class SpeechGain(unittest.TestCase):
    """The cases of Tests/OzenKitTests/SpeechGainTests.swift."""

    def test_speech_from_across_the_room_is_brought_up_to_a_level_a_16_bit_file_keeps(self):
        peak = np.abs(S.speech_gain(slow_wave(0.01))).max()
        self.assertTrue(0.45 < peak <= 0.55, peak)

    def test_speech_that_is_already_loud_is_left_as_it_is(self):
        loud = slow_wave(0.8)
        self.assertTrue(np.array_equal(S.speech_gain(loud), loud))

    def test_one_click_does_not_decide_the_level_or_leave_the_range_once_raised(self):
        samples = slow_wave(0.01)
        samples[8_000] = 0.9
        raised = S.speech_gain(samples)
        self.assertEqual(raised[8_000], 1)
        self.assertLess(abs(raised[8_001]), 0.6)
        self.assertGreater(np.sort(np.abs(raised))[len(raised) // 2], 0.2)

    def test_near_silence_is_not_blown_up_into_a_roar(self):
        self.assertAlmostEqual(float(np.abs(S.speech_gain(slow_wave(0.00001))).max()), 0.001, delta=0.00001)
        for audio in (np.zeros(100, dtype=np.float32), np.zeros(0, dtype=np.float32),
                      np.array([np.nan, np.inf], dtype=np.float32)):
            self.assertTrue(np.array_equal(S.speech_gain(audio), audio, equal_nan=True))


class Summary(unittest.TestCase):
    def play(self, pieces, gpu=None):
        session = S.Session(Socket(), gpu or GateGPU(0.01), "he", [], live_interval=0.3)

        async def feed():
            worker = asyncio.create_task(session.run())
            for seconds, level in pieces:
                session.add_audio(pcm(seconds, level))
                await asyncio.sleep(0.4 if level < 0.01 else 0.05)
            session.finished = True
            session.changed.set()
            await asyncio.wait_for(worker, 5)

        asyncio.run(feed())
        return session.summary()

    def test_lines_the_voice_gate_skipped_do_not_make_the_pass_look_fast(self):
        summary = self.play([(1.5, 0.3), (1.6, 0.0005), (0.2, 0.3), (1.6, 0.0005), (0.2, 0.3), (1.6, 0.0005)])
        self.assertIn("1 lines, 2 finished empty", summary)
        median = float(summary.split("finished-line pass median ")[1].split(" s")[0])
        self.assertGreaterEqual(median, 0.15, summary)
        self.assertLess(median, 1.0, summary)

    def test_a_session_of_only_noise_still_says_how_many_lines_came_back_empty(self):
        summary = self.play([(0.2, 0.3), (1.6, 0.0005), (0.2, 0.3), (1.6, 0.0005)])
        self.assertIn("no lines, 2 finished empty", summary)

    def test_a_line_left_to_the_phone_is_not_counted_empty(self):
        summary = self.play([(1.5, 0.3), (1.6, 0.0005)], gpu=RepeatGPU(0.01))
        self.assertIn("no lines, 0 finished empty, 1 left to the phone's filter", summary)


class LongLine(unittest.TestCase):
    def test_a_line_cut_for_length_stays_within_the_limit_after_a_slow_pass(self):
        gpu = WindowGPU(live_seconds=1.3)
        session = S.Session(Socket(), gpu, "he", [], live_interval=0.3)
        session.max_utterance = 2.0
        session.cut_look_back = 0.2

        async def feed():
            worker = asyncio.create_task(session.run())
            chunk = pcm(6.0, 0.3)
            step = int(0.1 * S.RATE) * 2
            for i in range(0, len(chunk), step):
                session.add_audio(chunk[i:i + step])
                await asyncio.sleep(0.1)
            session.finished = True
            session.changed.set()
            await asyncio.wait_for(worker, 10)

        asyncio.run(feed())
        finals = [n for n, final in gpu.passes if final]
        self.assertGreaterEqual(len(finals), 3, gpu.passes)
        self.assertTrue(all(n <= 2.0 * S.RATE for n in finals), finals)
        self.assertEqual(sum(finals), session.offset)


class BuriedPause(unittest.TestCase):
    def test_a_pause_the_next_sentence_followed_before_a_slow_pass_ended_still_ends_the_line(self):
        gpu = WindowGPU(live_seconds=1.5)
        session = S.Session(Socket(), gpu, "he", [], live_interval=0.3)

        async def feed():
            worker = asyncio.create_task(session.run())
            for seconds, level in ((0.6, 0.0005), (1.5, 0.3), (1.0, 0.0005), (1.5, 0.3), (1.6, 0.0005)):
                chunk = pcm(seconds, level)
                step = int(0.1 * S.RATE) * 2
                for i in range(0, len(chunk), step):
                    session.add_audio(chunk[i:i + step])
                    await asyncio.sleep(0.1)
            session.finished = True
            session.changed.set()
            await asyncio.wait_for(worker, 10)

        asyncio.run(feed())
        finals = [n / S.RATE for n, final in gpu.passes if final]
        self.assertEqual(len(finals), 2, gpu.passes)
        self.assertTrue(2.1 <= finals[0] <= 3.1, finals)
        self.assertGreaterEqual(finals[1], 1.5, finals)


class RepeatGPU(SlowGPU):
    """Every pass comes back as a sentence written twice, which this server
    drops for its compression and the phone keeps (WhisperResultFilter)."""

    async def transcribe(self, audio, language, prompt, final, hotwords=None, gate=True, beam=None):
        self.passes.append((time.monotonic(), final))
        await asyncio.sleep(0.05)
        return "", None, [{"text": "one two three four five one two three four five",
                           "no_speech": 0.0, "logprob": -0.1, "compression": 2.9}]


class DroppedLivePass(unittest.TestCase):
    def test_a_live_pass_with_only_dropped_segments_still_reaches_the_phone(self):
        socket = Socket()
        session = S.Session(socket, RepeatGPU(live_seconds=0.05), "he", [], live_interval=0.3)

        async def feed():
            worker = asyncio.create_task(session.run())
            for seconds, level in ((0.6, 0.0005), (1.5, 0.3), (1.6, 0.0005)):
                chunk = pcm(seconds, level)
                step = int(0.1 * S.RATE) * 2
                for i in range(0, len(chunk), step):
                    session.add_audio(chunk[i:i + step])
                    await asyncio.sleep(0.1)
            session.finished = True
            session.changed.set()
            await asyncio.wait_for(worker, 5)

        asyncio.run(feed())
        live = [f for f in map(json.loads, socket.sent) if f["type"] == "text" and not f["final"]]
        self.assertTrue(live, socket.sent)
        self.assertEqual(live[0]["segments"][0]["compression"], 2.9)


class GoneSocket:
    remote_address = ("203.0.113.9", 4444)

    async def recv(self):
        raise S.websockets.ConnectionClosed()

    async def send(self, text):
        raise S.websockets.ConnectionClosed()


class HungUp(unittest.TestCase):
    def test_a_peer_that_hangs_up_before_the_hello_ends_quietly(self):
        asyncio.run(S.handle(GoneSocket(), None, "code", 1.0))


class HelloSocket:
    remote_address = ("203.0.113.9", 4444)

    def __init__(self, hello):
        self.hello = hello
        self.sent = []

    async def recv(self):
        return self.hello

    async def send(self, text):
        self.sent.append(text)


class WrongCode(unittest.TestCase):
    def test_the_refusal_never_repeats_either_code(self):
        guess = "guessed-code-123"
        ws = HelloSocket(S.json.dumps({"type": "hello", "token": guess}))
        with self.assertLogs(S.log, "WARNING"):
            asyncio.run(S.handle(ws, None, "real-code-456", 1.0))
        self.assertEqual(len(ws.sent), 1)
        self.assertIn("unauthorized", ws.sent[0])
        self.assertNotIn(guess, ws.sent[0])
        self.assertNotIn("real-code-456", ws.sent[0])

    def test_a_byte_order_mark_in_front_of_the_code_is_dropped_in_any_code_page(self):
        bom = b"\xef\xbb\xbf"
        for mark in ["\ufeff", bom.decode("cp862"), bom.decode("cp1252"), bom.decode("cp437")]:
            self.assertEqual(S.pairing_code(mark + "example-code-123\r\n"), "example-code-123")
        self.assertEqual(S.pairing_code("  example-code-123 "), "example-code-123")
        self.assertEqual(S.pairing_code(""), "")

    def test_a_guess_with_letters_outside_ascii_is_refused_too(self):
        ws = HelloSocket(S.json.dumps({"type": "hello", "token": "קוד-שגוי"}))
        with self.assertLogs(S.log, "WARNING"):
            asyncio.run(S.handle(ws, None, "real-code-456", 1.0))
        self.assertEqual(len(ws.sent), 1)
        self.assertIn("unauthorized", ws.sent[0])

    def test_a_guess_that_is_not_valid_text_is_refused_like_any_other(self):
        ws = HelloSocket('{"type": "hello", "token": "\\ud800"}')
        with self.assertLogs(S.log, "WARNING"):
            asyncio.run(S.handle(ws, None, "real-code-456", 1.0))
        self.assertEqual(len(ws.sent), 1)
        self.assertIn("unauthorized", ws.sent[0])


class WorkerEndings(unittest.TestCase):
    def test_a_phone_hanging_up_mid_send_is_not_a_failure(self):
        self.assertFalse(S.worker_failed(S.websockets.ConnectionClosed(None, None)))

    def test_a_real_error_still_counts(self):
        self.assertTrue(S.worker_failed(RuntimeError("CUDA failed")))

    def test_a_clean_finish_is_not_a_failure(self):
        self.assertFalse(S.worker_failed(None))


class ModelsThatWontLoad(unittest.TestCase):
    def test_the_log_says_why_and_the_restart_waits(self):
        from unittest import mock

        def broken(*args, **kwargs):
            raise RuntimeError("CUDA failed with error out of memory")

        with mock.patch.object(S, "Transcriber", broken), \
                mock.patch.object(S.time, "sleep") as sleep, \
                mock.patch.object(sys, "argv", ["ozen_server.py"]), \
                mock.patch.dict("os.environ", {"OZEN_TOKEN": "x"}), \
                self.assertLogs(S.log, "CRITICAL") as logged:
            with self.assertRaises(SystemExit):
                asyncio.run(S.main())
        sleep.assert_called_once_with(S.LOAD_RETRY_SECONDS)
        self.assertIn("out of memory", logged.output[0])
        self.assertIn("graphics card", logged.output[0])

    def test_models_that_load_but_fail_their_first_pass_wait_and_restart_the_same_way(self):
        from unittest import mock

        class FailsWarmUp:
            def __init__(self, *args, **kwargs):
                pass

            async def transcribe(self, *args, **kwargs):
                raise RuntimeError("CUBLAS_STATUS_NOT_SUPPORTED")

        with mock.patch.object(S, "Transcriber", FailsWarmUp), \
                mock.patch.object(S.time, "sleep") as sleep, \
                mock.patch.object(sys, "argv", ["ozen_server.py"]), \
                mock.patch.dict("os.environ", {"OZEN_TOKEN": "x"}), \
                self.assertLogs(S.log, "CRITICAL") as logged:
            with self.assertRaises(SystemExit) as stopped:
                asyncio.run(S.main())
        self.assertEqual(stopped.exception.code, 4)
        sleep.assert_called_once_with(S.LOAD_RETRY_SECONDS)
        self.assertIn("CUBLAS_STATUS_NOT_SUPPORTED", logged.output[0])


class Listening(Exception):
    pass


class Startup(unittest.TestCase):
    """What main() makes of its command line, up to where it would listen."""

    def start(self, *argv):
        made, cleaners = [], []

        class Warm:
            name = "model"

            def __init__(self, *args):
                made.append(args)

            async def transcribe(self, *args, **kwargs):
                return "", None, []

        class Serve:
            def __init__(self, *args, **kwargs):
                pass

            async def __aenter__(self):
                raise Listening()

            async def __aexit__(self, *exc):
                return False

        enhance = types.ModuleType("enhance")
        enhance.StreamingEnhancer = lambda path, mix: cleaners.append(mix)
        with mock.patch.object(S, "Transcriber", Warm), \
                mock.patch.object(S.websockets, "serve", Serve, create=True), \
                mock.patch.dict(sys.modules, {"enhance": enhance}), \
                mock.patch.object(sys, "argv", ["ozen_server.py", *argv]), \
                mock.patch.dict("os.environ", {"OZEN_TOKEN": "x"}):
            with self.assertRaises(Listening):
                asyncio.run(S.main())
        return made, cleaners

    def test_the_finished_line_model_on_the_command_line_is_the_one_loaded(self):
        made, _ = self.start("--final-model", "ivrit-ai/whisper-large-v3-ct2")
        self.assertEqual(made[0][5], "ivrit-ai/whisper-large-v3-ct2")
        made, _ = self.start()
        self.assertIsNone(made[0][5])

    def test_the_audio_cleaner_is_loaded_only_when_its_mix_is_above_zero(self):
        self.assertEqual(self.start()[1], [])
        self.assertEqual(self.start("--enhance-mix", "0.3")[1], [0.3])


class PromptBudget(unittest.TestCase):
    def test_the_names_at_the_top_are_the_ones_kept(self):
        terms = [f"name{i}" for i in range(100)]
        kept = S.front_terms(terms, lambda text: len(text.split()), 10)
        self.assertEqual(kept, terms[:10])

    def test_a_short_list_is_kept_whole(self):
        self.assertEqual(S.front_terms(["a", "b"], lambda text: len(text), 200), ["a", "b"])

    def test_a_long_names_list_leaves_the_caption_half_of_the_context(self):
        # Prompt and hotwords share Whisper's 448 tokens with the caption; a
        # 60-name list filled 422 of them and cut 98 of 120 short sentences.
        class Tokens:
            context = False

            @staticmethod
            def count_tokens(text):
                return len(text.split())

        names = [f"name{i}" for i in range(300)]
        session = S.Session(Socket(), Tokens(), "he", names, live_interval=0.3)
        prompt, hotwords = session.prompt(), session.hotwords()
        self.assertTrue(hotwords.startswith("name0, name1,"))
        self.assertTrue(prompt.startswith("name0, name1,"))
        self.assertLessEqual(Tokens.count_tokens(prompt) + Tokens.count_tokens(" " + hotwords), 448 - 224)

    def test_no_names_sends_no_prompt_and_no_hotwords(self):
        session = S.Session(Socket(), SlowGPU(0.1), "he", [], live_interval=0.3)
        self.assertIsNone(session.prompt())
        self.assertIsNone(session.hotwords())


class Logging(unittest.TestCase):
    def test_the_log_keeps_the_servers_lines_but_not_one_per_model_pass(self):
        root, library = logging.getLogger(), logging.getLogger("faster_whisper")
        levels = root.level, library.level
        try:
            with mock.patch.object(S.logging, "basicConfig", side_effect=lambda **kw: root.setLevel(kw["level"])):
                S.setup_logging()
            self.assertTrue(S.log.isEnabledFor(logging.INFO))
            self.assertFalse(library.isEnabledFor(logging.INFO))
            self.assertTrue(library.isEnabledFor(logging.WARNING))
        finally:
            root.setLevel(levels[0])
            library.setLevel(levels[1])


class Reports(unittest.TestCase):
    def test_a_report_the_phone_sent_is_readable_only_by_the_servers_owner(self):
        import os
        import tempfile
        from unittest import mock
        previous = os.umask(0o022)
        try:
            with tempfile.TemporaryDirectory() as folder:
                reports = os.path.join(folder, "reports")
                with mock.patch.object(S, "REPORTS_DIR", reports):
                    name = S.save_report("line one\nline two", "Ozen 0.2")
                path = os.path.join(reports, name)
                self.assertEqual(os.stat(reports).st_mode & 0o777, 0o700)
                self.assertEqual(os.stat(path).st_mode & 0o777, 0o600)
                with open(path, encoding="utf-8") as f:
                    self.assertIn("line two", f.read())
        finally:
            os.umask(previous)

    def test_a_report_is_kept_even_where_permissions_cannot_be_changed(self):
        import os
        import tempfile
        from unittest import mock
        with tempfile.TemporaryDirectory() as folder:
            reports = os.path.join(folder, "reports")
            with mock.patch.object(S, "REPORTS_DIR", reports), mock.patch("os.chmod", side_effect=PermissionError("locked")):
                name = S.save_report("line one", "Ozen 0.2")
            with open(os.path.join(reports, name), encoding="utf-8") as f:
                self.assertIn("line one", f.read())

    def test_reports_sent_in_the_same_second_are_all_kept_and_the_oldest_go_first(self):
        import os
        import tempfile
        from unittest import mock
        stamps = ["20261004-090000"] * 12 + [f"20261004-0901{i:02d}" for i in range(40)]
        with tempfile.TemporaryDirectory() as folder:
            reports = os.path.join(folder, "reports")
            with mock.patch.object(S, "REPORTS_DIR", reports), mock.patch.object(S.time, "strftime", side_effect=stamps):
                names = [S.save_report(f"report {i}", "Ozen 0.2") for i in range(len(stamps))]
            self.assertEqual(len(set(names)), len(stamps))
            self.assertEqual(names[:3], ["20261004-090000.txt", "20261004-090000-2.txt", "20261004-090000-3.txt"])
            self.assertEqual(sorted(os.listdir(reports)), sorted(names[2:]))
            with open(os.path.join(reports, names[11]), encoding="utf-8") as f:
                self.assertIn("report 11", f.read())


class NamesGPU(SlowGPU):
    """Stands in for the Transcriber a connection is handed: remembers the
    names each pass was primed with."""
    name = "stand-in"
    beam = 5

    def __init__(self, fails=False):
        super().__init__(live_seconds=0.05)
        self.fails = fails
        self.hotwords = []

    @staticmethod
    def count_tokens(text):
        return len(text.split())

    async def transcribe(self, audio, language, prompt, final, hotwords=None, gate=True, beam=None):
        if self.fails:
            raise RuntimeError("CUDA error: an illegal memory access was encountered")
        self.hotwords.append(hotwords)
        return await super().transcribe(audio, language, prompt, final, hotwords, gate, beam)


class PhoneSocket:
    """A paired phone: the hello, then each message in turn; a number is a
    pause of that many seconds before the next one."""
    remote_address = ("192.168.1.20", 50000)

    def __init__(self, hello, messages):
        self.hello = json.dumps(hello)
        self.messages = messages
        self.sent = []
        self.closed = None

    async def recv(self):
        return self.hello

    async def send(self, text):
        self.sent.append(text)

    async def close(self, code=1000, reason=""):
        self.closed = (code, reason)

    async def _messages(self):
        for message in self.messages:
            if isinstance(message, float):
                await asyncio.sleep(message)
            else:
                yield message

    def __aiter__(self):
        return self._messages()


def speech_frames(seconds):
    chunk = pcm(seconds, 0.3)
    step = int(0.1 * S.RATE) * 2
    return [chunk[i:i + step] for i in range(0, len(chunk), step)]


class SessionAfterHello(unittest.TestCase):
    def test_new_names_reach_the_next_pass_a_report_is_kept_junk_is_skipped_and_end_finishes_the_line(self):
        import os
        import tempfile
        from unittest import mock
        hello = {"type": "hello", "token": "example-code-123", "vocabulary": ["Ruti"], "client": "Ozen test"}
        messages = ["not json", "[1, 2]", json.dumps({"type": "vocabulary", "terms": ["Dana", "Yossi"]}),
                    *speech_frames(1.5),
                    json.dumps({"type": "report", "text": "the diagnostics"}),
                    json.dumps({"type": "end"})]
        ws, gpu = PhoneSocket(hello, messages), NamesGPU()
        with tempfile.TemporaryDirectory() as folder:
            reports = os.path.join(folder, "reports")
            with mock.patch.object(S, "REPORTS_DIR", reports), self.assertLogs(S.log, "INFO") as logged:
                asyncio.run(asyncio.wait_for(S.handle(ws, gpu, "example-code-123", 1.0), 5))
            frames = [json.loads(text) for text in ws.sent]
            self.assertEqual(frames[0]["type"], "ready")
            saved = [f["name"] for f in frames if f["type"] == "report_saved"]
            self.assertEqual(len(saved), 1, frames)
            with open(os.path.join(reports, saved[0]), encoding="utf-8") as f:
                report = f.read()
        self.assertIn("from: Ozen test", report)
        self.assertIn("the diagnostics", report)
        finals = [f for f in frames if f["type"] == "text" and f["final"]]
        self.assertEqual([f["text"] for f in finals], ["שלום"])
        self.assertEqual(gpu.hotwords[-1], "Dana, Yossi")
        self.assertNotIn("Ruti", "".join(h or "" for h in gpu.hotwords))
        self.assertIsNone(ws.closed)
        self.assertTrue(any("1 lines" in line for line in logged.output), logged.output)

    def test_a_pass_that_fails_closes_the_connection_so_the_phone_stops_waiting(self):
        hello = {"type": "hello", "token": "example-code-123"}
        ws = PhoneSocket(hello, [*speech_frames(1.5), 0.6])
        with self.assertLogs(S.log, "ERROR") as logged:
            asyncio.run(asyncio.wait_for(S.handle(ws, NamesGPU(fails=True), "example-code-123", 0.3), 5))
        self.assertEqual(ws.closed, (1011, "transcription failed"))
        self.assertTrue(any("CUDA error" in line for line in logged.output), logged.output)


class BadFramesFromPairedPhone(unittest.TestCase):
    def run_session(self, messages):
        hello = {"type": "hello", "token": "example-code-123"}
        ws = PhoneSocket(hello, messages)
        with self.assertLogs(S.log, "INFO") as logged:
            asyncio.run(asyncio.wait_for(S.handle(ws, NamesGPU(), "example-code-123", 0.3), 5))
        return ws, [json.loads(t) for t in ws.sent], logged.output

    def test_an_audio_frame_cut_mid_sample_does_not_end_the_session(self):
        ws, frames, log_lines = self.run_session([b"\x01", *speech_frames(1.5), json.dumps({"type": "end"})])
        self.assertFalse(any("ended with" in line for line in log_lines), log_lines)
        self.assertEqual([f["text"] for f in frames if f["type"] == "text" and f["final"]], ["שלום"])

    def test_a_report_with_half_a_character_pair_is_still_saved(self):
        import tempfile
        from unittest import mock
        with tempfile.TemporaryDirectory() as folder:
            reports = os.path.join(folder, "reports")
            with mock.patch.object(S, "REPORTS_DIR", reports):
                ws, frames, log_lines = self.run_session([json.dumps({"type": "report", "text": "a\ud800b"}),
                                                          json.dumps({"type": "end"})])
                saved = [f["name"] for f in frames if f["type"] == "report_saved"]
                kept = {n: open(os.path.join(reports, n), encoding="utf-8").read() for n in os.listdir(reports)}
        self.assertFalse(any("ended with" in line for line in log_lines), log_lines)
        self.assertEqual(list(kept), saved)
        self.assertEqual([text.endswith("\n\na?b") for text in kept.values()], [True])


if __name__ == "__main__":
    unittest.main()
