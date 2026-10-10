#!/usr/bin/env python3
r"""Checks every tr("hebrew", "english") call in the Swift sources, and the
translation table that stands in for Hebrew and English in every other
supported language.

Both strings must be plain literals, the English one must hold no Hebrew,
and both must interpolate exactly the same expressions. A templated call
(`tr("...", "...", args: [...])`) uses `%1`, `%2`... instead of `\(...)`;
both literals must use exactly the placeholders `1...N` for an N-element
args list. With --untranslated it also lists Hebrew string literals that
sit outside a tr call, so what is left to translate can be seen file by
file. Every run also checks Sources/OzenKit/Resources/Translations.json:
every language must have every English key, and a key with placeholders
must keep the same placeholder numbers in translation.
"""
import json
import re
import sys
from pathlib import Path

HEBREW = re.compile(r"[֐-׿]")
PLACEHOLDER = re.compile(r"%(\d+)")
ROOTS = ["App", "Sources"]
TRANSLATIONS_PATH = Path("Sources/OzenKit/Resources/Translations.json")
TRANSLATED_LANGUAGES = [
    "arabic", "russian", "amharic", "french", "spanish",
    "ukrainian", "german", "portuguese", "chineseSimplified", "hindi",
]


def literals(text):
    """Yields (start, end, body) for each single-line or multi-line Swift string literal."""
    i = 0
    n = len(text)
    while i < n:
        if text.startswith("//", i):
            j = text.find("\n", i)
            i = n if j == -1 else j
            continue
        if text.startswith("/*", i):
            j = text.find("*/", i + 2)
            i = n if j == -1 else j + 2
            continue
        raw = 0
        k = i
        while k < n and text[k] == "#":
            raw += 1
            k += 1
        if k < n and text[k] == '"':
            triple = text.startswith('"""', k)
            quote = '"""' if triple else '"'
            j = k + len(quote)
            while j < n:
                if text[j] == "\\" and text.startswith("#" * raw, j + 1):
                    after = j + 1 + raw
                    if after < n and text[after] == "(":
                        level = 0
                        m = after
                        while m < n:
                            if text[m] == "(":
                                level += 1
                            elif text[m] == ")":
                                level -= 1
                                if level == 0:
                                    break
                            elif text[m] == '"':
                                inner = next(literals(text[m:]), None)
                                if inner:
                                    m += inner[1] - 1
                            m += 1
                        j = m + 1
                        continue
                    j = after + 1
                    continue
                if text.startswith(quote + "#" * raw, j):
                    end = j + len(quote) + raw
                    yield i, end, text[k + len(quote):j]
                    i = end
                    break
                if not triple and text[j] == "\n":
                    i = j
                    break
                j += 1
            else:
                return
            continue
        i += 1


def interpolations(body):
    found = []
    i = 0
    while True:
        i = body.find("\\(", i)
        if i == -1:
            return sorted(found)
        level = 0
        j = i + 1
        while j < len(body):
            if body[j] == "(":
                level += 1
            elif body[j] == ")":
                level -= 1
                if level == 0:
                    break
            j += 1
        found.append(re.sub(r"\s+", "", body[i + 2:j]))
        i = j


def placeholders(body):
    """The set of %N placeholder numbers used in a template string."""
    return {int(number) for number in PLACEHOLDER.findall(body)}


def unescape(body):
    """A Swift string literal's source text (as `literals()` yields it) still
    holds its escapes literally, e.g. a two-character `\\n` rather than a
    newline. `tr()` receives the runtime, already-unescaped string, so a key
    used to look it up in Translations.json must be unescaped the same way,
    or a key with an escape in it silently never matches."""
    out = []
    i = 0
    n = len(body)
    while i < n:
        c = body[i]
        if c == "\\" and i + 1 < n:
            nxt = body[i + 1]
            simple = {"n": "\n", "t": "\t", "r": "\r", '"': '"', "'": "'", "\\": "\\", "0": "\0"}
            if nxt in simple:
                out.append(simple[nxt])
                i += 2
                continue
            if nxt == "u" and i + 2 < n and body[i + 2] == "{":
                end = body.find("}", i + 3)
                if end != -1:
                    out.append(chr(int(body[i + 3:end], 16)))
                    i = end + 1
                    continue
            out.append(nxt)
            i += 2
            continue
        out.append(c)
        i += 1
    return "".join(out)


