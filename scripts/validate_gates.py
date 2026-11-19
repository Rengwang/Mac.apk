#!/usr/bin/env python3
"""Validate docs/compat/feature_gates.json against its schema, source code, and test registries."""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
from pathlib import Path
from typing import Any

REPO_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_GATES_JSON = REPO_ROOT / "docs" / "compat" / "feature_gates.json"
DEFAULT_SCHEMA_JSON = REPO_ROOT / "docs" / "compat" / "feature_gates.schema.json"
DEFAULT_CAPABILITIES_JSON = REPO_ROOT / "docs" / "compat" / "capabilities.json"
DEFAULT_FEATURE_GATES_MD = REPO_ROOT / "docs" / "compat" / "FEATURE_GATES.md"
DEFAULT_RUN_TESTS_PY = REPO_ROOT / "scripts" / "run_tests.py"
DEFAULT_FG_TEST_PY = REPO_ROOT / "debug" / "FeatureGatesTest.py"

TOKEN_RE = re.compile(r"\b(?:MACAPK|DEX2JVM)_[A-Z0-9_]+\b")
TESTSPEC_ID_RE = re.compile(r'TestSpec\(\s*id="([^"]+)"')
FG_TEST_ID_RE = re.compile(r'\b(FG-[A-Z]+-[0-9]{3}_[A-Za-z0-9_]+)\b')


def expect_keys(
    obj: Any,
    required: list[str],
    ctx: str,
    errors: list[str],
    optional: set[str] | None = None,
) -> bool:
    if not isinstance(obj, dict):
        errors.append(f"{ctx}: expected object, got {type(obj).__name__}")
        return False
    allowed = set(required) | (optional or set())
    missing = [k for k in required if k not in obj]
    extra = [k for k in obj if k not in allowed]
    if missing:
        errors.append(f"{ctx}: missing required keys {missing}")
    if extra:
        errors.append(f"{ctx}: unexpected extra keys {sorted(extra)}")
    return not missing and not extra


def is_nonempty_str(val: Any) -> bool:
    return isinstance(val, str) and len(val) > 0


def load_registered_run_tests(path: Path) -> set[str]:
    if not path.is_file():
        return set()
    return set(TESTSPEC_ID_RE.findall(path.read_text(encoding="utf-8", errors="replace")))


def load_registered_fg_tests(path: Path) -> set[str]:
    if not path.is_file():
        return set()
    return set(FG_TEST_ID_RE.findall(path.read_text(encoding="utf-8", errors="replace")))


