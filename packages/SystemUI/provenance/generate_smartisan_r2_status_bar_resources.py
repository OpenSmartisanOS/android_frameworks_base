#!/usr/bin/env python3
"""Generate the byte-level Smartisan R2 status-bar resource provenance manifest."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
from pathlib import Path


RESOURCE_PATTERNS = tuple(
    re.compile(pattern)
    for pattern in (
        r"^(?:colored_)?(?:smaritisan_)?stat_sys_(?:battery|low_battery|powersave_battery)",
        r"^(?:colored_)?stat_sys_wifi_",
        r"^(?:colored_)?stat_sys_(?:signal|data_connected|roaming)",
        r"^stat_sys_(?:alarm|ringer_|tty_mode|volte_mode|cast|phone2phonecharging)",
        r"^stat_sys_data_bluetooth(?:$|_)",
        r"^stat_sys_(?:normal_earphone|sync(?:$|_)|zen_|dnd(?:$|_)|hotspot$|data_saver$)",
        r"^stat_sys_(?:managed_profile|roaming_cdma_|rotate_)",
        r"^permission_(?:camera|gps|recorder)_icon$",
        r"^sidebar_drag$",
        r"^smartisan_bg_notification_count_",
    )
)

# These two drawables intentionally bridge the Android 16 SignalDrawable contract to the R2
# artwork and therefore are not byte-for-byte copies of the Android 7 era animation XML. Keeping
# their source and target hashes in the manifest makes the compatibility boundary explicit.
ALLOWED_ADAPTED_RESOURCES = {
    Path("drawable/stat_sys_signal_carrier_network_change.xml"),
    Path("drawable/stat_sys_signal_carrier_network_change_animation.xml"),
}


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def usage(name: str) -> str:
    if name.startswith("permission_"):
        return "status bar privacy highlight"
    if name == "sidebar_drag.png":
        return "OneStep inner-drag indicator"
    if name == "stat_sys_phone2phonecharging.png":
        return "USB OTG source-role indicator"
    if name.startswith("stat_sys_volte_mode"):
        return "VoLTE disabled/ready/calling state"
    if "battery" in name:
        return "status bar battery"
    if "wifi" in name:
        return "fixed Wi-Fi signal cluster"
    if name.startswith("smartisan_bg_notification_count_"):
        return "notification count badge"
    if name.startswith(("stat_sys_signal", "colored_stat_sys_signal", "stat_sys_data_connected", "stat_sys_roaming")):
        return "fixed mobile signal cluster"
    return "R2 dynamic status icon"


def density(relative: Path) -> str:
    directory = relative.parts[0]
    return directory.removeprefix("drawable-") if directory != "drawable" else "default"


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source-res", type=Path, required=True)
    parser.add_argument("--target-res", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    source_res = args.source_res.resolve(strict=True)
    target_res = args.target_res.resolve(strict=True)
    resources: list[dict[str, object]] = []
    for source in sorted(source_res.rglob("*")):
        if (
            not source.is_file()
            or source.suffix not in {".png", ".xml"}
            or not any(pattern.match(source.stem) for pattern in RESOURCE_PATTERNS)
        ):
            continue
        relative = source.relative_to(source_res)
        target = target_res / relative
        if not target.is_file():
            raise FileNotFoundError(f"missing imported resource: {target}")
        source_hash = sha256(source)
        target_hash = sha256(target)
        byte_identical = source_hash == target_hash
        if not byte_identical and relative not in ALLOWED_ADAPTED_RESOURCES:
            raise ValueError(f"resource is not byte-identical: {relative}")
        entry: dict[str, object] = {
            "sourcePath": f"res/{relative.as_posix()}",
            "targetPath": f"packages/SystemUI/res/{relative.as_posix()}",
            "density": density(relative),
            "usage": usage(relative.name),
        }
        if byte_identical:
            entry["sha256"] = source_hash
        else:
            entry.update(
                {
                    "sourceSha256": source_hash,
                    "targetSha256": target_hash,
                    "byteIdentical": False,
                    "adaptation": "Android 16 signal animation compatibility wrapper",
                }
            )
        resources.append(entry)

    manifest = {
        "source": "SmartisanSystemUI.apk / SmartisanOS R2 8.5.3-202207181710",
        "generator": "packages/SystemUI/provenance/generate_smartisan_r2_status_bar_resources.py",
        "resources": resources,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(manifest, indent=2, ensure_ascii=False) + "\n")


if __name__ == "__main__":
    main()