def args_list(rest, after):
    """rest[after:] optionally holds `, args: [ ... ]`. Returns the number
    of string-literal elements in that list, or None when there's no args
    list right after the two template literals."""
    i = after
    n = len(rest)
    while i < n and rest[i] in " \t\n":
        i += 1
    if i >= n or rest[i] != ",":
        return None
    i += 1
    while i < n and rest[i] in " \t\n":
        i += 1
    if not rest.startswith("args", i):
        return None
    i += len("args")
    while i < n and rest[i] in " \t\n":
        i += 1
    if i >= n or rest[i] != ":":
        return None
    i += 1
    while i < n and rest[i] in " \t\n":
        i += 1
    if i >= n or rest[i] != "[":
        return None
    depth = 0
    j = i
    while j < n:
        if rest[j] == "[":
            depth += 1
        elif rest[j] == "]":
            depth -= 1
            if depth == 0:
                break
        elif rest[j] == '"':
            inner = next(literals(rest[j:]), None)
            if inner:
                j += inner[1] - 1
        j += 1
    return len(list(literals(rest[i:j + 1])))


def check(path, list_untranslated, keys):
    text = path.read_text(encoding="utf-8")
    problems = []
    inside_tr = set()
    for match in re.finditer(r"\btr\(", text):
        rest = text[match.end():]
        found = list(literals(rest))
        if len(found) < 2 or found[0][0] != len(rest) - len(rest.lstrip()):
            line = text.count("\n", 0, match.start()) + 1
            problems.append(f"{path}:{line}: tr( is not followed by two string literals")
            continue
        (s1, e1, hebrew), (s2, e2, english) = found[0], found[1]
        between = rest[e1:s2]
        line = text.count("\n", 0, match.start()) + 1
        if between.strip() != ",":
            problems.append(f"{path}:{line}: tr( arguments must be two literals separated by a comma")
            continue
        inside_tr.add(match.end() + s1)
        if HEBREW.search(english):
            problems.append(f"{path}:{line}: English text contains Hebrew: {english[:60]}")
        if interpolations(hebrew) != interpolations(english):
            problems.append(f"{path}:{line}: interpolations differ: {interpolations(hebrew)} vs {interpolations(english)}")
        if not english.strip() and hebrew.strip():
            problems.append(f"{path}:{line}: English text is empty")
        count = args_list(rest, e2)
        heb_placeholders = placeholders(hebrew)
        eng_placeholders = placeholders(english)
        if count is None:
            if heb_placeholders or eng_placeholders:
                problems.append(f"{path}:{line}: template placeholders %N with no args: list")
        else:
            expected = set(range(1, count + 1))
            if heb_placeholders != expected or eng_placeholders != expected:
                problems.append(
                    f"{path}:{line}: placeholders must be exactly %1..%{count}: "
                    f"Hebrew has {sorted(heb_placeholders)}, English has {sorted(eng_placeholders)}"
                )
        keys.setdefault(unescape(english), set()).update(range(1, (count or 0) + 1))
    untranslated = []
    if list_untranslated:
        for start, _, body in literals(text):
            if HEBREW.search(body) and start not in inside_tr:
                untranslated.append(text.count("\n", 0, start) + 1)
    return problems, untranslated


