package cn.astriafr.acobattery.common;

import java.util.Locale;

/** A display cadence, deliberately independent of every recording cadence. */
public final class PowerDisplayState {
    public boolean enabled;
    private long next;
    public PowerDisplayState(boolean enabled){this.enabled=enabled;}
    public long deadline(boolean interactive){return enabled&&interactive?next:Long.MAX_VALUE;}
    public boolean due(long now,boolean interactive){return enabled&&interactive&&now>=next;}
    public void refresh(){next=0;}
    public void sampled(long now,long seconds){next=now+Math.max(1,Math.min(60,seconds))*1000;}
    public static String text(double watts){
        return Double.isNaN(watts)||Double.isInfinite(watts)?"—W":String.format(Locale.US,"%.2fW",Math.abs(watts));
    }
}
