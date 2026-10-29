package cn.astriafr.acobattery.daemon;

import java.util.*;

/** Timestamped screen spans; hourly values are derived in the current local zone. */
final class ScreenUsage {
    static final class Span {
        final long start,end;final boolean on;
        Span(long start,long end,boolean on){this.start=start;this.end=end;this.on=on;}
    }
    static final class Builder implements Core.ScreenSpan {
        final List<Span> spans=new ArrayList<>();
        final List<AppSpan> apps=new ArrayList<>();
        public void accept(long start,long end,boolean on,String pkg){
            accept(start,end,on);if(end<=start)return;
            String owner=on?pkg:Core.OFF;if(owner==null)owner=Core.OTHER;
            if(!apps.isEmpty()){
                AppSpan last=apps.get(apps.size()-1);
                if(last.end==start&&last.pkg.equals(owner)){apps.set(apps.size()-1,new AppSpan(last.start,end,owner));return;}
            }
            apps.add(new AppSpan(start,end,owner));
        }
        public void accept(long start,long end,boolean on){
            if(end<=start)return;
            if(!spans.isEmpty()){
                Span last=spans.get(spans.size()-1);
                if(last.end==start&&last.on==on){spans.set(spans.size()-1,new Span(last.start,end,on));return;}
            }
            spans.add(new Span(start,end,on));
        }
        Builder onRecordClock(long wallStart,long wallEnd,long recordStart,long recordEnd){
            if(wallEnd<=wallStart||recordEnd<=recordStart)return new Builder();
            Builder out=new Builder();
            for(Span span:spans)out.accept(map(span.start,wallStart,wallEnd,recordStart,recordEnd),map(span.end,wallStart,wallEnd,recordStart,recordEnd),span.on);
            for(AppSpan span:apps){
                long a=map(span.start,wallStart,wallEnd,recordStart,recordEnd),b=map(span.end,wallStart,wallEnd,recordStart,recordEnd);
                if(b>a)out.apps.add(new AppSpan(a,b,span.pkg));
            }
            return out;
        }
        private static long map(long time,long wallStart,long wallEnd,long recordStart,long recordEnd){
            return recordStart+Math.round((Math.max(wallStart,Math.min(wallEnd,time))-wallStart)*(double)(recordEnd-recordStart)/(wallEnd-wallStart));
        }
    }
    static final class AppSpan {
        final long start,end;final String pkg;
        AppSpan(long start,long end,String pkg){this.start=start;this.end=end;this.pkg=pkg;}
    }
    static List<Span> normalized(List<Span> input,long start,long end){
        List<Span> sorted=new ArrayList<>(input);sorted.sort(Comparator.comparingLong((Span s)->s.start).thenComparingLong(s->s.end));
        Builder out=new Builder();long cursor=start;
        for(Span span:sorted){long a=Math.max(cursor,Math.max(start,span.start)),b=Math.min(end,span.end);if(b>a){out.accept(a,b,span.on);cursor=b;}}
        return out.spans;
    }
    static List<Span> holes(List<Span> known,long start,long end){
        List<Span> out=new ArrayList<>();long cursor=start;
        for(Span span:normalized(known,start,end)){if(span.start>cursor)out.add(new Span(cursor,span.start,false));cursor=span.end;}
        if(end>cursor)out.add(new Span(cursor,end,false));return out;
    }
    static List<Span> fromEvents(List<Core.Event> events,long start,long end){
        return fromEvents(events,start,end,0);
    }
    static List<Span> fromEvents(List<Core.Event> events,long start,long end,int seed){
        Builder out=new Builder();boolean known=seed==1||seed==2,on=seed==2;long cursor=start;
        for(Core.Event event:events){
            if(event.time>end)break;
            if(event.type!=15&&event.type!=16&&event.type!=26&&event.type!=27)continue;
            if(event.time>start){if(known)out.accept(cursor,event.time,on);cursor=event.time;}
            on=event.type==15;known=true;
        }
        if(known&&end>cursor)out.accept(cursor,end,on);return out.spans;
    }
    static List<Span> fill(List<Span> source,List<Span> holes){
        Builder out=new Builder();int first=0;
        for(Span gap:holes){
            while(first<source.size()&&source.get(first).end<=gap.start)first++;
            for(int i=first;i<source.size();i++){
                Span span=source.get(i);if(span.start>=gap.end)break;
                out.accept(Math.max(gap.start,span.start),Math.min(gap.end,span.end),span.on);
            }
        }
        return out.spans;
    }
    static long midnight(long wall,TimeZone zone){
        Calendar day=Calendar.getInstance(zone);day.setTimeInMillis(wall);day.set(Calendar.HOUR_OF_DAY,0);day.set(Calendar.MINUTE,0);day.set(Calendar.SECOND,0);day.set(Calendar.MILLISECOND,0);return day.getTimeInMillis();
    }
    static long nextDay(long wall,TimeZone zone){Calendar day=Calendar.getInstance(zone);day.setTimeInMillis(midnight(wall,zone));day.add(Calendar.DAY_OF_MONTH,1);return day.getTimeInMillis();}
    static final class Hours {
        final long[] on=new long[24],covered=new long[24];long totalOn,totalCovered;
    }
    static Hours hours(List<Span> spans,long start,long end,TimeZone zone){
        Hours result=new Hours();
        for(Span span:normalized(spans,start,end)){
            long cursor=span.start;
            while(cursor<span.end){
                Calendar hour=Calendar.getInstance(zone);hour.setTimeInMillis(cursor);int index=hour.get(Calendar.HOUR_OF_DAY);
                long remaining=3600000L-hour.get(Calendar.MINUTE)*60000L-hour.get(Calendar.SECOND)*1000L-hour.get(Calendar.MILLISECOND);
                long stop=Math.min(span.end,cursor+remaining),duration=stop-cursor;
                result.covered[index]+=duration;result.totalCovered+=duration;
                if(span.on){result.on[index]+=duration;result.totalOn+=duration;}cursor=stop;
            }
        }
        return result;
    }
}
