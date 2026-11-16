#!/usr/bin/env python3
import re
import subprocess
import sys

BANNED = re.compile(r"UNICODE_CHARACTER_CLASS|\(\?[a-zA-Z]*U[a-zA-Z]*[:)]")
ALLOWED = "android/core/src/main/kotlin/com/arbelonson/ozen/core/UnicodePattern.kt"


def problems():
    listed = subprocess.run(
        ["git", "ls-files", "android/core/src/main", "android/app/src/main"],
        capture_output=True, text=True, check=True,
    ).stdout
    found = []
    for name in listed.splitlines():
        if not name.endswith((".kt", ".java")) or name == ALLOWED:
            continue
        for number, line in enumerate(open(name, encoding="utf-8").read().splitlines(), 1):
            if BANNED.search(line):
                found.append(f"{name}:{number}: Android's regex engine throws on the Unicode flag; use unicodePattern() from UnicodePattern.kt")
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
