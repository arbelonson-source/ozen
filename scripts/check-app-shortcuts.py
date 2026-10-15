#!/usr/bin/env python3
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CATALOG = ROOT / "App/Ozen/AppShortcuts.xcstrings"
INTENTS = ROOT / "App/Ozen/OzenIntents.swift"
LANGUAGES = {"am", "ar", "de", "en", "es", "fr", "he", "hi", "pt-PT", "ru", "uk", "zh-Hans"}
PLACEHOLDER = "${applicationName}"
BIG_TEXT_KEY = "כתבו לי ב" + PLACEHOLDER


def value(entry, lang):
    return entry.get("localizations", {}).get(lang, {}).get("stringUnit", {}).get("value")


def main():
    data = json.loads(CATALOG.read_text(encoding="utf-8"))
    source = data.get("sourceLanguage", "he")
    expected = (LANGUAGES - {source}) if source in LANGUAGES else LANGUAGES
    errors = []
    strings = data.get("strings", {})
    for key, entry in strings.items():
        if PLACEHOLDER not in key:
            errors.append(f"{key!r}: key lacks {PLACEHOLDER}")
        for lang in sorted(expected):
            text = value(entry, lang)
            if not text:
                errors.append(f"{key!r}: missing {lang}")
            elif PLACEHOLDER not in text:
                errors.append(f"{key!r}: {lang} lacks {PLACEHOLDER}")
    big = strings.get(BIG_TEXT_KEY)
    if big is None:
        errors.append("big-text key is missing from the catalog")
    else:
        english = value(big, "en") or ""
        if english.startswith("Big text"):
            errors.append(f"big-text key: English {english!r} does not mean 'write to me'")
    if INTENTS.exists():
        swift = INTENTS.read_text(encoding="utf-8")
        for before, after in re.findall(r'"([^"\n]*?)\\\(\.applicationName\)([^"\n]*)"', swift):
            literal = before + PLACEHOLDER + after
            if literal not in strings:
                print(f"warning: no catalog key for phrase {literal!r}")
    for error in errors:
        print(f"ERROR: {error}")
    if errors:
        return 1
    print(f"ok: {len(strings)} keys, all {len(expected)} translations carry {PLACEHOLDER}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
