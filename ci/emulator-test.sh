#!/usr/bin/env bash
# Emulator test for Cache Cleaner.
# Runs on a GitHub Actions emulator (AOSP / Google APIs image, NOT MIUI):
# installs the APK (the R8 release build, see the workflow), grants
# usage-stats, enables the accessibility service, launches the app, then:
#   2a. waits for the app list to load, taps "Select all" (re-tapping until
#       the effect is verified — a tap can be swallowed by a loaded system)
#       and verifies N apps get selected, then "Clear";
#   2b. switches to the "With cache" filter, selects one app that has
#       cache, taps "Clean cache for 1 apps" and verifies the CacheClearEngine
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

# ---------- adb reliability ----------

# Every adb call goes through adbw(). On a wedged system (no KVM on hosted
# runners) a plain `adb shell uiautomator dump` can hang for many minutes;
# run 36705397771 lost ~17 min inside hung adb calls with zero output, which
# turned a slow emulator into a doomed run. Capping each call keeps every
# poll loop on its intended pacing; a timeout is treated like a miss, not a
# hang.
ADB_TIMEOUT=60
adbw() {
  timeout "$ADB_TIMEOUT" adb "$@"
}

# Progress messages that must reach the step log even when the caller's
# stdout is captured by command substitution: in run 36705397771
# tap_until_text's diagnostics were swallowed by $(...) and the 17-minute
# tap loop was completely invisible. log() writes to stderr instead.
log() { echo "$@" >&2; }

echo "=== installing $APK ==="
adbw install -r "$APK" || fail "APK INSTALL FAILED"

echo "=== granting usage-stats access ==="
adbw shell appops set "$PKG" GET_USAGE_STATS allow || fail "APPOPS SET FAILED"

echo "=== enabling accessibility service ==="
adbw shell settings put secure enabled_accessibility_services "$SVC" || fail "A11Y ENABLE FAILED"
adbw shell settings put secure accessibility_enabled 1 || fail "A11Y ENABLE FAILED"
sleep 3
adbw shell settings get secure enabled_accessibility_services | tee "$OUT/accessibility.txt"
if ! grep -q "CacheAccessService" "$OUT/accessibility.txt"; then
  echo "ACCESSIBILITY SERVICE NOT ENABLED"; exit 1
fi

echo "=== granting notification permission (avoid runtime dialog) ==="
adbw shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS || true

# ---------- UI automation helpers ----------

