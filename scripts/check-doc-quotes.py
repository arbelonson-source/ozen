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


# A type the code declares, in its own CamelCase name: "CaptionStabilizer"
# in the README's list of what the app does tells its readers nothing.
TYPE_NAME = re.compile(r"\b(?:struct|enum|class|actor|protocol)\s+([A-Z][a-z0-9]+(?:[A-Z][a-z0-9]+)+)\b")


def code_names_in_features(root):
    names = set()
    for path in glob.glob(f"{root}/App/**/*.swift", recursive=True) + glob.glob(f"{root}/Sources/**/*.swift", recursive=True):
        with open(path, encoding="utf-8") as source:
            names.update(TYPE_NAME.findall(source.read()))
    with open(f"{root}/README.md", encoding="utf-8") as doc:
        lines = doc.read().split("\n")
    start = next(i for i, line in enumerate(lines) if line.startswith("## What it does"))
    end = next((i for i, line in enumerate(lines) if i > start and line.startswith("## ")), len(lines))
    return [f"{root}/README.md:{i + 1}: {word} is a name in the code, not a word for the reader"
            for i in range(start, end) for word in re.findall(r"\b[A-Z]\w+\b", lines[i]) if word in names]


# The walkthrough's pages, in the order OnboardingView shows them, against
# the numbered list in getting-started.md: a page named by its title must
# sit at the same number, and the count must match with and without the
# one page that is sometimes left out.
WORDS = {6: "six", 7: "seven", 8: "eight", 9: "nine", 10: "ten"}


def walkthrough_problems(root):
    with open(f"{root}/App/Ozen/Views/OnboardingView.swift", encoding="utf-8") as source:
        view = source.read()
    steps = [step.strip() for step in re.search(r"private enum Step\b[^{]*\{\s*case ([\w, ]+)", view).group(1).split(",")]
    titles = {normalized(title): step for step, title in re.findall(r'case \.(\w+): return tr\("[^"]*", "([^"]*)"', view)}
    optional = re.search(r"\$0 != \.(\w+) \|\|", view).group(1)
    path = f"{root}/docs/getting-started.md"
    with open(path, encoding="utf-8") as doc:
        section = doc.read().split("## The first launch", 1)[1].split("\n## ", 1)[0]
    problems = []
    items = re.findall(r"^(\d+)\. \*\*([^*]+?)\.?\*\*", section, re.MULTILINE)
    if len(items) != len(steps):
        problems.append(f"{path}: lists {len(items)} walkthrough pages, the app has {len(steps)}")
    for number, label in items:
        step = titles.get(normalized(label))
        if step is not None and steps.index(step) + 1 != int(number):
            problems.append(f"{path}: **{label}** is page {steps.index(step) + 1} in the app, not {number}")
    flat = " ".join(section.split())
    for phrase in [f"{WORDS.get(len(steps))} when", f"{WORDS.get(len(steps) - 1)} when", f"page {steps.index(optional) + 1} is left out"]:
        if phrase not in flat:
            problems.append(f"{path}: the first launch should say \"{phrase}\"")
    return problems


if __name__ == "__main__":
    problems = stale_quotes(ROOT) + code_names_in_features(ROOT) + walkthrough_problems(ROOT)
    for problem in problems:
        print(problem)
    if problems:
        print(f"{len(problems)} place(s) in docs/ and README.md no longer match the app")
    sys.exit(1 if problems else 0)
