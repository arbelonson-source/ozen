import contextlib
import io
import json
import sys
import tempfile
import unittest
from pathlib import Path

import wer

REFS = [json.loads(l) for l in (Path(wer.__file__).parent / "clips" / "refs.jsonl").read_text().splitlines() if l.strip()]


class Normalize(unittest.TestCase):
    def test_the_hebrew_hyphen_splits_words_like_a_space(self):
        self.assertEqual(wer.normalize("בית־ספר"),
                         wer.normalize("בית ספר"))

    def test_vowel_marks_and_punctuation_are_not_words(self):
        self.assertEqual(wer.normalize("שָׁלוֹם, עוֹלָם!"),
                         ["שלום", "עולם"])

    def test_letter_case_is_not_an_error(self):
        self.assertEqual(wer.normalize("2.5GHz"), wer.normalize("2.5Ghz"))


class EditDistance(unittest.TestCase):
    def test_counts_each_changed_missing_or_extra_word_once(self):
        self.assertEqual(wer.edit_distance(["a", "b", "c"], ["a", "x", "c"]), 1)
        self.assertEqual(wer.edit_distance(["a", "b", "c"], ["a", "c"]), 1)
        self.assertEqual(wer.edit_distance(["a", "c"], ["a", "b", "c"]), 1)
        self.assertEqual(wer.edit_distance(["a", "b"], ["b", "a"]), 2)

    def test_against_nothing_every_word_counts(self):
        self.assertEqual(wer.edit_distance(["a", "b", "c"], []), 3)
        self.assertEqual(wer.edit_distance([], ["a", "b"]), 2)


class ReportText(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.dir = Path(temp.name)

    def test_reads_a_report_named_for_the_clip_or_its_stem(self):
        (self.dir / "00.wav.json").write_text(json.dumps({"text": "שלום"}))
        (self.dir / "01.json").write_text(json.dumps([{"text": "שלום"}, {"text": "עולם"}]))
        self.assertEqual(wer.report_text(self.dir, "00.wav"), "שלום")
        self.assertEqual(wer.report_text(self.dir, "01.wav"), "שלום עולם")

    def test_reads_the_last_hebrew_line_the_command_printed(self):
        (self.dir / "02.txt").write_text("Loading model\nשורה ראשונה\nTranscription:\nשורה אחרונה\nDone in 3 s\n")
        self.assertEqual(wer.report_text(self.dir, "02.wav"), "שורה אחרונה")

    def test_a_missing_report_stops_the_check_and_names_the_clip(self):
        with self.assertRaises(SystemExit) as stopped:
            wer.report_text(self.dir, "03.wav")
        self.assertIn("03.wav", str(stopped.exception))


class Main(unittest.TestCase):
    def check(self, texts, *limit):
        with tempfile.TemporaryDirectory() as temp:
            for ref, text in zip(REFS, texts):
                (Path(temp) / f"{ref['file']}.json").write_text(json.dumps({"text": text}))
            out = io.StringIO()
            argv, sys.argv = sys.argv, ["wer.py", temp, *limit]
            try:
                with contextlib.redirect_stdout(out):
                    wer.main()
            finally:
                sys.argv = argv
            return out.getvalue()

    def test_the_reference_itself_scores_zero(self):
        self.assertIn("total: 0.0% of", self.check([r["ref"] for r in REFS]))

    def test_nonsense_fails_the_default_limit(self):
        with self.assertRaises(SystemExit) as stopped:
            self.check(["" for _ in REFS])
        self.assertEqual(str(stopped.exception), "above the 60% limit")

    def test_a_total_at_the_limit_passes(self):
        self.assertIn("total: 100.0% of", self.check(["" for _ in REFS], "100"))

    def test_the_total_counts_words_not_clips(self):
        texts = [r["ref"] for r in REFS]
        texts[0] = ""
        words = [len(wer.normalize(r["ref"])) for r in REFS]
        self.assertIn(f"total: {100 * words[0] / sum(words):.1f}% of {sum(words)} words", self.check(texts))


if __name__ == "__main__":
    unittest.main()
