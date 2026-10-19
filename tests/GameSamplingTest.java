package cn.astriafr.acobattery.daemon;

import android.database.Cursor;
import android.os.SystemClock;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import org.json.*;

/** Host SQLite integration: independent deadlines, upgrades and unchanged records. */
public final class GameSamplingTest {
    static void check(boolean v){if(!v)throw new AssertionError();}
    static Main open(File directory)throws Exception{
        Main m=new Main(null,new File(directory,"runtime"),directory);
        Field boot=Main.class.getDeclaredField("boot");boot.setAccessible(true);boot.set(m,"game-test");
        m.performance.close();return m;
    }
    static void close(Main m)throws Exception{m.performance.close();m.db.close();m.lock.release();m.lockFile.close();}
    static long count(Main m,String sql){try(Cursor c=m.db.rawQuery(sql,null)){check(c.moveToFirst());return c.getLong(0);}}
    static Core.Reading reading(long elapsed){
        Core.Reading r=new Core.Reading();r.elapsed=elapsed;r.wall=1000000+elapsed;r.rm=5000;r.pct=80;
        r.current=300;r.cellCount=2;r.cell1=3900;r.cell2=4000;return r;
    }
    static JSONArray settings(int power,int performance){return new JSONArray(new Object[]{5,30,560,1,300,450,50,60,2,15,power,1,2,performance});}
    static void save(Main m,JSONArray values)throws Exception{
        try{m.saveSettings(new JSONArray(values.toString()).put(0));}
        catch(UnsatisfiedLinkError hostOnly){check(hostOnly.getMessage().contains("interruptWait"));}
    }
    static void schedule(int power,int performance)throws Exception{
        Main m=open(Files.createTempDirectory("acol-game-clock-").toFile());
        try{
            m.settings=Main.decodeSettings(settings(power,performance));m.fastEnabled=true;m.state=0;m.configureSampling(false);
            long base=SystemClock.elapsedRealtime();m.begin(reading(base));m.sampling.sampled(0,base);long stats=m.fastStatsNext;
            // A wall-clock correction takes the existing attribution fallback;
            // this host harness has no Android UsageStats service.
            m.fastStatsPrevious.wall-=3000;
            int perfCount=1;
            for(int second=1;second<=60;second++){
                long now=base+second*1000,last=m.performanceCadence.last;
                if(now>=m.sampling.next){m.append(reading(now));m.sampling.sampled(0,now);}
                else if(now>=m.fastStatsNext)m.sampleFastStats(reading(now),false);
                m.samplePerformance(now);
                if(m.performanceCadence.last!=last)perfCount++;
            }
            check(count(m,"SELECT COUNT(*) FROM samples")==1+60/power);
            check(perfCount==1+60/performance);
            check(m.fastStatsNext==stats+60000); // Power sampling still settles RM once per minute.
            check(count(m,"SELECT COUNT(*) FROM sessions")==1);
            check(count(m,"SELECT fast_seconds FROM sessions")==power);
            check(count(m,"SELECT performance_seconds FROM sessions")==performance);
        }finally{close(m);}
    }
    static void edits()throws Exception{
        File directory=Files.createTempDirectory("acol-game-edit-").toFile();Main m=open(directory);long id;
        try{
            m.settings=Main.decodeSettings(settings(10,2));m.fastEnabled=true;m.state=0;m.configureSampling(false);
            long now=SystemClock.elapsedRealtime();m.begin(reading(now));m.sampling.sampled(0,now);id=m.session;
            long powerNext=m.sampling.next,stats=m.fastStatsNext;
            save(m,settings(10,5));check(m.session==id);check(m.sampling.next==powerNext);check(m.fastStatsNext==stats);
            long perfNext=m.performanceCadence.next;
            save(m,settings(3,5));check(m.session==id);check(m.performanceCadence.next==perfNext);check(m.fastStatsNext==stats);
            check(m.sampling.next==now+3000);check(count(m,"SELECT COUNT(*) FROM samples")==1);
            check(new JSONArray(m.runtimeValue("module_settings")).length()==14);
            JSONArray legacy=new JSONArray("[5,30,560,1,300,450,50,60,2,15,3,1,2]");
            save(m,legacy);check(m.settings.getInt("performanceSeconds")==5);
            check(AppBridge.parse(AppBridge.ROOT+"bin/curve-client set_settings 5 30 560 1 300 450 50 60 2 15 3 1 2 5 0").length==17);
            m.db.execSQL("ALTER TABLE sessions DROP COLUMN performance_seconds");m.db.setVersion(10);
        }finally{close(m);}
        m=open(directory);
        try{
            check(m.settings.getInt("performanceSeconds")==5);
            check(count(m,"SELECT performance_seconds FROM sessions")==3); // Old rows used one shared interval.
        }finally{close(m);}
    }
    public static void main(String[] args)throws Exception{
        JSONObject defaults=Main.decodeSettings(new JSONArray("[5,30,560,1,300,450,50,60]"));
        check(defaults.getInt("performanceSeconds")==3);check(defaults.getInt("screenOffMinutes")==20);check(defaults.getInt("chargeSeconds")==3);
        JSONArray legacy=new JSONArray("[5,30,560,1,300,450,50,60,2,15,7,1,2]");
        check(Main.decodeSettings(legacy).getInt("performanceSeconds")==7);
        for(Object bad:new Object[]{0,61,1.5}){
            JSONArray v=settings(10,10);v.put(13,bad);
            try{Main.decodeSettings(v);throw new AssertionError("Invalid performance interval accepted");}catch(java.io.IOException expected){}
        }
        schedule(60,1);schedule(1,60);schedule(7,3);edits();
        System.out.println("Game sampling: independent power/performance clocks, 1-60 s bounds, settings/schema upgrade, unchanged session and minute RM accounting passed");
    }
}
