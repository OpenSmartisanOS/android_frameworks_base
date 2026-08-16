#!/usr/bin/env python3
"""Import the reachable SmartisanOS R2 lock-screen resource closure.

The decoded vendor APK contains generated animated-vector children whose compiled resource names
begin with '$'. AAPT2 cannot accept those names in source form, so only those generated children
are deterministically namespaced while their XML payload and all animation parameters are kept.
Every other file is copied byte-for-byte, including PNG density variants and nine-patch chunks.
"""

from __future__ import annotations

import argparse
import copy
import hashlib
import json
import re
import shutil
import xml.etree.ElementTree as ET
from collections import defaultdict, deque
from pathlib import Path


ANDROID_NS = "http://schemas.android.com/apk/res/android"
REF_RE = re.compile(r"[@?](?!android:)(?:\*)?([a-zA-Z0-9_]+)\/([a-zA-Z0-9_.$]+)")

LAYOUT_SEEDS = {
    "keyguard_host_view_delta",
    "tpage_layout",
    "tpage_main_layout",
    "tpage_widgets_layout",
    "tpage_weather_widget_view",
    "tpage_music_widget_view",
    "tpage_recorder_widget_view",
    "tpage_time_layout",
    "layout_clock_view",
    "pinned_stack",
    "pinned_nail_window",
    "pinned_loading_bg_b",
    "pinned_loading_bg_w",
    "pinned_warning_dialog",
    "charge_battery_view",
    "charge_battery_view_small",
    "wireless_charging_display",
    "wireless_charging_music",
    "rolling_charging_clock",
    "rolling_2_charging_clock",
    "flip_charging_clock",
    "lcd_charging_clock",
}

DRAWABLE_SEEDS = {
    "animation_frame_charging",
    "btn_playing_next_selector",
    "btn_playing_pause_selector",
    "btn_playing_play_selector",
    "btn_playing_prev_selector",
    "capture_camera",
    "capture_camera_darwin",
    "flashlight_off_static",
    "flashlight_on_static",
    "icon_goto_camera",
    "icon_goto_camera_dark",
    "icon_goto_widget",
    "icon_goto_widget_dark",
    "icon_slide_unlock_dark",
    "icon_slide_unlock_light",
    "music_pause_in_album",
    "music_play_in_album",
    "pinned_close_btn",
    "pinned_indicator_0",
    "pinned_indicator_1",
    "pinned_indicator_2",
    "pinned_indicator_3",
    "pinned_indicator_4",
    "pinned_indicator_5",
    "pinned_indicator_6",
    "pinned_loading_progress_b",
    "pinned_loading_progress_w",
    "pinned_nail_0",
    "pinned_nail_1",
    "pinned_nail_2",
    "pinned_nail_3",
    "pinned_nail_4",
    "pinned_nail_5",
    "pinned_nail_6",
    "pinned_stack_view_bg_b",
    "pinned_stack_view_bg_w",
    "pinned_task_outer_frame",
    "recording_pause",
    "recording_pause_static",
    "recording_resume",
    "recording_resume_static",
    "recording_start",
    "recording_start_static",
    "recording_stop",
    "recording_stop_static",
    "tpage_round_corner_mask",
    "weatherbg",
}

RAW_SEEDS = {"charge_anim_1", "charge_anim_2"}

# PinRootView creates its indicator/selector hierarchy in Java, so these values cannot be reached
# by following XML references.  Keep the complete original geometry group in the source manifest;
# the Android 16 compatibility view consumes the same 1080-wide measurements through the shared
# layout model rather than resolving a device-specific sw qualifier at runtime.
VALUE_SEEDS = {
    ("dimen", "pin_btn_margin_top"),
    ("dimen", "pin_btn_size"),
    ("dimen", "pinned_close_btn_margin"),
    ("dimen", "pinned_close_btn_size"),
    ("dimen", "pinned_icon_distance_to_indicator_point"),
    ("dimen", "pinned_icon_selected_translation"),
    ("dimen", "pinned_icon_size"),
    ("dimen", "pinned_indicator_child_margin"),
    ("dimen", "pinned_indicator_child_size"),
    ("dimen", "pinned_indicator_margin_bottom"),
    ("dimen", "pinned_indicator_padding_others"),
    ("dimen", "pinned_indicator_padding_top"),
    ("dimen", "pinned_inner_radius"),
    ("dimen", "pinned_middle_radius"),
    ("dimen", "pinned_nail_anim_offset"),
    ("dimen", "pinned_nail_height"),
    ("dimen", "pinned_nail_margin"),
    ("dimen", "pinned_nail_size"),
    ("dimen", "pinned_nail_width"),
    ("dimen", "pinned_outer_radius"),
    ("dimen", "pinned_stack_view_corner_radius"),
    ("dimen", "pinned_stack_view_margin_bottom"),
    ("dimen", "pinned_stack_view_margin_left"),
    ("dimen", "pinned_stack_view_margin_right"),
    ("dimen", "pinned_stack_view_margin_top"),
}


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def resource_name(path: Path) -> str:
    name = path.name
    if name.endswith(".9.png"):
        return name[:-6]
    return path.stem


