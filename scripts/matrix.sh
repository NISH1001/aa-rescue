#!/bin/bash
# Scenario matrix: real Android Auto session (DHU) + real drops, checks real phone state after.
# Setup: see scripts/e2e.sh. Trips are started on the car screen by tapping the first suggestion
# and Start; card layouts vary, so if "(could not start trip on car screen)" shows up, start a
# trip in the DHU window by hand and re-run — an existing connection and trip are reused.
#
# Usage: scripts/matrix.sh <adb-serial> [s1 s2 s3 s4 s5 s6]
SERIAL=$1; shift; CASES=${*:-"s1 s2 s3 s4 s5 s6"}
S=${TMPDIR:-/tmp}/aa-rescue-e2e; mkdir -p "$S"; DHU_DIR=~/Library/Android/sdk/extras/google/auto
a(){ ~/Library/Android/sdk/platform-tools/adb -s "$SERIAL" "$@"; }
now_ms(){ echo $(($(date +%s)*1000)); }
log(){ echo "[$(date +%T)] $*"; }
state(){ a shell dumpsys activity service dev.nish.aarescue/.MediaWatcher state | grep -o "STATE.*"; }
front(){ a shell dumpsys activity activities | grep -m1 topResumedActivity | grep -oE '[a-z][a-z0-9.]+/' | head -1 | tr -d /; }
trace_since(){ a shell dumpsys activity service dev.nish.aarescue/.MediaWatcher | while read t rest; do
  case "$t" in ''|*[!0-9]*) continue;; esac; [ "$t" -ge "$1" ] && echo "$rest"; done | grep -v "keys=\["; }
PASS=0; FAIL=0
check(){ if eval "$2"; then log "  PASS: $1"; PASS=$((PASS+1)); else log "  FAIL: $1"; FAIL=$((FAIL+1)); fi; }

dhu_up(){
  state | grep -q "projecting=true" && pgrep -q desktop-head-unit && return 0
  pkill -f desktop-head-unit; pkill -f "tail -f $S/dhu.in"; sleep 1
  a forward tcp:5277 tcp:5277 >/dev/null; rm -f "$S/dhu.in"; mkfifo "$S/dhu.in"
  (cd "$DHU_DIR" && nohup sh -c "tail -f $S/dhu.in | ./desktop-head-unit -a" > "$S/dhu.log" 2>&1 &)
  for i in $(seq 1 20); do sleep 2; state | grep -q "projecting=true" && return 0; done
  log "DHU did not connect"; return 1
}
dhu_down(){ pkill -f desktop-head-unit; pkill -f "tail -f $S/dhu.in"; }
music(){ a shell cmd media_session dispatch "$1"; }
car(){ echo "$*" > "$S/dhu.in"; }
# Start/end a trip on the car screen (DHU 800x480): first suggestion → Start; trip card ✕.
trip_on(){ state | grep -q "trip=true" && return 0
  car tap 250 225; sleep 4; car tap 177 277; sleep 8
  state | grep -q "trip=true" || { car screenshot "$S/car_fail.png"; log "  (could not start trip on car screen)"; }; }
trip_off(){ state | grep -q "trip=false" && return 0; car tap 130 360; sleep 5
  state | grep -q "trip=false" || log "  (could not end trip on car screen)"; }

# setup <trip yes|no> <music play|pause>; drop; wait; then the case's checks.
run_drop(){ [ "$KEEP_FRONT" = 1 ] || a shell input keyevent KEYCODE_HOME; sleep 1; T=$(now_ms); log "  before: $(state) front=$(front)"; dhu_down; sleep 10; AFTER=$(state); FRONT=$(front); TR=$(trace_since $T)
  log "  after:  $AFTER front=$FRONT"; echo "$TR" | grep -E "DROP|EVENT|tapped|opened" | sed 's/^/      /'; }

a shell svc power stayon true; a shell input keyevent KEYCODE_WAKEUP

for c in $CASES; do case $c in
s1) log "S1: no trip, no music"
    dhu_up || exit 1; trip_off; music pause; sleep 4; run_drop
    check "music stays off" '! echo "$AFTER" | grep -q "youtube.music"'
    check "Maps not opened" '! echo "$TR" | grep -q "Maps opened"'
    check "event says left as is" 'echo "$TR" | grep -q "nothing was playing, left as is"' ;;
s2) log "S2: no trip, music playing"
    dhu_up || exit 1; trip_off; music play; sleep 6; run_drop
    check "music resumed" 'echo "$AFTER" | grep -q "youtube.music"'
    check "Maps not opened" '! echo "$TR" | grep -q "Maps opened"'
    check "no trip started" 'echo "$AFTER" | grep -q "trip=false"' ;;
s3) log "S3: trip, no music"
    dhu_up || exit 1; music pause; trip_on; sleep 3; run_drop
    check "trip running after drop" 'echo "$AFTER" | grep -q "trip=true"'
    check "music stays off" '! echo "$AFTER" | grep -q "youtube.music"'
    check "Maps in front" '[ "$FRONT" = com.google.android.apps.maps ]' ;;
s4) log "S4: trip + music, Settings app in front at the drop"
    dhu_up || exit 1; music play; trip_on; sleep 3
    a shell am start -a android.settings.SETTINGS >/dev/null; sleep 3; KEEP_FRONT=1 run_drop
    check "music resumed" 'echo "$AFTER" | grep -q "youtube.music"'
    check "trip running after drop" 'echo "$AFTER" | grep -q "trip=true"'
    check "Maps brought to front" '[ "$FRONT" = com.google.android.apps.maps ]' ;;
s5) log "S5: drop + instant reconnect"
    dhu_up || exit 1; music play; trip_on; sleep 3; T=$(now_ms); log "  before: $(state)"
    dhu_down; sleep 1; dhu_up; sleep 8; AFTER=$(state); TR=$(trace_since $T)
    log "  after reconnect: $AFTER"; echo "$TR" | grep -E "DROP|EVENT|tapped|opened|projection|PROJECTION" | sed 's/^/      /'
    check "connected again" 'echo "$AFTER" | grep -q "projecting=true"'
    check "music playing" 'echo "$AFTER" | grep -q "youtube.music"'
    check "no Start tapped after reconnect" '! echo "$TR" | sed -n "/NOT_CONNECTED -> PROJECTION/,\$p" | grep -q "tapped"' ;;
s6) log "S6: two drops in a row"
    for n in 1 2; do dhu_up || exit 1; music play; trip_on; sleep 3; run_drop
      check "drop $n: music resumed" 'echo "$AFTER" | grep -q "youtube.music"'
      check "drop $n: trip running" 'echo "$AFTER" | grep -q "trip=true"'; done ;;
esac; done

dhu_down; a shell svc power stayon false
log "RESULT: $PASS passed, $FAIL failed"
