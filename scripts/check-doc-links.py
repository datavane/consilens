#!/usr/bin/env python3
"""Check relative link and image targets in Markdown files.

External URLs, mailto links and pure anchors are skipped. Directory targets
resolve as directories; fragment identifiers are ignored.

Usage:
  scripts/check-doc-links.py [project-root]

Exit codes: 0 pass, 1 broken links, 2 malformed input.
"""
import re
import sys
from pathlib import Path

IGNORED_DIRS = {".git", "target", "node_modules", ".release-checks"}
LINK_PATTERN = re.compile(r"!?\[[^\]]*\]\(([^)]+)\)")
EXTERNAL_PREFIXES = ("http://", "https://", "mailto:", "data:", "#")


def main(argv):
    root = Path(argv[1] if len(argv) > 1 else ".").resolve()
    if not root.is_dir():
        print(f"not a directory: {root}", file=sys.stderr)
        return 2

    checked = 0
    broken = []
    for document in sorted(root.rglob("*.md")):
        relative = document.relative_to(root)
        if any(part in IGNORED_DIRS for part in relative.parts):
            continue
        text = document.read_text(encoding="utf-8", errors="replace")
        for match in LINK_PATTERN.finditer(text):
            target = match.group(1).strip()
            if not target or target.startswith(EXTERNAL_PREFIXES):
                continue
            target = target.split("#", 1)[0].strip()
            if not target:
                continue
            checked += 1
            resolved = (document.parent / target).resolve()
            if not resolved.exists():
                broken.append(f"{relative} -> {target}")

    print(f"Markdown links: checked={checked}")
    if broken:
        print(f"  broken: {len(broken)}", file=sys.stderr)
        for item in broken:
            print(f"    - {item}", file=sys.stderr)
        return 1
    print("  result      : PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