def check_translation_table(keys):
    """Every English key from every tr() call must have an entry in every
    other language, with the placeholder numbers it was built with."""
    problems = []
    if not TRANSLATIONS_PATH.exists():
        return [f"{TRANSLATIONS_PATH}: missing"]
    try:
        table = json.loads(TRANSLATIONS_PATH.read_text(encoding="utf-8"))
    except json.JSONDecodeError as error:
        return [f"{TRANSLATIONS_PATH}: invalid JSON: {error}"]
    for language in TRANSLATED_LANGUAGES:
        entries = table.get(language)
        if entries is None:
            problems.append(f"{TRANSLATIONS_PATH}: missing language {language!r}")
            continue
        missing = sorted(key for key in keys if key not in entries)
        if missing:
            shown = ", ".join(repr(key[:40]) for key in missing[:8])
            problems.append(f"{TRANSLATIONS_PATH}: {language} is missing {len(missing)} keys ({shown}{' ...' if len(missing) > 8 else ''})")
        # A key no tr() call uses is never looked up: the sentence calling
        # Ozen MIT-licensed stayed in every language after the app's own
        # text moved to AGPL-3.0, and nothing said so.
        stale = sorted(key for key in entries if key not in keys)
        if stale:
            shown = ", ".join(repr(key[:40]) for key in stale[:8])
            problems.append(f"{TRANSLATIONS_PATH}: {language} has {len(stale)} keys no tr() call uses ({shown}{' ...' if len(stale) > 8 else ''})")
        for key, expected_numbers in keys.items():
            translation = entries.get(key)
            if translation is None:
                continue
            if not translation.strip():
                problems.append(f"{TRANSLATIONS_PATH}: {language}[{key[:40]!r}] is empty")
                continue
            # A Swift escape copied into JSON as text shows on screen as
            # a backslash and a letter instead of a line break or a quote.
            if "\\n" in translation or '\\"' in translation:
                problems.append(f"{TRANSLATIONS_PATH}: {language}[{key[:40]!r}] has a literal backslash escape")
            found_numbers = placeholders(translation)
            if found_numbers != expected_numbers:
                problems.append(
                    f"{TRANSLATIONS_PATH}: {language}[{key[:40]!r}] placeholders {sorted(found_numbers)} "
                    f"don't match the source's {sorted(expected_numbers)}"
                )
    return problems


SYSTEM_STRING_FILES = ["App/Ozen/OzenIntents.swift", "App/Shared/StartCaptionsIntent.swift", "App/OzenWidget/OzenWidgetBundle.swift"]
SYSTEM_CATALOG = Path("App/Shared/Localizable.xcstrings")
SYSTEM_LANGUAGES = ["en", "ar", "ru", "am", "fr", "es", "uk", "de", "pt-PT", "zh-Hans", "hi"]
SYSTEM_STRING = re.compile(r'(?:LocalizedStringResource = |IntentDescription\(|@Parameter\(title: |Summary\(|shortTitle: |\.displayName\(|\.description\(|Label\()"([^"]*[\u0590-\u05FF][^"]*)"')


def check_system_strings():
    """Texts iOS itself shows (Siri and Shortcuts, the Control Center button)
    are translated by Apple's string catalog in the phone's language, not by
    tr(): each Hebrew one needs an entry there in every language."""
    catalog = json.loads(SYSTEM_CATALOG.read_text(encoding="utf-8"))["strings"]
    problems = []
    for name in SYSTEM_STRING_FILES:
        for number, line in enumerate(Path(name).read_text(encoding="utf-8").splitlines(), 1):
            for text in SYSTEM_STRING.findall(line):
                key = re.sub(r"\\\(\\\.\$(\w+)\)", r"${\1}", text)
                missing = [l for l in SYSTEM_LANGUAGES if l not in catalog.get(key, {}).get("localizations", {})]
                if missing:
                    problems.append(f"{name}:{number}: {key!r} missing from {SYSTEM_CATALOG} in {', '.join(missing)}")
    return problems


SHORTCUTS_FILE = Path("App/Ozen/OzenIntents.swift")
SHORTCUTS_CATALOG = Path("App/Ozen/AppShortcuts.xcstrings")


def check_siri_phrases():
    """The first phrase of each Siri command is the one iOS shows in
    Settings' Siri tip, whatever the phone's language: unless the catalog
    has it in every language, an English phone was told to say the
    Hebrew one."""
    catalog = json.loads(SHORTCUTS_CATALOG.read_text(encoding="utf-8"))["strings"]
    problems = []
    source = SHORTCUTS_FILE.read_text(encoding="utf-8")
    for match in re.finditer(r'phrases: \[\s*"([^"]*)"', source):
        key = re.sub(r"\\\(\.(\w+)\)", r"${\1}", match.group(1))
        missing = [l for l in SYSTEM_LANGUAGES if l not in catalog.get(key, {}).get("localizations", {})]
        if missing:
            number = source.count("\n", 0, match.start(1)) + 1
            problems.append(f"{SHORTCUTS_FILE}:{number}: first phrase {key!r} missing from {SHORTCUTS_CATALOG} in {', '.join(missing)}")
    return problems


