import asyncio
import io
import json
import os
import sys
import tempfile
import time
import types
import unittest
from unittest import mock

import numpy as np

sys.modules.setdefault("soundfile", types.ModuleType("soundfile"))
sys.modules.setdefault("websockets", types.ModuleType("websockets"))

import try_server as T


def line(number, text, final=True):
    return {"type": "text", "utterance": number, "final": final, "text": text, "end_s": 0}


class Socket:
    def __init__(self, replies):
        self.replies = replies
        self.sent = []

    async def __aenter__(self):
        return self

    async def __aexit__(self, *_):
        return False

    async def send(self, data):
        self.sent.append(data)

    async def recv(self):
        return json.dumps({"type": "ready", "model": "fake"})

    def __aiter__(self):
        return self.messages()

    async def messages(self):
        for reply in self.replies:
            await asyncio.sleep(0)
            yield json.dumps(reply)


def run(replies, reference=None):
    out = io.StringIO()
    with mock.patch.dict(sys.modules, {"jiwer": None}), \
            mock.patch.object(T.sf, "read", return_value=(np.zeros(1600, np.float32), 16000), create=True), \
            mock.patch.object(T.websockets, "connect", return_value=Socket(replies), create=True), \
            mock.patch("sys.stdout", out):
        asyncio.run(T.main("ws://localhost:8765", "example-code-123", "speech.wav", reference))
    return out.getvalue()


class WordsWrong(unittest.TestCase):
    def test_swapped_missing_and_extra_words_each_count_once(self):
        self.assertEqual(T.words_wrong("a b c d", "a b c d"), 0)
        self.assertEqual(T.words_wrong("a b c d", "a x c"), 0.5)
        self.assertEqual(T.words_wrong("a b", "a b c"), 0.5)
        self.assertEqual(T.words_wrong("a b", ""), 1)

    def test_punctuation_and_case_are_not_mistakes(self):
        self.assertEqual(T.words_wrong("Hello, world.", "hello world"), 0)

    def test_a_run_with_the_spoken_text_scores_it_with_what_setup_installs(self):
        with tempfile.TemporaryDirectory() as folder:
            reference = os.path.join(folder, "said.txt")
            with open(reference, "w", encoding="utf-8") as f:
                f.write("the doctor comes at ten\n")
            out = run([line(0, "the doctor comes"), line(1, "at two")], reference)
            self.assertIn("words wrong: 20.0%", out)


class Sending(unittest.TestCase):
    def test_the_whole_recording_goes_out_in_order_at_the_pace_it_was_said_then_the_end(self):
        audio = np.linspace(-0.5, 0.5, 8000, dtype=np.float32)
        socket = Socket([])
        with mock.patch.dict(sys.modules, {"jiwer": None}), \
                mock.patch.object(T.sf, "read", return_value=(audio, 16000), create=True), \
                mock.patch.object(T.websockets, "connect", return_value=socket, create=True), \
                mock.patch("sys.stdout", io.StringIO()):
            started = time.monotonic()
            asyncio.run(T.main("ws://localhost:8765", "example-code-123", "speech.wav", None))
            took = time.monotonic() - started
        sent = np.frombuffer(b"".join(m for m in socket.sent if isinstance(m, bytes)), dtype="<i2")
        self.assertTrue(np.array_equal(sent, (audio * 32767).astype("<i2")))
        self.assertEqual(json.loads(socket.sent[-1]), {"type": "end"})
        self.assertGreater(took, 0.4)


class Lines(unittest.TestCase):
    def test_lines_are_counted_as_the_phone_shows_them(self):
        out = run([line(0, "the doctor", final=False), line(0, ""), line(1, ""), line(2, " "), line(3, "at ten")])
        self.assertIn("2 lines, 1 live updates", out)
        self.assertIn("the doctor", out)
        self.assertNotRegex(out, r"\[\s*\d+\]\s+\S+s\s*\n")
        self.assertRegex(out, r"first words of a line on screen: median -?\d+\.\d\ds")


if __name__ == "__main__":
    unittest.main()
