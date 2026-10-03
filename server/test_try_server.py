import asyncio
import io
import json
import os
import sys
import tempfile
import types
import unittest
from unittest import mock

import numpy as np

sys.modules.setdefault("soundfile", types.ModuleType("soundfile"))
sys.modules.setdefault("websockets", types.ModuleType("websockets"))

import try_server as T


class Socket:
    def __init__(self, lines):
        self.lines = lines
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
        for i, text in enumerate(self.lines):
            await asyncio.sleep(0)
            yield json.dumps({"type": "text", "utterance": i, "final": True, "text": text, "end_s": 0})


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
            out = io.StringIO()
            socket = Socket(["the doctor comes", "at two"])
            with mock.patch.dict(sys.modules, {"jiwer": None}), \
                    mock.patch.object(T.sf, "read", return_value=(np.zeros(1600, np.float32), 16000), create=True), \
                    mock.patch.object(T.websockets, "connect", return_value=socket, create=True), \
                    mock.patch("sys.stdout", out):
                asyncio.run(T.main("ws://localhost:8765", "example-code-123", "speech.wav", reference))
            self.assertIn("words wrong: 20.0%", out.getvalue())


if __name__ == "__main__":
    unittest.main()