def check_permission_prompts():
    """iOS's own permission prompts and the languages the app declares:
    each *UsageDescription in project.yml needs every language in
    App/Ozen/InfoPlist.xcstrings, and every declared bundle localization
    list must name all the languages the catalogs carry, or iOS ignores
    those translations."""
    project = Path("project.yml").read_text(encoding="utf-8")
    catalog = json.loads(Path("App/Ozen/InfoPlist.xcstrings").read_text(encoding="utf-8"))["strings"]
    problems = []
    for key in sorted(set(re.findall(r"^\s+(NS\w+UsageDescription):", project, re.MULTILINE))):
        missing = [l for l in ["he"] + SYSTEM_LANGUAGES if l not in catalog.get(key, {}).get("localizations", {})]
        if missing:
            problems.append(f"App/Ozen/InfoPlist.xcstrings: {key} missing in {', '.join(missing)}")
    # On a phone that can't recognise the language itself, Apple's engine
    # asks for this permission only after its servers were allowed, so the
    # prompt names that switch as Settings does instead of promising that
    # nothing leaves the phone.
    settings = Path("App/Ozen/Views/SettingsView.swift").read_text(encoding="utf-8")
    toggle = re.search(r'Toggle\(tr\("([^"]*)", "([^"]*)"\), isOn: serverFallbackBinding\)', settings)
    base = re.search(r'NSSpeechRecognitionUsageDescription: "((?:[^"\\]|\\.)*)"', project)
    if not toggle or not base:
        problems.append("check_permission_prompts: the Apple's servers switch (SettingsView.swift) or the speech prompt (project.yml) changed shape; update this check")
    else:
        hebrew, english = toggle.groups()
        table = json.loads(TRANSLATIONS_PATH.read_text(encoding="utf-8"))
        switch = {"he": hebrew, "en": english} | {code: table.get(language, {}).get(english) for code, language in zip(SYSTEM_LANGUAGES[1:], TRANSLATED_LANGUAGES)}
        prompt = catalog.get("NSSpeechRecognitionUsageDescription", {}).get("localizations", {})
        for code, name in [("project.yml", english)] + sorted(switch.items()):
            text = base.group(1) if code == "project.yml" else prompt.get(code, {}).get("stringUnit", {}).get("value", "")
            if name is not None and name not in text:
                problems.append(f"NSSpeechRecognitionUsageDescription ({code}) must name Settings' switch {name!r}: {text!r}")
    for declared in re.findall(r"CFBundleLocalizations: \[([^\]]*)\]", project):
        missing = set(["he"] + SYSTEM_LANGUAGES) - {l.strip() for l in declared.split(",")}
        if missing:
            problems.append(f"project.yml: CFBundleLocalizations lacks {', '.join(sorted(missing))}")
    return problems


CJK = "\u3400-\u9fff\u3000-\u303f\uff00-\uffef"
ASCII_BESIDE_CHINESE = re.compile(f"(?<=[{CJK}])[,;:!?()]|[,;:!?()](?=[{CJK}])")


def check_chinese_punctuation():
    """Chinese text takes full-width punctuation (，；：！？（）); an ASCII
    comma or bracket beside a Chinese character reads as a typo there."""
    texts = [(str(TRANSLATIONS_PATH), key, value) for key, value in json.loads(TRANSLATIONS_PATH.read_text(encoding="utf-8")).get("chineseSimplified", {}).items()]
    for catalog in [SYSTEM_CATALOG, SHORTCUTS_CATALOG, Path("App/Ozen/InfoPlist.xcstrings")]:
        for key, entry in json.loads(catalog.read_text(encoding="utf-8"))["strings"].items():
            unit = entry.get("localizations", {}).get("zh-Hans", {}).get("stringUnit")
            if unit:
                texts.append((str(catalog), key, unit["value"]))
    return [f"{path}: zh-Hans for {key[:60]!r} has ASCII punctuation beside Chinese: {value!r}"
            for path, key, value in texts if ASCII_BESIDE_CHINESE.search(value)]


TR_PAIR = re.compile(r'tr\("((?:[^"\\]|\\.)*)", "((?:[^"\\]|\\.)*)"')


