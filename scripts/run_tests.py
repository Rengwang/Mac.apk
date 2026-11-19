#!/usr/bin/env python3
"""Unified, repeatable test runner for Mac.apk's categorized debug/ tests.

Zero external dependencies (Python 3.9+ standard library only).
Never launches GUI apps, modifies /Applications/Mac.apk.app, syncs bundles,
kills user processes, or touches shared runtime/user APK data unless the user
explicitly selects `installed_bundle`.
"""

from __future__ import annotations

import argparse
import dataclasses
import datetime
import json
import os
import pathlib
import platform
import shlex
import shutil
import subprocess
import sys
import tempfile
import time
import xml.etree.ElementTree as ET
from typing import Any, Callable, Dict, List, Optional, Sequence, Tuple


ALL_GROUPS: Tuple[str, ...] = (
    "bare_jvm",
    "jvm_launch_sensitive",
    "native_unit",
    "worktree_bundle",
    "gpu_pixel",
    "codegen_check",
    "runtime_fixture",
    "installed_bundle",
)

OFFLINE_GROUPS: Tuple[str, ...] = (
    "bare_jvm",
    "jvm_launch_sensitive",
    "native_unit",
    "worktree_bundle",
)

REQUIRED_WHEN_SELECTED: frozenset[str] = frozenset(OFFLINE_GROUPS)


@dataclasses.dataclass(frozen=True)
class TestSpec:
    id: str
    group: str
    kind: str  # java | native_cpp | native_swift | python | shell | jni_pair | runtime_harness
    source: str
    notes: str
    main_class: str = ""
    classpath_mode: str = "fw"  # fw | both
    jvm_flags: Tuple[str, ...] = ()
    env: Tuple[Tuple[str, str], ...] = ()
    needs_temp_arg: bool = False
    needs_isolated_home: bool = False
    needs_apk: bool = False
    needs_attrs_xml: bool = False
    needs_dist_bundle: bool = False
    needs_installed_app: bool = False
    needs_runtime_broker: bool = False
    compile_sources: Tuple[str, ...] = ()
    compile_flags: Tuple[str, ...] = ()
    run_args: Tuple[str, ...] = ()
    skip_exit_codes: Tuple[int, ...] = ()
    stdout_skip_prefixes: Tuple[str, ...] = ()
    timeout_sec: int = 60


