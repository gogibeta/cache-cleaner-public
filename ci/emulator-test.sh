#!/usr/bin/env bash
# Emulator test for Cache Cleaner.
# Runs on a GitHub Actions emulator (AOSP / Google APIs image, NOT MIUI):
# installs the APK, grants usage-stats, enables the accessibility service,
# launches the app, then:
#   2a. taps "Select all" and verifies N apps get selected, then "Clear";
#   2b. switches to the "With cache" filter, selects one app that has
#       cache, taps "Clean 1 apps" and verifies the CacheClearEngine
#       automation trace ([dbg] lines) in logcat through to the
#       run-finished marker ("[dbg] run finished: cleaned=N failed=M skipped=K freed=...").
# The harness tolerates slow emulator boot (system ANR dialogs are
# dismissed — "Close app" as a last resort — and tap targets are polled)
# and performs REAL crash detection: any FATAL EXCEPTION whose "Process:"
# line is our package fails the run.
# NOTE: this validates the full automation pipeline end-to-end on AOSP
# Settings. It cannot reproduce MIUI-specific Settings UI behavior.
set -u

APK="${1:?usage: emulator-test.sh <apk>}"
OUT="ci/out"
mkdir -p "$OUT"

PKG="com.cachecleaner.app"
SVC="com.cachecleaner.app/com.cachecleaner.app.accessibility.CacheAccessService"
MAIN="$PKG/com.cachecleaner.app.MainActivity"

echo "=== installing $APK ==="
adb install -r "$APK"

echo "=== granting usage-stats access ==="
adb shell appops set "$PKG" GET_USAGE_STATS allow

echo "=== enabling accessibility service ==="
adb shell settings put secure enabled_accessibility_services "$SVC"
adb shell settings put secure accessibility_enabled 1
sleep 3
adb shell settings get secure enabled_accessibility_services | tee "$OUT/accessibility.txt"
if ! grep -q "CacheAccessService" "$OUT/accessibility.txt"; then
  echo "ACCESSIBILITY SERVICE NOT ENABLED"; exit 1
fi

echo "=== granting notification permission (avoid runtime dialog) ==="
adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS || true

# ---------- UI automation helpers ----------

# Dump the current UI hierarchy into $OUT/ui-dump.xml.
ui_dump() {
  adb shell uiautomator dump /data/local/tmp/ui.xml > /dev/null 2>&1
  adb pull /data/local/tmp/ui.xml "$OUT/ui-dump.xml" > /dev/null 2>&1
}