def arrow_labels(key, keys):
    """The app's own labels along a path such as "Settings → Diagnostics →
    Send report": the steps that are labels, and the label that ends the
    words before the first arrow."""
    labels = []
    for path in re.findall(r"[^.:;!?()]*\u2192[^.:;!?()]*", key):
        steps = [step.strip(" \u201c\u201d") for step in path.split("\u2192")]
        words = steps[0].split()
        lead = next((" ".join(words[i:]) for i in range(len(words)) if " ".join(words[i:]) in keys), None)
        inner = [step for step in steps[1:] if step in keys]
        # A path with none of the app's labels after its first step leads
        # through the phone's own Settings, which iOS names in each language.
        labels += ([lead] if lead and inner else []) + inner
    return labels


def hebrew_texts():
    """English key -> Hebrew text, from the tr("he", "en") calls whose
    arguments are plain literals."""
    hebrew = {}
    for path in sorted(p for root in ROOTS for p in Path(root).rglob("*.swift")):
        for he, en in TR_PAIR.findall(path.read_text(encoding="utf-8")):
            hebrew[unescape(en)] = unescape(he)
    return hebrew


def check_siri_examples(keys):
    """The Siri commands Settings gives as examples ("Hey Siri, start
    captions in Ozen") carry, in each language, a phrase the app gives Siri
    in that language, or the reader says one Siri doesn't know."""
    catalog = json.loads(SHORTCUTS_CATALOG.read_text(encoding="utf-8"))["strings"]
    sources = [re.sub(r"\\\(\.(\w+)\)", r"${\1}", phrase) for phrase in re.findall(r'"([^"]*\\\(\.applicationName\)[^"]*)"', SHORTCUTS_FILE.read_text(encoding="utf-8"))]
    table = json.loads(TRANSLATIONS_PATH.read_text(encoding="utf-8"))
    hebrew = hebrew_texts()
    problems = []
    for key in sorted(k for k in keys if k.startswith("Hey Siri, ")):
        texts = [("he", hebrew.get(key)), ("en", key)] + [(code, table.get(language, {}).get(key)) for code, language in zip(SYSTEM_LANGUAGES[1:], TRANSLATED_LANGUAGES)]
        for code, text in texts:
            if code == "he":
                phrases = [source.replace("${applicationName}", "\u05d0\u05d5\u05d6\u05df") for source in sources]
            else:
                phrases = [catalog[source]["localizations"][code]["stringUnit"]["value"].replace("${applicationName}", "Ozen")
                           for source in sources if code in catalog.get(source, {}).get("localizations", {})]
            # Siri doesn't hear spaces: Chinese spaces a Latin name ("在 Ozen 中").
            squeezed = "".join((text or "").lower().split())
            if text is not None and not any("".join(phrase.lower().split()) in squeezed for phrase in phrases):
                problems.append(f"{code}: Siri example {key!r} has none of the phrases Siri knows in that language: {text!r}")
    return problems


def check_quoted_labels(keys):
    """A message that names another of the app's labels, in quotes (To fix:
    "Add speaker" ...) or along a path (Settings → Diagnostics), names it in
    each language as that label reads there, or the reader looks for a
    button that isn't on the screen."""
    table = json.loads(TRANSLATIONS_PATH.read_text(encoding="utf-8"))
    languages = {"hebrew": hebrew_texts()} | {language: table[language] for language in TRANSLATED_LANGUAGES}
    # iOS lists the app by this name in every language (searching Control
    # Center for it, say), so it stays as it is inside any translation.
    display_name = re.search(r'CFBundleDisplayName: "([^"]*)"', Path("project.yml").read_text(encoding="utf-8"))
    display_name = display_name.group(1) if display_name else None
    # Hebrew writes its quotes, and the gershayim of an abbreviation, as a
    # straight mark; the other languages use their curly or angled ones.
    problems = [f"{language}: {key[:50]!r} has a straight double quote; this language's text uses curly ones: {text!r}"
                for language, entries in [("english", {key: key for key in keys})] + list(languages.items())[1:]
                for key, text in entries.items() if '"' in text]
    for key in sorted(keys):
        for quoted in re.findall(r'["\u201c]([^"\u201d]+)["\u201d]', key) + arrow_labels(key, keys):
            if quoted not in keys or quoted == display_name:
                continue
            for language, entries in languages.items():
                if key in entries and quoted in entries and entries[quoted] not in entries[key]:
                    problems.append(f"{language}: {key[:50]!r} quotes {quoted!r}, not as that label reads there ({entries[quoted]!r}): {entries[key]!r}")
    return problems


