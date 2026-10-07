#!/usr/bin/env python3
import glob
import re
import sys

ROOT = sys.argv[1] if len(sys.argv) > 1 else "."
QUOTE = re.compile(r'"([^"\n]{3,120})"\s*\((?:[a-z][^)\n]{2,80})\)')
LITERAL = re.compile(r'"((?:[^"\\\n]|\\.)*)"')
PIECE = re.compile(r"\bN\b|\d+|[·/…:]|\.\.\.")


def normalized(text):
    return text.replace("’", "'").replace("‑", "-").lower()


def app_text(root):
    literals = []
    for path in glob.glob(f"{root}/App/**/*.swift", recursive=True) + glob.glob(f"{root}/Sources/**/*.swift", recursive=True):
        with open(path, encoding="utf-8") as source:
            literals += LITERAL.findall(source.read())
    return normalized("\n".join(literals))


NOT_APP_TEXT = {"and six", "in two weeks", "three pills", "twice", "Visit to the doctor"}
TABLE_ROW = re.compile(r'^\| ("[^|]*)\|', re.MULTILINE)
PLAIN_QUOTE = re.compile(r'"([^"\n]{3,120})"')


def quotes_in(doc):
    flat = doc.replace("\n", " ")
    found = [(m.start(), m.group(1)) for m in QUOTE.finditer(flat)]
    seen = {start for start, _ in found}
    for row in TABLE_ROW.finditer(doc):
        for m in PLAIN_QUOTE.finditer(row.group(1)):
            start = row.start(1) + m.start()
            if start not in seen:
                found.append((start, m.group(1)))
    quotes = [(doc.count("\n", 0, start) + 1, re.sub(r"\s+", " ", quote)) for start, quote in sorted(found)]
    return [(number, quote) for number, quote in quotes if quote not in NOT_APP_TEXT]


# A button or row named in bold with its Hebrew in brackets, as in
# **Send report** (shlichat ha-doch). Brackets with a digit hold a version
# or a date, not Hebrew.
BOLD_LABEL = re.compile(r'\*\*([^*"\n]{3,80})\*\*\s*\((?:[a-z][^)\n\d]{2,80})\)')


TR_ENGLISH = re.compile(r'\btr\(\s*"(?:[^"\\\n]|\\.)*"\s*,\s*"((?:[^"\\\n]|\\.)*)"')


def shown_labels(root):
    labels = set()
    for path in glob.glob(f"{root}/App/**/*.swift", recursive=True) + glob.glob(f"{root}/Sources/**/*.swift", recursive=True):
        with open(path, encoding="utf-8") as source:
            labels.update(normalized(label) for label in TR_ENGLISH.findall(source.read()))
    return labels


def bold_labels_in(doc):
    flat = doc.replace("\n", " ")
    return [(doc.count("\n", 0, m.start()) + 1, re.sub(r"\s+", " ", m.group(1))) for m in BOLD_LABEL.finditer(flat)]


def stale_quotes(root):
    text = app_text(root)
    labels = shown_labels(root)
    stale = []
    for path in sorted(glob.glob(f"{root}/docs/*.md")) + [f"{root}/README.md"]:
        with open(path, encoding="utf-8") as doc:
            content = doc.read()
            for number, quote in quotes_in(content):
                pieces = [p.strip(" .,") for p in PIECE.split(quote)]
                missing = [p for p in pieces if len(p) > 3 and normalized(p) not in text]
                if missing:
                    stale.append(f"{path}:{number}: \"{quote}\" not in the app ({missing[0]!r})")
            # A label is the whole of what tr() shows: "Send the report" is
            # part of another button's text, not the name of this one.
            for number, label in bold_labels_in(content):
                if normalized(label) not in labels:
                    stale.append(f"{path}:{number}: **{label}** is not a button or row name in the app")
    return stale


if __name__ == "__main__":
    problems = stale_quotes(ROOT)
    for problem in problems:
        print(problem)
    if problems:
        print(f"{len(problems)} quoted message(s) or button name(s) in docs/ and README.md no longer match the app's wording")
    sys.exit(1 if problems else 0)
