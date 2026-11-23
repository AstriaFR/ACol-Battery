package cn.astriafr.acobattery.daemon;

import android.database.Cursor;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import org.json.JSONArray;

/** Runs real Main recovery/append SQL against an isolated SQLite database. */
public final class RecoveryDatabaseTest {
    static android.content.Context context;
    static void check(boolean value){if(!value)throw new AssertionError();}
    static void close(double actual,double expected){if(Math.abs(actual-expected)>1e-9)throw new AssertionError(actual+" != "+expected);}
    static Core.Reading reading(long elapsed,double rm,boolean online){
        Core.Reading r=new Core.Reading();r.elapsed=elapsed;r.wall=1000000+elapsed;r.rm=rm;r.pct=rm/100;
        r.cellCount=2;r.cell1=r.vmin=3900;r.cell2=r.vmax=4000;r.current=online?-1000:300;r.online=online;return r;
    }
    static Main open(File directory)throws Exception {
        Main m=new Main(context,new File(directory,"runtime"),directory);
        m.performance.close();return m;
    }
    static Main open(File directory,String boot)throws Exception {
        Main m=open(directory);
        Field id=Main.class.getDeclaredField("boot");id.setAccessible(true);id.set(m,boot);
        return m;
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
        close(amount(next,"mah","example.app"),30);close(amount(next,"mah",Core.OTHER),0);close(amount(next,"energy_mwh",Core.OTHER),0);
        check(next.deferred.pending(id)); // Recovery preserves raw intervals; viewing settles them later.
        release(next);
        Main twice=open(dir,"boot-a");twice.recover(reading(16000,4994,false));check(twice.session==id);
        close(amount(twice,"mah","example.app"),30);close(amount(twice,"mah",Core.OTHER),0);check(twice.deferred.pending(id));
        check(value(twice,"SELECT COUNT(*) FROM samples")==3);check(value(twice,"SELECT COUNT(*) FROM sessions")==1);
        release(twice);
    }
    static void fastMinuteAnchor()throws Exception {
        File dir=Files.createTempDirectory("acol-recovery-game-").toFile();Main first=open(dir,"boot-a");
        first.gameContext=true;first.fastEnabled=true;first.sampling.fastMs=1000;first.state=0;
        first.begin(reading(10000,5000,false));long id=first.session;
        first.fastStatsPrevious=reading(30000,4998,false);first.fastStatsNext=70000;
        first.depletion.restore(-4);first.checkpoint(first.previous);release(first);
        Main next=open(dir,"boot-a");next.gameContext=true;next.fastEnabled=true;next.sampling.fastMs=1000;
        next.recover(reading(40000,4996,false));check(next.session==id);
        check(next.fastStatsPrevious.elapsed==40000);check(next.fastStatsNext==70000);
        close(amount(next,"mah",Core.OTHER),0); // Saved rebound credit absorbs the 2 mAh correction.
        check(value(next,"SELECT fast_seconds FROM sessions")>0);check(value(next,"SELECT active FROM sessions")==1);
        next.sampleFastStats(reading(40000,4996,false),true);close(amount(next,"mah",Core.OTHER),0);
        release(next);
        Main twice=open(dir,"boot-a");twice.gameContext=true;twice.fastEnabled=true;twice.sampling.fastMs=1000;
        twice.recover(reading(50000,4991,false));check(twice.session==id);check(twice.fastStatsNext==70000);
        close(amount(twice,"mah",Core.OTHER),0);close(amount(twice,"energy_mwh",Core.OTHER),0);check(twice.deferred.pending(id));release(twice);
    }
    static void splitAndTail()throws Exception {
        for(boolean reboot:new boolean[]{false,true}){
            File dir=Files.createTempDirectory("acol-recovery-split-").toFile();Main first=open(dir,"boot-a");
            first.state=0;first.begin(reading(10000,5000,false));long old=first.session;release(first);
            Main next=open(dir,reboot?"boot-b":"boot-a");next.recover(reading(reboot?1000:11000,4999,true));
            check(next.session!=old);check(next.state==1);check(value(next,"SELECT COUNT(*) FROM sessions WHERE active=1")==1);release(next);
        }
        File dir=Files.createTempDirectory("acol-recovery-full-").toFile();Main first=open(dir,"boot-a");
        first.state=1;first.begin(reading(10000,5000,true));first.append(reading(13000,5008,true));first.finish("full_tail_confirmed");
        org.json.JSONObject manual=first.store.recordBatteryHistory(new org.json.JSONObject().put("cycles",2),1014000);
        check(!manual.getJSONObject("snapshot").has("tailRm"));
        org.json.JSONArray trend=first.store.history("battery_history_trend",new org.json.JSONArray()).getJSONArray("items");
        close(trend.getJSONObject(0).getDouble("tailRm"),5008);
        check(trend.getJSONObject(1).isNull("tailRm"));
        release(first);
        Main next=open(dir,"boot-a");Core.Reading r=reading(14000,5008,true);r.full=true;next.recover(r);
        check(next.session<0);check(next.state==2);check(value(next,"SELECT COUNT(*) FROM sessions WHERE active=1")==0);release(next);
        File logDir=Files.createTempDirectory("acol-recovery-charge-log-").toFile();Main logged=open(logDir,"boot-a");
        logged.state=1;Core.Reading observed=reading(10000,5000,true);Core.applyTail(observed,4490,0,1);
        logged.begin(observed);long loggedId=logged.session;check(logged.fullGate.vendorLogSeen);release(logged);
        Main restored=open(logDir,"boot-a");restored.recover(reading(12000,5001,true));
        check(restored.session==loggedId&&restored.fullGate.vendorLogSeen&&restored.fullGate.electricalDuration()==600000);release(restored);
    }
    static void rebootResume()throws Exception {
        for(boolean online:new boolean[]{false,true}){
            File dir=Files.createTempDirectory("acol-recovery-reboot-").toFile();Main first=open(dir,"boot-a");
            Core.Reading initial=reading(10000,5000,online),before=reading(40000,4990,online);
            first.state=online?1:0;first.begin(initial);first.append(before);long id=first.session;release(first);
            Core.Reading after=reading(1000,4980,online);after.wall=before.wall+30000;
            Main next=open(dir,"boot-b");next.recover(after);
            long resumedX=before.elapsed-initial.elapsed+after.wall-before.wall;
            check(next.session==id&&next.startWall==initial.wall);
            check(value(next,"SELECT COUNT(*) FROM sessions")==1);
            check(value(next,"SELECT x FROM samples ORDER BY x DESC LIMIT 1")==resumedX);
            check(value(next,"SELECT COUNT(*) FROM samples WHERE (quality&"+Core.REBOOT_GAP+")!=0 AND interval_ms IS NULL")==1);
            if(!online)check(value(next,"SELECT power IS NULL FROM samples ORDER BY x DESC LIMIT 1")==1);
            release(next);
            Main twice=open(dir,"boot-b");Core.Reading later=reading(after.elapsed+1000,4970,online);later.wall=after.wall+1000;
            twice.recover(later);check(twice.session==id);
            check(value(twice,"SELECT x FROM samples ORDER BY x DESC LIMIT 1")==resumedX+later.elapsed-after.elapsed);
            release(twice);
        }
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
        check(value(retry,"SELECT COUNT(*) FROM samples")==2);close(amount(retry,"mah",Core.OTHER),0);check(retry.deferred.pending(id));release(retry);
        File chargeDir=Files.createTempDirectory("acol-recovery-charge-").toFile();Main charge=open(chargeDir,"boot-a");
        charge.state=1;charge.begin(reading(10000,5000,true));long chargeId=charge.session;release(charge);
        Main charging=open(chargeDir,"boot-a");charging.recover(reading(11000,5001,true));check(charging.session==chargeId);
        check(charging.state==1);check(value(charging,"SELECT COUNT(*) FROM sessions")==1);release(charging);
    }
    static void samplingSettings()throws Exception {
        File dir=Files.createTempDirectory("acol-settings-interval-").toFile();Main m=open(dir,"boot-a");
        org.json.JSONArray old=new org.json.JSONArray("[3,30,560,1,300,450,50,60,2,10,10,1]");
        check(Main.decodeSettings(old).getInt("chargeSeconds")==m.settings.getInt("chargeSeconds"));
        check(Main.decodeSettings(old).getInt("screenOffMinutes")==old.getInt(9));
        org.json.JSONArray values=new org.json.JSONArray("[5,30,560,1,300,450,50,60,2,15,10,1,10]");
        for(Object invalid:new Object[]{0,11,1.5}){
            values.put(12,invalid);try{Main.decodeSettings(values);throw new AssertionError("accepted invalid charging interval");}catch(java.io.IOException expected){}
        }
        values.put(12,10);m.state=1;m.begin(reading(10000,5000,true));long id=m.session;
        org.json.JSONArray request=new org.json.JSONArray(values.toString()).put(0);
        try{m.saveSettings(request);}catch(UnsatisfiedLinkError hostOnly){check(hostOnly.getMessage().contains("interruptWait"));}
        check(m.session==id);check(m.sampling.interval(1)==10000);check(value(m,"SELECT active FROM sessions")==1);
        check(value(m,"SELECT COUNT(*) FROM samples")==1);check(value(m,"SELECT COUNT(*) FROM sessions")==1);
        check(new org.json.JSONArray(m.runtimeValue("module_settings")).length()==16);
        try{m.saveSettings(new org.json.JSONArray(old.toString()).put(0));}catch(UnsatisfiedLinkError hostOnly){check(hostOnly.getMessage().contains("interruptWait"));}
        check(m.settings.getInt("chargeSeconds")==10); // Old frontend saves cannot erase the new setting.
        String command=AppBridge.ROOT+"bin/curve-client set_settings 5 30 560 1 300 450 50 60 2 15 10 1 2 0";
        check(AppBridge.parse(command).length==16);release(m);
        Main next=open(dir,"boot-a");check(next.settings.getInt("chargeSeconds")==10);next.recover(reading(20000,5001,true));
        check(next.session==id);check(next.sampling.interval(1)==10000);release(next);
    }
    static void standaloneGameWaitsForReadings()throws Exception {
        File dir=Files.createTempDirectory("acol-recovery-game-only-").toFile();
        Files.createFile(new File(dir,"recording.disabled").toPath());
        Main first=open(dir);
        first.rememberFast(true);
        first.withGame(()->first.begin(reading(10000,5000,false)));
        long id=first.gameTrack.session;
        release(first);

        Main next=open(dir);
        next.recoverWhenReady(reading(11000,Double.NaN,false));
        check(next.fastEnabled&&!next.recordingEnabled&&!next.powerReady&&next.powerRetryAt>11000);
        check(value(next,"SELECT active FROM sessions")==1);
        check(value(next,"SELECT COUNT(*) FROM samples")==1);
        next.recoverWhenReady(reading(16000,4998,false));
        check(next.powerReady&&next.gameTrack.session==id&&next.session<0);
        check(value(next,"SELECT COUNT(*) FROM sessions")==1);
        release(next);
    }
    static Core.Reading tailReading(long elapsed,double mv,double current){
        Core.Reading r=reading(elapsed,5000,true);
        r.cellCount=1;r.vmin=r.vmax=r.cell1=mv;r.cell2=Double.NaN;r.current=current;
        return r;
    }
    static void completedTailAtStableCurrent()throws Exception {
        File dir=Files.createTempDirectory("acol-recovery-tail-standby-").toFile();
        Main m=open(dir,"boot-a");
        try{
            m.state=1;m.stableOnline=true;
            m.begin(tailReading(0,4400,-75));
            m.sampling.sampled(1,0);
            for(long t=3000;t<=630000;t+=3000)m.collectStable(tailReading(t,4400,-75),false,t);
            check(m.state==2&&m.session<0&&value(m,"SELECT COUNT(*) FROM battery_history")==1);
            check(value(m,"SELECT COUNT(*) FROM sessions WHERE active=1")==0);
        }finally{release(m);}

        Main recovered=open(dir,"boot-a");
        try{
            recovered.stableOnline=true;
            recovered.recoverTracks(tailReading(645000,4399,-75));
            check(recovered.state==2&&recovered.session<0);
            recovered.collectStable(tailReading(660000,4399,-120),false,660000);
            check(recovered.state==2&&recovered.session<0);
            recovered.collectStable(tailReading(675000,4399,-75),false,675000);
            recovered.collectStable(tailReading(690000,4399,-120),false,690000);
            check(recovered.state==2&&recovered.session<0);
            recovered.collectStable(tailReading(705000,4399,-120),false,705000);
            check(recovered.state==1&&recovered.session>0);
            check(value(recovered,"SELECT COUNT(*) FROM battery_history")==1);
            check(value(recovered,"SELECT COUNT(*) FROM sessions WHERE active=1 AND kind='charge'")==1);
        }finally{release(recovered);}
    }
    static void historyPagination(File directory)throws Exception {
        RecordStore store=new RecordStore(directory);
        try(android.database.sqlite.SQLiteDatabase db=store.db){
            store.upgrade();
            for(int id=1;id<=46;id++)db.execSQL("INSERT INTO sessions(id,kind,active,fast_seconds) VALUES(?,'discharge',?,?)",
                new Object[]{id,id<=2?1:0,id==1?1:0});
            JSONArray args=new JSONArray().put(1).put(0).put(0).put(0).put(0);
            JSONArray first=store.list(args,0,1).getJSONArray("records");
            check(first.length()==40&&first.getJSONObject(0).getInt("id")==1&&first.getJSONObject(1).getInt("id")==2);
            int last=first.getJSONObject(39).getInt("id");check(last==9);
            args.put(1,last).put(1).put(2);
            JSONArray second=store.list(args,0,1).getJSONArray("records");
            check(second.length()==6);
            for(int i=0;i<second.length();i++)check(second.getJSONObject(i).getInt("id")==8-i);
            args.put(1,1).put(6,0);
            check(store.list(args,0,1).getJSONArray("records").getJSONObject(0).getInt("id")==2);
        }
    }
    public static void main(String[] args)throws Exception {
        android.os.Looper.prepareMainLooper();
        Class<?> activityThread=Class.forName("android.app.ActivityThread");
        Object thread=activityThread.getMethod("systemMain").invoke(null);
        context=(android.content.Context)activityThread.getMethod("getSystemContext").invoke(thread);
        historyPagination(Files.createTempDirectory("acol-history-page-").toFile());
        completedTailAtStableCurrent();
        sameBootAndLegacy();fastMinuteAnchor();splitAndTail();rebootResume();damagedCheckpointAndRollback();samplingSettings();standaloneGameWaitsForReadings();
        System.out.println("SQLite recovery: legacy upgrade, repeated resume, game minute accounting, power transition, kernel reboot, one tail snapshot at 4400 mV, charging restart, corrupt checkpoint and transaction rollback passed");
        System.exit(0);
    }
}
