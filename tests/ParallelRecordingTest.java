package cn.astriafr.acobattery.daemon;

import java.lang.reflect.Field;
import org.json.JSONObject;

/** Exercise the actual context boundary without starting Android services or opening a database. */
public final class ParallelRecordingTest {
    static void check(boolean value){if(!value)throw new AssertionError();}
    static void set(Main main,String name,Object value)throws Exception{
        Field field=Main.class.getDeclaredField(name);field.setAccessible(true);field.set(main,value);
    }
    public static void main(String[] args)throws Exception{
        Class<?> type=Class.forName("sun.misc.Unsafe");Field field=type.getDeclaredField("theUnsafe");field.setAccessible(true);
        Main main=(Main)type.getMethod("allocateInstance",Class.class).invoke(field.get(null),Main.class);
        set(main,"gameTrack",new Main.RecordTrack());set(main,"normalContext",new Main.RecordTrack());
        main.settings=new JSONObject().put("fastSeconds",3);
        main.sampling=new Core.Sampling();main.sampling.next=120000;main.sampling.expected=120000;
        main.fullGate=new Core.FullGate();main.depletion=new Core.Depletion();main.timeline=new Core.Timeline();
        main.session=10;main.state=0;main.startElapsed=100;main.startWall=1000;
        main.previous=new Core.Reading();main.previous.elapsed=100;
        Core.Reading normalPrevious=main.previous;Core.Timeline normalTimeline=main.timeline;
        Core.Depletion normalDepletion=main.depletion;Core.Sampling normalSampling=main.sampling;
        main.withGame(()->{
            check(main.session==-1&&main.gameContext);check(main.timeline!=normalTimeline&&main.depletion!=normalDepletion);
            main.session=20;main.startElapsed=200;main.previous=new Core.Reading();main.previous.elapsed=200;
            main.sampling.sampled(0,200);main.fastStatsPrevious=main.previous;main.fastStatsNext=60200;
            main.depletion.restore(2);main.timelineReady=true;main.powerGap=true;
        });
        check(main.session==10&&main.previous==normalPrevious&&main.timeline==normalTimeline);
        check(main.sampling==normalSampling&&main.sampling.next==120000&&main.sampling.fastMs==0);
        check(!main.powerGap&&!main.gameContext&&main.preferredSession()==20);
        check(main.activeSession(10)&&main.activeSession(20)&&!main.activeSession(30));
        check(main.settledReading(10)==normalPrevious&&main.settledReading(20)==main.gameTrack.fastStatsPrevious);
        try{main.withGame(()->{check(main.session==20&&main.sampling.next==3200);main.session=21;throw new Exception("interrupted");});throw new AssertionError();}
        catch(Exception expected){check("interrupted".equals(expected.getMessage()));}
        check(main.session==10&&main.previous==normalPrevious&&main.gameTrack.session==21&&!main.gameContext);
        main.settings.put("chargeSeconds",2);main.stableOnline=true;main.state=1;
        main.withGame(()->{check(main.sampling.fastMs==2000&&main.session==21);});
        check(main.session==10&&main.sampling==normalSampling&&main.state==1);
        // Even expired power/minute deadlines must not cause a second battery read during charging.
        main.fastEnabled=true;main.recordingEnabled=true;main.stopping=true;main.candidateAt=-1;
        main.gameTrack.sampling.next=0;main.gameTrack.fastStatsNext=0;
        main.collectGame(null,false,10000);check(main.session==10&&main.gameTrack.session==21);
        main.stableOnline=false;main.state=0;
        main.withGame(()->{check(main.session==21&&main.sampling.fastMs==3000);});
        Core.Reading off=new Core.Reading(),on=new Core.Reading();on.online=true;
        check(Core.gameDischargeInterval(off,off)&&!Core.gameDischargeInterval(off,on)&&!Core.gameDischargeInterval(on,on));
        Core.Reading saved=Main.restoreReading(Main.checkpointReading(on));check(saved.online&&saved.onlineKnown);
        saved.onlineKnown=false;saved=Main.restoreReading(Main.checkpointReading(saved));check(!saved.onlineKnown);
        main.withGame(()->{main.session=-1;main.previous=null;main.fastStatsPrevious=null;});
        check(main.session==10&&main.previous==normalPrevious&&main.preferredSession()==10);
        System.out.println("Parallel recording: separate samples, timelines, RM state, schedules, exception restoration and preferred record passed");
    }
}