# Dump the current UI hierarchy into $OUT/ui-dump.xml. Returns 1 when the
# device is wedged and no dump could be produced (adbw timeout).
ui_dump() {
  adbw shell uiautomator dump /data/local/tmp/ui.xml > /dev/null 2>&1
  adbw pull /data/local/tmp/ui.xml "$OUT/ui-dump.xml" > /dev/null 2>&1
  [ -s "$OUT/ui-dump.xml" ]
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
# Assumes a FRESH ui-dump.xml was just produced by the caller (avoids a
# second dump per iteration — on a wedged system each dump can cost the
# full ADB_TIMEOUT).
tap_node() {
  local pattern="$1" desc="$2"
  local bounds
  if ! bounds=$(find_node "$pattern"); then
    return 1
  fi
  log "tapping '$desc' at $bounds"
  echo "tap $desc $bounds" >> "$OUT/tap-journal.log"
  # shellcheck disable=SC2086
  adbw shell input tap $bounds
}

# True when logcat holds a FATAL EXCEPTION for our package.
app_crashed() {
  adbw logcat -d -t 4000 2>/dev/null | grep -A3 "FATAL EXCEPTION" | grep -q "Process: $PKG"
}

# Save our app's crash stack trace for the artifacts.
save_crash() {
  adbw logcat -d 2>/dev/null | grep -B2 -A30 "FATAL EXCEPTION" > "$OUT/app-crash.txt" || true
}

ANR_COUNT=0
APP_ANR_COUNT=0
# Our app's ANR dialog during the launch window is treated as transient:
# under full software emulation (no KVM on hosted runners) a Compose debug
# APK's cold start can exceed Android's ~10 s launch timeout, so the system
# shows the ANR dialog even though the app is still starting normally.
# Tap "Wait" and keep polling, bounded by APP_ANR_MAX; only fail if the app
# never becomes responsive.
APP_ANR_MAX=15

# Snapshot logcat + the ANR traces file into the artifacts so a failure can
# be diagnosed from the main-thread stack, not just a screenshot.
capture_anr_trace() {
  adbw logcat -d > "$OUT/logcat-full.txt" 2>/dev/null || true
  adbw shell cat /data/anr/traces.txt > "$OUT/anr-traces.txt" 2>/dev/null || true
  log "captured logcat + anr traces into $OUT"
}

# Fail helper: always capture diagnostics before exiting.
fail() {
  log "FAIL: $1"
  capture_anr_trace
  exit 1
}

# Dismiss "X isn't responding" ANR dialogs. System ANRs: tap "Wait" (after
# ~8 tries tap "Close app" to kill the wedged system process — it restarts).
# OUR app's ANR: tap "Wait" and keep polling (bounded); return 1 only when it
# persists past APP_ANR_MAX dismiss cycles.
dismiss_system_dialogs() {
  ui_dump || { log "ui_dump failed (wedged system), retrying"; return 0; }
  grep -q "isn't responding" "$OUT/ui-dump.xml" || { ANR_COUNT=0; APP_ANR_COUNT=0; return 0; }
  local title
  title=$(grep -o 'text="[^"]*isn'"'"'t responding"' "$OUT/ui-dump.xml" | head -1)
  if [[ "$title" == *"Cache Cleaner"* ]]; then
    APP_ANR_COUNT=$((APP_ANR_COUNT + 1))
    log "APP ANR (ours) — likely slow cold start under software emulation; tapping Wait ($APP_ANR_COUNT/$APP_ANR_MAX)"
    capture_anr_trace
    if [ "$APP_ANR_COUNT" -ge "$APP_ANR_MAX" ]; then
      log "APP ANR PERSISTED — failing"
      return 1
    fi
    local bounds
    if bounds=$(find_node "^Wait$"); then
      log "tapping 'Wait' at $bounds"
      # shellcheck disable=SC2086
      adbw shell input tap $bounds
      sleep 3
    fi
    return 0
  fi
  log "system ANR dialog: $title"
  ANR_COUNT=$((ANR_COUNT + 1))
  local bounds btn="Wait" bpat="^Wait$"
  if [ "$ANR_COUNT" -ge 8 ]; then
    btn="Close app"; bpat="^Close app$"; ANR_COUNT=0
  fi
  if bounds=$(find_node "$bpat"); then
    log "tapping '$btn' at $bounds"
    # shellcheck disable=SC2086
    adbw shell input tap $bounds
    sleep 3
  fi
  return 0
}

# Poll for a tap target up to timeout_s, dismissing system dialogs.
# Single UI dump per iteration (dismiss_system_dialogs leaves a fresh dump
# behind); tap_node reads that same dump so a dialog that pops up between
# two dumps can't desync the tap coordinates.
wait_and_tap() {
  local pattern="$1" desc="$2" timeout_s="$3"
  local tries=$((timeout_s / 5))
  for _ in $(seq 1 "$tries"); do
    if app_crashed; then
      log "APP CRASHED (logcat)"
      save_crash
      capture_anr_trace
      return 1
    fi
    dismiss_system_dialogs || return 1
    if tap_node "$pattern" "$desc"; then
      return 0
    fi
    sleep 5
  done
  log "TAP TARGET NOT FOUND after ${timeout_s}s: $desc (see ui-dump.xml)"
  return 1
}

# Poll for a node whose text matches; prints its text. Returns 1 on timeout.
wait_for_text() {
  local pattern="$1" timeout_s="$2"
  local tries=$((timeout_s / 5))
  for _ in $(seq 1 "$tries"); do
    dismiss_system_dialogs || return 1
    ui_dump || { sleep 5; continue; }
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
  adbw shell screencap -p "/data/local/tmp/$1"
  adbw pull "/data/local/tmp/$1" "$OUT/" > /dev/null 2>&1 || true
}

# Poll until the app list has finished loading: the filter row ("Select all")
# is visible AND no progress indicator remains. Tapping "Select all" while the
# list is still loading is a silent no-op (selectAllVisible over an empty
# list), which used to send phase 2a into a 26-minute doomed poll.
wait_for_list_loaded() {
  local timeout_s="$1"
  local tries=$((timeout_s / 5))
  for _ in $(seq 1 "$tries"); do
    dismiss_system_dialogs || return 1
    if ui_dump \
       && grep -q 'text="Select all"' "$OUT/ui-dump.xml" \
       && ! grep -q 'class="[^"]*ProgressBar"' "$OUT/ui-dump.xml"; then
      echo "app list loaded"
      return 0
    fi
    sleep 5
  done
  return 1
}

# Tap a target, then wait for an expected text to appear; re-tap until the
# text shows up or attempts run out. Prints the matched text on success.
# Needed because on a heavily loaded emulator a tap can be swallowed by the
# system itself (run 36698575930: the Gesture Monitor ANR'd on our "Select
# all" tap, which never reached the app) — assuming the tap landed turns a
# lost tap into a long doomed poll. Only use for idempotent taps.
#
# Dialog-aware: each attempt first settles the screen (dismisses any
# "isn't responding" dialog) and only taps when the target is present on a
# dialog-free dump. A tap issued while an ANR dialog is up lands on the
# dialog, not the app — the silent failure mode in runs 99 and 101.
# All progress goes through log() (stderr) so it survives the $(...)
# capture at the call site; run 101's 17-minute loop was invisible because
# its diagnostics went to stdout.
tap_until_text() {
  local tap_pattern="$1" tap_desc="$2" text_pattern="$3" attempts="$4" wait_s="$5"
  local attempt label
  for attempt in $(seq 1 "$attempts"); do
    log "tap_until_text: attempt $attempt/$attempts — settling screen for '$tap_desc'"
    local settled=""
    for _ in $(seq 1 24); do
      if app_crashed; then
        log "APP CRASHED (logcat)"
        save_crash
        capture_anr_trace
        return 1
      fi
      dismiss_system_dialogs || return 1
      if ! grep -q "isn't responding" "$OUT/ui-dump.xml" \
         && find_node "$tap_pattern" > /dev/null; then
        settled=1
        break
      fi
      sleep 5
    done
    if [ -z "$settled" ]; then
      log "tap_until_text: '$tap_desc' never appeared on a dialog-free screen"
      return 1
    fi
    tap_node "$tap_pattern" "$tap_desc" || return 1
    sleep 2
    log "tap '$tap_desc' #$attempt sent; waiting for '$text_pattern'..."
    if label=$(wait_for_text "$text_pattern" "$wait_s"); then
      log "tap effect verified: $label"
      echo "$label"
      return 0
    fi
    log "expected text not visible after tap #$attempt"
  done
  return 1
}

# ---------- test ----------

echo "=== launching app (retries survive a wedged system_server) ==="
# run 102: system_server was wedged (framework had just restarted, system
# ANR dumps in logcat) and `adb shell am start` hung past ADB_TIMEOUT, so
# the run died before any UI was exercised. Re-issuing am start on a wedged
# system just re-sends the launch intent; bound the attempts and verify the
# app process is alive before continuing.
LAUNCH_OK=""
for attempt in $(seq 1 6); do
  log "launch attempt $attempt/6: am start"
  if adbw shell am start -n "$MAIN" > /dev/null 2>&1; then
    sleep 8
    if [ -n "$(adbw shell pidof "$PKG" 2>/dev/null)" ]; then
      LAUNCH_OK=1
      break
    fi
    log "am start returned ok but no $PKG process yet; retrying"
  else
    log "am start failed/timed out; retrying"
  fi
  sleep 30
done
[ -n "$LAUNCH_OK" ] || fail "AM START FAILED (6 attempts)"
screenshot "cachecleaner-home.png"

echo "=== launch health check ==="
if app_crashed; then
  echo "APP CRASHED ON LAUNCH"
  save_crash
  fail "APP CRASHED ON LAUNCH"
fi
if [ -z "$(adbw shell pidof "$PKG" 2>/dev/null)" ]; then
  fail "APP PROCESS NOT RUNNING after launch"
fi
echo "app process is alive"

echo "=== phase 2a: Select-all UI test ==="
# Wait for the list to load first, then tap-until-verified: re-tapping
# "Select all" is idempotent (selection is a set union), so a swallowed tap
# just costs one more attempt instead of failing the run.
wait_for_list_loaded 600 || fail "APP LIST NEVER LOADED"
CLEANLABEL=$(tap_until_text "^Select all$" "Select all" "^Clean cache for [0-9]+ apps$" 5 45) \
  || fail "CLEAN-BUTTON NEVER APPEARED"
N=$(echo "$CLEANLABEL" | grep -o "[0-9][0-9]*")
echo "select-all -> '$CLEANLABEL' (N=$N)"
if [ -z "$N" ] || [ "$N" -eq 0 ]; then
  fail "SELECT ALL SELECTED ZERO APPS"
fi
screenshot "cachecleaner-selected.png"
wait_and_tap "^Clear$" "Clear" 60 || fail "CLEAR TAP FAILED"
echo "select-all/clear UI OK"

echo "=== phase 2b: real single-app cache-clean run ==="
# Switch to the "With cache" filter so the list holds only apps with cache.
wait_and_tap "^With cache$" "With cache filter chip" 60 || fail "WITH-CACHE CHIP NOT FOUND"
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
) || fail "NO CHECKABLE APP ROW FOUND"
echo "tapping first app row at $FIRST_ROW"
# shellcheck disable=SC2086
adbw shell input tap $FIRST_ROW
CLEANLABEL=$(wait_for_text "^Clean cache for 1 apps$" 60) || fail "CLEAN-1 BUTTON NEVER APPEARED"
echo "selected 1 app -> '$CLEANLABEL'"
wait_and_tap "^Clean cache for 1 apps$" "Clean 1 app" 60 || fail "CLEAN TAP FAILED"

