package cn.astriafr.acobattery.daemon;

import android.database.Cursor;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;

/** Runs real Main recovery/append SQL against an isolated SQLite database. */
public final class RecoveryDatabaseTest {
    static void check(boolean value){if(!value)throw new AssertionError();}
    static void close(double actual,double expected){if(Math.abs(actual-expected)>1e-9)throw new AssertionError(actual+" != "+expected);}
    static Core.Reading reading(long elapsed,double rm,boolean online){
        Core.Reading r=new Core.Reading();r.elapsed=elapsed;r.wall=1000000+elapsed;r.rm=rm;r.pct=rm/100;
        r.cellCount=2;r.cell1=r.vmin=3900;r.cell2=r.vmax=4000;r.current=online?-1000:300;r.online=online;return r;
    }
    static Main open(File directory,String boot)throws Exception {
        Main m=new Main(null,new File(directory,"runtime"),directory);
        Field id=Main.class.getDeclaredField("boot");id.setAccessible(true);id.set(m,boot);
        m.performance.close();return m;
    }
    static void release(Main m)throws Exception {m.performance.close();m.db.close();m.lock.release();m.lockFile.close();}
    static long value(Main m,String sql){try(Cursor c=m.db.rawQuery(sql,null)){check(c.moveToFirst());return c.getLong(0);}}
    static double amount(Main m,String column,String pkg){try(Cursor c=m.db.rawQuery("SELECT "+column+" FROM apps WHERE pkg=?",new String[]{pkg})){return c.moveToFirst()?c.getDouble(0):0;}}
    static void sameBootAndLegacy()throws Exception {
        File dir=Files.createTempDirectory("acol-recovery-normal-").toFile();Main first=open(dir,"boot-a");
        first.state=0;first.begin(reading(10000,5000,false));long id=first.session;
        first.db.execSQL("INSERT INTO apps(session,pkg,ms,mah,energy_mwh,energy_ms,mah_ms) VALUES(?, 'example.app',180000,30,120,180000,180000)",new Object[]{id});
        first.db.execSQL("DELETE FROM runtime WHERE key='session_progress_v1'");release(first); // previous APK had no checkpoint
        Main next=open(dir,"boot-a");next.recover(reading(13000,4997,false));check(next.session==id);
        check(value(next,"SELECT COUNT(*) FROM sessions")==1);check(value(next,"SELECT active FROM sessions")==1);
        check(value(next,"SELECT start_elapsed FROM sessions")==10000);check(value(next,"SELECT rm_start FROM sessions")==5000);
        check(value(next,"SELECT quality FROM samples ORDER BY x DESC LIMIT 1")==9);
        check(value(next,"SELECT COUNT(*) FROM samples WHERE x=3000 AND power IS NULL")==1);
        close(amount(next,"mah","example.app"),30);close(amount(next,"mah",Core.OTHER),3);close(amount(next,"energy_mwh",Core.OTHER),0);
        release(next);
        Main twice=open(dir,"boot-a");twice.recover(reading(16000,4994,false));check(twice.session==id);
        close(amount(twice,"mah","example.app"),30);close(amount(twice,"mah",Core.OTHER),6);
        check(value(twice,"SELECT COUNT(*) FROM samples")==3);check(value(twice,"SELECT COUNT(*) FROM sessions")==1);
        release(twice);
    }
    static void fastMinuteAnchor()throws Exception {
        File dir=Files.createTempDirectory("acol-recovery-game-").toFile();Main first=open(dir,"boot-a");
        first.fastEnabled=true;first.sampling.fastMs=1000;first.state=0;
        first.begin(reading(10000,5000,false));long id=first.session;
        first.fastStatsPrevious=reading(30000,4998,false);first.fastStatsNext=70000;
        first.depletion.restore(-4);first.checkpoint(first.previous);release(first);
        Main next=open(dir,"boot-a");next.fastEnabled=true;next.sampling.fastMs=1000;
        next.recover(reading(40000,4996,false));check(next.session==id);
        check(next.fastStatsPrevious.elapsed==40000);check(next.fastStatsNext==70000);
        close(amount(next,"mah",Core.OTHER),0); // Saved rebound credit absorbs the 2 mAh correction.
        check(value(next,"SELECT fast_seconds FROM sessions")>0);check(value(next,"SELECT active FROM sessions")==1);
        next.sampleFastStats(reading(40000,4996,false),true);close(amount(next,"mah",Core.OTHER),0);
        release(next);
        Main twice=open(dir,"boot-a");twice.fastEnabled=true;twice.sampling.fastMs=1000;
        twice.recover(reading(50000,4991,false));check(twice.session==id);check(twice.fastStatsNext==70000);
        close(amount(twice,"mah",Core.OTHER),3);close(amount(twice,"energy_mwh",Core.OTHER),0);release(twice);
    }
    static void splitAndTail()throws Exception {
        for(boolean reboot:new boolean[]{false,true}){
            File dir=Files.createTempDirectory("acol-recovery-split-").toFile();Main first=open(dir,"boot-a");
            first.state=0;first.begin(reading(10000,5000,false));long old=first.session;release(first);
            Main next=open(dir,reboot?"boot-b":"boot-a");next.recover(reading(reboot?1000:11000,4999,!reboot));
            check(next.session!=old);check(next.state==(reboot?0:1));check(value(next,"SELECT COUNT(*) FROM sessions WHERE active=1")==1);release(next);
        }
        File dir=Files.createTempDirectory("acol-recovery-full-").toFile();Main first=open(dir,"boot-a");
        first.state=1;first.begin(reading(10000,5000,true));first.finish("full_tail_confirmed");release(first);
        Main next=open(dir,"boot-a");Core.Reading r=reading(11000,5000,true);r.full=true;next.recover(r);
        check(next.session<0);check(next.state==2);check(value(next,"SELECT COUNT(*) FROM sessions WHERE active=1")==0);release(next);
    }
    static void damagedCheckpointAndRollback()throws Exception {
        File dir=Files.createTempDirectory("acol-recovery-rollback-").toFile();Main first=open(dir,"boot-a");
        first.state=0;first.begin(reading(10000,5000,false));long id=first.session;
        first.db.execSQL("UPDATE runtime SET value='{' WHERE key='session_progress_v1'");
        first.db.execSQL("CREATE TRIGGER fail_update BEFORE UPDATE OF end_elapsed ON sessions BEGIN SELECT RAISE(ABORT,'injected write failure'); END");release(first);
        Main failed=open(dir,"boot-a");
        try{failed.recover(reading(13000,4997,false));throw new AssertionError("expected SQL failure");}catch(RuntimeException expected){}
        check(value(failed,"SELECT COUNT(*) FROM samples")==1);check(value(failed,"SELECT active FROM sessions")==1);
        close(amount(failed,"mah",Core.OTHER),0);failed.db.execSQL("DROP TRIGGER fail_update");release(failed);
        Main retry=open(dir,"boot-a");retry.recover(reading(13000,4997,false));check(retry.session==id);
        check(value(retry,"SELECT COUNT(*) FROM samples")==2);close(amount(retry,"mah",Core.OTHER),3);release(retry);
        File chargeDir=Files.createTempDirectory("acol-recovery-charge-").toFile();Main charge=open(chargeDir,"boot-a");
        charge.state=1;charge.begin(reading(10000,5000,true));long chargeId=charge.session;release(charge);
        Main charging=open(chargeDir,"boot-a");charging.recover(reading(11000,5001,true));check(charging.session==chargeId);
        check(charging.state==1);check(value(charging,"SELECT COUNT(*) FROM sessions")==1);release(charging);
    }
    static void samplingSettings()throws Exception {
        File dir=Files.createTempDirectory("acol-settings-interval-").toFile();Main m=open(dir,"boot-a");
        check(m.settings.getInt("deletePct")==5);check(m.settings.getInt("hidePct")==30);
        check(m.settings.getInt("screenOnMinutes")==2);check(m.settings.getInt("screenOffMinutes")==15);check(m.settings.getInt("chargeSeconds")==2);
        org.json.JSONArray old=new org.json.JSONArray("[3,30,560,1,300,450,50,60,2,10,10,1]");
        check(Main.decodeSettings(old).getInt("chargeSeconds")==2);check(Main.decodeSettings(old).getInt("screenOffMinutes")==10);
        org.json.JSONArray values=new org.json.JSONArray("[5,30,560,1,300,450,50,60,2,15,10,1,10]");
        for(Object invalid:new Object[]{0,11,1.5}){
            values.put(12,invalid);try{Main.decodeSettings(values);throw new AssertionError("accepted invalid charging interval");}catch(java.io.IOException expected){}
        }
        values.put(12,10);m.state=1;m.begin(reading(10000,5000,true));long id=m.session;
        org.json.JSONArray request=new org.json.JSONArray(values.toString()).put(0);
        try{m.saveSettings(request);}catch(UnsatisfiedLinkError hostOnly){check(hostOnly.getMessage().contains("interruptWait"));}
        check(m.session==id);check(m.sampling.interval(1)==10000);check(value(m,"SELECT active FROM sessions")==1);
        check(value(m,"SELECT COUNT(*) FROM samples")==1);check(value(m,"SELECT COUNT(*) FROM sessions")==1);
        check(new org.json.JSONArray(m.runtimeValue("module_settings")).length()==13);
        try{m.saveSettings(new org.json.JSONArray(old.toString()).put(0));}catch(UnsatisfiedLinkError hostOnly){check(hostOnly.getMessage().contains("interruptWait"));}
        check(m.settings.getInt("chargeSeconds")==10); // Old frontend saves cannot erase the new setting.
        String command=AppBridge.ROOT+"bin/curve-client set_settings 5 30 560 1 300 450 50 60 2 15 10 1 2 0";
        check(AppBridge.parse(command).length==16);release(m);
        Main next=open(dir,"boot-a");check(next.settings.getInt("chargeSeconds")==10);next.recover(reading(20000,5001,true));
        check(next.session==id);check(next.sampling.interval(1)==10000);release(next);
    }
    public static void main(String[] args)throws Exception {
        sameBootAndLegacy();fastMinuteAnchor();splitAndTail();damagedCheckpointAndRollback();samplingSettings();
        System.out.println("SQLite recovery: legacy upgrade, repeated resume, game minute accounting, power transition, kernel reboot, completed tail, charging, corrupt checkpoint and transaction rollback passed");
    }
}
