#!/system/bin/sh
set -eu
BASE=/data/adb/aco_battery_app
RUNTIME=$BASE/runtime
export CLASSPATH=$RUNTIME/bin/daemon.dex.jar
umask 077
mkdir -p "$BASE"
echo $$ > "$BASE/supervisor.pid"
while [ -f "$RUNTIME/bin/daemon.dex.jar" ]; do
  [ -f "$BASE/update.pending" ] && exit 0
  if [ -f "$BASE/cleanup.request" ]; then
    /system/bin/app_process /system/bin cn.astriafr.acobattery.daemon.Admin cleanup
    exit 0
  fi
  if [ -f "$BASE/daemon.log" ] && [ "$(wc -c < "$BASE/daemon.log")" -gt 262144 ]; then
    mv -f "$BASE/daemon.log" "$BASE/daemon.previous.log"
  fi
  set +e
  /system/bin/app_process /system/bin cn.astriafr.acobattery.daemon.Main "$RUNTIME" "$BASE" >> "$BASE/daemon.log" 2>&1
  code=$?
  set -e
  [ "$code" = 73 ] && exit 0
  [ -f "$BASE/update.pending" ] && exit 0
  if [ -f "$BASE/cleanup.request" ]; then
    /system/bin/app_process /system/bin cn.astriafr.acobattery.daemon.Admin cleanup
    exit 0
  fi
  if [ "$code" = 75 ]; then sleep 1; else sleep 10; fi
done
