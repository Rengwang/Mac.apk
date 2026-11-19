#!/usr/bin/env python3
"""Unit tests for scripts/check_doc_sync.py using isolated temp git repos."""

from __future__ import annotations

import json
import subprocess
import tempfile
import unittest
from pathlib import Path
import sys

REPO_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(REPO_ROOT / "scripts"))
import check_doc_sync  # noqa: E402


def _git(repo: Path, *args: str) -> str:
    return subprocess.check_output(
        ["git", *args],
        cwd=str(repo),
        text=True,
    )


class CheckDocSyncTest(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.repo = Path(self._tmp.name)
        _git(self.repo, "init")
        _git(self.repo, "config", "user.name", "Test")
        _git(self.repo, "config", "user.email", "test@example.com")
        (self.repo / "runtime" / "java").mkdir(parents=True)
        (self.repo / "docs" / "tasks").mkdir(parents=True)
        (self.repo / "docs" / "compat").mkdir(parents=True)
        (self.repo / "PROJECT_STATE.md").write_text("# State\n", encoding="utf-8")
        (self.repo / "docs" / "UPGRADE_PLAN.md").write_text("# Plan\n", encoding="utf-8")
        (self.repo / "docs" / "compat" / "gaps.json").write_text(
            json.dumps({"counts": {"total": 1, "fixed": 1, "observed": 0, "predicted": 0}, "gaps": [{"gap_id": "G1", "status": "fixed"}]}),
            encoding="utf-8",
        )
        (self.repo / "runtime" / "java" / "Foo.java").write_text("class Foo {}\n", encoding="utf-8")
        _git(self.repo, "add", ".")
        _git(self.repo, "commit", "-m", "init")

    def tearDown(self) -> None:
        self._tmp.cleanup()

    def test_staged_code_without_status_doc_fails(self) -> None:
        (self.repo / "runtime" / "java" / "Foo.java").write_text("class Foo { int x; }\n", encoding="utf-8")
        _git(self.repo, "add", "runtime/java/Foo.java")
        rc, errors, warnings = check_doc_sync.validate(self.repo, "staged")
        self.assertEqual(rc, 1)
        self.assertTrue(any("1+1 status doc" in e for e in errors))
        self.assertTrue(any("cap_registry.py" in w for w in warnings))

    def test_staged_code_with_project_state_passes(self) -> None:
        (self.repo / "runtime" / "java" / "Foo.java").write_text("class Foo { int x; }\n", encoding="utf-8")
        (self.repo / "PROJECT_STATE.md").write_text("# State\n- updated\n", encoding="utf-8")
        _git(self.repo, "add", "runtime/java/Foo.java", "PROJECT_STATE.md")
        rc, errors, _ = check_doc_sync.validate(self.repo, "staged")
        self.assertEqual(rc, 0, errors)

    def test_broken_markdown_link_fails(self) -> None:
        (self.repo / "PROJECT_STATE.md").write_text("See [missing](docs/NO_SUCH_FILE.md)\n", encoding="utf-8")
        _git(self.repo, "add", "PROJECT_STATE.md")
        rc, errors, _ = check_doc_sync.validate(self.repo, "staged")
        self.assertEqual(rc, 1)
        self.assertTrue(any("Broken local link" in e for e in errors))

    def test_agent_stop_blocks_dirty_code_without_doc_and_respects_guard(self) -> None:
        (self.repo / "runtime" / "java" / "Foo.java").write_text("class Foo { int y; }\n", encoding="utf-8")
        rc, errors, _ = check_doc_sync.validate(self.repo, "agent-stop")
        self.assertEqual(rc, 2)
        self.assertTrue(errors)

        proc = subprocess.run(
            [sys.executable, str(REPO_ROOT / "scripts" / "check_doc_sync.py"), "--mode", "agent-stop"],
            input=json.dumps({"stop_hook_active": True, "cwd": str(self.repo)}),
            text=True,
            capture_output=True,
        )
        self.assertEqual(proc.returncode, 0)


if __name__ == "__main__":
    unittest.main()
