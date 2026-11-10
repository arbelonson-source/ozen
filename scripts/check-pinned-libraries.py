#!/usr/bin/env python3
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MANIFEST = ROOT / "Package.swift"
MODEL_CHECK = ROOT / ".github/workflows/verify-model.yml"
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
    for error in errors:
        print(error)
    if errors:
        sys.exit(1)
    print("argmax-oss-swift pinned to the same release in the app and the model check")


if __name__ == "__main__":
    main()
