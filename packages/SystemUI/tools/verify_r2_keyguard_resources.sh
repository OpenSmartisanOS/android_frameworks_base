#!/bin/sh
set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
systemui_root=$(CDPATH= cd -- "$script_dir/.." && pwd)
manifest="$systemui_root/r2-keyguard-resource-manifest.tsv"
allowlist="$systemui_root/r2-baseline/security/runtime-layout-allowlist.tsv"

[ -f "$manifest" ] || { echo "Missing manifest: $manifest" >&2; exit 1; }
[ -f "$allowlist" ] || { echo "Missing allowlist: $allowlist" >&2; exit 1; }

exact_count=0
adapted_count=0
while IFS="$(printf '\t')" read -r origin source target density purpose source_hash target_hash status; do
    [ "$origin" = origin_apk ] && continue
    actual=$(shasum -a 256 "$systemui_root/$target" | awk '{print $1}')
    [ "$actual" = "$target_hash" ] || {
        echo "Hash drift: $target" >&2
        exit 1
    }
    case $status in
        exact)
            [ "$source_hash" = "$target_hash" ] || exit 1
            exact_count=$((exact_count + 1))
            ;;
        adapted)
            grep -F "$(printf '\t%s\t' "$target")" "$allowlist" >/dev/null || {
                echo "Unallowlisted adapted layout: $target" >&2
                exit 1
            }
            adapted_count=$((adapted_count + 1))
            ;;
        *) echo "Unknown byte status: $status" >&2; exit 1 ;;
    esac
done < "$manifest"

[ "$adapted_count" -eq 6 ] || {
    echo "Expected 6 controlled runtime layout adaptations, found $adapted_count" >&2
    exit 1
}

echo "R2 resources verified: $exact_count exact, $adapted_count allowlisted adaptations"