TESTS: Tuple[TestSpec, ...] = (
    # ------------------------------------------------------------------
    # (a) bare_jvm -- Pure Java / headless Java2D, no JNI or launch trap
    # ------------------------------------------------------------------
    TestSpec(
        id="ActivityManagerTasksTest",
        group="bare_jvm",
        kind="java",
        source="debug/ActivityManagerTasksTest.java",
        main_class="ActivityManagerTasksTest",
        classpath_mode="both",
        jvm_flags=("-Djava.awt.headless=true",),
        env=(("MACAPK_LOG_LEVEL", "7"),),
        notes="AppTask base/top/count/run-state snapshot invariants.",
    ),
    TestSpec(
        id="DialogWindowBackgroundTest",
        group="bare_jvm",
        kind="java",
        source="debug/DialogWindowBackgroundTest.java",
        main_class="DialogWindowBackgroundTest",
        classpath_mode="fw",
        jvm_flags=("-Djava.awt.headless=true",),
        env=(("MACAPK_LOG_LEVEL", "7"),),
        notes="Dialog transparent background, fallback panel, hide/show/dismiss.",
    ),
    TestSpec(
        id="ImageViewLevelTest",
        group="bare_jvm",
        kind="java",
        source="debug/ImageViewLevelTest.java",
        main_class="ImageViewLevelTest",
        classpath_mode="fw",
        jvm_flags=("-Djava.awt.headless=true",),
        env=(("MACAPK_LOG_LEVEL", "7"),),
        notes="ImageView levels and ColorFilter replacement semantics.",
    ),
    TestSpec(
        id="InsetsControllabilityTest",
        group="bare_jvm",
        kind="java",
        source="debug/InsetsControllabilityTest.java",
        main_class="InsetsControllabilityTest",
        classpath_mode="fw",
        jvm_flags=("-Djava.awt.headless=true",),
        env=(("MACAPK_LOG_LEVEL", "7"),),
        notes="Immersive hide listener non-reentrancy.",
    ),
    TestSpec(
        id="LayerColorFilterTest",
        group="bare_jvm",
        kind="java",
        source="debug/LayerColorFilterTest.java",
        main_class="LayerColorFilterTest",
        classpath_mode="fw",
        jvm_flags=("-Djava.awt.headless=true",),
        env=(("MACAPK_LOG_LEVEL", "7"),),
        notes="Canvas.saveLayer ColorFilter pixel exactness (default fast path).",
    ),
    TestSpec(
        id="LayerColorFilterTest_legacy",
        group="bare_jvm",
        kind="java",
        source="debug/LayerColorFilterTest.java",
        main_class="LayerColorFilterTest",
        classpath_mode="fw",
        jvm_flags=("-Djava.awt.headless=true",),
        env=(("MACAPK_LOG_LEVEL", "7"), ("MACAPK_FRAMESPEED_LAYER", "0")),
        notes="Canvas.saveLayer ColorFilter pixel exactness (MACAPK_FRAMESPEED_LAYER=0).",
    ),
    TestSpec(
        id="MediaCodecDequeueTest",
        group="bare_jvm",
        kind="java",
        source="debug/MediaCodecDequeueTest.java",
        main_class="MediaCodecDequeueTest",
        classpath_mode="fw",
        jvm_flags=("-Djava.awt.headless=true",),
        env=(("MACAPK_LOG_LEVEL", "7"),),
        notes="MediaCodec bounded timeout, poll, producer progress, release cancel.",
    ),
    TestSpec(
        id="AudioTrackClockMonotonicityTest",
        group="bare_jvm",
        kind="java",
        source="debug/AudioTrackClockMonotonicityTest.java",
        main_class="AudioTrackClockMonotonicityTest",
        classpath_mode="both",
        jvm_flags=("-Djava.awt.headless=true",),
        env=(("MACAPK_LOG_LEVEL", "7"),),
        notes="AudioTrack 80ms min buffer floor and MessageQueue delayed wake/timeout monotonicity.",
    ),
    TestSpec(
        id="CodecObsTest",
        group="bare_jvm",
        kind="java",
        source="debug/CodecObsTest.java",
        main_class="CodecObsTest",
        classpath_mode="fw",
        jvm_flags=("-Djava.awt.headless=true",),
        env=(("MACAPK_LOG_LEVEL", "7"),),
        notes="MACAPK_CODEC_OBS=1 bounded queue/wait counters, drop cap, default-OFF invariance.",
    ),
    TestSpec(
        id="ContractSuiteRunner",
        group="bare_jvm",
        kind="python",
        source="scripts/run_contract_tests.py",
        run_args=("scripts/run_contract_tests.py",),
        notes="Offline Android API contract suite against pinned baseline (PASS + pinned XFAIL gaps).",
    ),
    TestSpec(
        id="ContractSuiteSelfTest",
        group="bare_jvm",
        kind="python",
        source="scripts/run_contract_tests.py",
        run_args=("scripts/run_contract_tests.py", "--self-test"),
        notes="Contract suite negative self-test (--strict-xfail, injected regression, missing baseline, corrupt output).",
    ),
    TestSpec(
        id="ParcelBinderTransportTest",
        group="bare_jvm",
        kind="java",
        source="debug/ParcelBinderTransportTest.java",
        main_class="ParcelBinderTransportTest",
        classpath_mode="fw",
        jvm_flags=("-Djava.awt.headless=true",),
        env=(("MACAPK_LOG_LEVEL", "7"),),
        notes="Binder transport round trip, malformed messages, overwrite, truncation.",
    ),
    TestSpec(
        id="SurfaceParcelTransportTest",
        group="bare_jvm",
        kind="java",
        source="debug/SurfaceParcelTransportTest.java",
        main_class="SurfaceParcelTransportTest",
        classpath_mode="fw",
        jvm_flags=("-Djava.awt.headless=true",),
        env=(("MACAPK_LOG_LEVEL", "7"),),
        notes="Surface Binder sink token parceling, remote geometry query and video sink activation.",
    ),
    TestSpec(
        id="ParcelWireTest",
        group="bare_jvm",
        kind="java",
        source="debug/ParcelWireTest.java",
        main_class="ParcelWireTest",
        classpath_mode="fw",
        jvm_flags=("-Djava.awt.headless=true",),
        env=(("MACAPK_LOG_LEVEL", "7"),),
        notes="Parcel frame wire round trip and malformed input handling.",
    ),
    TestSpec(
        id="RenderNodePunchClipTest",
        group="bare_jvm",
        kind="java",
        source="debug/RenderNodePunchClipTest.java",
        main_class="RenderNodePunchClipTest",
        classpath_mode="fw",
        jvm_flags=("-Djava.awt.headless=true",),
        env=(("MACAPK_LOG_LEVEL", "7"),),
        notes="RenderNode replay preserves punched region clip.",
    ),
    TestSpec(
        id="ResourceIndexCacheKeyTest",
        group="bare_jvm",
        kind="java",
        source="debug/ResourceIndexCacheKeyTest.java",
        main_class="ResourceIndexCacheKeyTest",
        classpath_mode="both",
        jvm_flags=("-Djava.awt.headless=true",),
        env=(("MACAPK_LOG_LEVEL", "7"),),
        notes="ResourceIndex cache key equality vs configuration/locale mutations.",
    ),
    TestSpec(
        id="SurfaceTextureRoutingTest",
        group="bare_jvm",
        kind="java",
        source="debug/SurfaceTextureRoutingTest.java",
        main_class="SurfaceTextureRoutingTest",
        classpath_mode="fw",
        jvm_flags=("-Djava.awt.headless=true",),
        env=(("MACAPK_LOG_LEVEL", "7"),),
        notes="Explicit SurfaceTexture destination survives unrelated Surface creation.",
    ),
    TestSpec(
        id="SurfaceVideoTargetIsolationTest",
        group="bare_jvm",
        kind="java",
        source="debug/SurfaceVideoTargetIsolationTest.java",
        main_class="SurfaceVideoTargetIsolationTest",
        classpath_mode="fw",
        jvm_flags=("-Djava.awt.headless=true",),
        env=(("MACAPK_LOG_LEVEL", "7"),),
        notes="Video SurfaceTexture latch isolation against unrelated overlay Surface.",
    ),
    TestSpec(
        id="TypedArrayColorStateListTest",
        group="bare_jvm",
        kind="java",
        source="debug/TypedArrayColorStateListTest.java",
        main_class="TypedArrayColorStateListTest",
        classpath_mode="fw",
        jvm_flags=("-Djava.awt.headless=true",),
        env=(("MACAPK_LOG_LEVEL", "7"),),
        notes="Absent/@null tint stays null; literal tint preserved.",
    ),
    TestSpec(
        id="TypedArrayVisibilityTest",
        group="bare_jvm",
        kind="java",
        source="debug/TypedArrayVisibilityTest.java",
        main_class="TypedArrayVisibilityTest",
        classpath_mode="fw",
        jvm_flags=("-Djava.awt.headless=true",),
        env=(("MACAPK_LOG_LEVEL", "7"),),
        notes="XML enums, numeric preservation, defaults, ConstraintSet visibility.",
    ),
    TestSpec(
        id="UpstreamResourceAliasTest",
        group="bare_jvm",
        kind="java",
        source="debug/UpstreamResourceAliasTest.java",
        main_class="UpstreamResourceAliasTest",
        classpath_mode="both",
        jvm_flags=("-Djava.awt.headless=true",),
        env=(("MACAPK_LOG_LEVEL", "7"),),
        notes="DomXmlResourceParser shrunk XML spelling, table alias, resource ID.",
    ),
    TestSpec(
        id="ViewAttachedDisplayTest",
        group="bare_jvm",
        kind="java",
        source="debug/ViewAttachedDisplayTest.java",
        main_class="ViewAttachedDisplayTest",
        classpath_mode="fw",
        jvm_flags=("-Djava.awt.headless=true",),
        env=(("MACAPK_LOG_LEVEL", "7"),),
        notes="Attached renderer View.getDisplay() discovery and fallback.",
    ),
    TestSpec(
        id="HotApiBatch2Test",
        group="bare_jvm",
        kind="java",
        source="debug/HotApiBatch2Test.java",
        main_class="HotApiBatch2Test",
        classpath_mode="both",
        jvm_flags=("-Djava.awt.headless=true",),
        env=(("MACAPK_LOG_LEVEL", "7"),),
        notes="HOTAPI-002 setter/getter round-trips and non-lib*.so ELF magic routing.",
    ),
    TestSpec(
        id="HotApiBatch3Test",
        group="bare_jvm",
        kind="java",
        source="debug/HotApiBatch3Test.java",
        main_class="HotApiBatch3Test",
        classpath_mode="both",
        jvm_flags=("-Djava.awt.headless=true",),
        env=(("MACAPK_LOG_LEVEL", "7"),),
        notes="HOTAPI-003 Window/AccessibilityNodeInfo/View fixes and AOSP inert invariants.",
    ),
    TestSpec(
        id="ResourceIndexRotationTest",
        group="bare_jvm",
        kind="java",
        source="debug/ResourceIndexRotationTest.java",
        main_class="ResourceIndexRotationTest",
        classpath_mode="both",
        jvm_flags=("-Djava.awt.headless=true",),
        env=(("MACAPK_LOG_LEVEL", "7"), ("MACAPK_FWVIEW_DISK_CACHE", "0")),
        notes="GFX-001 framework ResourceIndex consistency across portrait/landscape/night transitions.",
    ),
    TestSpec(
        id="TextureViewDirectVideoTest",
        group="bare_jvm",
        kind="java",
        source="debug/TextureViewDirectVideoTest.java",
        main_class="TextureViewDirectVideoTest",
        classpath_mode="fw",
        jvm_flags=("-Djava.awt.headless=true",),
        env=(("MACAPK_LOG_LEVEL", "7"),),
        notes="TextureView direct GPU CAMetalLayer routing, Canvas.punchHole, and fallback invariants.",
    ),
    TestSpec(
        id="Egl10KeyedSurfaceViewTest",
        group="bare_jvm",
        kind="java",
        source="debug/Egl10KeyedSurfaceViewTest.java",
        main_class="Egl10KeyedSurfaceViewTest",
        classpath_mode="fw",
        jvm_flags=("-Djava.awt.headless=true",),
        env=(("MACAPK_LOG_LEVEL", "7"),),
        notes="MacApkEGL10 keyed CAMetalLayer SurfaceView window routing, validation, and hole-punch notice.",
    ),
    TestSpec(
        id="AutomationServerTest",
        group="bare_jvm",
        kind="java",
        source="debug/AutomationServerTest.java",
        main_class="AutomationServerTest",
        classpath_mode="both",
        jvm_flags=("-Djava.awt.headless=true",),
        env=(("MACAPK_LOG_LEVEL", "7"),),
        notes="AutomationServer JSON codec, opt-out gating, fx/fy mapping, ui_tree schema, and atomic quit ack.",
    ),
    # ------------------------------------------------------------------
    # (b) jvm_launch_sensitive -- Must compile-then-run, special flags/env
    # ------------------------------------------------------------------
    TestSpec(
        id="OwnerFrameBarrierTest",
        group="jvm_launch_sensitive",
        kind="java",
        source="debug/OwnerFrameBarrierTest.java",
        main_class="android.os.OwnerFrameBarrierTest",
        classpath_mode="fw",
        jvm_flags=("-Djava.awt.headless=true",),
        env=(("MACAPK_LOG_LEVEL", "7"),),
        notes="Must javac-compile then java-run; source-file launcher hits IllegalAccessError.",
    ),
    TestSpec(
        id="BitmapShaderSamplingTest",
        group="jvm_launch_sensitive",
        kind="java",
        source="debug/BitmapShaderSamplingTest.java",
        main_class="android.graphics.BitmapShaderSamplingTest",
        classpath_mode="fw",
        jvm_flags=("-Djava.awt.headless=true", "-XX:-DoEscapeAnalysis"),
        env=(("MACAPK_LOG_LEVEL", "7"),),
        notes="Requires jdk.management ThreadMXBean + -XX:-DoEscapeAnalysis allocation gate.",
    ),
    TestSpec(
        id="ContainedBitmapTextureTest",
        group="jvm_launch_sensitive",
        kind="java",
        source="debug/ContainedBitmapTextureTest.java",
        main_class="android.graphics.ContainedBitmapTextureTest",
        classpath_mode="fw",
        jvm_flags=("-Djava.awt.headless=true",),
        env=(("MACAPK_LOG_LEVEL", "7"),),
        notes="Package-private Canvas.macapkContainedBitmapTexture fast-path gate (enabled).",
    ),
    TestSpec(
        id="ContainedBitmapTextureTest_disabled",
        group="jvm_launch_sensitive",
        kind="java",
        source="debug/ContainedBitmapTextureTest.java",
        main_class="android.graphics.ContainedBitmapTextureTest",
        classpath_mode="fw",
        jvm_flags=("-Djava.awt.headless=true",),
        env=(("MACAPK_LOG_LEVEL", "7"), ("MACAPK_BITMAPSHADER_CONTAINED_TEXTURE", "0")),
        notes="Verify MACAPK_BITMAPSHADER_CONTAINED_TEXTURE=0 disables fast path.",
    ),
    TestSpec(
        id="InnerClassAccessTest",
        group="jvm_launch_sensitive",
        kind="java",
        source="debug/InnerClassAccessTest.java",
        main_class="com.macapk.dex.InnerClassAccessTest",
        classpath_mode="both",
        jvm_flags=("-Djava.awt.headless=true",),
        env=(("MACAPK_LOG_LEVEL", "7"),),
        notes="DEX inner-class access flag normalization (package com.macapk.dex).",
    ),
    TestSpec(
        id="BrokerServiceStartupTest",
        group="jvm_launch_sensitive",
        kind="java",
        source="debug/BrokerServiceStartupTest.java",
        main_class="com.macapk.loader.BrokerServiceStartupTest",
        classpath_mode="both",
        jvm_flags=("-Djava.awt.headless=true",),
        env=(("MACAPK_LOG_LEVEL", "7"),),
        needs_temp_arg=True,
        notes="Cold service bind burst & worker REGISTER capacity with temp dir arg.",
    ),
    TestSpec(
        id="ProcessBrokerRoutingTest",
        group="jvm_launch_sensitive",
        kind="java",
        source="debug/ProcessBrokerRoutingTest.java",
        main_class="com.macapk.loader.ProcessBrokerRoutingTest",
        classpath_mode="both",
        jvm_flags=("-Djava.awt.headless=true",),
        env=(("MACAPK_LOG_LEVEL", "7"),),
        needs_temp_arg=True,
        needs_isolated_home=True,
        notes="Manifest process isolation routing in isolated temp HOME.",
    ),
    TestSpec(
        id="ProcessBrokerRoutingTest_optout",
        group="jvm_launch_sensitive",
        kind="java",
        source="debug/ProcessBrokerRoutingTest.java",
        main_class="com.macapk.loader.ProcessBrokerRoutingTest",
        classpath_mode="both",
        jvm_flags=("-Djava.awt.headless=true",),
        env=(("MACAPK_LOG_LEVEL", "7"), ("MACAPK_BROKER", "0")),
        needs_temp_arg=True,
        needs_isolated_home=True,
        notes="MACAPK_BROKER=0 opt-out preserves single-process naming.",
    ),
    TestSpec(
        id="RemoteBindCompletionTest",
        group="jvm_launch_sensitive",
        kind="java",
        source="debug/RemoteBindCompletionTest.java",
        main_class="com.macapk.loader.RemoteBindCompletionTest",
        classpath_mode="both",
        jvm_flags=("-Djava.awt.headless=true",),
        env=(("MACAPK_LOG_LEVEL", "7"),),
        notes="SystemClient delayed bind cancellation, generation reuse, death handling.",
    ),
    TestSpec(
        id="SurfaceZOrderRequestTest",
        group="jvm_launch_sensitive",
        kind="java",
        source="debug/SurfaceZOrderRequestTest.java",
        main_class="SurfaceZOrderRequestTest",
        classpath_mode="fw",
        jvm_flags=("-Djava.awt.headless=true",),
        env=(("MACAPK_LOG_LEVEL", "6"),),
        notes="Uses sun.misc.Unsafe.allocateInstance + requires MACAPK_LOG_LEVEL>=6.",
    ),
    TestSpec(
        id="WindowDamageLifecycleTest",
        group="jvm_launch_sensitive",
        kind="java",
        source="debug/WindowDamageLifecycleTest.java",
        main_class="WindowDamageLifecycleTest",
        classpath_mode="fw",
        jvm_flags=("-Djava.awt.headless=true",),
        env=(("MACAPK_LOG_LEVEL", "7"),),
        notes="Transform damage bounds + TextureView retained Surface reattach (needs MACAPK_LOG_LEVEL=7).",
    ),
    # ------------------------------------------------------------------
    # (c) native_unit -- Offline C++ / ObjC++ / Swift / Python unit tests
    # ------------------------------------------------------------------
    TestSpec(
        id="GuestReturnAliasTest",
        group="native_unit",
        kind="native_cpp",
        source="debug/GuestReturnAliasTest.cpp",
        compile_sources=("debug/GuestReturnAliasTest.cpp",),
        compile_flags=("-std=c++17", "-Wall", "-Wextra", "-Werror"),
        notes="ARM64 guest return alias invariants.",
    ),
    TestSpec(
        id="guest_jit_canary_test",
        group="native_unit",
        kind="native_cpp",
        source="debug/guest_jit_canary_test.cpp",
        compile_sources=("debug/guest_jit_canary_test.cpp",),
        compile_flags=("-std=c++17", "-Wall", "-Wextra", "-Werror"),
        notes="Guest JIT canary classification.",
    ),
    TestSpec(
        id="VideoPixelsTest",
        group="native_unit",
        kind="native_cpp",
        source="debug/VideoPixelsTest.cpp",
        compile_sources=("debug/VideoPixelsTest.cpp",),
        compile_flags=("-std=c++17", "-Wall", "-Wextra", "-Werror"),
        notes="ARM64 NEON / scalar video pixel color conversion.",
    ),
    TestSpec(
        id="SurfaceMetalLayerAlphaTest",
        group="native_unit",
        kind="native_cpp",
        source="debug/SurfaceMetalLayerAlphaTest.mm",
        compile_sources=("debug/SurfaceMetalLayerAlphaTest.mm",),
        compile_flags=(
            "-std=c++17",
            "-Wall",
            "-Wextra",
            "-framework",
            "Foundation",
            "-framework",
            "QuartzCore",
        ),
        notes="CAMetalLayer alpha/opaque bridge (compiled without -fobjc-arc).",
    ),
    TestSpec(
        id="app_fps_meter_test",
        group="native_unit",
        kind="native_cpp",
        source="debug/perf/app_fps_meter_test.cpp",
        compile_sources=("debug/perf/app_fps_meter_test.cpp",),
        compile_flags=("-std=c++17", "-Wall", "-Wextra", "-Werror", "-I{repo}/runtime/src"),
        notes="App FPS meter scenarios.",
    ),
    TestSpec(
        id="idle_present_policy_test",
        group="native_unit",
        kind="native_cpp",
        source="debug/perf/idle_present_policy_test.cpp",
        compile_sources=("debug/perf/idle_present_policy_test.cpp",),
        compile_flags=("-std=c++17", "-Wall", "-Wextra", "-Werror", "-I{repo}/runtime/src"),
        notes="Idle present gate decisions and wake priorities.",
    ),
    TestSpec(
        id="ProcessBrokerLaunchAgentTest",
        group="native_unit",
        kind="native_swift",
        source="debug/ProcessBrokerLaunchAgentTest.swift",
        compile_sources=(
            "launcher/ProcessBrokerLaunchAgent.swift",
            "debug/ProcessBrokerLaunchAgentTest.swift",
        ),
        compile_flags=(
            "-parse-as-library",
            "-target",
            "arm64-apple-macos26.0",
            "-framework",
            "ServiceManagement",
        ),
        notes="LaunchAgent plist generation and fingerprint stability (no launchctl side effects).",
    ),
    TestSpec(
        id="native_window_lifetime_test",
        group="native_unit",
        kind="python",
        source="debug/native_window_lifetime_test.py",
        run_args=("debug/native_window_lifetime_test.py",),
        timeout_sec=120,
        notes="Extracts and exercises ANativeWindow shim ownership under legacy/keyed modes.",
    ),
    TestSpec(
        id="MediaCodecEncoderTest",
        group="native_unit",
        kind="jni_pair",
        source="debug/MediaCodecEncoderTest.java",
        main_class="debug.MediaCodecEncoderTest",
        classpath_mode="both",
        jvm_flags=("-Djava.awt.headless=true",),
        env=(("MACAPK_LOG_LEVEL", "7"),),
        compile_sources=(
            "runtime/src/media_decode.mm",
            "runtime/src/video_toolbox.mm",
            "runtime/src/video_caps_probe.mm",
            "debug/media_encoder_jni_test.cpp",
        ),
        compile_flags=(
            "-std=c++17",
            "-O2",
            "-shared",
            "-fPIC",
            "-fobjc-arc",
            "-I{jdk_home}/include",
            "-I{jdk_home}/include/darwin",
            "-I{repo}/runtime/src",
            "-framework",
            "Foundation",
            "-framework",
            "AudioToolbox",
            "-framework",
            "VideoToolbox",
            "-framework",
            "CoreMedia",
            "-framework",
            "CoreVideo",
            "-framework",
            "CoreFoundation",
            "-framework",
            "IOSurface",
        ),
        timeout_sec=120,
        notes="VideoToolbox (H.264/HEVC ByteBuffer + InputSurface) & AudioToolbox AAC-LC MediaCodec encoder round-trips.",
    ),
    # ------------------------------------------------------------------
    # (d) worktree_bundle -- Local worktree Mac.apk.app checks (read-only)
    # ------------------------------------------------------------------
    TestSpec(
        id="worktree_entitlements_check",
        group="worktree_bundle",
        kind="shell",
        source="debug/entitlements_check.sh",
        run_args=("debug/entitlements_check.sh", "{app_bundle}/Contents/Resources/macapk-runtime"),
        notes="Checks all 8 required entitlements on local worktree build/Mac.apk.app.",
    ),
    TestSpec(
        id="worktree_audit_bundle_deps",
        group="worktree_bundle",
        kind="shell",
        source="scripts/audit_bundle_deps.sh",
        run_args=("scripts/audit_bundle_deps.sh", "{app_bundle}"),
        notes="Audits every Mach-O in local worktree build/Mac.apk.app for external deps.",
    ),
    TestSpec(
        id="worktree_codesign_verify",
        group="worktree_bundle",
        kind="shell",
        source="scripts/build_launcher.sh",
        run_args=("codesign", "--verify", "--deep", "--strict", "{app_bundle}"),
        notes="Verifies ad-hoc bundle seal on local worktree build/Mac.apk.app.",
    ),
    # ------------------------------------------------------------------
    # (e) gpu_pixel -- Metal / ANGLE / IOSurface / Skiko GPU tests
    # ------------------------------------------------------------------
    TestSpec(
        id="AgslRenderingSmoke",
        group="gpu_pixel",
        kind="java",
        source="debug/AgslRenderingSmoke.java",
        main_class="AgslRenderingSmoke",
        classpath_mode="both",
        jvm_flags=(
            "-Dmacapk.binary.dir={runtime_build}/bin",
            "--patch-module",
            "jdk.unsupported={runtime_build}/lib/jdk_unsupported_patch",
            "--add-opens",
            "java.base/jdk.internal.misc=jdk.unsupported",
        ),
        env=(("MACAPK_LOG_LEVEL", "7"),),
        notes="Metal Skia AGSL pixel-contract smoke (requires libskiko + libmacapk-skia-metal).",
    ),
    TestSpec(
        id="egl_iosurface_probe",
        group="gpu_pixel",
        kind="native_cpp",
        source="debug/perf/egl_iosurface_probe.mm",
        compile_sources=("debug/perf/egl_iosurface_probe.mm",),
        compile_flags=(
            "-std=c++17",
            "-O2",
            "-I{repo}/thirdparty/angle-prebuilt/include",
            "-L{runtime_build}/bin",
            "-Wl,-rpath,@executable_path",
            "-Wl,-rpath,{runtime_build}/bin",
            "-lEGL",
            "-lGLESv2",
            "-framework",
            "Foundation",
            "-framework",
            "IOSurface",
            "-framework",
            "Metal",
        ),
        run_args=("metal",),
        skip_exit_codes=(2,),
        notes="EGL_ANGLE_iosurface_client_buffer probe (exit 2 UNSUPPORTED -> SKIP).",
    ),
    TestSpec(
        id="egl_iosurface_orientation",
        group="gpu_pixel",
        kind="native_cpp",
        source="debug/perf/egl_iosurface_orientation.mm",
        compile_sources=("debug/perf/egl_iosurface_orientation.mm",),
        compile_flags=(
            "-std=c++17",
            "-O2",
            "-I{repo}/thirdparty/angle-prebuilt/include",
            "-L{runtime_build}/bin",
            "-Wl,-rpath,@executable_path",
            "-Wl,-rpath,{runtime_build}/bin",
            "-lEGL",
            "-lGLESv2",
            "-framework",
            "Foundation",
            "-framework",
            "IOSurface",
            "-framework",
            "Metal",
        ),
        run_args=("metal",),
        skip_exit_codes=(2,),
        notes="EGL IOSurface orientation check (exit 2 UNSUPPORTED -> SKIP).",
    ),
    TestSpec(
        id="egl_iosurface_multiframe",
        group="gpu_pixel",
        kind="native_cpp",
        source="debug/perf/egl_iosurface_multiframe.mm",
        compile_sources=("debug/perf/egl_iosurface_multiframe.mm",),
        compile_flags=(
            "-std=c++17",
            "-O2",
            "-I{repo}/thirdparty/angle-prebuilt/include",
            "-L{runtime_build}/bin",
            "-Wl,-rpath,@executable_path",
            "-Wl,-rpath,{runtime_build}/bin",
            "-lEGL",
            "-lGLESv2",
            "-framework",
            "Foundation",
            "-framework",
            "IOSurface",
            "-framework",
            "Metal",
        ),
        run_args=("metal",),
        skip_exit_codes=(2,),
        notes="60-frame EGL->IOSurface->Metal GPU blit check (exit 2 -> SKIP).",
    ),
    TestSpec(
        id="shared_gl_frames_test",
        group="gpu_pixel",
        kind="native_cpp",
        source="debug/perf/shared_gl_frames_test.mm",
        compile_sources=("runtime/src/shared_gl_frames.mm", "debug/perf/shared_gl_frames_test.mm"),
        compile_flags=(
            "-std=c++17",
            "-O2",
            "-I{repo}/thirdparty/angle-prebuilt/include",
            "-I{repo}/runtime/src",
            "-L{runtime_build}/bin",
            "-Wl,-rpath,@executable_path",
            "-Wl,-rpath,{runtime_build}/bin",
            "-lEGL",
            "-lGLESv2",
            "-framework",
            "Foundation",
            "-framework",
            "IOSurface",
            "-framework",
            "Metal",
        ),
        run_args=("metal",),
        skip_exit_codes=(2,),
        notes="Shared GL frame pool FIFO, ring capacity, stale tokens, rotation (exit 2 -> SKIP).",
    ),
    TestSpec(
        id="shared_gl_readback_compare",
        group="gpu_pixel",
        kind="native_cpp",
        source="debug/perf/shared_gl_readback_compare.mm",
        compile_sources=(
            "runtime/src/shared_gl_frames.mm",
            "debug/perf/shared_gl_readback_compare.mm",
        ),
        compile_flags=(
            "-std=c++17",
            "-O2",
            "-I{repo}/thirdparty/angle-prebuilt/include",
            "-I{repo}/runtime/src",
            "-L{runtime_build}/bin",
            "-Wl,-rpath,@executable_path",
            "-Wl,-rpath,{runtime_build}/bin",
            "-lEGL",
            "-lGLESv2",
            "-framework",
            "Foundation",
            "-framework",
            "IOSurface",
            "-framework",
            "Metal",
        ),
        run_args=("metal",),
        skip_exit_codes=(2,),
        notes="Pixel-exact equality vs glReadPixels (exit 2 -> SKIP).",
    ),
    TestSpec(
        id="SharedGlFramesJniTest",
        group="gpu_pixel",
        kind="jni_pair",
        source="debug/perf/SharedGlFramesJniTest.java",
        main_class="SharedGlFramesJniTest",
        classpath_mode="both",
        compile_sources=(
            "runtime/src/shared_gl_frames.mm",
            "runtime/src/shared_gl_frames_jni.cpp",
            "debug/perf/shared_gl_frames_jni_test.mm",
        ),
        compile_flags=(
            "-std=c++17",
            "-O2",
            "-shared",
            "-fPIC",
            "-I{jdk_home}/include",
            "-I{jdk_home}/include/darwin",
            "-I{repo}/runtime/src",
            "-I{repo}/thirdparty/angle-prebuilt/include",
            "-L{runtime_build}/bin",
            "-Wl,-rpath,{runtime_build}/bin",
            "-lEGL",
            "-lGLESv2",
            "-framework",
            "Foundation",
            "-framework",
            "IOSurface",
        ),
        notes="JNI SharedGlFrameBridge pool boundaries, EGL pixels, FIFO and lifetime.",
    ),
    TestSpec(
        id="SurfaceTextureNativePixbufTest",
        group="gpu_pixel",
        kind="java",
        source="debug/SurfaceTextureNativePixbufTest.java",
        main_class="SurfaceTextureNativePixbufTest",
        classpath_mode="both",
        jvm_flags=(
            "-Djava.awt.headless=true",
            "-Djava.library.path={runtime_build}/bin",
        ),
        env=(("MACAPK_LOG_LEVEL", "7"),),
        timeout_sec=120,
        notes="Zero-copy CVPixelBuffer/IOSurface GL SurfaceTexture latching & orientation on ANGLE Metal.",
    ),
    TestSpec(
        id="CrossProcessTimestampContractTest",
        group="gpu_pixel",
        kind="java",
        source="debug/CrossProcessTimestampContractTest.java",
        main_class="CrossProcessTimestampContractTest",
        classpath_mode="both",
        jvm_flags=(
            "-Djava.awt.headless=true",
            "-Djava.library.path={runtime_build}/bin",
        ),
        env=(("MACAPK_LOG_LEVEL", "7"),),
        timeout_sec=120,
        notes="2-JVM Mach Binder + IOSurface + ANGLE Metal timestamp contract, oneway FIFO burst & video_stats test.",
    ),
    # ------------------------------------------------------------------
    # (f) codegen_check -- Generator round-trip / attr check (no repo writes)
    # ------------------------------------------------------------------
    TestSpec(
        id="gles_sig_roundtrip",
        group="codegen_check",
        kind="python",
        source="debug/gles_gen/reconstruct_sigs.py",
        run_args=("debug/gles_gen/reconstruct_sigs.py", "--verify"),
        notes="Reconstructs /tmp/glsig signatures and verifies byte-identical GLES/EGL generation.",
    ),
    TestSpec(
        id="gen_framework_attrs_check",
        group="codegen_check",
        kind="python",
        source="debug/gen_framework_attrs.py",
        run_args=("debug/gen_framework_attrs.py", "--check", "--local", "{attrs_final}", "{attrs_staging}"),
        needs_attrs_xml=True,
        notes="Verifies FrameworkAttrIds.java against --attrs-xml <public-final.xml> <public-staging.xml>.",
    ),
    # ------------------------------------------------------------------
    # (g) runtime_fixture -- Real runtime/broker or --apk fixture required
    # ------------------------------------------------------------------
    TestSpec(
        id="RemoteServiceProcessTest",
        group="runtime_fixture",
        kind="python",
        source="debug/run_remote_service_broker_test.py",
        run_args=(
            "debug/run_remote_service_broker_test.py",
            "--runtime-build-dir",
            "{runtime_build}",
        ),
        needs_runtime_broker=True,
        skip_exit_codes=(2,),
        timeout_sec=120,
        notes="Headless macapk-runtime + transient LaunchAgent SystemServer broker isolation test (exit 2 -> SKIP).",
    ),
    TestSpec(
        id="DrawableIdentityAndTintTest",
        group="runtime_fixture",
        kind="java",
        source="debug/DrawableIdentityAndTintTest.java",
        main_class="DrawableIdentityAndTintTest",
        classpath_mode="both",
        jvm_flags=("-Djava.awt.headless=true",),
        env=(("MACAPK_LOG_LEVEL", "7"),),
        run_args=("{apk}",),
        needs_apk=True,
        notes="Requires --apk <path> with >=2 drawable file entries.",
    ),
    TestSpec(
        id="UpstreamPackageFeaturesTest",
        group="runtime_fixture",
        kind="java",
        source="debug/UpstreamPackageFeaturesTest.java",
        main_class="UpstreamPackageFeaturesTest",
        classpath_mode="both",
        jvm_flags=("-Djava.awt.headless=true",),
        env=(("MACAPK_LOG_LEVEL", "7"),),
        run_args=("{apk}",),
        needs_apk=True,
        notes="Requires --apk <path> with <uses-feature> entries.",
    ),
    # ------------------------------------------------------------------
    # (h) installed_bundle -- Opt-in installed-state / dist parity checks
    # ------------------------------------------------------------------
    TestSpec(
        id="dist_parity_check",
        group="installed_bundle",
        kind="shell",
        source="debug/dist_parity.sh",
        run_args=("debug/dist_parity.sh", "{app_bundle}", "{repo}/build/dist/Mac.apk.app"),
        needs_dist_bundle=True,
        stdout_skip_prefixes=("SKIP:",),
        notes="Compares local bundle against build/dist/Mac.apk.app (SKIP: if dist absent).",
    ),
    TestSpec(
        id="installed_staleness_check",
        group="installed_bundle",
        kind="shell",
        source="debug/staleness.sh",
        run_args=("debug/staleness.sh", "--quiet"),
        needs_installed_app=True,
        notes="Checks /Applications/Mac.apk.app, shared runtime, and per-app caches (opt-in only).",
    ),
    TestSpec(
        id="installed_entitlements_check",
        group="installed_bundle",
        kind="shell",
        source="debug/entitlements_check.sh",
        run_args=(
            "debug/entitlements_check.sh",
            "/Applications/Mac.apk.app/Contents/Resources/macapk-runtime",
        ),
        needs_installed_app=True,
        notes="Checks entitlements on /Applications/Mac.apk.app (opt-in only).",
    ),
)


