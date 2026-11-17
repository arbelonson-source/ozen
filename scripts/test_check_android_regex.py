import importlib.util
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location("check_android_regex", Path(__file__).parent / "check-android-regex.py")
check = importlib.util.module_from_spec(spec)
spec.loader.exec_module(check)


def lines(text):
    return [number for number, _ in check.scan(text)]


class Caught(unittest.TestCase):
    def test_the_named_flag_on_one_line(self):
        self.assertEqual(lines("val p = Pattern.compile(x, Pattern.UNICODE_CHARACTER_CLASS)"), [1])

    def test_the_named_flag_after_a_line_break_following_the_dot(self):
        self.assertEqual(lines("val p = Pattern.compile(\n  x,\n  Pattern.\n    UNICODE_CHARACTER_CLASS)\n"), [4])

    def test_the_named_flag_before_a_line_break_ahead_of_the_dot(self):
        self.assertEqual(lines("val f = Pattern\n    .UNICODE_CHARACTER_CLASS\n"), [2])

    def test_an_alias_constant_for_the_named_flag(self):
        self.assertEqual(lines("import java.util.regex.Pattern.UNICODE_CHARACTER_CLASS as U\n"), [1])

    def test_the_flag_looked_up_by_name(self):
        self.assertEqual(lines('Pattern::class.java.getField("UNICODE_CHARACTER_CLASS")'), [1])

    def test_a_hex_flag_argument(self):
        self.assertEqual(lines("Pattern.compile(p, 0x100)"), [1])

    def test_a_decimal_flag_argument_combined_with_another_flag(self):
        self.assertEqual(lines("Pattern.compile(p, Pattern.CASE_INSENSITIVE or 256)"), [1])

    def test_a_flag_argument_with_the_bit_among_others(self):
        self.assertEqual(lines("Pattern.compile(\n    p,\n    0x1_03\n)"), [3])

    def test_a_binary_and_a_long_flag_argument(self):
        self.assertEqual(lines("Pattern.compile(p, 0b1_0000_0000)\nPattern.compile(p, 256L)"), [1, 2])

    def test_a_numeric_flag_on_toPattern(self):
        self.assertEqual(lines('"a".toRegex().toPattern(256)'), [1])

    def test_the_inline_flag_in_a_pattern_string(self):
        self.assertEqual(lines('val p = Regex("(?U)\\\\w+")'), [1])

    def test_the_inline_flag_among_other_flags(self):
        self.assertEqual(lines('val p = Pattern.compile("(?iU:abc)")'), [1])

    def test_the_inline_flag_in_a_raw_string(self):
        self.assertEqual(lines('val p = """\n(?U)\\w+\n"""'), [2])

    def test_the_flag_after_a_comment_opener_inside_a_string(self):
        self.assertEqual(lines('val s = "// (?U)"'), [1])

    def test_the_flag_after_a_comment_on_the_same_line(self):
        self.assertEqual(lines("/* ok */ Pattern.UNICODE_CHARACTER_CLASS"), [1])


class Allowed(unittest.TestCase):
    def test_the_word_in_line_and_block_comments(self):
        self.assertEqual(lines("// UNICODE_CHARACTER_CLASS and (?U)\n/* UNICODE_CHARACTER_CLASS\n (?U) */\n/** doc\n /* nested UNICODE_CHARACTER_CLASS */ */\nval a = 1"), [])

    def test_the_word_in_log_text(self):
        self.assertEqual(lines('Log.d("tag", "UNICODE_CHARACTER_CLASS is not supported")'), [])

    def test_other_flags_and_small_numbers(self):
        self.assertEqual(lines("Pattern.compile(p, Pattern.CASE_INSENSITIVE or 2)\nPattern.compile(p, 0xFF)\nPattern.compile(p, 512)"), [])

    def test_a_number_outside_the_flag_argument(self):
        self.assertEqual(lines("Pattern.compile(\"a,256\", Pattern.MULTILINE)\nPattern.compile(p(256), 0)"), [])

    def test_a_pattern_that_only_turns_the_flag_off_or_is_not_a_group(self):
        self.assertEqual(lines('Regex("(?i-U)a")\nRegex("a(?:U)b")\nRegex("\\\\(U\\\\)")'), [])

    def test_a_quote_character_does_not_start_a_string(self):
        self.assertEqual(lines("val q = '\"'\nval r = Pattern.compile(p, 1)"), [])


if __name__ == "__main__":
    unittest.main()
