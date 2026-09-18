#!/usr/bin/env bash
# Red-Nose device provisioning (red-nose.md 14.2).
#
# Runs every command through `su` on the device (over `adb shell su -c ...`)
# and reads each value back so a failed line is visible rather than assumed.
# Idempotent: every step is safe to re-run.  There is nothing here that
# needs to run on the CI runner; this script is executed from the operator's
# laptop against a phone plugged into USB.
#
# Usage: provisioning/provision.sh [-s <adb-serial>]

set -u

PKG=com.wmsfo.rednose

ADB_ARGS=()
if [ "${1:-}" = "-s" ] && [ -n "${2:-}" ]; then
  ADB_ARGS=(-s "$2")
  shift 2
fi

pass_count=0
fail_count=0
declare -a failures=()

# adb_su <cmd...>: run a shell command on the device as root.
adb_su() {
  adb "${ADB_ARGS[@]}" shell su -c "$*"
}

# adb_su_out <cmd...>: same, capturing stdout stripped of the trailing CR
# that adb tends to add.
adb_su_out() {
  adb "${ADB_ARGS[@]}" shell su -c "$*" | tr -d '\r'
}

record_pass() {
  pass_count=$((pass_count + 1))
  printf '  ok   %s\n' "$1"
}

record_fail() {
  fail_count=$((fail_count + 1))
  failures+=("$1")
  printf '  FAIL %s (%s)\n' "$1" "$2"
}

# expect <label> <expected> <actual>
expect() {
  local label="$1" expected="$2" actual="$3"
  if [ "$actual" = "$expected" ]; then
    record_pass "$label"
  else
    record_fail "$label" "expected='$expected' actual='$actual'"
  fi
}

# expect_nonempty <label> <actual>
expect_nonempty() {
  local label="$1" actual="$2"
  if [ -n "$actual" ]; then
    record_pass "$label"
  else
    record_fail "$label" "empty"
  fi
}

# expect_contains <label> <needle> <haystack>
expect_contains() {
  local label="$1" needle="$2" haystack="$3"
  case "$haystack" in
    *"$needle"*) record_pass "$label" ;;
    *)           record_fail "$label" "missing '$needle'" ;;
  esac
}

section() {
  printf '\n== %s ==\n' "$1"
}

# --- preflight ------------------------------------------------------------

if ! command -v adb >/dev/null 2>&1; then
  echo "adb not on PATH" >&2
  exit 2
fi

DEVICES="$(adb "${ADB_ARGS[@]}" devices | awk 'NR>1 && $2=="device" {print $1}')"
if [ -z "$DEVICES" ]; then
  echo "no adb device attached (adb devices)" >&2
  exit 2
fi

ROOT_ID="$(adb_su_out id 2>/dev/null)"
case "$ROOT_ID" in
  *"uid=0"*) ;;
  *) echo "su did not return uid=0 (got: $ROOT_ID)" >&2; exit 2 ;;
esac

# --- 14.2 rows ------------------------------------------------------------

section "runtime permissions"
for perm in \
  ACCESS_FINE_LOCATION \
  ACCESS_COARSE_LOCATION \
  ACCESS_BACKGROUND_LOCATION \
  POST_NOTIFICATIONS \
  READ_PHONE_STATE; do
  adb_su "pm grant $PKG android.permission.$perm" >/dev/null 2>&1 || true
  actual="$(adb_su_out "dumpsys package $PKG | grep 'android.permission.$perm: granted='")"
  expect_contains "$perm granted" "granted=true" "$actual"
done

section "location appops (fine + coarse not downgraded)"
for op in FINE_LOCATION COARSE_LOCATION; do
  adb_su "appops set $PKG $op allow" >/dev/null 2>&1 || true
  actual="$(adb_su_out "appops get $PKG $op")"
  expect_contains "appops $op" "allow" "$actual"
done

section "battery / Doze allowlist"
adb_su "dumpsys deviceidle whitelist +$PKG" >/dev/null 2>&1 || true
whitelist="$(adb_su_out "dumpsys deviceidle whitelist")"
expect_contains "deviceidle whitelist" "$PKG" "$whitelist"

section "location services on"
adb_su "settings put secure location_mode 3" >/dev/null 2>&1 || true
mode="$(adb_su_out "settings get secure location_mode")"
expect "location_mode == 3" "3" "$mode"

section "stay awake while charging"
adb_su "settings put global stay_on_while_plugged_in 7" >/dev/null 2>&1 || true
stay="$(adb_su_out "settings get global stay_on_while_plugged_in")"
expect "stay_on_while_plugged_in == 7" "7" "$stay"

section "root for the app (Magisk policy, no on-screen prompt)"
app_uid="$(adb_su_out "stat -c %u /data/data/$PKG")"
if [ -n "$app_uid" ]; then
  adb_su "magisk --sqlite \\\"REPLACE INTO policies (uid,policy,until,logging,notification) VALUES ($app_uid,2,0,1,0)\\\"" >/dev/null 2>&1 || true
fi
policy="$(adb_su_out "magisk --sqlite \\\"SELECT policy FROM policies WHERE uid=$app_uid\\\"")"
expect "magisk su policy for uid $app_uid == allow" "policy=2" "$policy"

section "permission auto-revoke off"
adb_su "appops set $PKG AUTO_REVOKE_PERMISSIONS_IF_UNUSED ignore" >/dev/null 2>&1 || true
auto_revoke="$(adb_su_out "appops get $PKG AUTO_REVOKE_PERMISSIONS_IF_UNUSED")"
expect_contains "appops AUTO_REVOKE_PERMISSIONS_IF_UNUSED ignore" "ignore" "$auto_revoke"

section "safe boot disallowed"
adb_su "settings put global safe_boot_disallowed 1" >/dev/null 2>&1 || true
sb="$(adb_su_out "settings get global safe_boot_disallowed")"
expect "safe_boot_disallowed == 1" "1" "$sb"

section "system app + persistent"
flags="$(adb_su_out "dumpsys package $PKG | grep -E 'flags=|persistent='")"
expect_contains "FLAG_SYSTEM set" "SYSTEM" "$flags"
# Android 14 and earlier print a `persistent=true` line; Android 15 reports it as
# PERSISTENT inside flags=[ ... ]. Accept either.
case "$flags" in
  *"persistent=true"*|*" PERSISTENT "*) record_pass "persistent (manifest android:persistent honoured)" ;;
  *) record_fail "persistent (manifest android:persistent honoured)" "neither persistent=true nor PERSISTENT flag" ;;
esac

# --- summary --------------------------------------------------------------

section "summary"
printf '  passed: %d\n' "$pass_count"
printf '  failed: %d\n' "$fail_count"

if [ "$fail_count" -gt 0 ]; then
  printf '\nfailing rows:\n'
  for f in "${failures[@]}"; do
    printf '  - %s\n' "$f"
  done
  exit 1
fi

exit 0
