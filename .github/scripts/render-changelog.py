#!/usr/bin/env python3
"""Render release text and localized manifest fields without requiring a changelog."""
import argparse
import json
from pathlib import Path
import re
import subprocess
import sys

ALIASES = {
    "zh-hans": "zh-Hans", "zh-cn": "zh-Hans", "zh": "zh-Hans",
    "zh-hant": "zh-Hant", "zh-tw": "zh-Hant", "zh-hk": "zh-Hant",
    "en": "en", "en-us": "en", "ja": "ja", "ja-jp": "ja",
    "ko": "ko", "ko-kr": "ko",
}
LANGUAGES = ("zh-Hans", "zh-Hant", "en", "ja", "ko")


def warning(message):
    print(f"::warning::{message}", file=sys.stderr)


def load_changelog(version, git_ref):
    version = version.removeprefix("v")
    paths = [f".github/changelogs/{version}.md", f".github/changelogs/v{version}.md"]
    # The release tag is immutable; late editorial changes live on main.
    for path in paths:
        try:
            result = subprocess.run(
                ["git", "show", f"{git_ref}:{path}"], capture_output=True,
                encoding="utf-8", timeout=15, check=False,
            )
            if result.returncode == 0:
                return result.stdout
        except (OSError, UnicodeError, subprocess.TimeoutExpired):
            pass
    for path in paths:
        try:
            return Path(path).read_text(encoding="utf-8")
        except FileNotFoundError:
            pass
        except (OSError, UnicodeError) as error:
            warning(f"Cannot read changelog {path}: {type(error).__name__}")
    warning(f"Missing changelog for {version}")
    return ""


def parse_changelog(markdown):
    markdown = re.sub(r"<!--[\s\S]*?-->", "", markdown)
    notes = {}
    language = None
    lines = []

    def flush():
        body = []
        for line in lines:
            if line.strip() == "## 下载":
                break
            body.append(line.rstrip())
        text = "\n".join(body).strip()
        if language and text:
            notes[language] = text

    for line in markdown.splitlines():
        if line.startswith("# "):
            flush()
            language = ALIASES.get(line[2:].strip().lower())
            lines = []
        elif language:
            lines.append(line)
    flush()
    return notes


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--version", required=True)
    parser.add_argument("--template")
    parser.add_argument("--out-release-notes")
    parser.add_argument("--out-notes-json")
    parser.add_argument("--require-all", action="store_true")
    parser.add_argument("--validate-only", action="store_true")
    parser.add_argument("--git-ref", default="origin/main")
    args = parser.parse_args()
    notes = parse_changelog(load_changelog(args.version, args.git_ref))
    missing = [language for language in LANGUAGES if language not in notes]
    if missing:
        message = f"Changelog for {args.version} is missing non-empty languages: " + ", ".join(missing)
        if args.require_all:
            print(f"::error::{message}", file=sys.stderr)
            return 1
        warning(message)
    if args.validate_only:
        return 0
    if not all((args.template, args.out_release_notes, args.out_notes_json)):
        parser.error("rendering requires --template, --out-release-notes and --out-notes-json")
    body = notes.get("zh-Hans", "")
    template = Path(args.template).read_text(encoding="utf-8").replace("{{version}}", args.version)
    with open(args.out_release_notes, "w", encoding="utf-8", newline="\n") as output:
        output.write((body + "\n\n" if body else "") + template)
    with open(args.out_notes_json, "w", encoding="utf-8", newline="\n") as output:
        json.dump({"body": body, "localized_notes": notes}, output, ensure_ascii=False, indent=2)
        output.write("\n")


if __name__ == "__main__":
    sys.exit(main())