def check_schema_and_structure(
    data: Any,
    schema: dict[str, Any],
    errors: list[str],
) -> None:
    root_req = schema.get("required", [])
    if not expect_keys(data, root_req, "root", errors):
        return

    if data.get("schema_version") != schema["properties"]["schema_version"]["const"]:
        errors.append(f"root.schema_version: expected '1.0.0', got {data.get('schema_version')!r}")

    bc = data.get("baseline_commit")
    if not isinstance(bc, str) or not re.match(r"^[0-9a-f]{40}$", bc):
        errors.append(f"root.baseline_commit: expected 40-hex commit sha, got {bc!r}")

    inv = data.get("inventory_summary")
    inv_schema = schema["properties"]["inventory_summary"]
    if expect_keys(inv, inv_schema["required"], "inventory_summary", errors):
        for list_k in ("scan_roots", "excluded_dirs"):
            v = inv[list_k]
            if not isinstance(v, list) or not all(is_nonempty_str(x) for x in v):
                errors.append(f"inventory_summary.{list_k}: expected list of non-empty strings")
        for int_k in (
            "files_scanned",
            "distinct_tokens",
            "dex2jvm_tokens",
            "experimental_tokens",
            "priority_gates_documented",
        ):
            v = inv[int_k]
            if not isinstance(v, int) or isinstance(v, bool) or v < 1:
                errors.append(f"inventory_summary.{int_k}: expected integer >= 1, got {v!r}")

    le_schema = schema["properties"]["launch_entries"]["items"]
    entries = data.get("launch_entries")
    if not isinstance(entries, list) or not entries:
        errors.append("launch_entries: expected non-empty list")
    else:
        for i, entry in enumerate(entries):
            ctx = f"launch_entries[{i}]"
            if expect_keys(entry, le_schema["required"], ctx, errors):
                for sk in ("id", "name", "notes"):
                    if not is_nonempty_str(entry[sk]):
                        errors.append(f"{ctx}.{sk}: expected non-empty string")
                for lk in ("source", "unsets_env"):
                    val = entry[lk]
                    if not isinstance(val, list) or not all(is_nonempty_str(x) for x in val):
                        errors.append(f"{ctx}.{lk}: expected list of non-empty strings")
                if not entry["source"]:
                    errors.append(f"{ctx}.source: expected non-empty list")
                se = entry["sets_env"]
                if not isinstance(se, dict) or not all(
                    isinstance(k, str) and isinstance(v, str) for k, v in se.items()
                ):
                    errors.append(f"{ctx}.sets_env: expected string->string object")

    em_schema = schema["properties"]["entry_mismatches"]["items"]
    sev_enum = set(em_schema["properties"]["severity"]["enum"])
    mismatches = data.get("entry_mismatches")
    if not isinstance(mismatches, list) or not mismatches:
        errors.append("entry_mismatches: expected non-empty list")
    else:
        for i, mm in enumerate(mismatches):
            ctx = f"entry_mismatches[{i}]"
            if expect_keys(mm, em_schema["required"], ctx, errors):
                if not isinstance(mm["id"], str) or not re.match(r"^GAP-GATE-[0-9]{3}$", mm["id"]):
                    errors.append(f"{ctx}.id: invalid format {mm['id']!r}")
                if mm["severity"] not in sev_enum:
                    errors.append(f"{ctx}.severity: invalid {mm['severity']!r}")
                for sk in ("title", "impact", "followup_card"):
                    if not is_nonempty_str(mm[sk]):
                        errors.append(f"{ctx}.{sk}: expected non-empty string")
                for lk in ("gates", "affected_entries", "evidence"):
                    val = mm[lk]
                    if not isinstance(val, list) or not val or not all(is_nonempty_str(x) for x in val):
                        errors.append(f"{ctx}.{lk}: expected non-empty list of strings")

    fc_schema = schema["properties"]["followup_cards"]["items"]
    fc_status_enum = set(fc_schema["properties"]["status"]["enum"])
    cards = data.get("followup_cards")
    if not isinstance(cards, list) or not cards:
        errors.append("followup_cards: expected non-empty list")
    else:
        for i, card in enumerate(cards):
            ctx = f"followup_cards[{i}]"
            if expect_keys(card, fc_schema["required"], ctx, errors):
                for sk in ("id", "path", "title", "summary"):
                    if not is_nonempty_str(card[sk]):
                        errors.append(f"{ctx}.{sk}: expected non-empty string")
                if card["status"] not in fc_status_enum:
                    errors.append(f"{ctx}.status: invalid {card['status']!r}")

    gate_schema = schema["properties"]["gates"]["items"]
    g_props = gate_schema["properties"]
    domain_enum = set(g_props["domain"]["enum"])
    kind_enum = set(g_props["kind"]["enum"])
    type_enum = set(g_props["type"]["enum"])
    timing_enum = set(g_props["read_timing"]["enum"])
    loc_req = g_props["read_locations"]["items"]["required"]
    dbe_req = g_props["defaults_by_entry"]["required"]

    gates = data.get("gates")
    if not isinstance(gates, list) or not gates:
        errors.append("gates: expected non-empty list")
        return
    for i, g in enumerate(gates):
        ctx = f"gates[{i}]({g.get('id', '?') if isinstance(g, dict) else '?'})"
        if not expect_keys(g, gate_schema["required"], ctx, errors):
            continue
        if not isinstance(g["id"], str) or not re.match(r"^GATE-[A-Z0-9]+-[0-9]{3}$", g["id"]):
            errors.append(f"{ctx}.id: invalid pattern {g['id']!r}")
        if not isinstance(g["name"], str) or not re.match(r"^(MACAPK|DEX2JVM)_[A-Z0-9_]+$", g["name"]):
            errors.append(f"{ctx}.name: invalid pattern {g['name']!r}")
        if g["domain"] not in domain_enum:
            errors.append(f"{ctx}.domain: invalid {g['domain']!r}")
        if g["kind"] not in kind_enum:
            errors.append(f"{ctx}.kind: invalid {g['kind']!r}")
        if g["type"] not in type_enum:
            errors.append(f"{ctx}.type: invalid {g['type']!r}")
        if g["read_timing"] not in timing_enum:
            errors.append(f"{ctx}.read_timing: invalid {g['read_timing']!r}")
        for sk in ("read_timing_detail", "owner", "applicable_versions", "promotion_or_sunset_criteria"):
            if not is_nonempty_str(g[sk]):
                errors.append(f"{ctx}.{sk}: expected non-empty string")
        if not isinstance(g["entry_consistent"], bool):
            errors.append(f"{ctx}.entry_consistent: expected bool")
        if g["mismatch_ref"] is not None and not is_nonempty_str(g["mismatch_ref"]):
            errors.append(f"{ctx}.mismatch_ref: expected non-empty string or null")
        for lk in ("dependencies", "capability_ids", "test_ids"):
            val = g[lk]
            if not isinstance(val, list) or not all(is_nonempty_str(x) for x in val):
                errors.append(f"{ctx}.{lk}: expected list of non-empty strings")
        locs = g["read_locations"]
        if not isinstance(locs, list) or not locs:
            errors.append(f"{ctx}.read_locations: expected non-empty list")
        else:
            for j, loc in enumerate(locs):
                lctx = f"{ctx}.read_locations[{j}]"
                if expect_keys(loc, loc_req, lctx, errors):
                    for sk in ("file", "symbol", "expression"):
                        if not is_nonempty_str(loc[sk]):
                            errors.append(f"{lctx}.{sk}: expected non-empty string")
                    ln = loc["line"]
                    if not isinstance(ln, int) or isinstance(ln, bool) or ln < 1:
                        errors.append(f"{lctx}.line: expected integer >= 1")
        dbe = g["defaults_by_entry"]
        if expect_keys(dbe, dbe_req, f"{ctx}.defaults_by_entry", errors):
            for k in dbe_req:
                if not is_nonempty_str(dbe[k]):
                    errors.append(f"{ctx}.defaults_by_entry.{k}: expected non-empty string")