@dataclasses.dataclass
class RuntimeContext:
    repo: pathlib.Path
    runtime_build: pathlib.Path
    app_bundle: pathlib.Path
    jdk_home: pathlib.Path
    javac_version: str
    sdk_root: Optional[str]
    apk_path: Optional[pathlib.Path]
    attrs_final: Optional[pathlib.Path]
    attrs_staging: Optional[pathlib.Path]
    allow_installed: bool


def tail_lines(text: str, limit: int = 40) -> List[str]:
    lines = text.splitlines()
    return lines[-limit:] if len(lines) > limit else lines


def resolve_jdk(repo: pathlib.Path, explicit: Optional[str]) -> Tuple[Optional[pathlib.Path], str]:
    if explicit:
        home = pathlib.Path(explicit).expanduser().resolve()
    else:
        script = repo / "debug" / "jdk25_select.sh"
        proc = subprocess.run(
            ["bash", "-c", f". {shlex.quote(str(script))} && macapk_jdk_home"],
            capture_output=True,
            text=True,
        )
        if proc.returncode == 0 and proc.stdout.strip():
            home = pathlib.Path(proc.stdout.strip())
        elif os.environ.get("JAVA_HOME"):
            home = pathlib.Path(os.environ["JAVA_HOME"])
        else:
            return None, "missing"
    javac = home / "bin" / "javac"
    java = home / "bin" / "java"
    if not (javac.is_file() and java.is_file()):
        return None, f"missing javac/java under {home}"
    ver_proc = subprocess.run([str(javac), "-version"], capture_output=True, text=True)
    ver = (ver_proc.stdout or ver_proc.stderr).strip()
    return home, ver


