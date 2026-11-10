package cn.astriafr.acobattery.daemon;

/** Keep concurrent active records above history, with game statistics first. */
final class RecordOrder {
    private static final String RANK="CASE WHEN active=1 AND fast_seconds>0 THEN 0 WHEN active=1 THEN 1 ELSE 2 END";
    static final String SQL=" ORDER BY "+RANK+",id DESC";
    static final String AFTER="("+RANK+">? OR ("+RANK+"=? AND id<?))";
}
