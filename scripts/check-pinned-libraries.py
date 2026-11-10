#!/usr/bin/env python3
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MANIFEST = ROOT / "Package.swift"
MODEL_CHECK = ROOT / ".github/workflows/verify-model.yml"
SERVER_REQUIREMENTS = ROOT / "server/requirements.txt"
URL = "https://github.com/argmaxinc/argmax-oss-swift.git"


def main():
    errors = []
    manifest = MANIFEST.read_text(encoding="utf-8")
    declared = re.findall(r'\.package\(url: "' + re.escape(URL) + r'", ([a-zA-Z]+): "([^"]+)"\)', manifest)
    if len(declared) != 1:
        errors.append(f"Package.swift: expected one argmax-oss-swift package line, found {len(declared)}")
    else:
        kind, version = declared[0]
        if kind != "exact":
            errors.append(f'Package.swift: argmax-oss-swift is "{kind}: {version}"; use exact: so every build gets the same speech code')
        tags = re.findall(r"--branch v([^ ]+) " + re.escape(URL), MODEL_CHECK.read_text(encoding="utf-8"))
        if tags != [version]:
            errors.append(f"verify-model.yml checks models with argmax-oss-swift {tags}, the app uses {version}")
    errors += server_requirement_errors(SERVER_REQUIREMENTS.read_text(encoding="utf-8"))
    for error in errors:
        print(error)
    if errors:
        sys.exit(1)
    print("argmax-oss-swift pinned to the same release in the app and the model check; "
          "home server libraries pinned or capped")


def server_requirement_errors(text):
    """The home server installs with whatever Python the computer has, so
    an exact pin can leave no wheel for a newer Python; a library may range
    over releases the tests have seen, but a release past the cap waits for
    a change that raises it (and the tests that run with it)."""
    errors = []
    for line in text.splitlines():
        line = line.split("#", 1)[0].strip()
        if not line:
            continue
        if "==" not in line and "<" not in line:
            errors.append(f"server/requirements.txt: '{line}' has no upper bound; pin it (==) or cap it (<)")
    return errors


if __name__ == "__main__":
    main()
