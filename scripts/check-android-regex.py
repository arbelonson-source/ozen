#!/usr/bin/env python3
import re
import subprocess
import sys

FLAG_NAME = "UNICODE_CHARACTER_CLASS"
NAMED = re.compile(r"\b" + FLAG_NAME + r"\b")
EMBEDDED = re.compile(r"\(\?[a-zA-Z]*U[a-zA-Z]*(?:-[a-zA-Z]*)?[:)]")
FLAG_CALL = re.compile(r"\b(compile|toPattern)\s*\(")
NUMBER = re.compile(r"\b(?:0[xX][0-9a-fA-F_]+|0[bB][01_]+|[0-9][0-9_]*)[uUlL]*\b")
FLAG_BIT = 0x100
ALLOWED = "android/core/src/main/kotlin/com/arbelonson/ozen/core/UnicodePattern.kt"
MESSAGE = "Android's regex engine throws on the Unicode flag; use unicodePattern() from UnicodePattern.kt"


def blank(text):
    code = list(text)
    strings = []
    i, n = 0, len(text)

    def wipe(a, b):
        for k in range(a, b):
            if code[k] != "\n":
                code[k] = " "

    while i < n:
        two = text[i:i + 2]
        if two == "//":
            j = text.find("\n", i)
            j = n if j < 0 else j
            wipe(i, j)
            i = j
        elif two == "/*":
            depth, j = 1, i + 2
            while j < n and depth:
                if text.startswith("/*", j):
                    depth, j = depth + 1, j + 2
                elif text.startswith("*/", j):
                    depth, j = depth - 1, j + 2
                else:
                    j += 1
            wipe(i, j)
            i = j
        elif text.startswith('"""', i):
            j = text.find('"""', i + 3)
            j = n if j < 0 else j
            strings.append((i + 3, text[i + 3:j]))
            wipe(i + 3, j)
            i = j + 3
        elif text[i] == '"':
            j = i + 1
            while j < n and text[j] not in '"\n':
                j += 2 if text[j] == "\\" else 1
            strings.append((i + 1, text[i + 1:j]))
            wipe(i + 1, j)
            i = j + 1
        elif text[i] == "'":
            j = i + 1
            while j < n and text[j] not in "'\n":
                j += 2 if text[j] == "\\" else 1
            wipe(i + 1, j)
            i = j + 1
        else:
            i += 1
    return "".join(code), strings


def argument(code, start, index):
    depth, args, begin, j = 0, [], start, start
    while j < len(code):
        c = code[j]
        if c in "([{":
            depth += 1
        elif c in ")]}":
            if depth == 0:
                args.append((begin, j))
                break
            depth -= 1
        elif c == "," and depth == 0:
            args.append((begin, j))
            begin = j + 1
        j += 1
    return args[index] if index < len(args) else None


def has_flag_bit(literal):
    digits = literal.rstrip("uUlL").replace("_", "")
    if digits[:2] in ("0x", "0X"):
        value = int(digits[2:], 16)
    elif digits[:2] in ("0b", "0B"):
        value = int(digits[2:], 2)
    else:
        value = int(digits)
    return bool(value & FLAG_BIT)


def scan(text):
    code, strings = blank(text)
    hits = {}

    def add(offset, kind):
        hits[(text.count("\n", 0, offset) + 1, kind)] = True

    for m in NAMED.finditer(code):
        add(m.start(), "named")
    for offset, body in strings:
        if body.strip() == FLAG_NAME:
            add(offset, "named")
        for m in EMBEDDED.finditer(body):
            add(offset + m.start(), "embedded")
    for m in FLAG_CALL.finditer(code):
        span = argument(code, m.end(), 1 if m.group(1) == "compile" else 0)
        if span:
            for number in NUMBER.finditer(code[span[0]:span[1]]):
                if has_flag_bit(number.group()):
                    add(span[0] + number.start(), "number")
    return sorted(hits)


def problems():
    listed = subprocess.run(
        ["git", "ls-files", "android/core/src/main", "android/app/src/main"],
        capture_output=True, text=True, check=True,
    ).stdout
    found = []
    for name in listed.splitlines():
        if not name.endswith((".kt", ".java")) or name == ALLOWED:
            continue
        with open(name, encoding="utf-8") as handle:
            for number, _ in scan(handle.read()):
                found.append(f"{name}:{number}: {MESSAGE}")
    return found


def main():
    found = problems()
    for line in found:
        print(line)
    if found:
        sys.exit(1)
    print("no direct Unicode regex flag in Android main sources")


if __name__ == "__main__":
    main()
