package cn.astriafr.acobattery.daemon;

import java.util.*;

/** Hardware-independent rules. All time arguments are monotonic milliseconds. */
public final class Core {
    public static final class Reading {
        public long elapsed, wall;
        public double rm = Double.NaN, pct = Double.NaN, current = Double.NaN;
        public double vmax = Double.NaN, vmin = Double.NaN, fv = Double.NaN, temp = Double.NaN;
        public double cell1 = Double.NaN, cell2 = Double.NaN;
        public int cellCount=2;
        public String voltageSource="",currentSource="",rmSource="",onlineSource="";
        public boolean online, full, enabled;
        public boolean tailAvailable;
        public boolean chargeEnabledKnown;
        public String tailSource="unavailable";
        public boolean onlineKnown=true;
        public String source = "", error = "";
        public double voltage() { return cellCount==0?Double.NaN:cellCount==1?vmin:(vmax + vmin) / 2; }
        // OPLUS gauge current is negative while charging.
        public double chargingCurrent() { return -current; }
        public double compensatedCurrent() { return chargingCurrent() * (cellCount==2?2:1); }
        public double power() { return cellCount==0?Double.NaN:compensatedCurrent() * voltage() / 1_000_000; }
        public boolean valid() { return finite(rm) && finite(pct) && rm >= 0 && pct >= 0 && pct <= 100; }
    }
    public static boolean finite(double v) { return !Double.isNaN(v) && !Double.isInfinite(v); }
    /** Android CHARGE_COUNTER already represents remaining capacity in microamp-hours. */
    public static double remainingMah(long microampHours) {
        return microampHours>=0&&microampHours<=Integer.MAX_VALUE?microampHours/1000.0:Double.NaN;
    }
    /** elapsedRealtime survives a framework restart, but never a kernel reboot. */
    public static boolean resumeSession(String savedBoot,String boot,String kind,boolean wasFast,
            boolean online,boolean fast,long start,long end,long now) {
        return boot!=null&&!boot.isEmpty()&&boot.equals(savedBoot)&&start>=0&&end>=start&&now>=end
            &&(fast?wasFast&&"discharge".equals(kind):!wasFast&&(online?"charge":"discharge").equals(kind));
    }
    public static long gamePowerInterval(boolean online,long gameSeconds,long chargeSeconds){
        return (online?chargeSeconds:gameSeconds)*1000;
    }
    public static boolean gameDischargeInterval(Reading before,Reading after){
        return before.onlineKnown&&after.onlineKnown&&!before.online&&!after.online;
    }
    public static boolean resumableStop(String reason) {
        return "service_updated".equals(reason)||"framework_restarted".equals(reason);
    }
    public static int detectCellCount(String raw) {
        String[] fields=raw.trim().split(",",-1);
        if(fields.length<2)return 0;
        return "1".equals(fields[1].trim())?1:"2".equals(fields[1].trim())?2:0;
    }
    static double parse(String raw){try{return Double.parseDouble(raw.trim());}catch(Exception e){return Double.NaN;}}
    static boolean cellVoltage(double value){return finite(value)&&value>=2200&&value<=4800;}
    /** Read one BCC response; single-cell devices never require field 11. */
    public static boolean applyBcc(Reading r,String raw) {
        String[] fields=raw.trim().split(",",-1);
        if(fields.length!=19)return false;
        double a=parse(fields[6]),b=r.cellCount==2?parse(fields[11]):Double.NaN;
        if(r.cellCount==1&&cellVoltage(a)){r.cell1=a;r.cell2=Double.NaN;r.vmin=r.vmax=a;r.voltageSource="bcc";}
        else if(r.cellCount==2&&cellVoltage(a)&&cellVoltage(b)){
            r.cell1=a;r.cell2=b;r.vmax=Math.max(a,b);r.vmin=Math.min(a,b);r.voltageSource="bcc";
        }
        double current=parse(fields[8]),temp=parse(fields[7])/10;
        if(finite(current)&&Math.abs(current)<=30000){r.current=current;r.currentSource="bcc";}
        if(finite(temp)&&temp>=-40&&temp<=100)r.temp=temp;
        return finite(r.voltage())&&finite(r.current);
    }
    /** Android voltage is mV; CURRENT_NOW is microamps, positive while charging. */
    public static void applySystem(Reading r,double millivolts,double microamps,double temperature) {
        if(!finite(r.voltage())&&r.cellCount>0){
            double voltage=r.cellCount==2&&millivolts>6000?millivolts/2:millivolts;
            if(cellVoltage(voltage)){
                r.vmin=r.vmax=voltage;r.cell1=r.cellCount==1?voltage:Double.NaN;r.cell2=Double.NaN;r.voltageSource="android";
            }
        }
        if(!finite(r.current)&&finite(microamps)&&Math.abs(microamps)<=30000000){r.current=-microamps/1000;r.currentSource="android";}
        if(!finite(r.temp)&&finite(temperature)&&temperature>=-40&&temperature<=100)r.temp=temperature;
    }
    // battery_rm discharge uses the cell-mean voltage basis; do not multiply by 2S again.
    public static double dischargeEnergyMwh(double cellMeanA,double cellMeanB,double mah) {
        if(!finite(cellMeanA)||!finite(cellMeanB)||cellMeanA<=0||cellMeanB<=0||!finite(mah)||mah<0)return Double.NaN;
        return mah*(cellMeanA+cellMeanB)/2000;
    }
    public static double averagePower(double energyMwh,long ms) {
        return finite(energyMwh)&&energyMwh>=0&&ms>0?energyMwh*3600/ms:Double.NaN;
    }
    public static double instantEnergyMwh(double signedPowerA,double signedPowerB,long ms){
        if(!finite(signedPowerA)||!finite(signedPowerB)||signedPowerA>0||signedPowerB>0||ms<=0)return Double.NaN;
        return -(signedPowerA+signedPowerB)/2*ms/3600;
    }
    public static double instantChargeMah(double signedCurrentA,double signedCurrentB,long ms){
        if(!finite(signedCurrentA)||!finite(signedCurrentB)||signedCurrentA>0||signedCurrentB>0||ms<=0)return Double.NaN;
        return -(signedCurrentA+signedCurrentB)/2*ms/3600000;
    }
    public static final class Integral {
        public final double mah,energyMwh;
        Integral(double mah,double energyMwh){this.mah=mah;this.energyMwh=energyMwh;}
    }
    /** RM and percentage are deliberately independent of fast current/power integration. */
    public static Integral integrateFast(Reading before,Reading after,long maxGap){
        long ms=after.elapsed-before.elapsed;
        if(ms<=0||ms>maxGap||before.cellCount<1||before.cellCount>2||before.cellCount!=after.cellCount)
            return new Integral(Double.NaN,Double.NaN);
        return new Integral(instantChargeMah(before.compensatedCurrent(),after.compensatedCurrent(),ms),
            instantEnergyMwh(before.power(),after.power(),ms));
    }

