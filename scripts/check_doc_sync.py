#!/usr/bin/env python3
"""Enforce minimal 1+1 documentation sync and link/ledger hygiene.

Modes:
  staged     Check staged git changes (used by .githooks/pre-commit).
  worktree   Check dirty working tree + validate gaps.json & core links (ci_gate.sh).
  agent-stop Stop-hook friendly check on dirty working tree (exits 2 on block).
"""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
from pathlib import Path
from typing import Iterable

CODE_PREFIXES = ("runtime/", "launcher/", "scripts/")
CODE_EXCLUDES = ("scripts/relink_bundle_deps.sh",)
STATUS_DOCS = ("PROJECT_STATE.md", "docs/UPGRADE_PLAN.md")
STATUS_DOC_PREFIXES = ("docs/tasks/",)
API_SURFACE_PREFIXES = ("runtime/java/",)
API_SURFACE_FILES = (
    "runtime/src/native_methods.cpp",
    "runtime/translator/src/bionic_shim.cpp",
)
CORE_DOC_FILES = (
    "AGENTS.md",
    "PROJECT_STATE.md",
    "docs/UPGRADE_PLAN.md",
    "docs/ROADMAP.md",
    "docs/UPGRADE_START_HERE.md",
    "docs/AUTONOMOUS_EXECUTION.md",
    "docs/compat/GAPS.md",
)
MD_LINK_RE = re.compile(r"\[[^\]]+\]\(([^)#]+)(?:#[^)]*)?\)")


def repo_root_from(cwd: Path) -> Path:
    out = subprocess.check_output(
        ["git", "rev-parse", "--show-toplevel"],
        cwd=str(cwd),
        text=True,
    ).strip()
    return Path(out)


def staged_files(root: Path) -> list[str]:
    out = subprocess.check_output(
        ["git", "diff", "--cached", "--name-only", "--diff-filter=ACMRD"],
        cwd=str(root),
        text=True,
    )
    return [line.strip() for line in out.splitlines() if line.strip()]


def worktree_files(root: Path) -> list[str]:
    raw = subprocess.check_output(
        ["git", "status", "--porcelain=v1", "-z"],
        cwd=str(root),
    )
    entries = raw.split(b"\x00")
    files: list[str] = []
    i = 0
    while i < len(entries):
        item = entries[i]
        if not item:
            i += 1
            continue
        status = item[:2].decode("utf-8", errors="replace")
        path = item[3:].decode("utf-8", errors="replace")
        files.append(path)
        if status.startswith("R") or status.startswith("C"):
            i += 2
            continue
        i += 1
    return files


def is_code_file(rel: str) -> bool:
    if rel in CODE_EXCLUDES:
        return False
    return rel.startswith(CODE_PREFIXES) and not rel.endswith(".md")


def is_status_doc(rel: str) -> bool:
    return rel in STATUS_DOCS or (
        rel.startswith(STATUS_DOC_PREFIXES) and rel.endswith(".md")
    )


def needs_cap_registry_hint(changed: Iterable[str]) -> bool:
    changed_set = set(changed)
    api_touched = any(
        p.startswith(API_SURFACE_PREFIXES) or p in API_SURFACE_FILES
        for p in changed_set
    )
    if not api_touched:
        return False
    cap_touched = any(
        p in ("docs/compat/inventory.json", "docs/compat/capabilities.json")
        for p in changed_set
    )
    return not cap_touched


def check_gaps_json(root: Path) -> list[str]:
    gaps_path = root / "docs" / "compat" / "gaps.json"
    if not gaps_path.exists():
        return []
    errors: list[str] = []
    try:
        data = json.loads(gaps_path.read_text(encoding="utf-8"))
        gaps = data.get("gaps", [])
        counts = data.get("counts", {})
        tally: dict[str, int] = {}
        for item in gaps:
            st = item.get("status", "")
            tally[st] = tally.get(st, 0) + 1
        if counts.get("total") != len(gaps):
            errors.append(
                f"docs/compat/gaps.json counts.total={counts.get('total')} != len(gaps)={len(gaps)}"
            )
        for key in ("fixed", "observed", "predicted"):
            if key in counts and counts[key] != tally.get(key, 0):
                errors.append(
                    f"docs/compat/gaps.json counts.{key}={counts[key]} != actual={tally.get(key, 0)}"
                )
    except Exception as exc:  # noqa: BLE001
        errors.append(f"docs/compat/gaps.json parse/schema error: {exc}")
    return errors


