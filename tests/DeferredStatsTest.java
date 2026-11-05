package cn.astriafr.acobattery.daemon;

import android.content.Context;
import android.database.Cursor;
import android.os.Looper;
import android.os.SystemClock;
import java.io.File;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.*;

/** Android/SQLite integration in an explicitly isolated directory; no production records. */
public final class DeferredStatsTest {
    static void check(boolean value,String why){if(!value)throw new AssertionError(why);}
    static long base,wall;
    static Core.Reading reading(long x,double rm,boolean online){Core.Reading r=new Core.Reading();r.elapsed=base+x;r.wall=wall+x;r.rm=rm;r.pct=80;r.current=online?-300:300;r.cell1=r.cell2=r.vmin=r.vmax=4000;r.online=online;return r;}
    static long count(Main m,String sql){try(Cursor c=m.db.rawQuery(sql,null)){c.moveToFirst();return c.getLong(0);}}
    static double amount(Main m,long id,String field){try(Cursor c=m.db.rawQuery("SELECT SUM("+field+") FROM apps WHERE session=?",new String[]{""+id})){c.moveToFirst();return c.getDouble(0);}}
    static void near(double a,double b,String why){check(Math.abs(a-b)<1e-7,why+": "+a+" != "+b);}
    static void close(Main m)throws Exception{m.performance.close();m.powerIndicator.close();m.db.close();m.lock.release();m.lockFile.close();}
    static Main open(Context ctx,File dir)throws Exception{Main m=new Main(ctx,new File(dir,"runtime"),dir);m.performance.close();m.sampling.interactive=true;return m;}
    static void ordinary(Context ctx,File dir)throws Exception{
        Main m=open(ctx,dir);List<Core.Event> events=new ArrayList<>(Arrays.asList(new Core.Event(wall,15,null),new Core.Event(wall,18,null),new Core.Event(wall,1,"a","A",1),new Core.Event(wall+60000,1,"b","B",2)));
        AtomicInteger reads=new AtomicInteger();m.deferred.reader=(a,b)->{reads.incrementAndGet();return events;};m.state=0;m.begin(reading(0,5000,false));long id=m.session;
        m.append(reading(120000,4990,false));check(count(m,"SELECT COUNT(*) FROM apps")==0,"no background attribution");check(count(m,"SELECT COUNT(*) FROM usage_raw")==2,"normal raw cadence");
        JSONObject initial=m.dispatch(new JSONObject().put("action","detail").put("args",new JSONArray().put(id)));
        check(initial.getBoolean("usagePending")&&initial.getJSONArray("apps").length()==0&&reads.get()==0,"first detail exposes pending accounting without blocking on event queries");
        m.deferred.capture(false,0);check(reads.get()==1,"initial batch read");m.deferred.capture(false,0);check(reads.get()==1,"no repeated event query before hourly deadline");
        check(count(m,"SELECT COUNT(*) FROM apps")==0,"capture never calculates");m.deferred.capture(true,id);m.deferred.calculate(id);near(amount(m,id,"mah"),10,"first RM interval");near(amount(m,id,"ms"),120000,"usage duration");
        JSONObject settled=m.dispatch(new JSONObject().put("action","settle_usage").put("args",new JSONArray().put(id)));
        check(!settled.getBoolean("usagePending")&&settled.getJSONArray("apps").length()>0,"settlement completion returns ready totals");
        m.deferred.calculate(id);near(amount(m,id,"mah"),10,"cached result is idempotent");
        m.append(reading(240000,4980,false));m.deferred.capture(true,id);m.deferred.calculate(id);near(amount(m,id,"mah"),20,"incremental result");
        near(amount(m,id,"energy_mwh"),Core.dischargeEnergyMwh(4000,4000,20),"energy formula preserved");
        check(m.deferred.intervalApps(id,0).getJSONArray("apps").getJSONArray(1).getString(1).equals("a"),"top app tie resolved deterministically");
        long revision=m.deferred.appRevision(id);m.db.execSQL("INSERT INTO usage_events VALUES(?,?,?,?,?,?)",new Object[]{id,wall+30000,1,"c","C",3});m.db.execSQL("UPDATE usage_deferred SET dirty=1 WHERE session=?",new Object[]{id});
        check(m.deferred.pending(id),"late-event repair is pending even without new RM samples");
        m.deferred.calculate(id);near(amount(m,id,"mah"),20,"late-event rebuild does not double count");check(m.deferred.appRevision(id)==revision+1,"repair invalidates frontend cache");
        close(m);m=open(ctx,dir);m.deferred.calculate(id);near(amount(m,id,"mah"),20,"persistent calculation cache");close(m);
    }
    static void game(Context ctx,File dir)throws Exception{
        Main m=open(ctx,dir);m.state=0;m.fastEnabled=true;m.withGame(()->{});final Main owner=m;
        m.withGame(()->{owner.begin(reading(0,5000,false));for(int i=1;i<=6;i++)owner.append(reading(i*10000,5000-i,false));});long id=m.gameTrack.session;
        check(count(m,"SELECT COUNT(*) FROM usage_raw WHERE session="+id)==2,"game stats only minute snapshots");check(count(m,"SELECT COUNT(*) FROM apps WHERE session="+id)==0,"game attribution deferred");
        m.db.execSQL("INSERT INTO usage_events VALUES(?,?,?,?,?,?)",new Object[]{id,wall,15,"","",0});m.db.execSQL("INSERT INTO usage_events VALUES(?,?,?,?,?,?)",new Object[]{id,wall,1,"game","Main",1});
        m.db.execSQL("UPDATE usage_deferred SET event_wall=? WHERE session=?",new Object[]{wall+60000,id});m.deferred.calculate(id);near(amount(m,id,"mah"),6,"game minute RM");
        // A charging endpoint must not suppress the following explicitly discharged interval.
        m.deferred.raw(id,base,reading(120000,5010,true),0,90000,false);m.deferred.raw(id,base,reading(180000,5005,false),0,90000,true);
        m.db.execSQL("UPDATE usage_deferred SET event_wall=? WHERE session=?",new Object[]{wall+180000,id});m.deferred.calculate(id);near(amount(m,id,"mah"),11,"charge gains excluded; subsequent discharge counted");close(m);
    }
    static void baseline(Context ctx,File dir)throws Exception{
        Main m=open(ctx,dir);m.state=0;m.begin(reading(0,5000,false));long id=m.session;
        m.db.execSQL("DELETE FROM usage_deferred WHERE session=?",new Object[]{id});m.db.execSQL("DELETE FROM usage_raw WHERE session=?",new Object[]{id});m.deferred.modes.remove(id);
        m.db.execSQL("INSERT INTO apps VALUES(?,?,?,?,?,?,?)",new Object[]{id,"legacy",120000,10,40,120000,120000});
        m.deferred.register(id,reading(120000,4990,false),base,true);m.deferred.raw(id,base,reading(240000,4980,false),0,180000);
        m.db.execSQL("INSERT INTO usage_events VALUES(?,?,?,?,?,?)",new Object[]{id,wall+120000,15,"","",0});m.db.execSQL("INSERT INTO usage_events VALUES(?,?,?,?,?,?)",new Object[]{id,wall+120000,1,"new","Main",1});
        m.db.execSQL("UPDATE usage_deferred SET event_wall=? WHERE session=?",new Object[]{wall+240000,id});m.deferred.calculate(id);near(amount(m,id,"mah"),20,"legacy baseline preserved");
        m.db.execSQL("UPDATE usage_deferred SET dirty=1 WHERE session=?",new Object[]{id});m.deferred.calculate(id);near(amount(m,id,"mah"),20,"baseline restored during repair");
        long cursor=count(m,"SELECT computed_x FROM usage_deferred WHERE session="+id);check(cursor==240000,"persistent cursor");close(m);
    }
    static void legacyAndFailure(Context ctx,File dir)throws Exception{
        Main m=open(ctx,dir);
        m.db.execSQL("INSERT INTO sessions(kind,start_wall,start_elapsed,end_elapsed,active,fast_seconds) VALUES('discharge',?,?,?,0,0)",new Object[]{wall,base,base+120000});long old=count(m,"SELECT MAX(id) FROM sessions");
        m.db.execSQL("INSERT INTO samples(session,x,quality,interval_ms) VALUES(?,0,0,NULL),(?,120000,0,120000)",new Object[]{old,old});
        m.db.execSQL("INSERT INTO apps VALUES(?,?,?,?,?,?,?)",new Object[]{old,"old.saved",120000,123,456,120000,120000});m.deferred.saveScreenApps(old,Collections.singletonList(new ScreenUsage.AppSpan(wall,wall+120000,"old.saved")));
        check(m.deferred.intervalApps(old,0).getJSONArray("apps").getJSONArray(1).getString(1).equals("old.saved"),"old record top app available");near(amount(m,old,"mah"),123,"completed legacy totals unchanged");check(!m.deferred.owns(old),"legacy record not converted");
        m.db.execSQL("DELETE FROM screen_app_spans WHERE session=?",new Object[]{old});
        m.deferred.screenUsageReads.put("legacy-top:"+old,SystemClock.elapsedRealtime()); // Keep the test independent of real historical UsageStats.
        m.deferred.saveScreenApps(old,Collections.singletonList(new ScreenUsage.AppSpan(wall,wall+60000,"old.saved")));
        JSONObject partial=m.deferred.intervalApps(old,0);check(!partial.getBoolean("complete")&&partial.getLong("retryCursor")==120000,"partially covered legacy intervals remain retryable");
        m.deferred.saveScreenApps(old,Collections.singletonList(new ScreenUsage.AppSpan(wall+60000,wall+120000,"recovered")));
        check(m.deferred.intervalApps(old,partial.getLong("retryCursor")).getBoolean("complete"),"recovered legacy interval can become complete");near(amount(m,old,"mah"),123,"tooltip recovery never rewrites legacy totals");
        JSONObject identities=m.dispatch(new JSONObject().put("action","cache_records").put("args",new JSONArray().put(old).put(999999)));
        check(identities.getJSONArray("records").length()==1&&identities.getJSONArray("records").getJSONArray(0).getLong(0)==old&&identities.getJSONArray("records").getJSONArray(0).getLong(1)==wall,"cache GC returns the full existing record identity");
        m.state=0;m.begin(reading(0,5000,false));long id=m.session;m.append(reading(120000,4990,false));m.deferred.reader=(a,b)->{throw new java.io.IOException("simulated unavailable usage service");};
        m.deferred.capture(true,id);m.deferred.calculate(id);check(count(m,"SELECT COUNT(*) FROM apps WHERE session="+id)==0,"failed capture stays pending");
        check(!m.deferred.intervalApps(id,0).getBoolean("complete"),"pending intervals are never cached as final empty results");
        check(m.deferred.pending(id),"failed first event read remains pending for foreground retry");
        m.deferred.reader=(a,b)->Arrays.asList(new Core.Event(wall,15,null),new Core.Event(wall,18,null),new Core.Event(wall,1,"recovered","Main",1));
        JSONObject recovered=m.dispatch(new JSONObject().put("action","settle_usage").put("args",new JSONArray().put(id)));
        check(!recovered.getBoolean("usagePending")&&recovered.getJSONArray("apps").length()>0,"retry completes without another battery sample");
        m.append(reading(240000,4980,false));
        long beforeLate=m.deferred.appRevision(id);
        m.deferred.eventBatch=Collections.singletonList(new DeferredStats.Pending(id,wall+115000,wall+240000));
        m.deferred.eventRead=new java.util.concurrent.FutureTask<>(()->Arrays.asList(new Core.Event(wall+119000,1,"late","Main",1),new Core.Event(wall+150000,1,"late","Main",1)));
        m.deferred.eventRead.run();
        m.deferred.reader=(a,b)->{throw new AssertionError("completed event read must not be replaced");};
        m.deferred.capture(true,id);m.deferred.calculate(id);
        check(!m.deferred.pending(id)&&m.deferred.eventRead==null&&m.usageError.isEmpty(),"read finishing after timeout is consumed on retry");near(amount(m,id,"mah"),20,"completed slow query retains all RM intervals");
        check(m.deferred.appRevision(id)==beforeLate+1,"completed read checks the current calculation cursor when detecting late events");
        m.deferred.eventBatch=Collections.singletonList(new DeferredStats.Pending(id,wall,wall+240000));
        m.deferred.eventRead=new java.util.concurrent.FutureTask<>(()->Collections.singletonList(new Core.Event(wall,1,"deleted","Main",1)));m.deferred.eventRead.run();
        m.store.deleteSession(id);m.deferred.capture(true,id);
        check(m.deferred.eventRead==null&&m.usageError.isEmpty(),"completed queries for deleted records cannot violate foreign keys");close(m);
    }
    public static void main(String[] args)throws Exception{
        File root=new File(args[0]);check(root.getPath().startsWith("/data/local/tmp/acol-deferred-test-")&&!root.exists(),"new isolated directory required");
        Looper.prepareMainLooper();Class<?> at=Class.forName("android.app.ActivityThread");Object thread=at.getMethod("systemMain").invoke(null);Context ctx=(Context)at.getMethod("getSystemContext").invoke(thread);
        base=SystemClock.elapsedRealtime();wall=System.currentTimeMillis();ordinary(ctx,new File(root,"ordinary"));game(ctx,new File(root,"game"));baseline(ctx,new File(root,"baseline"));legacyAndFailure(ctx,new File(root,"legacy-failure"));
        System.out.println("PASS: raw-only background, hourly event cadence, foreground incremental/idempotent/persistent accounting, late-event repair, game charge boundaries, active legacy baseline");System.exit(0);
    }
}