    public static final class Sampling {
        public static final long MAX_INTERVAL_MS=120*60000L;
        public boolean interactive=true,screenSample;
        public long next,expected=120000;
        public long screenOnMs=120000,screenOffMs=1800000,chargeMs=3000,fastMs;
        public long interval(int state) { return state==1?chargeMs:state==2?15000:fastMs>0?fastMs:interactive?screenOnMs:screenOffMs; }
        public void sampled(int state,long now) { expected=interval(state);next=now+expected; }
        public void restoreExpected(long saved) { expected=Math.max(1000,Math.min(MAX_INTERVAL_MS,saved)); }
        public boolean screen(boolean on,long now) {
            if(interactive==on)return false;
            interactive=on;screenSample=true;next=now;return true;
        }
        public boolean takeScreenSample() { boolean pending=screenSample;screenSample=false;return pending; }
        // Follow the previous sample's plan, even if the screen has just turned on.
        public long maxGap() { return fastMs>0?Math.max(5000,expected*3):expected+expected/2; }
    }

    /** Independent periodic work; changing it does not move the power sampler. */
    public static final class Cadence {
        public long interval,last=-1,next;
        public Cadence(long interval){this.interval=interval;}
        public void configure(long interval,long now){
            if(interval<=0)throw new IllegalArgumentException("interval");
            if(this.interval==interval)return;
            this.interval=interval;next=last<0?now:Math.max(now,last+interval);
        }
        public boolean due(long now){return last<0||now>=next;}
        public void sampled(long now){last=now;next=now+interval;}
        public void reset(){last=-1;next=0;}
    }

    /** A false driver flag is valid; a missing or malformed flag is not. */
    public static void applyTail(Reading r,double fv,double full,double enabled) {
        r.fv=fv;r.full=full==1;r.enabled=enabled==1;
        r.chargeEnabledKnown=enabled==0||enabled==1;
        r.tailAvailable=finite(fv)&&fv>0&&(full==0||full==1)&&r.chargeEnabledKnown;
        r.tailSource=r.tailAvailable?"oplus_log":"unavailable";
    }
    public static void applyTailFallback(Reading r,double fv,int status,String source) {
        r.fv=fv;r.full=status==5;r.tailSource=source;
        r.tailAvailable=status>=2&&status<=5&&finite(fv)&&fv>0;
    }

