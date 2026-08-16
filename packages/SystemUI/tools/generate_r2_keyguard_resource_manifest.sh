#!/bin/sh
set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
systemui_root=$(CDPATH= cd -- "$script_dir/.." && pwd)
original_res=${R2_ORIGINAL_RES:-"$systemui_root/../../../out/KeyguardSmartisan-apktool/res"}
original_apk=${R2_ORIGINAL_APK:-"$systemui_root/../../../jianuo-r2-8.5.3-202207181710/extracted/system/system/priv-app/KeyguardSmartisan/KeyguardSmartisan.apk"}
output="$systemui_root/r2-keyguard-resource-manifest.tsv"
temporary=$(mktemp "${TMPDIR:-/tmp}/r2-keyguard-manifest.XXXXXX")
trap 'rm -f "$temporary"' EXIT HUP INT TERM

if [ ! -d "$original_res" ]; then
    echo "Original resource tree not found: $original_res" >&2
    exit 1
fi
if [ ! -f "$original_apk" ]; then
    echo "Original APK not found: $original_apk" >&2
    exit 1
fi

printf 'origin_apk\toriginal_path\ttarget_path\tdensity\tpurpose\tsource_sha256\ttarget_sha256\tbyte_status\n' > "$temporary"

add_resource() {
    source_rel=$1
    target_rel=$2
    density=$3
    purpose=$4
    source_file="$original_res/$source_rel"
    target_file="$systemui_root/$target_rel"
    [ -f "$source_file" ] || { echo "Missing source: $source_file" >&2; exit 1; }
    [ -f "$target_file" ] || { echo "Missing target: $target_file" >&2; exit 1; }
    source_hash=$(shasum -a 256 "$source_file" | awk '{print $1}')
    target_hash=$(shasum -a 256 "$target_file" | awk '{print $1}')
    if [ "$source_hash" = "$target_hash" ]; then byte_status=exact; else byte_status=adapted; fi
    printf 'KeyguardSmartisan.apk\tres/%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
        "$source_rel" "$target_rel" "$density" "$purpose" "$source_hash" "$target_hash" "$byte_status" >> "$temporary"
}

# APK nine-patches must be decoded back to canonical one-pixel-border source images before Soong
# invokes aapt2. The manifest records both the compiled APK hash and decoded source hash so this
# necessary representation change is explicit and the final APK's npTc chunk can be audited.
add_apk_ninepatch_resource() {
    apk_entry=$1
    target_rel=$2
    density=$3
    purpose=$4
    target_file="$systemui_root/$target_rel"
    [ -f "$target_file" ] || { echo "Missing target: $target_file" >&2; exit 1; }
    unzip -Z1 "$original_apk" | grep -Fxq "$apk_entry" \
        || { echo "Missing APK entry: $apk_entry" >&2; exit 1; }
    source_hash=$(unzip -p "$original_apk" "$apk_entry" | shasum -a 256 | awk '{print $1}')
    target_hash=$(shasum -a 256 "$target_file" | awk '{print $1}')
    if [ "$source_hash" = "$target_hash" ]; then byte_status=exact; else byte_status=decoded_source; fi
    printf 'KeyguardSmartisan.apk\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
        "$apk_entry" "$target_rel" "$density" "$purpose" "$source_hash" "$target_hash" "$byte_status" >> "$temporary"
}

# Runtime host/TPage layouts. These remain byte-identical to the R2 APK.
add_resource layout/keyguard_host_view_delta.xml res/layout/keyguard_host_view_delta.xml default host_layout
add_resource layout/tpage_layout.xml res/layout/tpage_layout.xml default tpage_root
add_resource layout/tpage_main_layout.xml res/layout/tpage_main_layout.xml default main_page
add_resource layout/tpage_widgets_layout.xml res/layout/tpage_widgets_layout.xml default widget_page
add_resource layout-xxhdpi/tpage_music_widget_view.xml res/layout-xxhdpi/tpage_music_widget_view.xml xxhdpi music_widget
add_resource layout-xxhdpi/tpage_weather_widget_view.xml res/layout-xxhdpi/tpage_weather_widget_view.xml xxhdpi weather_widget
add_resource layout/tpage_recorder_widget_view.xml res/layout/tpage_recorder_widget_view.xml default recorder_widget
add_resource layout/wireless_charging_display.xml res/layout/wireless_charging_display.xml default charging_display
add_resource layout/wireless_charging_music.xml res/layout/wireless_charging_music.xml default charging_music

# Immutable security-layout baselines used by the static-difference check.
add_resource layout/keyguard_pattern_view.xml r2-baseline/security/layout/keyguard_pattern_view.xml default security_baseline
for name in keyguard_pin_view.xml keyguard_password_view.xml keyguard_sim_pin_view.xml \
    keyguard_sim_puk_unlock_view.xml keyguard_multi_sim_pin_view.xml \
    keyguard_multi_single_sim_pin_view.xml easy_password_keyboard.xml; do
    add_resource "layout-xxhdpi/$name" "r2-baseline/security/layout-xxhdpi/$name" xxhdpi security_baseline
