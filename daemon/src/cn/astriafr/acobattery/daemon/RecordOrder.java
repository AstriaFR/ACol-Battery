package cn.astriafr.acobattery.daemon;

/** Keep concurrent active records above history, with game statistics first. */
final class RecordOrder {
    static final String SQL=" ORDER BY CASE WHEN active=1 AND fast_seconds>0 THEN 0 WHEN active=1 THEN 1 ELSE 2 END,id DESC";
}