    public static final class FullGate {
        public long since = -1, last = -1;
        private double minRm, maxRm;
        public void reset() { since = last = -1; }
        public boolean update(Reading r) {
            return update(r,1000);
        }
        public boolean update(Reading r,long expectedMs) {
            boolean fallback=r.tailSource.equals("sysfs_status")||r.tailSource.equals("android_status");
            boolean tail = r.tailAvailable && r.online && r.full && (r.enabled||fallback&&!r.chargeEnabledKnown) && r.valid()
                && finite(r.current) && r.current >= -60 && r.current <= 40
                && finite(r.fv) && r.vmin >= Math.max(4300, r.fv - 100)
                && r.temp >= 10 && r.temp <= 45;
            if (!tail) { reset(); return false; }
            long maxGap=Math.max(3500,expectedMs+expectedMs/2);
            if (since < 0 || last < 0 || r.elapsed - last > maxGap || r.elapsed < last) {
                since = r.elapsed; minRm = maxRm = r.rm;
            }
            minRm = Math.min(minRm, r.rm); maxRm = Math.max(maxRm, r.rm);
            if (maxRm - minRm > 3) { since = r.elapsed; minRm = maxRm = r.rm; }
            last = r.elapsed;
            return r.elapsed - since >= 300_000;
        }
        public double progress(long now) { return since < 0 ? 0 : Math.min(1, (now - since) / 300_000.0); }
    }

    public static final class Depletion {
        private double credit;
        public void reset() { credit = 0; }
        public double checkpoint() { return credit; }
        public void restore(double value) { credit=finite(value)&&value<=0?value:0; }
        /** Upward corrections are carried forward to avoid counting the same drop twice. */
        public double consume(double before, double after, long dt) {
            return consume(before,after,dt,90000);
        }
        public double consume(double before,double after,long dt,long maxGap) {
            if (!finite(before) || !finite(after) || dt <= 0 || dt > maxGap
                || Math.abs(before - after) > Math.max(120, dt / 1000.0 * 10)) {
                reset(); return Double.NaN;
            }
            credit += before - after;
            double loss = Math.max(0, credit);
            if (loss > 0) credit = 0;
            return loss;
        }
    }

    public static final class Event {
        public final long time; public final int type; public final String pkg;
        public final String activity; public final int instance;
        public Event(long t, int type, String p) { this(t,type,p,null,0); }
        public Event(long t,int type,String p,String activity,int instance) {
            time=t;this.type=type;pkg=p;this.activity=activity;this.instance=instance;
        }
        String activityKey() { return pkg+"\u0000"+(instance!=0?"#"+instance:Objects.toString(activity,"")); }
    }
    public static final String OTHER = "__other__", OFF = "__screenoff__";
    public interface ScreenSpan {
        void accept(long start,long end,boolean on);
        default void accept(long start,long end,boolean on,String pkg){accept(start,end,on);}
    }
    public static final class Timeline {
        // Lifecycle events belong to an activity instance, not the whole package.
        // A previous page may stop after the next page has already resumed.
        private final LinkedHashMap<String,String> resumed = new LinkedHashMap<>();
        private boolean screen = false, locked = true, screenKnown = false;
        static final class State {
            boolean screen,locked,known;
            final LinkedHashMap<String,String> resumed=new LinkedHashMap<>();
        }
        State snapshot(){State s=new State();s.screen=screen;s.locked=locked;s.known=screenKnown;s.resumed.putAll(resumed);return s;}
        void restore(State s){screen=s.screen;locked=s.locked;screenKnown=s.known;resumed.clear();resumed.putAll(s.resumed);}
        boolean screenKnown(){return screenKnown;}
        boolean screenOn(){return screen;}
        String currentOwner(){return owner();}
        public void apply(Event e) {
            switch(e.type) {
                case 1:
                    if(e.pkg!=null) {resumed.remove(e.activityKey());resumed.put(e.activityKey(),e.pkg);}
                    break; // ACTIVITY_RESUMED
                case 2: case 23: resumed.remove(e.activityKey()); break;
                case 15: screen=true;screenKnown=true;break;
                case 16: screen=false;screenKnown=true;break;
                case 17: locked=true; break;
                case 18: locked=false; break;
                case 26: case 27: resumed.clear();screen=false;locked=true;screenKnown=true;break;
            }
        }
        public void seed(boolean interactive, boolean keyguard) { resumed.clear();screen=interactive;locked=keyguard;screenKnown=true; }
        private String owner() {
            if(!screen)return OFF;if(locked)return OTHER;
            String top=OTHER;for(String pkg:resumed.values())top=pkg;return top;
        }
        public Map<String,Long> split(long start, long end, List<Event> events) {
            return split(start,end,events,null);
        }
        public Map<String,Long> split(long start,long end,List<Event> events,ScreenSpan spans) {
            Map<String,Long> result = new LinkedHashMap<>();
            long cursor=start;
            for(Event e:events) {
                if(e.time <= start) {apply(e);continue;}
                if(e.time > end) break;
                add(result,owner(),e.time-cursor);if(spans!=null&&e.time>cursor)spans.accept(cursor,e.time,screen,owner());apply(e);cursor=e.time;
            }
            add(result,owner(),end-cursor);if(spans!=null&&end>cursor)spans.accept(cursor,end,screen,owner());return result;
        }
        private static void add(Map<String,Long> m,String key,long dt) {
            if(dt>0) m.put(key,m.getOrDefault(key,0L)+dt);
        }
    }
}
