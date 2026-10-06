#!/usr/bin/env bash
# Tải Xposed API 82 (compileOnly) — LSPosed cung cấp class này lúc runtime.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/app/libs/xposed-api-82.jar"
SRC_OUT="$ROOT/app/libs/xposed-api-82-sources.jar"
BASE="https://github.com/MALTF/XposedBridgeAPI/releases/download/v82"

echo "==> Tải $BASE/api-82.jar"
curl -sS -L -m 120 -o "$OUT" "$BASE/api-82.jar"
ls -la "$OUT"
unzip -l "$OUT" | grep -q 'de/robv/android/xposed/IXposedHookLoadPackage.class' \
    || { echo "Jar không hợp lệ"; exit 1; }

echo "==> Tải $BASE/api-82-sources.jar"
curl -sS -L -m 120 -o "$SRC_OUT" "$BASE/api-82-sources.jar"
ls -la "$SRC_OUT"
echo "==> Xong"