def resource_type(path: Path) -> str:
    return path.parent.name.split("-", 1)[0]


def generated_name(name: str) -> str:
    if name.startswith("$$"):
        return "sos_r2_generated_2_" + name[2:]
    if name.startswith("$"):
        return "sos_r2_generated_" + name[1:]
    return name


def rewrite_generated_references(text: str) -> str:
    return re.sub(
        r"([@?]drawable\/)(\$\$?[a-zA-Z0-9_.$]+)",
        lambda match: match.group(1) + generated_name(match.group(2)),
        text,
    )


def index_file_resources(res_root: Path):
    result: dict[tuple[str, str], list[Path]] = defaultdict(list)
    for path in sorted(res_root.rglob("*")):
        if path.is_file() and not path.parent.name.startswith("values"):
            result[(resource_type(path), resource_name(path))].append(path)
    return result


def value_key(node: ET.Element):
    name = node.attrib.get("name")
    if not name:
        return None
    kind = node.attrib.get("type") if node.tag == "item" else node.tag
    return (kind, name) if kind else None


def index_values(res_root: Path, ignored_filenames: set[str] | None = None):
    result: dict[tuple[str, str], list[tuple[str, Path, ET.Element]]] = defaultdict(list)
    for directory in sorted(res_root.glob("values*")):
        if not directory.is_dir():
            continue
        for path in sorted(directory.glob("*.xml")):
            if ignored_filenames and path.name in ignored_filenames:
                continue
            try:
                root = ET.parse(path).getroot()
            except ET.ParseError as error:
                raise RuntimeError(f"Cannot parse {path}: {error}") from error
            for node in root:
                key = value_key(node)
                if key:
                    result[key].append((directory.name, path, node))
    return result


def refs_from_text(text: str):
    for match in REF_RE.finditer(text):
        yield match.group(1), match.group(2)