def probe_sdk_root(repo: pathlib.Path) -> Optional[str]:
    script = repo / "scripts" / "sdk_fallback.sh"
    if not script.is_file() or platform.system() != "Darwin":
        return os.environ.get("SDKROOT")
    proc = subprocess.run(
        ["bash", "-c", f". {shlex.quote(str(script))} && printf '%s' \"${{SDKROOT:-}}\""],
        capture_output=True,
        text=True,
    )
    val = proc.stdout.strip()
    return val or os.environ.get("SDKROOT")


def read_build_identity(ctx: RuntimeContext) -> Dict[str, Any]:
    def parse_kv(path: pathlib.Path) -> Optional[Dict[str, str]]:
        if not path.is_file():
            return None
        out: Dict[str, str] = {}
        for line in path.read_text(errors="replace").splitlines():
            if "=" in line:
                k, v = line.split("=", 1)
                out[k.strip()] = v.strip()
        return out

    git_head = "unknown"
    git_proc = subprocess.run(
        ["git", "-C", str(ctx.repo), "rev-parse", "HEAD"],
        capture_output=True,
        text=True,
    )
    if git_proc.returncode == 0:
        git_head = git_proc.stdout.strip()

    return {
        "git_head": git_head,
        "jdk_home": str(ctx.jdk_home) if ctx.jdk_home else None,
        "javac_version": ctx.javac_version,
        "runtime_build_id": parse_kv(ctx.runtime_build / "build-id.txt"),
        "bundle_build_id": parse_kv(ctx.app_bundle / "Contents" / "Resources" / "build-id.txt"),
    }


