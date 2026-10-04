import asyncio
import json
import logging
import sys
import time
import types
import unittest
from unittest import mock

import numpy as np

fake = types.ModuleType("faster_whisper")
fake.WhisperModel = object
fake_vad = types.ModuleType("faster_whisper.vad")
fake_vad.VadOptions = lambda **kw: kw
fake_vad.get_speech_timestamps = lambda *a, **kw: []
sys.modules.setdefault("faster_whisper", fake)
sys.modules.setdefault("faster_whisper.vad", fake_vad)
sys.modules.setdefault("websockets", types.ModuleType("websockets"))
if not hasattr(sys.modules["websockets"], "ConnectionClosed"):
    sys.modules["websockets"].ConnectionClosed = type("ConnectionClosed", (Exception,), {})

import ozen_server as S


class Model:
    def __init__(self):
        self.beams = []

    def transcribe(self, audio, **kw):
        self.beams.append(kw["beam_size"])
        return [], None


class Beam(unittest.TestCase):
    def test_only_a_sensible_beam_from_the_phone_counts(self):
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


class Summary(unittest.TestCase):
    def play(self, pieces):
        session = S.Session(Socket(), GateGPU(0.01), "he", [], live_interval=0.3)

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

    def test_a_session_of_only_noise_still_says_how_many_lines_came_back_empty(self):
        summary = self.play([(0.2, 0.3), (1.6, 0.0005), (0.2, 0.3), (1.6, 0.0005)])
        self.assertIn("no lines, 2 finished empty", summary)


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


if __name__ == "__main__":
    unittest.main()
