#!/system/bin/sh
# Voltage writes are available only through a one-time confirmed commit.
set -eu
STATE=/data/adb/aco_battery_app/cutoff
BASE=/proc/oplus-votable
NODES="GAUGE_TERM_VOLTAGE GAUGE_SHUTDOWN_VOLTAGE"
LOCKED=0
TRANSACTION=0
umask 077
fail() { printf '%s\n' "$*" >&2; exit 1; }
rollback() {
 set +e
 while read -r node active value; do
  echo "$value" > "$BASE/$node/force_val"
  echo "$active" > "$BASE/$node/force_active"
 done < "$STATE/cutoff-snapshot"
 printf '%s cutoff rollback attempted\n' "$(date -Iseconds)" >> "$STATE/events.log"
}
cleanup() {
 rc=$?
 trap - EXIT
 if [ "$TRANSACTION" = 1 ]; then rollback; fi
 if [ "$LOCKED" = 1 ]; then
  rm -f "$STATE/lock/pid"
  rmdir "$STATE/lock" 2>/dev/null || :
 fi
 exit "$rc"
}
trap cleanup EXIT
trap 'exit 130' INT TERM HUP
[ "$(id -u)" = 0 ] || fail "需要 root。"
for n in $NODES; do
 [ -f "$BASE/$n/status" ] && [ -f "$BASE/$n/force_active" ] && [ -f "$BASE/$n/force_val" ] || fail "截止电压节点尚未就绪：$n"
done
mkdir -p "$STATE"
chmod 0700 "$STATE"
lock() {
 attempts=0
 while ! mkdir "$STATE/lock" 2>/dev/null; do
  attempts=$((attempts + 1))
  [ "$attempts" -le 10 ] || fail "另一项操作正在进行，请稍后刷新。"
  if [ -f "$STATE/lock/pid" ]; then
   owner=$(cat "$STATE/lock/pid")
   case "$owner" in ''|*[!0-9]*) fail "锁状态异常，请稍后重试。";; esac
   if ! kill -0 "$owner" 2>/dev/null; then
    rm -f "$STATE/lock/pid"
    rmdir "$STATE/lock" 2>/dev/null || :
   fi
  fi
  sleep 1
 done
 LOCKED=1
 echo "$$" > "$STATE/lock/pid"
}
effective() { sed -n 's/.*effective=.* v=\([-0-9]*\)$/\1/p' "$BASE/$1/status"; }
status() {
 printf '{"term":"%s","shutdown":"%s","termForced":"%s","shutdownForced":"%s"}\n' \
  "$(effective GAUGE_TERM_VOLTAGE)" "$(effective GAUGE_SHUTDOWN_VOLTAGE)" \
  "$(cat "$BASE/GAUGE_TERM_VOLTAGE/force_active")" "$(cat "$BASE/GAUGE_SHUTDOWN_VOLTAGE/force_active")"
}
fingerprint() {
 for n in $NODES; do cat "$BASE/$n/force_active" "$BASE/$n/force_val"; done | cksum | awk '{print $1}'
}
validate_voltages() {
 for value in "$1" "$2"; do
  case "$value" in [0-9][0-9][0-9][0-9]) :;; *) fail "请输入四位整数电压，单位 mV。";; esac
  [ "$value" -ge 2600 ] && [ "$value" -le 3400 ] || fail "可调范围为 2600–3400 mV。"
 done
}
snapshot() {
 : > "$STATE/cutoff-snapshot"
 for n in $NODES; do
  printf '%s %s %s\n' "$n" "$(cat "$BASE/$n/force_active")" "$(cat "$BASE/$n/force_val")" >> "$STATE/cutoff-snapshot"
 done
 TRANSACTION=1
}
force() { echo "$2" > "$BASE/$1/force_val"; echo 1 > "$BASE/$1/force_active"; }
verify_force() {
 [ "$(cat "$BASE/$1/force_active")" = 1 ] && [ "$(cat "$BASE/$1/force_val")" = "$2" ] &&
 grep -q "effective=DEBUG_FORCE_CLIENT type=Max v=$2\$" "$BASE/$1/status"
}
apply() {
 snapshot
 if [ "$1" = set ]; then
  force GAUGE_TERM_VOLTAGE "$3"
  force GAUGE_SHUTDOWN_VOLTAGE "$2"
 else
  for n in $NODES; do
   echo 0 > "$BASE/$n/force_active"
   echo 0 > "$BASE/$n/force_val"
  done
 fi
 sleep 2
 if [ "$1" = set ]; then
  verify_force GAUGE_TERM_VOLTAGE "$3" || fail "电量计截止读回失败，尝试回滚。"
  verify_force GAUGE_SHUTDOWN_VOLTAGE "$2" || fail "关机阈值读回失败，尝试回滚。"
  [ "$(cat /sys/class/oplus_chg/battery/vbat_uv)" = "$2" ] || fail "关机阈值未下发，尝试回滚。"
 else
  for n in $NODES; do
   [ "$(cat "$BASE/$n/force_active")" = 0 ] && [ "$(cat "$BASE/$n/force_val")" = 0 ] || fail "取消覆盖失败：$n，尝试回滚。"
   if grep -q 'effective=DEBUG_FORCE_CLIENT' "$BASE/$n/status"; then fail "覆盖仍生效：$n，尝试回滚。"; fi
  done
 fi
 TRANSACTION=0
}
case "${1:-status}" in
 status) status;;
 plan)
  action=${2:-}
  case "$action" in
   set) [ -r /sys/class/oplus_chg/battery/vbat_uv ] || fail "当前驱动不支持关机阈值读回。"; shutdown=${3:-}; term=${4:-}; validate_voltages "$shutdown" "$term";;
   stock) shutdown=0; term=0;;
   *) fail "无效选择。";;
  esac
  lock
  token=$(cat /proc/sys/kernel/random/uuid)
  expires=$(($(date +%s) + 300))
  printf '%s %s %s %s %s %s\n' "$action" "$shutdown" "$term" "$token" "$expires" "$(fingerprint)" > "$STATE/cutoff-pending"
  printf '{"action":"%s","shutdown":"%s","term":"%s","token":"%s"}\n' "$action" "$shutdown" "$term" "$token"
  ;;
 commit)
  token=${2:-}
  [ -n "$token" ] || fail "缺少确认凭据。"
  lock
  [ -f "$STATE/cutoff-pending" ] || fail "请重新打开确认窗口。"
  read -r action shutdown term expected expires before < "$STATE/cutoff-pending"
  [ "$token" = "$expected" ] || fail "确认凭据无效。"
  [ "$(date +%s)" -le "$expires" ] || fail "确认已过期，请重试。"
  [ "$(fingerprint)" = "$before" ] || fail "手机状态已变化，请刷新后重新确认。"
  case "$action" in set) validate_voltages "$shutdown" "$term";; stock) :;; *) fail "无效选择。";; esac
  rm -f "$STATE/cutoff-pending"
  apply "$action" "$shutdown" "$term"
  printf '%s confirmed cutoff %s shutdown=%s term=%s\n' "$(date -Iseconds)" "$action" "$shutdown" "$term" >> "$STATE/events.log"
  status
  ;;
 *) fail "无效命令。";;
esac
