#!/usr/bin/env bash
# ci_gate.sh -- Unified regression gate entrypoint for local dev and CI.
#
# Calls scripts/run_tests.py with --strict so both FAIL (exit 1) and any
# SKIP / missing precondition in selected groups (exit 2) fail non-zero.
# Also checks upfront that required built artifacts exist under runtime/build
# so an unbuilt tree never silently degrades into SKIP/PASS.
#
# Usage:
#   scripts/ci_gate.sh [options]
#
# Options:
#   --group <name>              Group(s) to run, repeatable or comma-separated
#                               (default: offline; e.g. bare_jvm,jvm_launch_sensitive)
#   --test <id>                 Specific test ID(s) within selected group(s), repeatable
#   --runtime-build-dir <dir>   Override runtime/build path
#   --app-bundle <dir>          Override build/Mac.apk.app path
#   --jdk-home <dir>            Override JDK home passed to run_tests.py
#   --out-dir <dir>             Report output directory
#                               (default: work/upgrade/CI-001/gate)
#   --retries <N>               Retries passed to run_tests.py (default: 0)
#   -h, --help                  Show this help and exit

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

RUNTIME_BUILD_DIR="${MACAPK_RUNTIME_BUILD_DIR:-$REPO_ROOT/runtime/build}"
APP_BUNDLE="${MACAPK_APP_BUNDLE:-$REPO_ROOT/build/Mac.apk.app}"
JDK_HOME_ARG="${MACAPK_JDK_HOME:-}"
OUT_DIR="${MACAPK_CI_OUT_DIR:-$REPO_ROOT/work/upgrade/CI-001/gate}"
RETRIES="${MACAPK_CI_RETRIES:-0}"

declare -a SELECTED_GROUPS=()
declare -a SELECTED_TESTS=()

usage() {
    sed -n '2,23p' "$0" | sed 's/^# \{0,1\}//'
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --group)
            [[ $# -ge 2 ]] || { echo "[ci_gate] ERROR: --group requires an argument" >&2; exit 2; }
            SELECTED_GROUPS+=("$2")
            shift 2
            ;;
        --test)
            [[ $# -ge 2 ]] || { echo "[ci_gate] ERROR: --test requires an argument" >&2; exit 2; }
            SELECTED_TESTS+=("$2")
            shift 2
            ;;
        --runtime-build-dir)
            [[ $# -ge 2 ]] || { echo "[ci_gate] ERROR: --runtime-build-dir requires an argument" >&2; exit 2; }
            RUNTIME_BUILD_DIR="$2"
            shift 2
            ;;
        --app-bundle)
            [[ $# -ge 2 ]] || { echo "[ci_gate] ERROR: --app-bundle requires an argument" >&2; exit 2; }
            APP_BUNDLE="$2"
            shift 2
            ;;
        --jdk-home)
            [[ $# -ge 2 ]] || { echo "[ci_gate] ERROR: --jdk-home requires an argument" >&2; exit 2; }
            JDK_HOME_ARG="$2"
            shift 2
            ;;
        --out-dir)
            [[ $# -ge 2 ]] || { echo "[ci_gate] ERROR: --out-dir requires an argument" >&2; exit 2; }
            OUT_DIR="$2"
            shift 2
            ;;
        --retries)
            [[ $# -ge 2 ]] || { echo "[ci_gate] ERROR: --retries requires an argument" >&2; exit 2; }
            RETRIES="$2"
            shift 2
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            echo "[ci_gate] ERROR: unknown argument: $1" >&2
            usage >&2
            exit 2
            ;;
    esac
done

if [[ ${#SELECTED_GROUPS[@]} -eq 0 ]]; then
    SELECTED_GROUPS=("${MACAPK_CI_GROUP:-offline}")
fi

if ! command -v python3 >/dev/null 2>&1; then
    echo "[ci_gate] ERROR: python3 is required to run scripts/run_tests.py" >&2
    exit 2
fi

RUNNER="$REPO_ROOT/scripts/run_tests.py"
if [[ ! -f "$RUNNER" ]]; then
    echo "[ci_gate] ERROR: missing test runner at $RUNNER" >&2
    exit 2
fi

# Determine which built runtime/build artifacts are required for the selected groups.
needs_java_jars=0
needs_native_runtime=0
for raw in "${SELECTED_GROUPS[@]}"; do
    IFS=',' read -ra parts <<< "$raw"
    for g in "${parts[@]}"; do
        case "$g" in
            offline|all)
                needs_java_jars=1
                needs_native_runtime=1
                ;;
            bare_jvm|jvm_launch_sensitive|gpu_pixel|runtime_fixture)
                needs_java_jars=1
                ;;
            worktree_bundle)
                needs_native_runtime=1
                ;;
            native_unit|codegen_check|installed_bundle)
                ;;
            "")
                ;;
            *)
                echo "[ci_gate] ERROR: unknown group '$g'" >&2
                exit 2
                ;;
        esac
    done
done

missing_build=0
if [[ "$needs_java_jars" -eq 1 ]]; then
    for rel in "lib/android-framework.jar" "lib/macapk-loader.jar"; do
        if [[ ! -f "$RUNTIME_BUILD_DIR/$rel" ]]; then
            echo "[ci_gate] ERROR: missing built artifact: $RUNTIME_BUILD_DIR/$rel" >&2
            missing_build=1
        fi
    done
fi
if [[ "$needs_native_runtime" -eq 1 ]]; then
    if [[ ! -x "$RUNTIME_BUILD_DIR/bin/macapk-runtime" ]]; then
        echo "[ci_gate] ERROR: missing built native runtime: $RUNTIME_BUILD_DIR/bin/macapk-runtime" >&2
        missing_build=1
    fi
fi

if [[ "$missing_build" -ne 0 ]]; then
    echo "[ci_gate] Build the tree first (worktree-safe, no bundle sync):" >&2
    echo "  MACAPK_NO_BUNDLE_SYNC=1 runtime/scripts/build.sh" >&2
    echo "  MACAPK_NO_BUNDLE_SYNC=1 scripts/build_launcher.sh" >&2
    echo "  (or Java-only for bare_jvm/jvm_launch_sensitive: MACAPK_JAVA_NOLOCK=1 debug/build_java_only.sh)" >&2
    exit 2
fi

if [[ -f "$RUNTIME_BUILD_DIR/build-id.txt" ]]; then
    echo "[ci_gate] runtime/build identity ($(tr '\n' ' ' < "$RUNTIME_BUILD_DIR/build-id.txt"))"
fi

declare -a CMD=(
    python3 "$RUNNER"
    --strict
    --runtime-build-dir "$RUNTIME_BUILD_DIR"
    --app-bundle "$APP_BUNDLE"
    --out-dir "$OUT_DIR"
    --retries "$RETRIES"
)

for g in "${SELECTED_GROUPS[@]}"; do
    CMD+=(--group "$g")
done
for t in ${SELECTED_TESTS[@]+"${SELECTED_TESTS[@]}"}; do
    CMD+=(--test "$t")
done
if [[ -n "$JDK_HOME_ARG" ]]; then
    CMD+=(--jdk-home "$JDK_HOME_ARG")
fi

if [[ -f "$REPO_ROOT/scripts/check_doc_sync.py" ]]; then
    echo "[ci_gate] Checking minimal 1+1 doc sync & links..."
    python3 "$REPO_ROOT/scripts/check_doc_sync.py" --mode worktree --cwd "$REPO_ROOT"
fi

echo "[ci_gate] Running: ${CMD[*]}"
"${CMD[@]}"