def check_markdown_links(root: Path, md_rels: Iterable[str]) -> list[str]:
    errors: list[str] = []
    seen: set[str] = set()
    for rel in md_rels:
        if rel in seen or not rel.endswith(".md"):
            continue
        seen.add(rel)
        path = root / rel
        if not path.is_file():
            continue
        content = path.read_text(encoding="utf-8", errors="replace")
        for match in MD_LINK_RE.finditer(content):
            target = match.group(1).strip()
            if not target or target.startswith(("http://", "https://", "mailto:", "conversation://", "file://")):
                continue
            resolved = (path.parent / target).resolve()
            if not resolved.exists():
                errors.append(f"Broken local link in {rel}: ({target})")
    return errors


def validate(root: Path, mode: str) -> tuple[int, list[str], list[str]]:
    if os.environ.get("MACAPK_SKIP_DOC_CHECK") == "1":
        return 0, [], ["[check_doc_sync] skipped via MACAPK_SKIP_DOC_CHECK=1"]

    changed = staged_files(root) if mode == "staged" else worktree_files(root)
    errors: list[str] = []
    warnings: list[str] = []

    has_code = any(is_code_file(p) for p in changed)
    has_doc = any(is_status_doc(p) for p in changed)

    if has_code and not has_doc:
        code_sample = [p for p in changed if is_code_file(p)][:5]
        errors.append(
            "Code changes detected without 1+1 status doc update: "
            + ", ".join(code_sample)
            + ". Update PROJECT_STATE.md and docs/UPGRADE_PLAN.md (or the active docs/tasks/*.md card)."
        )

    if needs_cap_registry_hint(changed):
        warnings.append(
            "Reminder: public Java/JNI/bionic surface changed; run "
            "'python3 scripts/cap_registry.py generate --update-capabilities --output-md docs/compat/CAPABILITIES.md' "
            "if API inventory changed (or CapRegistryTest may report STALE)."
        )

    md_targets = (
        [p for p in changed if p.endswith(".md")]
        if mode == "staged"
        else list(CORE_DOC_FILES) + [p for p in changed if p.endswith(".md")]
    )
    errors.extend(check_markdown_links(root, md_targets))
    errors.extend(check_gaps_json(root))

    if not errors:
        return 0, [], warnings
    return (2 if mode == "agent-stop" else 1), errors, warnings


def main() -> int:
    parser = argparse.ArgumentParser(description="Validate Mac.apk minimal doc sync & links.")
    parser.add_argument(
        "--mode",
        choices=("staged", "worktree", "agent-stop"),
        default="worktree",
    )
    parser.add_argument("--cwd", default=".")
    args = parser.parse_args()

    cwd = Path(args.cwd).resolve()
    if args.mode == "agent-stop" and not sys.stdin.isatty():
        raw_stdin = sys.stdin.read().strip()
        if raw_stdin:
            try:
                payload = json.loads(raw_stdin)
                if payload.get("stop_hook_active"):
                    return 0
                if payload.get("cwd"):
                    cwd = Path(payload["cwd"]).resolve()
            except json.JSONDecodeError:
                pass

    try:
        root = repo_root_from(cwd)
    except subprocess.CalledProcessError as exc:
        print(f"[check_doc_sync] ERROR: not a git repository ({exc})", file=sys.stderr)
        return 1

    rc, errors, warnings = validate(root, args.mode)
    for w in warnings:
        print(f"[check_doc_sync] WARN: {w}", file=sys.stderr)
    for err in errors:
        print(f"[check_doc_sync] FAIL: {err}", file=sys.stderr)
    if rc == 0:
        print(f"[check_doc_sync] PASS (mode={args.mode})")
    return rc


if __name__ == "__main__":
    raise SystemExit(main())