def target_filename(source: Path) -> str:
    original = resource_name(source)
    renamed = generated_name(original)
    if original == renamed:
        return source.name
    if source.name.endswith(".9.png"):
        return renamed + ".9.png"
    return renamed + source.suffix


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--keyguard-res", required=True, type=Path)
    parser.add_argument("--systemui-res", required=True, type=Path)
    parser.add_argument("--fonts-root", required=True, type=Path)
    parser.add_argument("--target-res", required=True, type=Path)
    parser.add_argument("--manifest", required=True, type=Path)
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()

    for source in (args.keyguard_res, args.systemui_res):
        if not source.is_dir():
            raise SystemExit(f"Decoded resource directory does not exist: {source}")
    if not args.fonts_root.is_dir():
        raise SystemExit(f"Vendor font directory does not exist: {args.fonts_root}")

    files = index_file_resources(args.keyguard_res)
    values = index_values(args.keyguard_res)
    existing_values = index_values(
        args.target_res,
        ignored_filenames={"smartisan_keyguard_original.xml", "smartisan_aod_original.xml"},
    )

    queue = deque(
        [("layout", name) for name in sorted(LAYOUT_SEEDS)]
        + [("drawable", name) for name in sorted(DRAWABLE_SEEDS)]
        + [("raw", name) for name in sorted(RAW_SEEDS)]
        + sorted(VALUE_SEEDS)
    )
    visited: set[tuple[str, str]] = set()
    selected_files: set[Path] = set()
    selected_values: dict[str, list[tuple[tuple[str, str], Path, ET.Element]]] = defaultdict(list)
    unresolved: set[tuple[str, str]] = set()

    while queue:
        key = queue.popleft()
        if key in visited or key[0] == "android":
            continue
        visited.add(key)
        family = files.get(key)
        if family:
            for source in family:
                selected_files.add(source)
                if source.suffix == ".xml":
                    for ref in refs_from_text(source.read_text(encoding="utf-8")):
                        queue.append(ref)
            continue
        entries = values.get(key)
        if entries:
            # Existing IDs are intentionally reused. Android permits all imported layouts to bind
            # to the same package ID; redeclaring them would create duplicate values entries.
            if key in existing_values and key[0] == "id":
                continue
            if key in existing_values:
                unresolved.add(key)
                continue
            for qualifier, source, node in entries:
                selected_values[qualifier].append((key, source, copy.deepcopy(node)))
                serialized = ET.tostring(node, encoding="unicode")
                for ref in refs_from_text(serialized):
                    queue.append(ref)
                parent = node.attrib.get("parent")
                if node.tag == "style" and parent and not parent.startswith("@android:"):
                    queue.append(("style", parent.removeprefix("@style/")))
            continue
        if key in existing_values:
            continue
        unresolved.add(key)

    # SmartisanSystemUI owns the Dream/AOD surface.
    aod_explicit = [
        ("layout/dream.xml", "layout/dream.xml", {}),
        (
            "drawable/doze_battery_level_icon.xml",
            "drawable/doze_battery_level_icon.xml",
            {},
        ),
        (
            "drawable-nodpi/fingerprint_icon_in_doze.png",
            "drawable-nodpi/fingerprint_icon_in_doze.png",
            {},
        ),
    ]
    for density in ("drawable-400dpi", "drawable-xxhdpi"):
        for name in (
            "battery_02.png",
            "battery_03.png",
            "battery_04.png",
            "battery_05.png",
            "battery_06.png",
            "battery_07.png",
            "battery_08.png",
            "battery_09.png",
            "battery_10.png",
            "battery_full.png",
            "battery_frame.png",
            "battery_charge.png",
        ):
            relative = f"{density}/{name}"
            aod_explicit.append((relative, relative, {}))

    records = []
    for source in sorted(selected_files):
        relative = source.relative_to(args.keyguard_res)
        destination = args.target_res / relative.parent / target_filename(source)
        raw = source.read_bytes()
        transformed_name = source.name != destination.name
        transformed_references = source.suffix == ".xml" and b"@drawable/$" in raw
        transformations = []
        if transformed_name:
            transformations.append("generated-aapt-name-namespace")
        if transformed_references:
            transformations.append("generated-aapt-reference-namespace")
        records.append(
            {
                "sourceApk": "KeyguardSmartisan.apk",
                "sourcePath": str(relative),
                "targetPath": str(destination.relative_to(args.target_res.parent)),
                "density": relative.parent.name.removeprefix("drawable-")
                if relative.parent.name.startswith("drawable-")
                else None,
                "sha256": sha256(source),
                "transformation": "+".join(transformations) if transformations else None,
            }
        )
        if not args.dry_run:
            destination.parent.mkdir(parents=True, exist_ok=True)
            if source.suffix == ".xml" and (transformed_references or transformed_name):
                destination.write_text(
                    rewrite_generated_references(raw.decode("utf-8")), encoding="utf-8"
                )
            else:
                shutil.copyfile(source, destination)

    ET.register_namespace("android", ANDROID_NS)
    for qualifier, entries in sorted(selected_values.items()):
        destination = args.target_res / qualifier / "smartisan_keyguard_original.xml"
        root = ET.Element("resources")
        seen = set()
        for key, source, node in entries:
            if key in seen:
                continue
            seen.add(key)
            root.append(node)
            records.append(
                {
                    "sourceApk": "KeyguardSmartisan.apk",
                    "sourcePath": str(source.relative_to(args.keyguard_res)) + f"#{key[0]}/{key[1]}",
                    "targetPath": str(destination.relative_to(args.target_res.parent)),
                    "density": qualifier,
                    "sha256": hashlib.sha256(ET.tostring(node)).hexdigest(),
                    "transformation": "values-closure-extraction",
                }
            )
        if not args.dry_run:
            destination.parent.mkdir(parents=True, exist_ok=True)
            ET.indent(root, space="    ")
            ET.ElementTree(root).write(destination, encoding="utf-8", xml_declaration=True)

    for source_relative, target_relative, replacements in aod_explicit:
        source = args.systemui_res / source_relative
        if not source.is_file():
            raise SystemExit(f"Missing SmartisanSystemUI AOD resource: {source}")
        destination = args.target_res / target_relative
        records.append(
            {
                "sourceApk": "SmartisanSystemUI.apk",
                "sourcePath": source_relative,
                "targetPath": str(destination.relative_to(args.target_res.parent)),
                "density": source.parent.name.removeprefix("drawable-")
                if source.parent.name.startswith("drawable-")
                else None,
                "sha256": sha256(source),
                "transformation": "merged-apk-name-namespace" if replacements else None,
            }
        )
        if not args.dry_run:
            destination.parent.mkdir(parents=True, exist_ok=True)
            if replacements:
                text = source.read_text(encoding="utf-8")
                for old, new in replacements.items():
                    text = text.replace(old, new)
                destination.write_text(text, encoding="utf-8")
            else:
                shutil.copyfile(source, destination)

    aod_value_keys = {
        ("dimen", "doze_am_pm_text_size"),
        ("dimen", "doze_battery_padding_top"),
        ("dimen", "doze_offset_from_time_view"),
        ("dimen", "doze_second_container_padding_left"),
        ("dimen", "doze_second_container_padding_top"),
        ("dimen", "doze_time_view_padding_left"),
        ("dimen", "doze_time_view_padding_top"),
        ("dimen", "doze_time_view_text_size"),
        ("dimen", "doze_week_view_padding_left"),
        ("dimen", "doze_week_view_text_size"),
        ("id", "doze_info_container"),
        ("id", "time_view"),
        ("id", "second_container"),
        ("id", "am_pm_view"),
        ("id", "week_view"),
        ("id", "battery_view"),
        ("id", "battery_charge"),
    }
    systemui_values = index_values(args.systemui_res)
    aod_values_by_qualifier = defaultdict(list)
    for key in sorted(aod_value_keys):
        entries = systemui_values.get(key)
        if not entries:
            raise SystemExit(f"Missing SmartisanSystemUI value: {key[0]}/{key[1]}")
        if key in existing_values and key[0] == "id":
            continue
        for qualifier, source, node in entries:
            aod_values_by_qualifier[qualifier].append((key, source, copy.deepcopy(node)))
    for qualifier, entries in sorted(aod_values_by_qualifier.items()):
        destination = args.target_res / qualifier / "smartisan_aod_original.xml"
        root = ET.Element("resources")
        seen = set()
        for key, source, node in entries:
            if key in seen:
                continue
            seen.add(key)
            root.append(node)
            records.append(
                {
                    "sourceApk": "SmartisanSystemUI.apk",
                    "sourcePath": str(source.relative_to(args.systemui_res)) + f"#{key[0]}/{key[1]}",
                    "targetPath": str(destination.relative_to(args.target_res.parent)),
                    "density": qualifier,
                    "sha256": hashlib.sha256(ET.tostring(node)).hexdigest(),
                    "transformation": "values-closure-extraction",
                }
            )
        if not args.dry_run:
            destination.parent.mkdir(parents=True, exist_ok=True)
            ET.indent(root, space="    ")
            ET.ElementTree(root).write(destination, encoding="utf-8", xml_declaration=True)

    for source_name, target_name in (
        ("Smartisan_Compact-Regular.otf", "smartisan_compact_regular.otf"),
        ("Smartisan_Compact-Bold.otf", "smartisan_compact_bold.otf"),
    ):
        source = args.fonts_root / source_name
        if not source.is_file():
            raise SystemExit(f"Missing Smartisan font: {source}")
        destination = args.target_res / "font" / target_name
        records.append(
            {
                "sourceApk": "R2 system font partition",
                "sourcePath": f"system/fonts/{source_name}",
                "targetPath": str(destination.relative_to(args.target_res.parent)),
                "density": None,
                "sha256": sha256(source),
                "transformation": "android-resource-name-only",
            }
        )
        if not args.dry_run:
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source, destination)

    report = {
        "designCanvas": {"width": 1080, "height": 2242},
        "files": sorted(records, key=lambda item: (item["targetPath"], item["sourcePath"])),
        "unresolvedOrConflicting": [f"{kind}/{name}" for kind, name in sorted(unresolved)],
    }
    if not args.dry_run:
        args.manifest.parent.mkdir(parents=True, exist_ok=True)
        args.manifest.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")

    print(f"Selected {len(selected_files)} Keyguard files and {sum(len(v) for v in selected_values.values())} values entries")
    print(f"AOD explicit files: {len(aod_explicit)}")
    if unresolved:
        print("Unresolved/conflicting resources:")
        for kind, name in sorted(unresolved):
            print(f"  {kind}/{name}")
        raise SystemExit(2)


if __name__ == "__main__":
    main()
