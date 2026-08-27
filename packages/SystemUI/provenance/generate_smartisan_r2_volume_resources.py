#!/usr/bin/env python3
"""Generate byte-level provenance for the SmartisanOS R2 volume-panel resources."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path


PREFIXES = (
    "headset",
    "wireless_headset",
    "ic_smartisan_volume_",
    "mute_active",
    "mute_close",
    "mute_time_",
    "mute_timer_",
)
EXACT_XML = {"active_bg", "active_bg_blue", "cancel_mute_btn_bg", "cancel_mute_btn_icon"}
LAYOUT_SOURCE = {
    "volume_dialog.xml": "smartisan_volume_adjust.xml",
    "volume_dialog_column.xml": "smartisan_volume_adjust_item.xml",
}


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def selected(relative: Path) -> bool:
    if relative.parts[0] == "layout":
        return relative.name in LAYOUT_SOURCE
    return relative.stem in EXACT_XML or relative.stem.startswith(PREFIXES)


def density(relative: Path) -> str:
    directory = relative.parts[0]
    return directory.removeprefix("drawable-") if directory.startswith("drawable-") else "default"


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source-res", required=True, type=Path)
    parser.add_argument("--target-res", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()

    source_res = args.source_res.resolve(strict=True)
    target_res = args.target_res.resolve(strict=True)
    resources: list[dict[str, object]] = []
    for target in sorted(target_res.rglob("*")):
        if not target.is_file():
            continue
        relative = target.relative_to(target_res)
        if not selected(relative):
            continue
        source_relative = relative
        if relative.parts[0] == "layout":
            source_relative = relative.with_name(LAYOUT_SOURCE[relative.name])
        source = source_res / source_relative
        if not source.is_file():
            raise FileNotFoundError(f"missing original R2 resource: {source}")
        source_hash = sha256(source)
        target_hash = sha256(target)
        byte_identical = source_hash == target_hash
        if target.suffix == ".png" and not byte_identical:
            raise ValueError(f"PNG/9-patch was modified: {relative}")
        entry: dict[str, object] = {
            "sourcePath": f"res/{source_relative.as_posix()}",
            "targetPath": f"packages/SystemUI/res/{relative.as_posix()}",
            "density": density(relative),
            "usage": "SmartisanOS R2 volume panel",
            "byteIdentical": byte_identical,
        }
        if byte_identical:
            entry["sha256"] = source_hash
        else:
            entry["sourceSha256"] = source_hash
            entry["targetSha256"] = target_hash
            entry["adaptation"] = (
                "Android 16 IDs, controllers and responsive geometry; original visual values retained"
            )
        resources.append(entry)

    manifest = {
        "source": "SmartisanSystemUI.apk / SmartisanOS R2 8.5.3-202207181710",
        "generator": "packages/SystemUI/provenance/generate_smartisan_r2_volume_resources.py",
        "resources": resources,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(manifest, indent=2, ensure_ascii=False) + "\n")


if __name__ == "__main__":
    main()