def check_precondition(spec: TestSpec, ctx: RuntimeContext) -> Optional[str]:
    src = ctx.repo / spec.source
    if not src.is_file():
        return f"source file missing: {spec.source}"

    if spec.kind in ("java", "jni_pair"):
        if ctx.jdk_home is None:
            return f"JDK unavailable ({ctx.javac_version})"
        fw_jar = ctx.runtime_build / "lib" / "android-framework.jar"
        loader_jar = ctx.runtime_build / "lib" / "macapk-loader.jar"
        if not fw_jar.is_file():
            return f"missing {fw_jar}"
        if spec.classpath_mode == "both" and not loader_jar.is_file():
            return f"missing {loader_jar}"
        if spec.id == "SurfaceTextureNativePixbufTest":
            if not shutil.which("clang++"):
                return "clang++ not found on PATH"
            egl = ctx.runtime_build / "bin" / "libEGL.dylib"
            gles = ctx.runtime_build / "bin" / "libGLESv2.dylib"
            if not egl.is_file() or not gles.is_file():
                return f"missing ANGLE dylibs under {ctx.runtime_build / 'bin'}"
        if spec.id == "AgslRenderingSmoke":
            patch_dir = ctx.runtime_build / "lib" / "jdk_unsupported_patch"
            skiko = ctx.runtime_build / "bin" / "libskiko-macos-arm64.dylib"
            metal = ctx.runtime_build / "bin" / "libmacapk-skia-metal.dylib"
            if not patch_dir.is_dir():
                return f"missing {patch_dir}"
            if not skiko.is_file() or not metal.is_file():
                return f"missing Skia Metal dylibs under {ctx.runtime_build / 'bin'}"

    if spec.kind in ("native_cpp", "jni_pair") and not shutil.which("clang++"):
        return "clang++ not found on PATH"
    if spec.kind == "native_swift" and not shutil.which("swiftc"):
        return "swiftc not found on PATH"

    if spec.group == "worktree_bundle" and not ctx.app_bundle.is_dir():
        return f"missing local app bundle: {ctx.app_bundle}"
    if spec.id == "worktree_entitlements_check":
        rt_bin = ctx.app_bundle / "Contents" / "Resources" / "macapk-runtime"
        if not rt_bin.is_file():
            return f"missing bundle runtime binary: {rt_bin}"

    if spec.needs_apk:
        if not ctx.apk_path:
            return "no --apk fixture provided"
        if not ctx.apk_path.is_file():
            return f"--apk file not found: {ctx.apk_path}"

    if spec.needs_attrs_xml:
        if not ctx.attrs_final or not ctx.attrs_staging:
            return "no --attrs-xml <public-final.xml> <public-staging.xml> provided"
        if not ctx.attrs_final.is_file() or not ctx.attrs_staging.is_file():
            return "one or both --attrs-xml files not found"

    if spec.needs_runtime_broker:
        rt_bin = ctx.runtime_build / "bin" / "macapk-runtime"
        if not rt_bin.is_file():
            return f"missing {rt_bin}"

    if spec.needs_dist_bundle:
        dist = ctx.repo / "build" / "dist" / "Mac.apk.app"
        if not dist.is_dir():
            return f"no dist bundle at {dist}"

    if spec.needs_installed_app:
        if not ctx.allow_installed:
            return "installed-state test requires explicit --allow-installed"
        if not pathlib.Path("/Applications/Mac.apk.app").is_dir():
            return "no canonical app at /Applications/Mac.apk.app"

    return None