def check_references_and_sources(
    data: dict[str, Any],
    repo_root: Path,
    cap_ids: set[str],
    run_test_ids: set[str],
    fg_test_ids: set[str],
    md_path: Path | None,
    errors: list[str],
) -> dict[str, int]:
    gates = data["gates"]
    if data["inventory_summary"]["priority_gates_documented"] != len(gates):
        errors.append(
            f"inventory_summary.priority_gates_documented={data['inventory_summary']['priority_gates_documented']} "
            f"!= len(gates)={len(gates)}"
        )

    seen_gate_ids: set[str] = set()
    seen_gate_names: set[str] = set()
    for g in gates:
        if g["id"] in seen_gate_ids:
            errors.append(f"duplicate gate id: {g['id']}")
        seen_gate_ids.add(g["id"])
        if g["name"] in seen_gate_names:
            errors.append(f"duplicate gate name: {g['name']}")
        seen_gate_names.add(g["name"])

    entry_ids: set[str] = set()
    for entry in data["launch_entries"]:
        if entry["id"] in entry_ids:
            errors.append(f"duplicate launch_entry id: {entry['id']}")
        entry_ids.add(entry["id"])

    card_ids: set[str] = set()
    for card in data["followup_cards"]:
        if card["id"] in card_ids:
            errors.append(f"duplicate followup_card id: {card['id']}")
        card_ids.add(card["id"])
        if not (repo_root / card["path"]).is_file():
            errors.append(f"followup_card {card['id']} missing file: {card['path']}")

    mismatch_ids: set[str] = set()
    for mm in data["entry_mismatches"]:
        if mm["id"] in mismatch_ids:
            errors.append(f"duplicate mismatch id: {mm['id']}")
        mismatch_ids.add(mm["id"])
        for gname in mm["gates"]:
            if gname not in seen_gate_names:
                errors.append(f"mismatch {mm['id']} references unknown gate name {gname!r}")
        for eid in mm["affected_entries"]:
            if eid not in entry_ids:
                errors.append(f"mismatch {mm['id']} references unknown launch_entry {eid!r}")
        if mm["followup_card"] not in card_ids:
            errors.append(f"mismatch {mm['id']} references unknown followup_card {mm['followup_card']!r}")

    file_lines_cache: dict[Path, list[str]] = {}
    locations_checked = 0
    for g in gates:
        if not g["entry_consistent"] and not g["mismatch_ref"]:
            errors.append(f"{g['id']} ({g['name']}): entry_consistent=false requires non-null mismatch_ref")
        if g["mismatch_ref"] is not None and g["mismatch_ref"] not in mismatch_ids:
            errors.append(f"{g['id']}: unknown mismatch_ref {g['mismatch_ref']!r}")
        for cid in g["capability_ids"]:
            if cid not in cap_ids:
                errors.append(f"{g['id']}: unknown capability_id {cid!r}")
        for tid in g["test_ids"]:
            if tid not in run_test_ids and tid not in fg_test_ids:
                errors.append(f"{g['id']}: unknown test_id {tid!r} (not in run_tests.py or FeatureGatesTest.py)")

        for loc in g["read_locations"]:
            locations_checked += 1
            fpath = repo_root / loc["file"]
            if not fpath.is_file():
                errors.append(f"{g['id']}: missing source file {loc['file']}")
                continue
            if fpath not in file_lines_cache:
                file_lines_cache[fpath] = fpath.read_text(encoding="utf-8", errors="replace").splitlines()
            lines = file_lines_cache[fpath]
            ln = loc["line"]
            if ln < 1 or ln > len(lines):
                errors.append(f"{g['id']}: line {ln} out of range for {loc['file']} ({len(lines)} lines)")
                continue
            lo = max(0, ln - 4)
            hi = min(len(lines), ln + 3)
            window = "\n".join(lines[lo:hi])
            token_match = g["name"] in window or (
                "DEX2JVM_" in loc["expression"] and "DEX2JVM_" in window
            )
            if not token_match:
                errors.append(
                    f"{g['id']}: gate {g['name']} not found within +-3 lines of {loc['file']}:{ln}"
                )

    if md_path is not None:
        if not md_path.is_file():
            errors.append(f"missing documentation file: {md_path}")
        else:
            md_text = md_path.read_text(encoding="utf-8", errors="replace")
            for g in gates:
                if g["name"] not in md_text:
                    errors.append(f"{md_path.name}: missing gate {g['name']}")
            for mm in data["entry_mismatches"]:
                if mm["id"] not in md_text:
                    errors.append(f"{md_path.name}: missing mismatch {mm['id']}")

    return {
        "gates_checked": len(gates),
        "locations_checked": locations_checked,
        "mismatches_checked": len(mismatch_ids),
    }


