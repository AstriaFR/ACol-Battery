#!/system/bin/sh
BASE=/data/adb/aco_battery_app
RUNTIME=$BASE/runtime
while [ "$(getprop sys.boot_completed)" != 1 ]; do sleep 5; done
if [ -f "$BASE/update.transaction" ]; then
  RECOVERY=$RUNTIME/update-stage/bin/daemon.dex.jar
  [ -f "$RECOVERY" ] || RECOVERY=$RUNTIME/bin/daemon.dex.jar
  CLASSPATH="$RECOVERY" /system/bin/app_process /system/bin cn.astriafr.acobattery.daemon.Admin recover-update boot >> "$BASE/boot.log" 2>&1 || exit 1
fi
attempt=0
while [ "$attempt" -lt 20 ] && [ -f "$RUNTIME/bin/daemon.dex.jar" ]; do
  CLASSPATH="$RUNTIME/bin/daemon.dex.jar" /system/bin/app_process /system/bin cn.astriafr.acobattery.daemon.Admin boot >> "$BASE/boot.log" 2>&1
  code=$?
  [ "$code" = 0 ] && exit 0
  attempt=$((attempt+1))
  sleep 15
done