def fmt_token(token: str, ctx: RuntimeContext, extra: Dict[str, str]) -> str:
    mapping = {
        "repo": str(ctx.repo),
        "runtime_build": str(ctx.runtime_build),
        "app_bundle": str(ctx.app_bundle),
        "jdk_home": str(ctx.jdk_home) if ctx.jdk_home else "",
        "apk": str(ctx.apk_path) if ctx.apk_path else "",
        "attrs_final": str(ctx.attrs_final) if ctx.attrs_final else "",
        "attrs_staging": str(ctx.attrs_staging) if ctx.attrs_staging else "",
    }
    mapping.update(extra)
    return token.format(**mapping)


def run_step(
    cmd: List[str],
    cwd: pathlib.Path,
    env: Dict[str, str],
    timeout_sec: int,
) -> Tuple[int, str, str, float, bool]:
    start = time.monotonic()
    try:
        proc = subprocess.run(
            cmd,
            cwd=str(cwd),
            env=env,
            capture_output=True,
            text=True,
            timeout=timeout_sec,
        )
        elapsed_ms = (time.monotonic() - start) * 1000.0
        return proc.returncode, proc.stdout, proc.stderr, elapsed_ms, False
    except subprocess.TimeoutExpired as exc:
        elapsed_ms = (time.monotonic() - start) * 1000.0
        stdout = exc.stdout if isinstance(exc.stdout, str) else (exc.stdout or b"").decode(errors="replace")
        stderr = exc.stderr if isinstance(exc.stderr, str) else (exc.stderr or b"").decode(errors="replace")
        return 124, stdout, stderr + f"\nTIMEOUT after {timeout_sec}s\n", elapsed_ms, True


def run_single_attempt(spec: TestSpec, ctx: RuntimeContext) -> Dict[str, Any]:
    scratch = pathlib.Path(tempfile.mkdtemp(prefix=f"macapk_test_{spec.id}_"))
    try:
        env = os.environ.copy()
        if ctx.sdk_root:
            env["SDKROOT"] = ctx.sdk_root
        for k, v in spec.env:
            env[k] = v

        extra: Dict[str, str] = {}
        compile_cmds: List[List[str]] = []
        run_cmd: List[str] = []

        if spec.kind in ("java", "jni_pair"):
            classes_dir = scratch / "classes"
            classes_dir.mkdir(parents=True, exist_ok=True)
            fw_jar = ctx.runtime_build / "lib" / "android-framework.jar"
            loader_jar = ctx.runtime_build / "lib" / "macapk-loader.jar"
            cp = str(fw_jar) if spec.classpath_mode == "fw" else f"{fw_jar}:{loader_jar}"

            if spec.kind == "jni_pair":
                out_lib = scratch / "libtest_jni.dylib"
                extra["out_lib"] = str(out_lib)
                cxx_cmd = [
                    "clang++",
                    *(fmt_token(f, ctx, extra) for f in spec.compile_flags),
                    *(str(ctx.repo / s) for s in spec.compile_sources),
                    "-o",
                    str(out_lib),
                ]
                compile_cmds.append(cxx_cmd)

            javac = str(ctx.jdk_home / "bin" / "javac")
            java = str(ctx.jdk_home / "bin" / "java")
            compile_cmds.append([javac, "-cp", cp, "-d", str(classes_dir), str(ctx.repo / spec.source)])

            jvm_flags = [fmt_token(f, ctx, extra) for f in spec.jvm_flags]
            if spec.needs_isolated_home:
                fake_home = scratch / "home"
                fake_home.mkdir(parents=True, exist_ok=True)
                env["HOME"] = str(fake_home)
                jvm_flags.append(f"-Duser.home={fake_home}")

            run_cmd = [java, *jvm_flags, "-cp", f"{classes_dir}:{cp}", spec.main_class]
            if spec.kind == "jni_pair":
                run_cmd.append(extra["out_lib"])
            if spec.needs_temp_arg:
                tmp_arg = scratch / "arg_tmp"
                tmp_arg.mkdir(parents=True, exist_ok=True)
                run_cmd.append(str(tmp_arg))
            run_cmd.extend(fmt_token(a, ctx, extra) for a in spec.run_args)

        elif spec.kind == "native_cpp":
            out_bin = scratch / spec.id
            extra["out_bin"] = str(out_bin)
            compile_cmds.append([
                "clang++",
                *(fmt_token(f, ctx, extra) for f in spec.compile_flags),
                *(str(ctx.repo / s) for s in spec.compile_sources),
                "-o",
                str(out_bin),
            ])
            run_cmd = [str(out_bin), *(fmt_token(a, ctx, extra) for a in spec.run_args)]

        elif spec.kind == "native_swift":
            out_bin = scratch / spec.id
            extra["out_bin"] = str(out_bin)
            compile_cmds.append([
                "swiftc",
                *(fmt_token(f, ctx, extra) for f in spec.compile_flags),
                *(str(ctx.repo / s) for s in spec.compile_sources),
                "-o",
                str(out_bin),
            ])
            run_cmd = [str(out_bin), *(fmt_token(a, ctx, extra) for a in spec.run_args)]

        elif spec.kind == "python":
            args = [fmt_token(a, ctx, extra) for a in spec.run_args]
            run_cmd = [sys.executable, str(ctx.repo / args[0]), *args[1:]]

        elif spec.kind == "shell":
            args = [fmt_token(a, ctx, extra) for a in spec.run_args]
            if args[0] == "codesign":
                run_cmd = args
            else:
                run_cmd = ["bash", str(ctx.repo / args[0]), *args[1:]]

        else:
            return {
                "stage": "dispatch",
                "status": "FAIL",
                "exit_code": 1,
                "duration_ms": 0.0,
                "compile_commands": [],
                "run_command": [],
                "stdout": "",
                "stderr": f"unsupported kind: {spec.kind}",
            }

        total_ms = 0.0
        combined_out: List[str] = []
        combined_err: List[str] = []

        for ccmd in compile_cmds:
            rc, out, err, ms, timed_out = run_step(ccmd, ctx.repo, env, spec.timeout_sec)
            total_ms += ms
            if out:
                combined_out.append(out)
            if err:
                combined_err.append(err)
            if rc != 0:
                return {
                    "stage": "compile",
                    "status": "FAIL",
                    "exit_code": rc,
                    "timed_out": timed_out,
                    "duration_ms": round(total_ms, 2),
                    "compile_commands": compile_cmds,
                    "run_command": run_cmd,
                    "stdout": "\n".join(combined_out),
                    "stderr": "\n".join(combined_err),
                }

        rc, out, err, ms, timed_out = run_step(run_cmd, ctx.repo, env, spec.timeout_sec)
        total_ms += ms
        if out:
            combined_out.append(out)
        if err:
            combined_err.append(err)
        full_out = "\n".join(combined_out)
        full_err = "\n".join(combined_err)

        status = "PASS"
        reason = None
        if rc in spec.skip_exit_codes:
            status = "SKIP"
            reason = f"exit code {rc} indicates unsupported environment"
        elif rc == 0 and any(line.startswith(spec.stdout_skip_prefixes) for line in full_out.splitlines() if spec.stdout_skip_prefixes):
            status = "SKIP"
            reason = "stdout reported SKIP:"
        elif rc != 0:
            status = "FAIL"
            reason = f"non-zero exit code {rc}"

        return {
            "stage": "run",
            "status": status,
            "reason": reason,
            "exit_code": rc,
            "timed_out": timed_out,
            "duration_ms": round(total_ms, 2),
            "compile_commands": compile_cmds,
            "run_command": run_cmd,
            "stdout": full_out,
            "stderr": full_err,
        }
    finally:
        shutil.rmtree(scratch, ignore_errors=True)


