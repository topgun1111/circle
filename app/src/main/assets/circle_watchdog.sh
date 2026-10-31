#!/system/bin/sh
# Circle to Search keep-alive watchdog. Runs as root (Magisk / KernelSU / APatch).
#
# Why: HyperOS kills the app process in the background. Android then marks the accessibility
# service as "crashed" and never restarts it until it is switched off and on again.
# This script does exactly that switch-off/on whenever it sees the service dead.
#
#   sh circle_watchdog.sh          run the watchdog loop (installed in /data/adb/service.d)
#   sh circle_watchdog.sh revive   toggle the service once and exit

PKG=com.example.circlesearch
SVC=$PKG/$PKG.CircleService
SELF=/data/adb/service.d/circle_watchdog.sh
LOG=/data/local/tmp/circle_watchdog.log
PIDF=/data/local/tmp/circle_watchdog.pid

log() {
  echo "$(date '+%m-%d %H:%M:%S') $*" >> "$LOG"
  if [ "$(wc -c < "$LOG" 2>/dev/null || echo 0)" -gt 30000 ]; then
    tail -n 80 "$LOG" > "$LOG.t" && mv "$LOG.t" "$LOG"
  fi
}

enabled() {
  settings get secure enabled_accessibility_services 2>/dev/null | grep -q "$PKG"
}

# Android lists the service as "Crashed services" in dumpsys once its process died.
crashed() {
  dumpsys accessibility 2>/dev/null | grep -i "crashed" | grep -q "$PKG"
}

# Switch our service off and on again in the secure setting; other services are left alone.
revive() {
  cur=$(settings get secure enabled_accessibility_services 2>/dev/null)
  [ "$cur" = "null" ] && cur=""
  out=""
  OLDIFS=$IFS
  IFS=':'
  for s in $cur; do
    case "$s" in
      "" | *"$PKG"*) ;;
      *) out="${out:+$out:}$s" ;;
    esac
  done
  IFS=$OLDIFS
  if [ -n "$out" ]; then
    settings put secure enabled_accessibility_services "$out"
  else
    settings delete secure enabled_accessibility_services
  fi
  sleep 1
  settings put secure enabled_accessibility_services "${out:+$out:}$SVC"
  settings put secure accessibility_enabled 1
}

harden() {
  dumpsys deviceidle whitelist +$PKG >/dev/null 2>&1
  cmd appops set $PKG RUN_IN_BACKGROUND allow >/dev/null 2>&1
  cmd appops set $PKG RUN_ANY_IN_BACKGROUND allow >/dev/null 2>&1
  cmd appops set $PKG 10008 allow >/dev/null 2>&1 # MIUI / HyperOS "Autostart"
}

protect() {
  for p in $(pidof $PKG); do
    echo -1000 > /proc/$p/oom_score_adj 2>/dev/null
  done
}

if [ "$1" = "revive" ]; then
  revive
  exit 0
fi

# Only one watchdog at a time.
if [ -f "$PIDF" ]; then
  old=$(cat "$PIDF" 2>/dev/null)
  if [ -n "$old" ] && [ "$old" != "$$" ] && grep -q circle_watchdog "/proc/$old/cmdline" 2>/dev/null; then
    exit 0
  fi
fi
echo $$ > "$PIDF"

# Wait for the system to finish booting, then give it time to bind accessibility services itself.
until [ "$(getprop sys.boot_completed)" = "1" ]; do sleep 5; done
sleep 20

log "watchdog started (pid $$)"
harden

i=0
gone=0
while true; do
  # App uninstalled: remove ourselves.
  if [ ! -d "/data/data/$PKG" ]; then
    gone=$((gone + 1))
    if [ "$gone" -ge 3 ]; then
      log "app uninstalled, watchdog removed"
      rm -f "$SELF" "$PIDF"
      exit 0
    fi
  else
    gone=0
    # Only act while the user wants the service on (it is still in the enabled list).
    # If they switch it off, it disappears from the list and we leave it alone.
    if enabled; then
      if [ -z "$(pidof $PKG)" ]; then
        log "process is gone -> reviving service"
        revive
        sleep 10
      elif [ $((i % 6)) -eq 0 ] && crashed; then
        log "service marked as crashed -> reviving service"
        revive
        sleep 10
      fi
      protect
    fi
  fi
  i=$((i + 1))
  sleep 5
done
