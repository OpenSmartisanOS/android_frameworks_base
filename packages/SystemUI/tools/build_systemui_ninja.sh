#!/usr/bin/env bash

# Rebuild SystemUI directly from an existing Soong/Ninja graph.
# This intentionally does not regenerate the graph; run a normal lunch/m SystemUI once after
# Android.bp/AndroidManifest/resource-list changes, then use this helper for source iterations.

set -euo pipefail

mode="${1:-core}"
product="${2:-timelm}"
jobs="${3:-32}"
script_dir="$(cd "$(dirname "$0")" && pwd)"
android_root="$(cd "$script_dir/../../../.." && pwd)"
graph="$android_root/out/combined-lineage_${product}.ninja"
ninja="$android_root/prebuilts/build-tools/linux-x86/bin/ninja"
jdk="$android_root/prebuilts/jdk/jdk21/linux-x86"

if [[ ! -f "$graph" ]]; then
    echo "Missing generated Ninja graph: $graph" >&2
    echo "Generate it once with the normal product lunch/build flow." >&2
    exit 2
fi
if [[ ! -x "$ninja" || ! -x "$jdk/bin/java" ]]; then
    echo "Missing bundled Ninja or JDK 21 under: $android_root/prebuilts" >&2
    exit 2
fi

case "$mode" in
    core)
        target="out/soong/.intermediates/frameworks/base/packages/SystemUI/SystemUI-core/android_common/combined/SystemUI-core.jar"
        ;;
    tests)
        target="out/soong/.intermediates/frameworks/base/packages/SystemUI/tests/SystemUITests/android_common/combined/SystemUITests.jar"
        ;;
    apk)
        target="SystemUI"
        ;;
    *)
        echo "Usage: $0 [core|tests|apk] [product] [jobs]" >&2
        exit 2
        ;;
esac

cd "$android_root"
export JAVA_HOME="$jdk"
export PATH="$JAVA_HOME/bin:$PATH"
exec "$ninja" -f "$graph" -j "$jobs" "$target"