def check_source_inventory(
    data: dict[str, Any],
    repo_root: Path,
    errors: list[str],
) -> dict[str, Any]:
    inv = data["inventory_summary"]
    scan_roots = inv["scan_roots"]
    excluded = set(inv["excluded_dirs"])
    gate_names = {g["name"] for g in data["gates"]}

    # Verify live runtime/ + launcher/ contain no undocumented MACAPK_EXPERIMENTAL_* gates.
    live_exp_tokens: set[str] = set()
    for root_name in ("runtime", "launcher"):
        base = repo_root / root_name
        if not base.is_dir():
            continue
        for p in base.rglob("*"):
            if not p.is_file():
                continue
            if any(part in excluded for part in p.relative_to(repo_root).parts):
                continue
            text = p.read_text(encoding="utf-8", errors="replace")
            for tok in TOKEN_RE.findall(text):
                if tok.startswith("MACAPK_EXPERIMENTAL_"):
                    live_exp_tokens.add(tok)

    missing_exp = sorted(live_exp_tokens - gate_names)
    if missing_exp:
        errors.append(f"undocumented MACAPK_EXPERIMENTAL_* tokens in runtime/launcher: {missing_exp}")

    # Verify baseline_commit snapshot metrics via git ls-tree if that commit exists in git.
    baseline_sha = data["baseline_commit"]
    git_check = subprocess.run(
        ["git", "-C", str(repo_root), "cat-file", "-e", f"{baseline_sha}^{{commit}}"],
        capture_output=True,
        text=True,
    )
    baseline_verified = False
    baseline_stats: dict[str, Any] = {}
    if git_check.returncode == 0:
        ls = subprocess.run(
            ["git", "-C", str(repo_root), "ls-tree", "-r", "--name-only", baseline_sha, "--", *scan_roots],
            capture_output=True,
            text=True,
            check=True,
        )
        rel_paths = [
            line
            for line in ls.stdout.splitlines()
            if line and not any(part in excluded for part in Path(line).parts)
        ]
        # Read blobs via git archive or cat-file --batch for speed/accuracy.
        cat = subprocess.run(
            ["git", "-C", str(repo_root), "cat-file", "--batch"],
            input="".join(f"{baseline_sha}:{p}\n" for p in rel_paths).encode("utf-8"),
            capture_output=True,
            check=True,
        )
        raw = cat.stdout
        pos = 0
        distinct_tokens: set[str] = set()
        dex_tokens: set[str] = set()
        exp_tokens: set[str] = set()
        files_count = 0
        while pos < len(raw):
            nl = raw.find(b"\n", pos)
            if nl == -1:
                break
            header = raw[pos:nl].decode("utf-8", errors="replace")
            pos = nl + 1
            parts = header.split()
            if len(parts) >= 3 and parts[1] == "blob":
                size = int(parts[2])
                blob = raw[pos : pos + size]
                pos += size + 1  # trailing LF
                files_count += 1
                for tok in TOKEN_RE.findall(blob.decode("utf-8", errors="replace")):
                    distinct_tokens.add(tok)
                    if tok.startswith("DEX2JVM_"):
                        dex_tokens.add(tok)
                    if tok.startswith("MACAPK_EXPERIMENTAL_"):
                        exp_tokens.add(tok)
        baseline_verified = True
        baseline_stats = {
            "files_scanned": files_count,
            "distinct_tokens": len(distinct_tokens),
            "dex2jvm_tokens": len(dex_tokens),
            "experimental_tokens": len(exp_tokens),
        }
        for k, actual in baseline_stats.items():
            if inv[k] != actual:
                errors.append(
                    f"baseline inventory_summary.{k}={inv[k]} != git {baseline_sha[:8]} actual {actual}"
                )

    return {
        "live_experimental_tokens": sorted(live_exp_tokens),
        "baseline_commit_verified": baseline_verified,
        "baseline_stats": baseline_stats,
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Validate Mac.apk feature gates registry.")
    parser.add_argument("--gates", type=Path, default=DEFAULT_GATES_JSON)
    parser.add_argument("--schema", type=Path, default=DEFAULT_SCHEMA_JSON)
    parser.add_argument("--capabilities", type=Path, default=DEFAULT_CAPABILITIES_JSON)
    parser.add_argument("--doc", type=Path, default=DEFAULT_FEATURE_GATES_MD)
    parser.add_argument("--run-tests", type=Path, default=DEFAULT_RUN_TESTS_PY)
    parser.add_argument("--fg-test", type=Path, default=DEFAULT_FG_TEST_PY)
    parser.add_argument("--skip-doc", action="store_true", help="Skip FEATURE_GATES.md presence check.")
    parser.add_argument(
        "--check-source-inventory",
        action="store_true",
        help="Verify live MACAPK_EXPERIMENTAL_* tokens and git baseline_commit inventory counts.",
    )
    parser.add_argument("--json", action="store_true", help="Emit machine-readable summary JSON.")
    args = parser.parse_args(argv)

    errors: list[str] = []
    gates_data = json.loads(args.gates.read_text(encoding="utf-8"))
    schema_data = json.loads(args.schema.read_text(encoding="utf-8"))
    caps_data = json.loads(args.capabilities.read_text(encoding="utf-8"))
    cap_ids = {c["id"] for c in caps_data["capabilities"]}

    check_schema_and_structure(gates_data, schema_data, errors)

    ref_stats: dict[str, int] = {}
    if not errors:
        run_test_ids = load_registered_run_tests(args.run_tests)
        fg_test_ids = load_registered_fg_tests(args.fg_test)
        ref_stats = check_references_and_sources(
            gates_data,
            REPO_ROOT,
            cap_ids,
            run_test_ids,
            fg_test_ids,
            None if args.skip_doc else args.doc,
            errors,
        )

    inv_stats: dict[str, Any] = {}
    if args.check_source_inventory:
        inv_stats = check_source_inventory(gates_data, REPO_ROOT, errors)

    summary = {
        "status": "PASS" if not errors else "FAIL",
        "gates_file": str(args.gates),
        "error_count": len(errors),
        "errors": errors,
        "references": ref_stats,
        "inventory": inv_stats,
    }
    if args.json:
        print(json.dumps(summary, indent=2, ensure_ascii=False))
    else:
        print(
            f"validate_gates: {summary['status']} "
            f"(gates={ref_stats.get('gates_checked', 0)}, "
            f"locations={ref_stats.get('locations_checked', 0)}, "
            f"mismatches={ref_stats.get('mismatches_checked', 0)}, "
            f"errors={len(errors)})"
        )
        if inv_stats:
            print(f"inventory: {json.dumps(inv_stats)}")
        for err in errors:
            print(f"  ERROR: {err}", file=sys.stderr)
    return 0 if not errors else 1


if __name__ == "__main__":
    raise SystemExit(main())