done

# Runtime security layouts are controlled adaptations of the immutable originals.
add_resource layout-xxhdpi/keyguard_pin_view.xml res-keyguard/layout/sos_keyguard_pin_view.xml responsive credential_pin
add_resource layout/keyguard_pattern_view.xml res-keyguard/layout/sos_keyguard_pattern_view.xml responsive credential_pattern
add_resource layout-xxhdpi/keyguard_password_view.xml res-keyguard/layout/sos_keyguard_password_view.xml responsive credential_password
add_resource layout-xxhdpi/keyguard_sim_pin_view.xml res-keyguard/layout/sos_keyguard_sim_pin_view.xml responsive credential_sim_pin
add_resource layout-xxhdpi/keyguard_sim_puk_unlock_view.xml res-keyguard/layout/sos_keyguard_sim_puk_view.xml responsive credential_sim_puk
add_resource layout-xxhdpi/easy_password_keyboard.xml res-keyguard/layout/sos_sim_keyboard.xml responsive credential_keyboard

# Unmodified bitmap and frame-animation resources imported by this port.
find "$systemui_root/res/drawable-400dpi" "$systemui_root/res/drawable-xxhdpi" -type f \
    \( -name 'easy_password_dot_*.png' \
       -o -name 'keyguard_lock_pattern_view_*.png' \
       -o -name 'keyguard_sim_pass_confirm_button*.png' \
       -o -name 'no[0-9].png' \
       -o -name 'face_loop_*.png' \
       -o -name 'face_failed_*.png' \
       -o -name 'face_successed_*.png' \) | sort | while IFS= read -r target_file; do
    target_rel=${target_file#"$systemui_root/"}
    source_rel=${target_rel#res/}
    density=${source_rel%%/*}
    case ${source_rel##*/} in
        easy_password_dot_*) purpose=pin_dots ;;
        keyguard_lock_pattern_view_*) purpose=pattern_renderer ;;
        keyguard_sim_pass_confirm_button*) purpose=sim_confirm ;;
        no[0-9].png) purpose=recorder_clock_digits ;;
        face_*) purpose=faceid_frames ;;
    esac
    add_resource "$source_rel" "$target_rel" "$density" "$purpose"
done

for name in detecting failed refresh success; do
    add_resource "drawable/animation_faceid_${name}.xml" "res/drawable/animation_faceid_${name}.xml" default faceid_animation
done

# KG-003 selector/picker resources and KG-004 dual-SIM status assets.
for name in secletor_quicklaunch_pick_btn secletor_quicklaunch_pick_btn_light \
    secletor_slide_bar_btn_camera secletor_slide_bar_btn_camera_light \
    secletor_slide_bar_btn_phone secletor_slide_bar_btn_phone_light \
    secletor_slide_bar_btn_sms secletor_slide_bar_btn_sms_light; do
    add_resource "drawable/$name.xml" "res/drawable/$name.xml" default quick_launch_selector
done

find "$systemui_root/res/drawable-400dpi" "$systemui_root/res/drawable-xxhdpi" -type f \
    \( -name 'phone_icon*.png' \
       -o -name 'phone_light_icon_disabled.png' \
       -o -name 'message_icon*.png' \
       -o -name 'message_light_icon_disabled.png' \
       -o -name 'camera_icon*.png' \
       -o -name 'buttom_framepop_icon_*.png' \
       -o -name 'buttom_small*.png' \
       -o -name 'focus*.png' \
       -o -name 'unfocus*.png' \
       -o -name 'quicklunch_slide_bg_*shadow.png' \
       -o -name 'capture_appunknow_splash.png' \
       -o -name 'sim1_icon.png' \
       -o -name 'sim2_icon.png' \
       -o -name 'multi_pin_unlock_checked.png' \) | sort | while IFS= read -r target_file; do
    target_rel=${target_file#"$systemui_root/"}
    source_rel=${target_rel#res/}
    density=${source_rel%%/*}
    case ${source_rel##*/} in
        sim1_icon.png|sim2_icon.png|multi_pin_unlock_checked.png) purpose=sim_status ;;
        focus*.png|unfocus*.png) purpose=quick_launch_indicator ;;
        quicklunch_slide_bg_*shadow.png) purpose=quick_launch_shadow ;;
        *) purpose=quick_launch_picker ;;
    esac
    add_resource "$source_rel" "$target_rel" "$density" "$purpose"
done

for name in btn btn_light btn_pressed btn_pressed_light popup_bg popup_bg_light popup_top popup_top_light; do
    add_apk_ninepatch_resource \
        "res/drawable-xxhdpi-v4/$name.9.png" \
        "res/drawable-xxhdpi/$name.9.png" \
        xxhdpi quick_launch_ninepatch
done

mv "$temporary" "$output"
trap - EXIT HUP INT TERM
echo "$output"
