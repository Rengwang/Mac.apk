#!/usr/bin/env python3
"""Isolated unit and regression tests for CAP-001 (scripts/cap_registry.py)."""

from __future__ import annotations

import copy
import json
import os
import pathlib
import re
import subprocess
import sys
import tempfile
import unittest

REPO_ROOT = pathlib.Path(__file__).resolve().parent.parent
sys.path.insert(0, str(REPO_ROOT / "scripts"))

import cap_registry  # noqa: E402


def discover_runtime_build_dir() -> pathlib.Path | None:
    env_dir = os.environ.get("CAP_RUNTIME_BUILD_DIR")
    if env_dir:
        p = pathlib.Path(env_dir).resolve()
        if (p / "lib" / "android-framework.jar").is_file():
            return p
    local = REPO_ROOT / "runtime" / "build"
    if (local / "lib" / "android-framework.jar").is_file():
        return local
    try:
        proc = subprocess.run(
            ["git", "-C", str(REPO_ROOT), "worktree", "list", "--porcelain"],
            capture_output=True,
            text=True,
            check=True,
        )
        for line in proc.stdout.splitlines():
            if line.startswith("worktree "):
                wt = pathlib.Path(line[len("worktree "):].strip())
                cand = wt / "runtime" / "build"
                if (cand / "lib" / "android-framework.jar").is_file():
                    return cand
    except Exception:
        pass
    return None


def discover_javap() -> pathlib.Path | None:
    sel = REPO_ROOT / "debug" / "jdk25_select.sh"
    if sel.is_file():
        proc = subprocess.run(["bash", str(sel)], capture_output=True, text=True)
        if proc.returncode == 0:
            home = proc.stdout.strip()
            if home:
                cand = pathlib.Path(home) / "bin" / "javap"
                if cand.is_file():
                    return cand
    return None


class CapRegistryValidationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.cap_path = REPO_ROOT / "docs" / "compat" / "capabilities.json"
        cls.schema_path = REPO_ROOT / "docs" / "compat" / "capabilities.schema.json"
        cls.inv_path = REPO_ROOT / "docs" / "compat" / "inventory.json"
        cls.cap_data = json.loads(cls.cap_path.read_text(encoding="utf-8"))
        cls.schema_data = json.loads(cls.schema_path.read_text(encoding="utf-8"))
        cls.inv_bytes = cls.inv_path.read_bytes()
        cls.inv_sha = cap_registry.sha256_bytes(cls.inv_bytes)
        cls.inv_data = json.loads(cls.inv_bytes.decode("utf-8"))
        cls.tests = cap_registry.load_registered_tests(REPO_ROOT)
        cls.runtime_build_dir = discover_runtime_build_dir()

    def test_real_registry_valid_and_fresh(self) -> None:
        self.assertGreaterEqual(len(self.cap_data["capabilities"]), 20)
        subsystems = {e["subsystem"] for e in self.cap_data["capabilities"]}
        self.assertEqual(subsystems, set(cap_registry.SUBSYSTEMS))
        surfaces = {e["surface"] for e in self.cap_data["capabilities"]}
        self.assertEqual(surfaces, set(cap_registry.SURFACES))
        errors, warnings = cap_registry.validate_registry(
            self.cap_data,
            self.schema_data,
            self.tests,
            REPO_ROOT,
            inventory=self.inv_data,
            inventory_sha256=self.inv_sha,
            runtime_build_dir=self.runtime_build_dir,
            check_staleness=True,
        )
        self.assertEqual(errors, [])
        self.assertEqual(warnings, [])

    def test_rejects_duplicate_id(self) -> None:
        doc = copy.deepcopy(self.cap_data)
        doc["capabilities"].append(copy.deepcopy(doc["capabilities"][0]))
        errors, _ = cap_registry.validate_registry(doc, self.schema_data, self.tests, REPO_ROOT)
        self.assertTrue(any("Duplicate capability id" in e for e in errors), errors)

    def test_rejects_invalid_support_status(self) -> None:
        doc = copy.deepcopy(self.cap_data)
        doc["capabilities"][0]["support_status"] = "experimental"
        errors, _ = cap_registry.validate_registry(doc, self.schema_data, self.tests, REPO_ROOT)
        self.assertTrue(any("invalid support_status" in e for e in errors), errors)

    def test_rejects_invalid_verification_status(self) -> None:
        doc = copy.deepcopy(self.cap_data)
        doc["capabilities"][0]["verification_status"] = "verified"
        errors, _ = cap_registry.validate_registry(doc, self.schema_data, self.tests, REPO_ROOT)
        self.assertTrue(any("invalid verification_status" in e for e in errors), errors)

    def test_rejects_dangling_test_id(self) -> None:
        doc = copy.deepcopy(self.cap_data)
        doc["capabilities"][0]["test_ids"] = ["NonExistentGhostTest_999"]
        errors, _ = cap_registry.validate_registry(doc, self.schema_data, self.tests, REPO_ROOT)
        self.assertTrue(any("dangling test_id" in e for e in errors), errors)

    def test_rejects_verified_entry_without_test_ids(self) -> None:
        doc = copy.deepcopy(self.cap_data)
        target = next(e for e in doc["capabilities"] if e["verification_status"] == "contract-tested")
        target["test_ids"] = []
        errors, _ = cap_registry.validate_registry(doc, self.schema_data, self.tests, REPO_ROOT)
        self.assertTrue(any("empty test_ids" in e for e in errors), errors)

    def test_rejects_verified_entry_without_evidence_or_version_or_date(self) -> None:
        doc = copy.deepcopy(self.cap_data)
        target = next(e for e in doc["capabilities"] if e["verification_status"] == "unverified")
        target["verification_status"] = "android-compared"
        target["test_ids"] = ["ParcelWireTest"]
        target["evidence"] = []
        target["verified_version"] = ""
        target["verified_date"] = None
        errors, _ = cap_registry.validate_registry(doc, self.schema_data, self.tests, REPO_ROOT)
        self.assertTrue(any("empty evidence" in e for e in errors), errors)
        self.assertTrue(any("missing verified_version" in e for e in errors), errors)
        self.assertTrue(any("invalid verified_date" in e for e in errors), errors)

    def test_rejects_bad_inventory_refs_and_surface_count_mismatch(self) -> None:
        doc = copy.deepcopy(self.cap_data)
        doc["capabilities"][0]["inventory_refs"].append("method:android/view/SurfaceView#noSuchMethod()V")
        doc["surfaces"]["public_sdk"]["counts"]["partial"] += 1
        errors, _ = cap_registry.validate_registry(
            doc,
            self.schema_data,
            self.tests,
            REPO_ROOT,
            inventory=self.inv_data,
        )
        self.assertTrue(any("inventory_ref method not found" in e for e in errors), errors)
        self.assertTrue(any("surfaces.public_sdk.counts.partial" in e for e in errors), errors)

    def test_staleness_detection_and_strict_exit_code(self) -> None:
        doc = copy.deepcopy(self.cap_data)
        doc["staleness_anchor"]["inventory_sha256"] = "f" * 64
        first_tracked = next(iter(doc["staleness_anchor"]["tracked_file_hashes"]))
        doc["staleness_anchor"]["tracked_file_hashes"][first_tracked] = "0" * 64
        errors, warnings = cap_registry.validate_registry(
            doc,
            self.schema_data,
            self.tests,
            REPO_ROOT,
            inventory=self.inv_data,
            inventory_sha256=self.inv_sha,
            check_staleness=True,
        )
        self.assertEqual(errors, [])
        self.assertTrue(any("STALE inventory_sha256" in w for w in warnings), warnings)
        self.assertTrue(any("STALE tracked file changed" in w for w in warnings), warnings)

        with tempfile.TemporaryDirectory() as td:
            bad_cap = pathlib.Path(td) / "capabilities.json"
            bad_cap.write_bytes(cap_registry.canonical_json_bytes(doc))
            rc = cap_registry.main([
                "validate",
                "--capabilities", str(bad_cap),
                "--schema", str(self.schema_path),
                "--inventory", str(self.inv_path),
                "--check-staleness",
                "--strict-staleness",
            ])
            self.assertNotEqual(rc, 0)

    def test_class_vs_interface_inheritance_and_overloads(self) -> None:
        classes = self.inv_data["classes"]
        self.assertFalse(classes["android/os/ServiceManager"]["is_interface"])
        self.assertEqual(classes["android/os/ServiceManager"]["kind"], "class")
        self.assertTrue(classes["android/view/Window$Callback"]["is_interface"])
        self.assertEqual(classes["android/view/Window$Callback"]["kind"], "interface")

        # Inherited method resolution: SurfaceView -> View.getVisibility()I
        vis = cap_registry.resolve_method(classes, "android/view/SurfaceView", "getVisibility", "()I")
        self.assertIsNotNone(vis)
        self.assertTrue(vis["inherited"])
        self.assertEqual(vis["declared_in"], "android/view/View")

        # Overloads preserved distinctly by descriptor
        sm_eff = cap_registry.effective_methods(classes, "android/os/ServiceManager")
        add_descs = sorted(desc for (name, desc) in sm_eff if name == "addService")
        self.assertEqual(
            add_descs,
            [
                "(Ljava/lang/String;Landroid/os/IBinder;)V",
                "(Ljava/lang/String;Landroid/os/IBinder;Z)V",
                "(Ljava/lang/String;Landroid/os/IBinder;ZI)V",
            ],
        )

        # Synthetic hierarchy checks: private and <init> are NOT inherited; interfaces ARE inherited
        synthetic = {
            "I": {
                "kind": "interface",
                "is_interface": True,
                "super": "java/lang/Object",
                "interfaces": [],
                "methods": [
                    {"name": "ifaceMethod", "descriptor": "(I)V", "access": 0x0401, "flags": "0x0401"},
                ],
            },
            "Base": {
                "kind": "class",
                "is_interface": False,
                "super": "java/lang/Object",
                "interfaces": ["I"],
                "methods": [
                    {"name": "<init>", "descriptor": "()V", "access": 0x0001, "flags": "0x0001"},
                    {"name": "secret", "descriptor": "()V", "access": 0x0002, "flags": "0x0002"},
                    {"name": "ping", "descriptor": "()V", "access": 0x0001, "flags": "0x0001"},
                    {"name": "ping", "descriptor": "(I)I", "access": 0x0001, "flags": "0x0001"},
                ],
            },
            "Sub": {
                "kind": "class",
                "is_interface": False,
                "super": "Base",
                "interfaces": [],
                "methods": [
                    {"name": "<init>", "descriptor": "(I)V", "access": 0x0001, "flags": "0x0001"},
                    {"name": "ping", "descriptor": "()V", "access": 0x0001, "flags": "0x0001"},
                ],
            },
        }
        sub_eff = cap_registry.effective_methods(synthetic, "Sub")
        self.assertIn(("ping", "()V"), sub_eff)
        self.assertFalse(sub_eff[("ping", "()V")]["inherited"])
        self.assertIn(("ping", "(I)I"), sub_eff)
        self.assertTrue(sub_eff[("ping", "(I)I")]["inherited"])
        self.assertIn(("ifaceMethod", "(I)V"), sub_eff)
        self.assertEqual(sub_eff[("ifaceMethod", "(I)V")]["declared_kind"], "interface")
        self.assertNotIn(("secret", "()V"), sub_eff)
        self.assertNotIn(("<init>", "()V"), sub_eff)

    def test_javap_cross_check_when_jdk_available(self) -> None:
        if self.runtime_build_dir is None:
            self.skipTest("runtime/build not found")
        javap = discover_javap()
        if javap is None:
            self.skipTest("JDK javap not found via debug/jdk25_select.sh")
        jar_path = self.runtime_build_dir / "lib" / "android-framework.jar"
        for fqcn, internal in (
            ("android.os.ServiceManager", "android/os/ServiceManager"),
            ("android.view.Window$Callback", "android/view/Window$Callback"),
        ):
            proc = subprocess.run(
                [str(javap), "-p", "-s", "-cp", str(jar_path), fqcn],
                capture_output=True,
                text=True,
                check=True,
            )
            descs = set(re.findall(r"descriptor:\s+(\S+)", proc.stdout))
            # Filter out method descriptors only (contain '(')
            javap_method_descs = {d for d in descs if "(" in d}
            inv_method_descs = {m["descriptor"] for m in self.inv_data["classes"][internal]["methods"]}
            self.assertEqual(inv_method_descs, javap_method_descs)

    def test_deterministic_regeneration_byte_identical(self) -> None:
        if self.runtime_build_dir is None:
            self.skipTest("runtime/build not found")
        with tempfile.TemporaryDirectory() as td:
            out1 = pathlib.Path(td) / "inv1.json"
            out2 = pathlib.Path(td) / "inv2.json"
            rc1 = cap_registry.main([
                "generate",
                "--runtime-build-dir", str(self.runtime_build_dir),
                "--output-inventory", str(out1),
            ])
            rc2 = cap_registry.main([
                "generate",
                "--runtime-build-dir", str(self.runtime_build_dir),
                "--output-inventory", str(out2),
            ])
            self.assertEqual(rc1, 0)
            self.assertEqual(rc2, 0)
            b1 = out1.read_bytes()
            b2 = out2.read_bytes()
            self.assertEqual(b1, b2)
            self.assertEqual(b1, self.inv_bytes)


if __name__ == "__main__":
    unittest.main(verbosity=2)
