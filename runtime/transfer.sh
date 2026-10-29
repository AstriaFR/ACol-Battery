#!/system/bin/sh
set -eu
export CLASSPATH=/data/adb/aco_battery_app/runtime/bin/daemon.dex.jar
exec /system/bin/app_process /system/bin cn.astriafr.acobattery.daemon.Admin transfer-run
