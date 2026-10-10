#!/bin/bash
# End-to-end: real Android Auto session via the Desktop Head Unit (DHU), real drop by killing it.
#
# Setup (once): SDK Manager → "Android Auto Desktop Head Unit Emulator" (2.1+), and on the phone
# Android Auto → Developer settings → ⋮ → Start head unit server. If the DHU connects but nothing
# happens, stop/start the head unit server and run again.
#
# Usage: scripts/e2e.sh <adb-serial> <case: music_nav | nothing>
SERIAL=$1; CASE=${2:-music_nav}
S=${TMPDIR:-/tmp}/aa-rescue-e2e; mkdir -p "$S"
DHU_DIR=~/Library/Android/sdk/extras/google/auto
a(){ ~/Library/Android/sdk/platform-tools/adb -s "$SERIAL" "$@"; }
yt(){ a shell dumpsys media_session | grep -A30 "package=com.google.android.apps.youtube.music" | grep -m1 -oE "state=[A-Z_]+" | cut -d= -f2; }
nav(){ a shell dumpsys notification --noredact | grep -c "category=navigation"; }
log(){ echo "[$(date +%T)] $*"; }

start_dhu() {
  pkill -f desktop-head-unit; pkill -f "tail -f $S/dhu.in"; sleep 1
  a forward tcp:5277 tcp:5277 >/dev/null
  rm -f "$S/dhu.in"; mkfifo "$S/dhu.in"
  (cd "$DHU_DIR" && nohup sh -c "tail -f $S/dhu.in | ./desktop-head-unit -a" > "$S/dhu.log" 2>&1 &)
  for i in $(seq 1 20); do
    sleep 2
    a logcat -d -s AARescue | grep -q "NOT_CONNECTED -> PROJECTION" && { log "DHU connected (projection)"; return 0; }
  done
  log "DHU did not connect"; return 1
}

T0=$(($(date +%s)*1000))
a logcat -c
start_dhu || exit 1
sleep 5

if [ "$CASE" = music_nav ]; then
  a shell am start -a android.intent.action.VIEW -d "'google.navigation:q=${E2E_DEST:-Space+Needle+Seattle}'" -p com.google.android.apps.maps >/dev/null
  a shell cmd media_session dispatch play
  sleep 12
else
  a shell cmd media_session dispatch pause
  sleep 6
fi
log "before drop: yt=$(yt) nav_notifications=$(nav)"

log "killing DHU (real Android Auto drop)"
pkill -f desktop-head-unit; pkill -f "tail -f $S/dhu.in"
sleep 10
log "after drop: yt=$(yt) nav_notifications=$(nav) front=$(a shell dumpsys activity activities | grep -m1 topResumedActivity | grep -oE '[a-z.]+/[A-Za-z.]+' | head -1)"
a exec-out screencap -p > "$S/e2e_after_$CASE.png"

log "reconnecting DHU"
a logcat -c
start_dhu && sleep 8 && log "after reconnect: yt=$(yt)"

echo "== AA Rescue log (this run)"
a shell dumpsys activity service dev.nish.aarescue/.MediaWatcher | while read t rest; do
  case "$t" in ''|*[!0-9]*) continue;; esac
  [ "$t" -ge "$T0" ] && printf "%s %s\n" "$(date -r $((t/1000)) +%T)" "$rest"
done | grep -v "keys=\["
pkill -f desktop-head-unit; pkill -f "tail -f $S/dhu.in"; log "DHU stopped"
