#!/bin/bash
# Deploy Synergy Android APK and restore permissions
# Usage: ./deploy.sh [device_serial]

ADB="/c/Users/NAS/AppData/Local/Android/Sdk/platform-tools/adb.exe"
APK="app/build/outputs/apk/debug/app-debug.apk"
PKG="org.symless.synergy"
DEVICE="${1:-HVA5KNQ8}"

ACCESSIBILITY_SVC="$PKG/$PKG.services.GlobalInputService"
IME_SVC="$PKG/.services.VirtualKeyboardService"

if [ ! -f "$APK" ]; then
  echo "ERROR: APK not found at $APK -- build first"
  exit 1
fi

echo "=== Deploying to $DEVICE ==="

# Install APK
echo "[1/4] Installing APK..."
"$ADB" -s "$DEVICE" install -r "$APK"
if [ $? -ne 0 ]; then
  echo "ERROR: Install failed"
  exit 1
fi

# Re-enable accessibility service
echo "[2/4] Enabling accessibility service..."
"$ADB" -s "$DEVICE" shell settings put secure enabled_accessibility_services "$ACCESSIBILITY_SVC"

# Re-enable and set IME
echo "[3/4] Enabling Synergy keyboard..."
"$ADB" -s "$DEVICE" shell ime enable "$IME_SVC"
"$ADB" -s "$DEVICE" shell ime set "$IME_SVC"

# Grant overlay permission
echo "[4/4] Granting overlay permission..."
"$ADB" -s "$DEVICE" shell appops set "$PKG" SYSTEM_ALERT_WINDOW allow

echo "=== Done ==="