def execute_test(
    spec: TestSpec,
    ctx: RuntimeContext,
    retries: int,
    logs_dir: pathlib.Path,
) -> Dict[str, Any]:
    pre_err = check_precondition(spec, ctx)
    if pre_err is not None:
        return {
            "id": spec.id,
            "group": spec.group,
            "kind": spec.kind,
            "source": spec.source,
            "status": "SKIP",
            "reason": pre_err,
            "exit_code": None,
            "duration_ms": 0.0,
            "retry_count": 0,
            "first_failure": None,
            "attempts": [],
            "command": None,
            "log_path": None,
        }

    attempts: List[Dict[str, Any]] = []
    first_failure: Optional[Dict[str, Any]] = None
    log_sections: List[str] = []

    for idx in range(1, retries + 2):
        res = run_single_attempt(spec, ctx)
        attempt_rec = {
            "attempt": idx,
            "stage": res["stage"],
            "status": res["status"],
            "exit_code": res["exit_code"],
            "duration_ms": res["duration_ms"],
            "reason": res.get("reason"),
            "stdout_tail": tail_lines(res["stdout"]),
            "stderr_tail": tail_lines(res["stderr"]),
        }
        attempts.append(attempt_rec)
        log_sections.append(
            f"=== Attempt {idx} ({res['stage']}) status={res['status']} rc={res['exit_code']} duration_ms={res['duration_ms']} ===\n"
            f"compile: {res['compile_commands']}\n"
            f"run: {res['run_command']}\n"
            f"--- stdout ---\n{res['stdout']}\n"
            f"--- stderr ---\n{res['stderr']}\n"
        )
        if res["status"] == "FAIL" and first_failure is None:
            first_failure = {
                "attempt": idx,
                "stage": res["stage"],
                "exit_code": res["exit_code"],
                "reason": res.get("reason"),
                "stdout_tail": attempt_rec["stdout_tail"],
                "stderr_tail": attempt_rec["stderr_tail"],
            }
        if res["status"] in ("PASS", "SKIP"):
            break

    last = attempts[-1]
    final_status = last["status"]
    if final_status == "PASS" and first_failure is not None:
        final_status = "FLAKY_PASS"

    log_file = logs_dir / f"{spec.id}.log"
    log_file.write_text("\n".join(log_sections), encoding="utf-8")

    cmd_str = " ".join(shlex.quote(x) for x in (res["run_command"] or []))
    return {
        "id": spec.id,
        "group": spec.group,
        "kind": spec.kind,
        "source": spec.source,
        "status": final_status,
        "reason": last.get("reason"),
        "exit_code": last["exit_code"],
        "duration_ms": round(sum(a["duration_ms"] for a in attempts), 2),
        "retry_count": len(attempts) - 1,
        "first_failure": first_failure,
        "attempts": attempts,
        "command": cmd_str,
        "log_path": str(log_file),
    }


def write_junit_xml(results: Sequence[Dict[str, Any]], out_path: pathlib.Path) -> None:
    suite = ET.Element("testsuite")
    suite.set("name", "Mac.apk")
    suite.set("tests", str(len(results)))
    suite.set("failures", str(sum(1 for r in results if r["status"] == "FAIL")))
    suite.set("skipped", str(sum(1 for r in results if r["status"] in ("SKIP", "NOT_RUN"))))
    total_sec = sum(r["duration_ms"] for r in results) / 1000.0
    suite.set("time", f"{total_sec:.3f}")

    for r in results:
        tc = ET.SubElement(suite, "testcase")
        tc.set("classname", r["group"])
        tc.set("name", r["id"])
        tc.set("time", f"{r['duration_ms'] / 1000.0:.3f}")
        status = r["status"]
        if status == "FAIL":
            fail = ET.SubElement(tc, "failure")
            fail.set("message", r.get("reason") or "test failed")
            att = r["attempts"][-1] if r["attempts"] else {}
            fail.text = "\n".join(att.get("stderr_tail", []) + att.get("stdout_tail", []))
        elif status in ("SKIP", "NOT_RUN"):
            sk = ET.SubElement(tc, "skipped")
            sk.set("message", f"{status}: {r.get('reason') or ''}")
        elif status == "FLAKY_PASS":
            so = ET.SubElement(tc, "system-out")
            so.text = f"FLAKY_PASS after {r['retry_count']} retry(s); first failure: {r['first_failure']}"

    tree = ET.ElementTree(suite)
    tree.write(out_path, encoding="utf-8", xml_declaration=True)


def render_summary(report: Dict[str, Any]) -> str:
    counts = report["counts"]
    ident = report["identity"]
    lines = [
        f"Mac.apk Test Report ({report['timestamp']})",
        f"Git HEAD: {ident.get('git_head')} | JDK: {ident.get('javac_version')}",
        f"Runtime Build ID: {ident.get('runtime_build_id')}",
        f"Bundle Build ID:  {ident.get('bundle_build_id')}",
        f"Selected groups:  {', '.join(report['selected_groups'])}",
        (
            "Counts: "
            f"PASS={counts['PASS']} FLAKY_PASS={counts['FLAKY_PASS']} "
            f"FAIL={counts['FAIL']} SKIP={counts['SKIP']} NOT_RUN={counts['NOT_RUN']}"
        ),
        f"Exit code: {report['overall_exit_code']}",
        "",
    ]
    for r in report["results"]:
        rc_str = "-" if r["exit_code"] is None else str(r["exit_code"])
        line = f"[{r['status']:10s}] {r['group']:20s} {r['id']:38s} rc={rc_str:>3s} {r['duration_ms']:7.1f}ms"
        if r["status"] == "FLAKY_PASS":
            ff = r["first_failure"] or {}
            line += f" (flaky: attempt 1 rc={ff.get('exit_code')}, retries={r['retry_count']})"
        elif r.get("reason"):
            line += f" -- {r['reason']}"
        lines.append(line)
    return "\n".join(lines) + "\n"


def resolve_groups(raw_groups: Sequence[str]) -> List[str]:
    if not raw_groups:
        return list(OFFLINE_GROUPS)
    out: List[str] = []
    for item in raw_groups:
        for part in item.split(","):
            name = part.strip()
            if not name:
                continue
            if name == "offline":
                for g in OFFLINE_GROUPS:
                    if g not in out:
                        out.append(g)
            elif name == "all":
                for g in ALL_GROUPS:
                    if g not in out:
                        out.append(g)
            elif name in ALL_GROUPS:
                if name not in out:
                    out.append(name)
            else:
                raise ValueError(f"unknown group '{name}' (valid: offline, all, {', '.join(ALL_GROUPS)})")
    return out


def compute_exit_code(
    results: Sequence[Dict[str, Any]],
    selected_groups: Sequence[str],
    strict: bool,
) -> int:
    if any(r["status"] == "FAIL" for r in results):
        return 1
    req_groups = set(selected_groups) if strict else (set(selected_groups) & REQUIRED_WHEN_SELECTED)
    if any(r["status"] == "SKIP" and r["group"] in req_groups for r in results):
        return 2
    return 0


