package cn.astriafr.acobattery.daemon;

import java.util.*;

/** Regression checks for usage events published at a sampling boundary. */
public final class TimelineTest {
    static Core.Event event(long time,int type,String pkg){return new Core.Event(time,type,pkg,pkg,1);}
    static void duration(Map<String,Long> split,String owner,long expected){
        long actual=split.getOrDefault(owner,0L);
        if(actual!=expected)throw new AssertionError(owner+": "+actual+" != "+expected);
    }
    public static void main(String[] args)throws Exception{
        Core.Timeline timeline=new Core.Timeline();timeline.seed(true,false);
        timeline.apply(event(0,1,"first"));
        duration(timeline.split(0,60000,Collections.emptyList()),"first",60000);
        // A delayed screen event timestamped exactly at the previous endpoint must
        // affect the following interval, without allocating its duration twice.
        duration(timeline.split(60000,120000,Arrays.asList(event(60000,16,null))),Core.OFF,60000);
        duration(timeline.split(120000,180000,Arrays.asList(event(120000,15,null))),"first",60000);
        duration(timeline.split(180000,240000,Arrays.asList(event(180000,1,"second"))),"second",60000);

        // A display-triggered partial settlement leaves the rest of the minute
        // with the new screen owner; replaying its event cannot double-count it.
        timeline=new Core.Timeline();timeline.seed(true,false);timeline.apply(event(0,1,"game"));
        duration(timeline.split(0,20000,Collections.emptyList()),"game",20000);
        timeline.apply(event(20000,16,null));
        duration(timeline.split(20000,60000,Arrays.asList(event(20000,16,null))),Core.OFF,40000);
        timeline.apply(event(60000,15,null));
        Map<String,Long> mixed=timeline.split(60000,120000,Arrays.asList(event(60000,15,null),event(90000,1,"next")));
        duration(mixed,"game",30000);duration(mixed,"next",30000);
        // Normal discharge uses the same authoritative callback when the
        // UsageStats event is timestamped before the next query's lower bound.
        timeline=new Core.Timeline();timeline.seed(true,false);timeline.apply(event(0,1,"normal"));
        timeline.split(0,20000,Collections.emptyList());timeline.apply(event(20000,16,null));
        duration(timeline.split(20000,80000,Collections.emptyList()),Core.OFF,60000);
        timeline.seed(false,false);duration(timeline.split(0,60000,Collections.emptyList()),Core.OFF,60000);
        timeline.seed(true,false);timeline.apply(event(0,1,"z-first"));timeline.apply(event(1,1,"a-second"));
        Core.Timeline restored=new Core.Timeline();DeferredStats.decode(restored,DeferredStats.encode(timeline));
        duration(restored.split(2,1002,Collections.emptyList()),"a-second",1000);
        restored.apply(event(1002,2,"a-second"));duration(restored.split(1002,2002,Collections.emptyList()),"z-first",1000);
        System.out.println("Timeline boundary, partial settlement and persistent activity-order checks passed");
    }
}
