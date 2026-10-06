#!/usr/bin/env bash
# Kiểm tra APK đã build: nội dung, assets, dex, manifest, chữ ký.
#
# Lưu ý kỹ thuật: KHÔNG dùng `strings | grep -q` để kiểm tra class, vì
#   - type descriptor xuất hiện cả khi class CHỈ ĐƯỢC THAM CHIẾU (không được đóng gói), và
#   - với `set -o pipefail`, `grep -q` thoát sớm làm `strings` bị SIGPIPE -> false negative.
# => dùng dexdump để kiểm tra class ĐƯỢC ĐỊNH NGHĨA.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APK="${1:?Dùng: verify-apk.sh <duong-dan.apk>}"
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
BT="$(ls -d "$SDK"/build-tools/* | sort -V | tail -1)"

TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
unzip -o -q "$APK" 'classes*.dex' -d "$TMP"

defined_classes() {
    for f in "$TMP"/classes*.dex; do
        "$BT/dexdump" -f "$f" 2>/dev/null | grep 'Class descriptor' | sed "s/.*: '\(.*\)'/\1/"
    done
}

echo "===== 1. Nội dung APK ====="
unzip -l "$APK" | grep -E 'classes.*\.dex|assets/xposed_init|resources.arsc|AndroidManifest.xml'

echo
echo "===== 2. assets/xposed_init ====="
unzip -p "$APK" assets/xposed_init

echo
echo "===== 3. Dex: class phải được ĐỊNH NGHĨA ====="
CLASSES="$(defined_classes)"
for s in \
    "Lcom/hieptran/aacast/doudou/aabridge/AACastBridge;" \
    "Lcom/hieptran/aacast/doudou/AACastCarService;" \
    "Lcom/hieptran/aacast/doudou/AACastCarActivity;" \
    "Lcom/hieptran/aacast/doudou/hook/GearheadDockPatcher;" \
    "Lcom/hieptran/aacast/doudou/hook/InputHooks;" \
    "Lcom/google/android/apps/auto/sdk/CarActivity;" \
    "Lcom/google/android/gms/car/CarActivityHost;"; do
  if grep -Fx "$s" <<< "$CLASSES" > /dev/null; then echo "  ok: $s"; else echo "  THIẾU: $s"; exit 1; fi
done

echo
echo "===== 4. Xposed API KHÔNG được đóng gói (compileOnly) ====="
if grep -F 'de/robv' <<< "$CLASSES" > /dev/null; then
  echo "  LỖI: dex định nghĩa class de.robv (Xposed API bị đóng gói sai)"; exit 1
else
  echo "  ok: không có class de.robv nào được định nghĩa"
fi

echo
echo "===== 5. Manifest ====="
"$BT/aapt2" dump badging "$APK" | grep -E "^package|^application-label|^launchable-activity" || true
echo "  -- service / provider / meta-data --"
"$BT/aapt2" dump xmltree --file AndroidManifest.xml "$APK" \
    | grep -E 'E: (service|provider)|A: android:name.*(AACastCarService|DockStateProvider|CATEGORY_PROJECTION|xposed)' \
    | sed 's/^ *//' | sort -u | head -20

echo
echo "===== 6. Chữ ký ====="
"$BT/apksigner" verify --print-certs "$APK" | head -4

echo
echo "===== 7. Hash ====="
shasum -a 256 "$APK"
md5 "$APK"