def run_suite(
    specs: Sequence[TestSpec],
    ctx: RuntimeContext,
    selected_groups: Sequence[str],
    selected_tests: Sequence[str],
    retries: int,
    strict: bool,
    out_dir: pathlib.Path,
) -> Tuple[int, Dict[str, Any]]:
    out_dir.mkdir(parents=True, exist_ok=True)
    logs_dir = out_dir / "logs"
    logs_dir.mkdir(parents=True, exist_ok=True)

    wanted_ids = set(selected_tests)
    selected_set = set(selected_groups)
    results: List[Dict[str, Any]] = []

    for spec in specs:
        should_run = (spec.id in wanted_ids) if wanted_ids else (spec.group in selected_set)
        if should_run:
            results.append(execute_test(spec, ctx, retries=retries, logs_dir=logs_dir))
        else:
            results.append(
                {
                    "id": spec.id,
                    "group": spec.group,
                    "kind": spec.kind,
                    "source": spec.source,
                    "status": "NOT_RUN",
                    "reason": "not in selected group/tests",
                    "exit_code": None,
                    "duration_ms": 0.0,
                    "retry_count": 0,
                    "first_failure": None,
                    "attempts": [],
                    "command": None,
                    "log_path": None,
                }
            )

    counts = {k: 0 for k in ("PASS", "FLAKY_PASS", "FAIL", "SKIP", "NOT_RUN")}
    for r in results:
        counts[r["status"]] = counts.get(r["status"], 0) + 1

    overall_rc = compute_exit_code(results, selected_groups, strict=strict)
    report: Dict[str, Any] = {
        "timestamp": datetime.datetime.now(datetime.timezone.utc).isoformat(),
        "selected_groups": list(selected_groups),
        "selected_tests": list(selected_tests),
        "retries": retries,
        "strict": strict,
        "identity": read_build_identity(ctx),
        "counts": counts,
        "overall_exit_code": overall_rc,
        "results": results,
    }

    (out_dir / "report.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    (out_dir / "summary.txt").write_text(render_summary(report), encoding="utf-8")
    write_junit_xml(results, out_dir / "report.xml")
    return overall_rc, report


def run_self_test(repo: pathlib.Path, ctx: RuntimeContext) -> int:
    """Isolated negative/retry/launcher self-tests in a temporary directory."""
    tmp = pathlib.Path(tempfile.mkdtemp(prefix="macapk_runner_selftest_"))
    try:
        checks: List[Tuple[str, bool, str]] = []

        # 1. Assertion failure must propagate FAIL and exit code 1.
        fail_py = tmp / "fail_assert.py"
        fail_py.write_text("raise AssertionError('controlled failure')\n")
        tmp_ctx = dataclasses.replace(ctx, repo=tmp)
        spec_fail = TestSpec(
            id="selftest_py_fail",
            group="native_unit",
            kind="python",
            source="fail_assert.py",
            run_args=("fail_assert.py",),
            notes="self-test assertion failure",
        )
        rc1, rep1 = run_suite([spec_fail], tmp_ctx, ["native_unit"], [], 0, False, tmp / "out1")
        ok1 = rc1 == 1 and rep1["counts"]["FAIL"] == 1
        checks.append(("assertion_failure_propagates", ok1, f"rc={rc1}, counts={rep1['counts']}"))

        # 2. Missing required precondition must report SKIP and exit 2 (never 0).
        bad_ctx = dataclasses.replace(ctx, runtime_build=tmp / "missing_runtime_build")
        bare_sample = next(t for t in TESTS if t.id == "ParcelWireTest")
        rc2, rep2 = run_suite([bare_sample], bad_ctx, ["bare_jvm"], [], 0, False, tmp / "out2")
        ok2 = rc2 == 2 and rep2["counts"]["SKIP"] == 1 and rep2["counts"]["PASS"] == 0
        checks.append(("missing_required_precondition_fails_nonzero", ok2, f"rc={rc2}, counts={rep2['counts']}"))

        # 3. stdout "SKIP:" with exit 0 must classify as SKIP and fail under --strict.
        skip_sh = tmp / "skip_zero.sh"
        skip_sh.write_text("#!/bin/bash\necho 'SKIP: missing dist bundle'\nexit 0\n")
        spec_skip = TestSpec(
            id="selftest_stdout_skip",
            group="installed_bundle",
            kind="shell",
            source="skip_zero.sh",
            run_args=("skip_zero.sh",),
            stdout_skip_prefixes=("SKIP:",),
            notes="self-test stdout SKIP",
        )
        rc3, rep3 = run_suite([spec_skip], tmp_ctx, ["installed_bundle"], [], 0, True, tmp / "out3")
        ok3 = rc3 == 2 and rep3["counts"]["SKIP"] == 1 and rep3["counts"]["PASS"] == 0
        checks.append(("stdout_skip_not_masked_as_pass", ok3, f"rc={rc3}, counts={rep3['counts']}"))

        # 4. Flaky test with --retries 1 preserves first failure and marks FLAKY_PASS.
        flaky_py = tmp / "flaky.py"
        marker = tmp / "flaky_once.marker"
        flaky_py.write_text(
            f"import pathlib, sys\n"
            f"m = pathlib.Path({str(marker)!r})\n"
            f"if not m.exists():\n"
            f"    m.write_text('failed_once')\n"
            f"    print('first attempt failed', file=sys.stderr)\n"
            f"    sys.exit(3)\n"
            f"print('second attempt passed')\n"
        )
        spec_flaky = TestSpec(
            id="selftest_flaky",
            group="native_unit",
            kind="python",
            source="flaky.py",
            run_args=("flaky.py",),
            notes="self-test flaky retry",
        )
        rc4, rep4 = run_suite([spec_flaky], tmp_ctx, ["native_unit"], [], 1, False, tmp / "out4")
        r4 = rep4["results"][0]
        ok4 = (
            rc4 == 0
            and r4["status"] == "FLAKY_PASS"
            and r4["retry_count"] == 1
            and r4["first_failure"] is not None
            and r4["first_failure"]["exit_code"] == 3
            and len(r4["attempts"]) == 2
        )
        checks.append(("flaky_preserves_first_failure", ok4, f"rc={rc4}, status={r4['status']}, ff={r4['first_failure']}"))

        # 5. OwnerFrameBarrierTest single-file source launcher must fail with IllegalAccessError,
        # while compile-then-run passes.
        fw_jar = ctx.runtime_build / "lib" / "android-framework.jar"
        if ctx.jdk_home and fw_jar.is_file():
            java_bin = str(ctx.jdk_home / "bin" / "java")
            src_rc, src_out, src_err, _, _ = run_step(
                [java_bin, "-cp", str(fw_jar), str(repo / "debug" / "OwnerFrameBarrierTest.java")],
                repo,
                {**os.environ, "MACAPK_LOG_LEVEL": "7"},
                30,
            )
            ok5 = src_rc != 0 and "IllegalAccessError" in (src_out + src_err)
            checks.append(("owner_frame_source_launcher_rejected", ok5, f"rc={src_rc}"))
        else:
            checks.append(("owner_frame_source_launcher_rejected", False, "JDK or framework jar missing"))

        all_ok = all(ok for _, ok, _ in checks)
        for name, ok, detail in checks:
            print(f"[{'PASS' if ok else 'FAIL'}] {name}: {detail}")
        return 0 if all_ok else 1
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


def parse_args(argv: Sequence[str]) -> argparse.Namespace:
    p = argparse.ArgumentParser(description="Unified categorized test runner for Mac.apk")
    p.add_argument("--list", action="store_true", help="List all tests and categories without running")
    p.add_argument(
        "--group",
        action="append",
        default=[],
        help=f"Group(s) to run (default: offline). Valid: offline, all, {', '.join(ALL_GROUPS)}",
    )
    p.add_argument("--test", action="append", default=[], help="Specific test ID(s) to run")
    p.add_argument("--retries", type=int, default=0, help="Retries on FAIL (records first failure + history)")
    p.add_argument("--strict", action="store_true", help="Exit non-zero if any selected test is SKIP")
    p.add_argument("--apk", type=str, default=None, help="APK fixture path for runtime_fixture tests")
    p.add_argument(
        "--attrs-xml",
        nargs=2,
        metavar=("PUBLIC_FINAL", "PUBLIC_STAGING"),
        default=None,
        help="Local AOSP public XML files for gen_framework_attrs_check",
    )
    p.add_argument("--runtime-build-dir", type=str, default=None, help="Override runtime/build dir")
    p.add_argument("--app-bundle", type=str, default=None, help="Override build/Mac.apk.app path")
    p.add_argument("--jdk-home", type=str, default=None, help="Override JDK home")
    p.add_argument("--out-dir", type=str, default=None, help="Report output dir (default: work/upgrade/TEST-001)")
    p.add_argument(
        "--allow-installed",
        action="store_true",
        help="Allow opt-in installed-state checks touching /Applications/Mac.apk.app or shared runtime",
    )
    p.add_argument("--self-test", action="store_true", help="Run isolated runner self-tests and exit")
    return p.parse_args(argv)


def main(argv: Sequence[str]) -> int:
    args = parse_args(argv)
    repo = pathlib.Path(__file__).resolve().parents[1]

    if args.list:
        for spec in TESTS:
            print(f"{spec.group:22s} {spec.id:38s} {spec.kind:15s} {spec.source} -- {spec.notes}")
        print(f"Total registered tests: {len(TESTS)}")
        return 0

    try:
        selected_groups = resolve_groups(args.group)
    except ValueError as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 2

    known_ids = {t.id for t in TESTS}
    for tid in args.test:
        if tid not in known_ids:
            print(f"ERROR: unknown test id '{tid}' (use --list)", file=sys.stderr)
            return 2

    jdk_home, javac_ver = resolve_jdk(repo, args.jdk_home)
    ctx = RuntimeContext(
        repo=repo,
        runtime_build=pathlib.Path(args.runtime_build_dir).resolve() if args.runtime_build_dir else repo / "runtime" / "build",
        app_bundle=pathlib.Path(args.app_bundle).resolve() if args.app_bundle else repo / "build" / "Mac.apk.app",
        jdk_home=jdk_home,
        javac_version=javac_ver,
        sdk_root=probe_sdk_root(repo),
        apk_path=pathlib.Path(args.apk).resolve() if args.apk else None,
        attrs_final=pathlib.Path(args.attrs_xml[0]).resolve() if args.attrs_xml else None,
        attrs_staging=pathlib.Path(args.attrs_xml[1]).resolve() if args.attrs_xml else None,
        allow_installed=args.allow_installed,
    )

    if args.self_test:
        return run_self_test(repo, ctx)

    out_dir = pathlib.Path(args.out_dir).resolve() if args.out_dir else repo / "work" / "upgrade" / "TEST-001"
    rc, report = run_suite(
        TESTS,
        ctx,
        selected_groups=selected_groups,
        selected_tests=args.test,
        retries=max(0, args.retries),
        strict=args.strict,
        out_dir=out_dir,
    )
    print((out_dir / "summary.txt").read_text(encoding="utf-8"), end="")
    print(f"JSON report:  {out_dir / 'report.json'}")
    print(f"JUnit XML:    {out_dir / 'report.xml'}")
    return rc


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
