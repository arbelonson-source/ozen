import unittest

import wer


class Normalize(unittest.TestCase):
    def test_the_hebrew_hyphen_splits_words_like_a_space(self):
        self.assertEqual(wer.normalize("בית־ספר"),
                         wer.normalize("בית ספר"))

    def test_vowel_marks_and_punctuation_are_not_words(self):
        self.assertEqual(wer.normalize("שָׁלוֹם, עוֹלָם!"),
                         ["שלום", "עולם"])


if __name__ == "__main__":
    unittest.main()
