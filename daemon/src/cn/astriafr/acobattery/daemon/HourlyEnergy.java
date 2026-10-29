package cn.astriafr.acobattery.daemon;

import java.util.*;

/** Split recorded interval energy, without the display-only power-curve filters. */
final class HourlyEnergy {
    final double[] wh=new double[24];
    final long[] covered=new long[24];
    final List<Map<String,Double>> apps=new ArrayList<>();
    private final List<ScreenUsage.AppSpan> spans;
    private final TimeZone zone;
    private final long from,to;
    private int first;

    HourlyEnergy(List<ScreenUsage.AppSpan> spans,long from,long to,TimeZone zone){
        this.spans=spans;this.from=from;this.to=to;this.zone=zone;
        for(int i=0;i<24;i++)apps.add(new HashMap<>());
    }
    void add(long start,long end,double watts){
        if(end<=start||!Core.finite(watts)||watts<0)return;
        long cursor=Math.max(start,from),stop=Math.min(end,to);
        while(first<spans.size()&&spans.get(first).end<=cursor)first++;
        int index=first;
        while(cursor<stop){
            while(index<spans.size()&&spans.get(index).end<=cursor)index++;
            ScreenUsage.AppSpan span=index<spans.size()?spans.get(index):null;
            String pkg=Core.OTHER;long next=stop;
            if(span!=null){
                if(span.start>cursor)next=Math.min(next,span.start);
                else{pkg=span.pkg;next=Math.min(next,span.end);}
            }
            Calendar hour=Calendar.getInstance(zone);hour.setTimeInMillis(cursor);
            int h=hour.get(Calendar.HOUR_OF_DAY);
            long remaining=3600000L-hour.get(Calendar.MINUTE)*60000L-hour.get(Calendar.SECOND)*1000L-hour.get(Calendar.MILLISECOND);
            next=Math.min(next,cursor+remaining);
            double energy=watts*(next-cursor)/3600000.0;
            wh[h]+=energy;covered[h]+=next-cursor;
            Map<String,Double> values=apps.get(h);values.put(pkg,values.getOrDefault(pkg,0.0)+energy);
            cursor=next;
        }
    }
    /** Legacy game history: minute RM accounting, never instantaneous power. */
    static final class RmMinutes {
        private final HourlyEnergy hours;
        private final long start;
        private final Core.Depletion depletion=new Core.Depletion();
        private long previous=-1,last=-1,next;
        private double rm,voltage,lastRm,lastVoltage;
        private boolean resumed;
        RmMinutes(HourlyEnergy hours,long start){this.hours=hours;this.start=start;}
        void sample(long x,double rm,double voltage,int quality){
            last=x;lastRm=rm;lastVoltage=voltage;resumed=(quality&8)!=0;
            if(previous<0){previous=x;this.rm=rm;this.voltage=voltage;next=(x/60000+1)*60000;return;}
            if(x>=next||resumed)settle();
        }
        private void settle(){
            long dt=last-previous;if(dt<=0)return;
            double loss=depletion.consume(rm,lastRm,dt,90000);
            double energy=resumed||dt>90000?Double.NaN:Core.dischargeEnergyMwh(voltage,lastVoltage,loss);
            hours.add(start+previous,start+last,Core.averagePower(energy,dt));
            previous=last;rm=lastRm;voltage=lastVoltage;
            if(resumed)depletion.reset();
            while(next<=last)next+=60000;
        }
        void finish(){settle();}
    }
}