def check_system_wording():
    """Controls that bring their own words show them in the phone's
    language, not the one picked in Ozen: SwiftUI's EditButton, and a
    confirmation dialog with no cancel button of its own (iOS adds one)."""
    problems = []
    for path in sorted(p for root in ROOTS for p in Path(root).rglob("*.swift")):
        lines = path.read_text(encoding="utf-8").splitlines()
        for number, line in enumerate(lines, 1):
            if "EditButton()" in line:
                problems.append(f"{path}:{number}: EditButton() reads in the phone's language; use a tr() Edit/Done button")
            if ".confirmationDialog(" not in line:
                continue
            base = len(line) - len(line.lstrip())
            block = [line]
            for later in lines[number:]:
                indent = len(later) - len(later.lstrip())
                if later.strip() and (indent < base or (indent == base and later.lstrip().startswith("."))):
                    break
                block.append(later)
            if not any("role: .cancel" in l for l in block):
                problems.append(f"{path}:{number}: confirmation dialog without a tr() cancel button; iOS adds one in the phone's language")
    return problems


CATALOG_CODES = {
    "arabic": "ar", "russian": "ru", "amharic": "am", "french": "fr", "spanish": "es",
    "ukrainian": "uk", "german": "de", "portuguese": "pt-PT", "chineseSimplified": "zh-Hans", "hindi": "hi",
}


def check_system_names_in_help(keys):
    """Help that names a button iOS draws from the string catalog (the
    Control Center button) must call it what iOS calls it, in every
    language: there the button reads "Iniciar legendas", so Portuguese
    help can't send her looking for "Começar Legendas"."""
    catalog = json.loads(SYSTEM_CATALOG.read_text(encoding="utf-8"))["strings"]
    table = json.loads(TRANSLATIONS_PATH.read_text(encoding="utf-8"))
    problems = []
    for entry in catalog.values():
        names = {code: unit["stringUnit"]["value"] for code, unit in entry.get("localizations", {}).items()}
        english = names.get("en", "")
        if len(english.split()) < 2:
            continue
        for key in keys:
            quoted = re.findall(r"“([^”]+)”", key)
            if english.lower() not in (q.lower() for q in quoted):
                continue
            if english not in quoted:
                problems.append(f"tr English {key[:60]!r}...: names {english!r} with other capitals")
            for language, code in CATALOG_CODES.items():
                if names.get(code) and names[code] not in table.get(language, {}).get(key, ""):
                    problems.append(f"{TRANSLATIONS_PATH}: {language} for {key[:50]!r}... doesn't name {names[code]!r}, the button's name there")
    return problems


def main():
    list_untranslated = "--untranslated" in sys.argv
    skip_table = "--no-table" in sys.argv
    paths = [a for a in sys.argv[1:] if not a.startswith("--")]
    requested = {Path(p) for p in paths}
    all_files = [p for root in ROOTS for p in Path(root).rglob("*.swift") if p.name != "Localization.swift"]
    # The translation table is checked against every tr() call in the whole
    # tree, even when explicit paths narrow which files get a per-line report.
    all_problems = []
    keys = {}
    for path in sorted(all_files):
        problems, untranslated = check(path, list_untranslated, keys)
        if not requested or path in requested:
            all_problems += problems
            if untranslated:
                print(f"{path}: {len(untranslated)} Hebrew literals outside tr (lines {', '.join(map(str, untranslated[:12]))}{' ...' if len(untranslated) > 12 else ''})")
    if not skip_table:
        all_problems += check_translation_table(keys)
        all_problems += check_system_strings()
        all_problems += check_siri_phrases()
        all_problems += check_permission_prompts()
        all_problems += check_system_wording()
        all_problems += check_chinese_punctuation()
        all_problems += check_quoted_labels(keys)
        all_problems += check_siri_examples(keys)
        all_problems += check_system_names_in_help(keys)
    for problem in all_problems:
        print(problem)
    sys.exit(1 if all_problems else 0)


if __name__ == "__main__":
    main()