# Print "x y" (center) of the first node whose text matches the regex.
find_node() {
  python3 - "$1" "$OUT/ui-dump.xml" <<'EOF'
import sys, re, xml.etree.ElementTree as ET
pat, path = sys.argv[1], sys.argv[2]
try:
    tree = ET.parse(path)
except Exception:
    sys.exit(1)
for node in tree.iter('node'):
    t = node.get('text') or ''
    if re.search(pat, t):
        m = re.match(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', node.get('bounds') or '')
        if m:
            x1, y1, x2, y2 = map(int, m.groups())
            print((x1 + x2) // 2, (y1 + y2) // 2)
            sys.exit(0)
sys.exit(1)
EOF
}

# Print the text of the first node matching the regex (for reading labels).
node_text() {
  python3 - "$1" "$OUT/ui-dump.xml" <<'EOF'
import sys, re, xml.etree.ElementTree as ET
pat, path = sys.argv[1], sys.argv[2]
try:
    tree = ET.parse(path)
except Exception:
    sys.exit(1)
for node in tree.iter('node'):
    t = node.get('text') or ''
    if re.search(pat, t):
        print(t)
        sys.exit(0)
sys.exit(1)
EOF
}

# Single tap attempt on a node matching the regex. Returns 1 if absent.
tap_node() {
  local pattern="$1" desc="$2"
  ui_dump
  local bounds
  if ! bounds=$(find_node "$pattern"); then
    return 1
  fi
  echo "tapping '$desc' at $bounds"
  # shellcheck disable=SC2086
  adb shell input tap $bounds
}

# True when logcat holds a FATAL EXCEPTION for our package.
app_crashed() {
  adb logcat -d -t 4000 2>/dev/null | grep -A3 "FATAL EXCEPTION" | grep -q "Process: $PKG"
}

# Save our app's crash stack trace for the artifacts.
save_crash() {
  adb logcat -d 2>/dev/null | grep -B2 -A30 "FATAL EXCEPTION" > "$OUT/app-crash.txt" || true
}

ANR_COUNT=0

# Dismiss system "X isn't responding" ANR dialogs. Fails (returns 1) if the
# ANR is for OUR app. If "Wait" doesn't clear a system ANR after ~8 tries,
# taps "Close app" to kill the wedged system process (it restarts).
dismiss_system_dialogs() {
  ui_dump
  grep -q "isn't responding" "$OUT/ui-dump.xml" || { ANR_COUNT=0; return 0; }
  local title
  title=$(grep -o 'text="[^"]*isn'"'"'t responding"' "$OUT/ui-dump.xml" | head -1)
  echo "system ANR dialog: $title"
  if [[ "$title" == *"Cache Cleaner"* ]]; then
    echo "APP ANR — our app is not responding"
    return 1
  fi
  ANR_COUNT=$((ANR_COUNT + 1))
  local bounds btn="Wait" bpat="^Wait$"
  if [ "$ANR_COUNT" -ge 8 ]; then
    btn="Close app"; bpat="^Close app$"; ANR_COUNT=0
  fi
  if bounds=$(find_node "$bpat"); then
    echo "tapping '$btn' at $bounds"
    # shellcheck disable=SC2086
    adb shell input tap $bounds
    sleep 3
  fi
  return 0
}

# Poll for a tap target up to timeout_s, dismissing system dialogs.
wait_and_tap() {
  local pattern="$1" desc="$2" timeout_s="$3"
  local tries=$((timeout_s / 5))
  for _ in $(seq 1 "$tries"); do
    if app_crashed; then
      echo "APP CRASHED (logcat)"
      save_crash
      return 1
    fi
    dismiss_system_dialogs || return 1
    if tap_node "$pattern" "$desc"; then
      return 0
    fi
    sleep 5
  done
  echo "TAP TARGET NOT FOUND after ${timeout_s}s: $desc (see ui-dump.xml)"
  return 1
}

# Poll for a node whose text matches; prints its text. Returns 1 on timeout.
wait_for_text() {
  local pattern="$1" timeout_s="$2"
  local tries=$((timeout_s / 5))
  for _ in $(seq 1 "$tries"); do
    dismiss_system_dialogs || return 1
    ui_dump
    local t
    if t=$(node_text "$pattern"); then
      echo "$t"
      return 0
    fi
    sleep 5
  done
  return 1
}

screenshot() {
  adb shell screencap -p "/data/local/tmp/$1"
  adb pull "/data/local/tmp/$1" "$OUT/" > /dev/null 2>&1 || true
}

# ---------- test ----------

echo "=== launching app ==="
adb shell am start -n "$MAIN"
sleep 8
screenshot "cachecleaner-home.png"

echo "=== launch health check ==="
if app_crashed; then
  echo "APP CRASHED ON LAUNCH"
  save_crash
  exit 1
fi
if [ -z "$(adb shell pidof "$PKG" 2>/dev/null)" ]; then
  echo "APP PROCESS NOT RUNNING after launch"
  exit 1
fi
echo "app process is alive"

echo "=== phase 2a: Select-all UI test ==="
wait_and_tap "^Select all$" "Select all" 120 || { echo "SELECT-ALL TAP FAILED"; exit 1; }
CLEANLABEL=$(wait_for_text "^Clean [0-9]+ apps$" 120) || { echo "CLEAN-BUTTON NEVER APPEARED"; exit 1; }
N=$(echo "$CLEANLABEL" | grep -o "[0-9][0-9]*")
echo "select-all -> '$CLEANLABEL' (N=$N)"
if [ -z "$N" ] || [ "$N" -eq 0 ]; then
  echo "SELECT ALL SELECTED ZERO APPS"
  exit 1
fi
screenshot "cachecleaner-selected.png"
wait_and_tap "^Clear$" "Clear" 60 || { echo "CLEAR TAP FAILED"; exit 1; }
echo "select-all/clear UI OK"

echo "=== phase 2b: real single-app cache-clean run ==="
# Switch to the "With cache" filter so the list holds only apps with cache.
wait_and_tap "^With cache$" "With cache filter chip" 60 || { echo "WITH-CACHE CHIP NOT FOUND"; exit 1; }
sleep 3
# Tap the first app row (any app with cache). The row's package label is not
# needed: tap the first checkbox in the list.
ui_dump
FIRST_ROW=$(python3 - "$OUT/ui-dump.xml" <<'EOF'
import sys, xml.etree.ElementTree as ET
try:
    tree = ET.parse(sys.argv[1])
except Exception:
    sys.exit(1)
for node in tree.iter('node'):
    if node.get('checkable') == 'true':
        import re
        m = re.match(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', node.get('bounds') or '')
        if m:
            x1, y1, x2, y2 = map(int, m.groups())
            print((x1 + x2) // 2, (y1 + y2) // 2)
            sys.exit(0)
sys.exit(1)
EOF
) || { echo "NO CHECKABLE APP ROW FOUND"; exit 1; }
echo "tapping first app row at $FIRST_ROW"
# shellcheck disable=SC2086
adb shell input tap $FIRST_ROW
CLEANLABEL=$(wait_for_text "^Clean 1 apps$" 60) || { echo "CLEAN-1 BUTTON NEVER APPEARED"; exit 1; }
echo "selected 1 app -> '$CLEANLABEL'"
wait_and_tap "^Clean 1 apps$" "Clean 1 app" 60 || { echo "CLEAN TAP FAILED"; exit 1; }

echo "=== waiting for the run to finish (up to 8 min) ==="
FOUND=""
for _ in $(seq 1 48); do
  sleep 10
  if app_crashed; then
    echo "APP CRASHED DURING RUN"
    save_crash
    exit 1
  fi
  if adb logcat -d -t 4000 2>/dev/null | grep -q "\[dbg\] run finished: cleaned="; then FOUND=1; break; fi
done
if [ -z "$FOUND" ]; then
  echo "RUN DID NOT FINISH IN TIME"
  adb logcat -d -t 4000 > "$OUT/logcat-full.txt" || true
  exit 1
fi

echo "=== collecting clean-run diagnostics ==="
adb logcat -d > "$OUT/logcat-full.txt" || true
grep -iE "cachecleaner|CacheAccess|CacheClear" "$OUT/logcat-full.txt" | tail -120 > "$OUT/logcat-cachecleaner.txt" || true
grep -a "CacheCleaner" "$OUT/logcat-full.txt" > "$OUT/clean-run-dbg.txt" || true
screenshot "cachecleaner-after.png"

DBG=$(grep -ac "\[dbg\]" "$OUT/clean-run-dbg.txt" || true)
CLEANED=$(grep -ac "\[dbg\] run finished: cleaned=" "$OUT/clean-run-dbg.txt" || true)
echo "engine [dbg] lines : $DBG"
echo "run-finished marks : $CLEANED"
grep -a "\[dbg\] run finished: cleaned=" "$OUT/clean-run-dbg.txt" | tail -1
if [ "$DBG" -eq 0 ]; then echo "ENGINE NEVER ENGAGED (no [dbg] lines)"; exit 1; fi

echo "=== final crash check ==="
if app_crashed; then
  echo "APP CRASH DETECTED — see ci/out/app-crash.txt"
  save_crash
  exit 1
fi

echo "EMULATOR TEST OK"