echo "=== waiting for the run to finish (up to 8 min) ==="
FOUND=""
for _ in $(seq 1 48); do
  sleep 10
  if app_crashed; then
    echo "APP CRASHED DURING RUN"
    save_crash
    fail "APP CRASHED DURING RUN"
  fi
  if adbw logcat -d -t 4000 2>/dev/null | grep -q "\[dbg\] run finished: cleaned="; then FOUND=1; break; fi
done
if [ -z "$FOUND" ]; then
  fail "RUN DID NOT FINISH IN TIME"
fi

echo "=== collecting clean-run diagnostics ==="
adbw logcat -d > "$OUT/logcat-full.txt" || true
grep -iE "cachecleaner|CacheAccess|CacheClear" "$OUT/logcat-full.txt" | tail -120 > "$OUT/logcat-cachecleaner.txt" || true
grep -a "CacheCleaner" "$OUT/logcat-full.txt" > "$OUT/clean-run-dbg.txt" || true
screenshot "cachecleaner-after.png"

DBG=$(grep -ac "\[dbg\]" "$OUT/clean-run-dbg.txt" || true)
CLEANED=$(grep -ac "\[dbg\] run finished: cleaned=" "$OUT/clean-run-dbg.txt" || true)
echo "engine [dbg] lines : $DBG"
echo "run-finished marks : $CLEANED"
grep -a "\[dbg\] run finished: cleaned=" "$OUT/clean-run-dbg.txt" | tail -1
if [ "$DBG" -eq 0 ]; then fail "ENGINE NEVER ENGAGED (no [dbg] lines)"; fi

echo "=== final crash check ==="
if app_crashed; then
  echo "APP CRASH DETECTED — see ci/out/app-crash.txt"
  save_crash
  fail "APP CRASH DETECTED"
fi

echo "EMULATOR TEST OK"
