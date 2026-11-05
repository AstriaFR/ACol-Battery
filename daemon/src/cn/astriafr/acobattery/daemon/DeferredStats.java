package cn.astriafr.acobattery.daemon;

import android.database.Cursor;
import android.content.Context;
import android.app.usage.*;
import java.lang.reflect.Method;
import java.io.IOException;
import android.database.sqlite.SQLiteDatabase;
import static cn.astriafr.acobattery.daemon.BatteryService.*;
import android.database.sqlite.SQLiteStatement;
import android.os.SystemClock;
import org.json.*;
import java.util.*;
import java.util.concurrent.*;
import cn.astriafr.acobattery.common.FallbackDiagnostics;

/** Raw capture is background work; attribution is run only for an explicitly viewed record. */
final class DeferredStats {
    interface Host {
        boolean activeSession(long id);
        Core.Reading settledReading(long id);
        long startWall();boolean clockAdjusted();boolean interactive();double credit();
        void usageError(Throwable error);void observe(Throwable error);void statsChanged();
    }
    final Context ctx;final RecordStore store;final SQLiteDatabase db;final Host host;
    final LinkedHashMap<String,Long> screenUsageReads=new LinkedHashMap<>(16,.75f,true);
    final Map<Long,Boolean> modes=new HashMap<>();
    interface EventReader{List<Core.Event> read(long from,long to)throws Exception;}
    EventReader reader;
    FutureTask<List<Core.Event>> eventRead;
    List<Pending> eventBatch;
    long nextRead;
    DeferredStats(Context ctx,RecordStore store,Host host){
        this.ctx=ctx;this.store=store;this.db=store.db;this.host=host;
        reader=(from,to)->queryUsage(ctx,from,to);
        db.execSQL("CREATE TABLE IF NOT EXISTS usage_deferred(session INTEGER PRIMARY KEY REFERENCES sessions(id) ON DELETE CASCADE,start_x INTEGER NOT NULL,event_wall INTEGER,event_revision INTEGER NOT NULL DEFAULT 0,computed_x INTEGER NOT NULL DEFAULT -1,computed_wall INTEGER NOT NULL DEFAULT 0,credit REAL NOT NULL DEFAULT 0,timeline TEXT NOT NULL DEFAULT '',dirty INTEGER NOT NULL DEFAULT 0,baseline_credit REAL NOT NULL DEFAULT 0)");
        db.execSQL("CREATE TABLE IF NOT EXISTS usage_raw(session INTEGER NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,x INTEGER NOT NULL,wall INTEGER NOT NULL,rm REAL,voltage REAL,online INTEGER NOT NULL,screen_on INTEGER NOT NULL,quality INTEGER NOT NULL,max_gap INTEGER NOT NULL,discharge_interval INTEGER NOT NULL,PRIMARY KEY(session,x)) WITHOUT ROWID");
        db.execSQL("CREATE TABLE IF NOT EXISTS usage_events(session INTEGER NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,time INTEGER NOT NULL,type INTEGER NOT NULL,pkg TEXT NOT NULL,activity TEXT NOT NULL,instance INTEGER NOT NULL,UNIQUE(session,time,type,pkg,activity,instance))");
        db.execSQL("CREATE INDEX IF NOT EXISTS usage_events_time ON usage_events(session,time)");
        db.execSQL("CREATE TABLE IF NOT EXISTS usage_baseline(session INTEGER NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,pkg TEXT NOT NULL,ms INTEGER NOT NULL,mah REAL NOT NULL,energy_mwh REAL NOT NULL,energy_ms INTEGER NOT NULL,mah_ms INTEGER NOT NULL,PRIMARY KEY(session,pkg)) WITHOUT ROWID");
    }
    boolean owns(long id){
        if(id<1)return false;
        Boolean cached=modes.get(id);if(cached!=null)return cached;
        try(Cursor c=db.rawQuery("SELECT 1 FROM usage_deferred WHERE session=?",new String[]{""+id})){boolean value=c.moveToFirst();modes.put(id,value);return value;}
    }
    boolean pending(long id){
        if(!owns(id))return false;
        try(Cursor c=db.rawQuery("SELECT dirty,computed_x,(SELECT MAX(x) FROM usage_raw WHERE session=?) FROM usage_deferred WHERE session=?",new String[]{""+id,""+id})){
            return c.moveToFirst()&&(c.getInt(0)!=0||!c.isNull(2)&&c.getLong(1)<c.getLong(2));
        }
    }
    void register(long id,Core.Reading r,long start,boolean baseline){
        if(owns(id))return;
        long x=Math.max(0,r.elapsed-start);
        db.execSQL("INSERT INTO usage_deferred(session,start_x,baseline_credit) VALUES(?,?,?)",new Object[]{id,x,baseline?host.credit():0});
        if(baseline)db.execSQL("INSERT INTO usage_baseline SELECT session,pkg,ms,mah,energy_mwh,energy_ms,mah_ms FROM apps WHERE session=?",new Object[]{id});
        modes.put(id,true);raw(id,start,r,0,90000);requestCapture();
    }
    void raw(long id,long start,Core.Reading r,int quality,long maxGap){
        raw(id,start,r,quality,maxGap,true);
    }
    void raw(long id,long start,Core.Reading r,int quality,long maxGap,boolean discharge){
        if(host.clockAdjusted()||Math.abs(r.wall-(host.startWall()+r.elapsed-start))>2000)quality|=16;
        // Preserve the existing main-user attribution boundary without querying usage events.
        try{if((Integer)Class.forName("android.app.ActivityManager").getMethod("getCurrentUser").invoke(null)!=0)quality|=16;}
        catch(Throwable unavailable){quality|=16;}
        db.execSQL("INSERT OR IGNORE INTO usage_raw(session,x,wall,rm,voltage,online,screen_on,quality,max_gap,discharge_interval) VALUES(?,?,?,?,?,?,?,?,?,?)",
            new Object[]{id,Math.max(0,r.elapsed-start),r.wall,nullable(r.rm),nullable(r.voltage()),r.online?1:0,host.interactive()?1:0,quality,maxGap,discharge?1:0});
    }
    void requestCapture(){nextRead=0;}
    static final class Pending{final long id,from,to;Pending(long id,long from,long to){this.id=id;this.from=from;this.to=to;}}
    void saveEvents(List<Pending> batch,List<Core.Event> events){
        boolean own=!db.inTransaction();if(own)db.beginTransaction();
        try(SQLiteStatement insert=db.compileStatement("INSERT OR IGNORE INTO usage_events(session,time,type,pkg,activity,instance) VALUES(?,?,?,?,?,?)")){
            for(Pending item:batch){
                long computed;
                try(Cursor c=db.rawQuery("SELECT event_wall,computed_wall FROM usage_deferred WHERE session=?",new String[]{""+item.id})){
                    if(!c.moveToFirst()||!c.isNull(0)&&c.getLong(0)>item.to)continue;
                    computed=c.getLong(1);
                }
                boolean late=false;
                for(Core.Event event:events){
                    if(event.time<item.from||event.time>item.to)continue;
                    insert.clearBindings();insert.bindLong(1,item.id);insert.bindLong(2,event.time);insert.bindLong(3,event.type);insert.bindString(4,Objects.toString(event.pkg,""));insert.bindString(5,Objects.toString(event.activity,""));insert.bindLong(6,event.instance);
                    if(insert.executeInsert()!=-1&&event.time<computed)late=true;
                }
                db.execSQL("UPDATE usage_deferred SET event_wall=?,event_revision=event_revision+1,dirty=MAX(dirty,?) WHERE session=?",new Object[]{item.to,late?1:0,item.id});
            }
            if(own)db.setTransactionSuccessful();
        }finally{if(own)db.endTransaction();}
    }
    void completeRead(List<Core.Event> events){
        saveEvents(eventBatch,events);eventRead=null;eventBatch=null;host.usageError(null);
    }
    static void requireMainUser()throws Exception{
        int user=(Integer)Class.forName("android.app.ActivityManager").getMethod("getCurrentUser").invoke(null);
        if(user!=0)throw new IllegalStateException("当前用户不是主用户，事件留待重试");
    }
    void capture(boolean force,long only){
        long now=SystemClock.elapsedRealtime();if(!force&&now<nextRead)return;
        try{
            // A timed-out Binder read continues in its worker. Consume it once it
            // finishes instead of replacing a useful result with another query.
            if(eventRead!=null){
                requireMainUser();
                if(!eventRead.isDone())throw new IllegalStateException("前次事件查询尚未返回，暂不重复发起");
                completeRead(eventRead.get());
            }
            List<Pending> pending=new ArrayList<>();long from=Long.MAX_VALUE,to=0;
            String sql="SELECT d.session,d.event_wall,s.active,(SELECT wall FROM usage_raw WHERE session=d.session ORDER BY x LIMIT 1),(SELECT MAX(wall) FROM usage_raw WHERE session=d.session) FROM usage_deferred d JOIN sessions s ON s.id=d.session"+(only>0?" WHERE d.session=?":"");
            try(Cursor c=db.rawQuery(sql,only>0?new String[]{""+only}:null)){
                while(c.moveToNext()){
                    if(c.isNull(3)||c.isNull(4))continue;
                    long target=!force&&c.getInt(2)!=0?Math.max(System.currentTimeMillis(),c.getLong(4)):c.getLong(4);
                    long cursor=c.isNull(1)?Math.max(0,c.getLong(3)-86400000L):c.getLong(1);
                    if(target<=cursor)continue;
                    long begin=c.isNull(1)?cursor:Math.max(0,cursor-5000);
                    pending.add(new Pending(c.getLong(0),begin,target));from=Math.min(from,begin);to=Math.max(to,target);
                }
            }
            if(pending.isEmpty()){if(!force)nextRead=now+3600000;return;}
            requireMainUser();
            final long queryFrom=from,queryTo=to;
            eventBatch=pending;eventRead=new FutureTask<>(()->reader.read(queryFrom,queryTo));
            Thread query=new Thread(eventRead,"aco-usage-batch");query.setDaemon(true);query.start();
            completeRead(eventRead.get(8,TimeUnit.SECONDS));
            if(only==0)nextRead=now+3600000;
        }catch(Throwable error){
            if(error instanceof ExecutionException||error instanceof CancellationException){eventRead=null;eventBatch=null;}
            nextRead=now+60000;host.usageError(error);
            FallbackDiagnostics.record("应用事件批量读取",error);host.observe(error);
        }
    }
    static String encode(Core.Timeline timeline)throws JSONException{
        Core.Timeline.State state=timeline.snapshot();JSONArray resumed=new JSONArray();for(Map.Entry<String,String> entry:state.resumed.entrySet())resumed.put(new JSONArray().put(entry.getKey()).put(entry.getValue()));
        return new JSONObject().put("screen",state.screen).put("locked",state.locked).put("known",state.known).put("resumed",resumed).toString();
    }
    static void decode(Core.Timeline timeline,String json)throws JSONException{
        JSONObject value=new JSONObject(json);Core.Timeline.State state=new Core.Timeline.State();state.screen=value.getBoolean("screen");state.locked=value.getBoolean("locked");state.known=value.getBoolean("known");
        JSONArray resumed=value.getJSONArray("resumed");for(int i=0;i<resumed.length();i++){JSONArray entry=resumed.getJSONArray(i);state.resumed.put(entry.getString(0),entry.getString(1));}timeline.restore(state);
    }
    static final class Sample{long x,wall,maxGap;double rm,voltage;boolean online,screen,discharge;int quality;}
    static Sample sample(Cursor c){Sample r=new Sample();r.x=c.getLong(0);r.wall=c.getLong(1);r.rm=c.isNull(2)?Double.NaN:c.getDouble(2);r.voltage=c.isNull(3)?Double.NaN:c.getDouble(3);r.online=c.getInt(4)!=0;r.screen=c.getInt(5)!=0;r.quality=c.getInt(6);r.maxGap=c.getLong(7);r.discharge=c.getInt(8)!=0;return r;}
    List<Core.Event> events(long id){
        List<Core.Event> list=new ArrayList<>();try(Cursor c=db.rawQuery("SELECT time,type,pkg,activity,instance FROM usage_events WHERE session=? ORDER BY time,rowid",new String[]{""+id})){
            while(c.moveToNext())list.add(new Core.Event(c.getLong(0),c.getInt(1),c.getString(2).isEmpty()?null:c.getString(2),c.getString(3).isEmpty()?null:c.getString(3),c.getInt(4)));
        }return list;
    }
    /** No call to this method is made by the sampling loop or homepage watch. */
    void calculate(long id)throws Exception{
        if(!owns(id))return;
        long startX,cursor,eventWall;double credit,baselineCredit;String saved;boolean dirty,game;long wallStart;
        try(Cursor c=db.rawQuery("SELECT d.start_x,d.computed_x,d.event_wall,d.credit,d.timeline,d.dirty,s.fast_seconds,s.start_wall,d.baseline_credit FROM usage_deferred d JOIN sessions s ON s.id=d.session WHERE d.session=?",new String[]{""+id})){
            if(!c.moveToFirst())return;startX=c.getLong(0);cursor=c.getLong(1);eventWall=c.isNull(2)?-1:c.getLong(2);credit=c.getDouble(3);saved=c.getString(4);dirty=c.getInt(5)!=0;game=c.getDouble(6)>0;wallStart=c.getLong(7);baselineCredit=c.getDouble(8);
        }
        if(eventWall<0)return; // Failed capture is pending, never finalized as empty usage.
        if(!dirty)try(Cursor c=db.rawQuery("SELECT MAX(x) FROM usage_raw WHERE session=?",new String[]{""+id})){if(c.moveToFirst()&&!c.isNull(0)&&cursor>=c.getLong(0))return;}
        List<Core.Event> events=events(id);Core.Timeline timeline=new Core.Timeline();Core.Depletion depletion=new Core.Depletion();
        boolean ready=cursor>=0&&!saved.isEmpty()&&!dirty;
        if(ready){decode(timeline,saved);depletion.restore(credit);}else{cursor=-1;depletion.restore(baselineCredit);}
        boolean own=!db.inTransaction();if(own)db.beginTransaction();boolean changed=false;
        try{
            if(dirty){
                db.execSQL("DELETE FROM apps WHERE session=?",new Object[]{id});db.execSQL("INSERT INTO apps SELECT * FROM usage_baseline WHERE session=?",new Object[]{id});
                db.execSQL("DELETE FROM screen_spans WHERE session=? AND start_wall>=?",new Object[]{id,wallStart+startX});db.execSQL("DELETE FROM screen_app_spans WHERE session=? AND start_wall>=?",new Object[]{id,wallStart+startX});
                db.execSQL("UPDATE sessions SET app_revision=app_revision+1 WHERE id=?",new Object[]{id});changed=true;
            }
            int eventIndex=0;Sample previous=null;long processed=cursor,processedWall=0;
            try(Cursor c=db.rawQuery("SELECT x,wall,rm,voltage,online,screen_on,quality,max_gap,discharge_interval FROM usage_raw WHERE session=? AND x>=? ORDER BY x",new String[]{""+id,""+Math.max(startX,cursor)})){
                while(c.moveToNext()){
                    Sample current=sample(c);if(current.wall>eventWall)break;
                    if(previous==null){
                        previous=current;processed=current.x;processedWall=current.wall;
                        if(!ready){timeline.seed(current.screen,false);while(eventIndex<events.size()&&events.get(eventIndex).time<=current.wall)timeline.apply(events.get(eventIndex++));}
                        else while(eventIndex<events.size()&&events.get(eventIndex).time<=current.wall)eventIndex++;
                        continue;
                    }
                    long dt=current.x-previous.x;if(dt<=0)continue;
                    boolean gap=(current.quality&9)!=0||Math.abs(current.wall-previous.wall-dt)>2000;
                    boolean unknown=((current.quality|previous.quality)&16)!=0;
                    if(current.wall<=previous.wall){timeline.seed(current.screen,false);eventIndex=0;while(eventIndex<events.size()&&events.get(eventIndex).time<=current.wall)timeline.apply(events.get(eventIndex++));}
                    int first=eventIndex;while(eventIndex<events.size()&&events.get(eventIndex).time<=current.wall)eventIndex++;
                    ScreenUsage.Builder spans=new ScreenUsage.Builder();Map<String,Long> split;
                    if(gap||unknown){split=Collections.singletonMap(Core.OTHER,dt);for(int i=first;i<eventIndex;i++)timeline.apply(events.get(i));if(gap)depletion.reset();}
                    else split=timeline.split(previous.wall,current.wall,events.subList(first,eventIndex),spans);
                    ScreenUsage.Builder mapped=!gap&&!unknown?spans.onRecordClock(previous.wall,current.wall,wallStart+previous.x,wallStart+current.x):null;
                    if(mapped!=null){split=new LinkedHashMap<>();for(ScreenUsage.AppSpan span:mapped.apps)split.put(span.pkg,split.getOrDefault(span.pkg,0L)+span.end-span.start);}
                    boolean discharging=!game||current.discharge;
                    if(discharging){
                        double loss=gap?Double.NaN:depletion.consume(previous.rm,current.rm,dt,current.maxGap);
                        double energy=Core.dischargeEnergyMwh(previous.voltage,current.voltage,loss);
                        add(id,split,dt,loss,energy);
                        if(mapped!=null){saveScreenSpans(id,mapped.spans);saveScreenApps(id,mapped.apps);}
                        if(game)db.execSQL("UPDATE game_rm_intervals SET energy_mwh=? WHERE session=? AND x=?",new Object[]{nullable(energy),id,current.x});
                    }else depletion.reset();
                    previous=current;processed=current.x;processedWall=current.wall;changed=true;
                }
            }
            if(processed>=0)db.execSQL("UPDATE usage_deferred SET computed_x=?,computed_wall=?,credit=?,timeline=?,dirty=0 WHERE session=?",new Object[]{processed,processedWall,depletion.checkpoint(),encode(timeline),id});
            if(changed)host.statsChanged();
            if(own)db.setTransactionSuccessful();
        }finally{if(own)db.endTransaction();}
    }
    void add(long id,Map<String,Long> split,long dt,double loss,double energy){
        for(Map.Entry<String,Long> part:split.entrySet()){
            long ms=part.getValue();boolean mah=Core.finite(loss),wh=Core.finite(energy);
            db.execSQL("INSERT INTO apps(session,pkg,ms,mah,energy_mwh,energy_ms,mah_ms) VALUES(?,?,?,?,?,?,?) ON CONFLICT(session,pkg) DO UPDATE SET ms=ms+excluded.ms,mah=mah+excluded.mah,energy_mwh=energy_mwh+excluded.energy_mwh,energy_ms=energy_ms+excluded.energy_ms,mah_ms=mah_ms+excluded.mah_ms",new Object[]{id,part.getKey(),ms,mah?loss*ms/dt:0,wh?energy*ms/dt:0,wh?ms:0,mah?ms:0});
        }
    }
    static final Method USAGE_INSTANCE_ID=usageInstanceMethod();
    static Method usageInstanceMethod() {
        try {return UsageEvents.Event.class.getMethod("getInstanceId");}
        catch(ReflectiveOperationException|SecurityException e){return null;}
    }
    static int usageInstance(UsageEvents.Event event) {
        if(USAGE_INSTANCE_ID!=null)try {return (Integer)USAGE_INSTANCE_ID.invoke(event);}
        catch(ReflectiveOperationException|SecurityException ignored){}
        return 0; // Class name remains the fallback on ROMs without instance IDs.
    }
    static List<Core.Event> queryUsage(Context ctx,long start,long end)throws Exception {
        UsageStatsManager manager=ctx.getSystemService(UsageStatsManager.class);
        if(manager==null)throw new IOException("应用使用统计服务暂不可用");
        UsageEvents events=manager.queryEvents(start,end+1);
        if(events==null)throw new IOException("UsageStats 未返回数据，可能尚未解锁");
        List<Core.Event> list=new ArrayList<>();UsageEvents.Event e=new UsageEvents.Event();
        while(events.hasNextEvent()) {
            events.getNextEvent(e);
            int t=e.getEventType();
            if(t==UsageEvents.Event.ACTIVITY_RESUMED||t==UsageEvents.Event.ACTIVITY_PAUSED
                ||t==UsageEvents.Event.ACTIVITY_STOPPED||t==UsageEvents.Event.SCREEN_INTERACTIVE
                ||t==UsageEvents.Event.SCREEN_NON_INTERACTIVE||t==UsageEvents.Event.KEYGUARD_SHOWN
                ||t==UsageEvents.Event.KEYGUARD_HIDDEN||t==UsageEvents.Event.DEVICE_SHUTDOWN
                ||t==UsageEvents.Event.DEVICE_STARTUP)
                list.add(new Core.Event(e.getTimeStamp(),t,e.getPackageName(),e.getClassName(),usageInstance(e)));
        }
        list.sort(Comparator.comparingLong(e2->e2.time));return list;
    }
    void saveScreenSpans(long id,List<ScreenUsage.Span> spans){
        if(spans.isEmpty())return;
        boolean own=!db.inTransaction();if(own)db.beginTransaction();
        try{
            for(ScreenUsage.Span span:spans)db.execSQL("INSERT OR IGNORE INTO screen_spans(session,start_wall,end_wall,screen_on) VALUES(?,?,?,?)",new Object[]{id,span.start,span.end,span.on?1:0});
            if(own)db.setTransactionSuccessful();
        }finally{if(own)db.endTransaction();}
    }
    List<ScreenUsage.Span> screenSpans(long id,long from,long to){
        List<ScreenUsage.Span> spans=new ArrayList<>();
        try(Cursor c=db.rawQuery("SELECT start_wall,end_wall,screen_on FROM screen_spans WHERE session=? AND end_wall>? AND start_wall<? ORDER BY start_wall,end_wall",new String[]{""+id,""+from,""+to})){
            while(c.moveToNext())spans.add(new ScreenUsage.Span(c.getLong(0),c.getLong(1),c.getInt(2)!=0));
        }
        return ScreenUsage.normalized(spans,from,to);
    }
    /** Display-only overlay: read settled screen spans without backfilling UsageStats. */
    JSONObject screenOffSpans(long id)throws Exception{
        JSONArray found=rows("SELECT kind,start_wall,start_elapsed,end_elapsed FROM sessions WHERE id=?",new String[]{""+id});
        if(found.length()==0)throw new IOException("记录不存在或已删除");
        JSONObject record=found.getJSONObject(0);
        if(!record.getString("kind").equals("discharge"))throw new IOException("息屏投影仅用于放电记录");
        long start=record.getLong("start_wall"),end=start+Math.max(0,record.getLong("end_elapsed")-record.getLong("start_elapsed"));
        JSONArray intervals=new JSONArray();
        for(ScreenUsage.Span span:screenSpans(id,start,end))if(!span.on)
            intervals.put(new JSONArray().put(span.start-start).put(span.end-start));
        return new JSONObject().put("ok",true).put("spans",intervals);
    }
    JSONObject screenUsage(long id,long requested,long requestedFrom,long requestedTo,int seed)throws Exception{
        JSONArray found=rows("SELECT start_wall,start_elapsed,end_elapsed,active,kind,clock_adjusted FROM sessions WHERE id=?",new String[]{""+id});
        if(found.length()==0)throw new IOException("记录不存在或已删除");JSONObject record=found.getJSONObject(0);
        if(!record.getString("kind").equals("discharge"))throw new IOException("亮屏使用时长仅用于放电记录");
        TimeZone zone=TimeZone.getDefault();long day=ScreenUsage.midnight(requested,zone),next=ScreenUsage.nextDay(day,zone);
        long recordStart=record.getLong("start_wall"),recordEnd=recordStart+Math.max(0,record.getLong("end_elapsed")-record.getLong("start_elapsed"));
        if(day>recordEnd||next<=recordStart)throw new IOException("日期不在记录范围内");
        long cutoff=recordEnd;
        if(host.activeSession(id)&&record.optInt("active")==1){Core.Reading settled=host.settledReading(id);cutoff=settled==null?recordStart:Math.min(recordEnd,recordStart+settled.elapsed-record.getLong("start_elapsed"));}
        long from=Math.max(Math.max(day,recordStart),requestedFrom>0?requestedFrom:day),to=Math.max(from,Math.min(Math.min(next,cutoff),requestedTo>0?requestedTo:next));
        List<ScreenUsage.Span> spans=screenSpans(id,from,to),missing=ScreenUsage.holes(spans,from,to);
        String key=id+":"+from+":"+to+":"+zone.getID();long now=SystemClock.elapsedRealtime();Long attempted=screenUsageReads.get(key);
        // UsageStats uses wall time. After a clock change only already captured,
        // normalized spans are trustworthy; querying chart timestamps is unsafe.
        if(!owns(id)&&record.optInt("clock_adjusted")==0&&!missing.isEmpty()&&(attempted==null||now-attempted>=60000)){
            screenUsageReads.put(key,now);while(screenUsageReads.size()>64)screenUsageReads.remove(screenUsageReads.keySet().iterator().next());
            List<ScreenUsage.Span> extra=Collections.emptyList();
            try{
                int user=(Integer)Class.forName("android.app.ActivityManager").getMethod("getCurrentUser").invoke(null);
                if(user==0){
                    extra=new ArrayList<>();
                    for(ScreenUsage.Span gap:missing){
                        int state=gap.start==from?seed:0;long queryStart=state==1||state==2?gap.start:Math.max(0,gap.start-86400000L);
                        // Use the last confirmed state; never query a confirmed span again.
                        try(Cursor c=db.rawQuery("SELECT end_wall,screen_on FROM screen_spans WHERE session=? AND end_wall<=? ORDER BY end_wall DESC LIMIT 1",new String[]{""+id,""+gap.start})){
                            if(c.moveToFirst()&&c.getLong(0)>=queryStart){queryStart=c.getLong(0);state=c.getInt(1)!=0?2:1;}
                        }
                        extra.addAll(ScreenUsage.fromEvents(queryUsage(ctx,queryStart,gap.end),gap.start,gap.end,state));
                    }
                }
            }catch(Throwable error){host.observe(error);}
            if(!extra.isEmpty()){saveScreenSpans(id,extra);spans.addAll(extra);spans=ScreenUsage.normalized(spans,from,to);}
        }
        ScreenUsage.Hours hours=ScreenUsage.hours(spans,from,to,zone);JSONArray on=new JSONArray(),covered=new JSONArray(),segments=new JSONArray();
        for(int hour=0;hour<24;hour++){on.put(hours.on[hour]);covered.put(hours.covered[hour]);}
        for(ScreenUsage.Span span:spans)segments.put(new JSONArray().put(span.start).put(span.end).put(span.on?1:0));
        return new JSONObject().put("ok",true).put("dayStart",day).put("dayEnd",next).put("rangeStart",from).put("rangeEnd",to).put("settledEnd",cutoff).put("segments",segments)
            .put("hours",on).put("covered",covered).put("totalMs",hours.totalOn).put("coveredMs",hours.totalCovered).put("unknownMs",Math.max(0,to-from-hours.totalCovered)).put("appRevision",appRevision(id));
    }
    void saveScreenApps(long id,List<ScreenUsage.AppSpan> spans){
        if(spans.isEmpty())return;boolean own=!db.inTransaction();if(own)db.beginTransaction();
        try{
            for(ScreenUsage.AppSpan span:spans)db.execSQL("INSERT OR IGNORE INTO screen_app_spans(session,start_wall,end_wall,pkg) VALUES(?,?,?,?)",new Object[]{id,span.start,span.end,span.pkg});
            if(own)db.setTransactionSuccessful();
        }finally{if(own)db.endTransaction();}
    }
    List<ScreenUsage.AppSpan> screenApps(long id,long from,long to){
        List<ScreenUsage.AppSpan> out=new ArrayList<>();long cursor=from;
        try(Cursor c=db.rawQuery("SELECT start_wall,end_wall,pkg FROM screen_app_spans WHERE session=? AND end_wall>? AND start_wall<? ORDER BY start_wall,end_wall",new String[]{""+id,""+from,""+to})){
            while(c.moveToNext()){long a=Math.max(cursor,c.getLong(0)),b=Math.min(to,c.getLong(1));if(b>a){out.add(new ScreenUsage.AppSpan(a,b,c.getString(2)));cursor=b;}}
        }return out;
    }
    JSONObject intervalApps(long id,long after)throws Exception{
        prepareViewedUsage(id);
        JSONArray found=rows("SELECT kind,fast_seconds,start_wall,start_elapsed,end_elapsed FROM sessions WHERE id=?",new String[]{""+id});
        if(found.length()==0)throw new IOException("记录不存在或已删除");JSONObject record=found.getJSONObject(0);
        if(!record.getString("kind").equals("discharge")||record.optDouble("fast_seconds")>0)throw new IOException("区间应用仅用于普通放电记录");
        long through=record.getLong("end_elapsed")-record.getLong("start_elapsed");boolean complete=true;
        if(owns(id))try(Cursor c=db.rawQuery("SELECT computed_x FROM usage_deferred WHERE session=?",new String[]{""+id})){c.moveToFirst();long computed=c.getLong(0);complete=computed>=through;through=Math.min(through,computed);}
        List<long[]> intervals=new ArrayList<>();boolean more=false;
        try(Cursor c=db.rawQuery("SELECT x,interval_ms FROM samples WHERE session=? AND x>? AND x<=? AND (interval_ms IS NULL OR interval_ms>=20000) ORDER BY x LIMIT 2001",new String[]{""+id,""+(Math.max(0,after)-1),""+through})){
            while(c.moveToNext()){
                if(intervals.size()==2000){more=true;break;}
                long x=c.getLong(0),dt=c.isNull(1)?0:Math.max(0,c.getLong(1));intervals.add(new long[]{Math.max(0,x-dt),x});
            }
        }
        long start=record.getLong("start_wall"),retryCursor=-1;JSONArray apps=new JSONArray();JSONObject labels=new JSONObject();int offset=0;
        List<ScreenUsage.AppSpan> spans=intervals.isEmpty()?Collections.emptyList():screenApps(id,start+intervals.get(0)[0],start+intervals.get(intervals.size()-1)[1]);
        for(long[] interval:intervals){
            long from=start+interval[0],to=start+interval[1],covered=0;Map<String,Long> totals=new TreeMap<>();
            while(offset<spans.size()&&spans.get(offset).end<=from)offset++;
            for(int i=offset;i<spans.size()&&spans.get(i).start<to;i++){
                ScreenUsage.AppSpan span=spans.get(i);long ms=Math.min(to,span.end)-Math.max(from,span.start);if(ms<=0)continue;
                covered+=ms;if(!span.pkg.equals(Core.OFF))totals.put(span.pkg,totals.getOrDefault(span.pkg,0L)+ms);
            }
            if(covered<to-from){complete=false;if(retryCursor<0)retryCursor=interval[1];}
            String top="";long longest=0;for(Map.Entry<String,Long> entry:totals.entrySet())if(entry.getValue()>longest){top=entry.getKey();longest=entry.getValue();}
            if(longest>0&&!labels.has(top)){
                String label=top.equals(Core.OTHER)?"其他亮屏使用":top;
                if(!top.equals(Core.OTHER))try{label=ctx.getPackageManager().getApplicationLabel(ctx.getPackageManager().getApplicationInfo(top,0)).toString();}catch(Exception ignored){}
                labels.put(top,label);
            }
            apps.put(new JSONArray().put(interval[1]).put(longest>0?top:JSONObject.NULL).put(to<=from?"none":longest>0?"app":covered>=to-from?"off":"missing"));
        }
        return new JSONObject().put("ok",true).put("apps",apps).put("labels",labels).put("cursor",intervals.isEmpty()?after:intervals.get(intervals.size()-1)[1]+1).put("more",more).put("through",through).put("complete",complete)
            .put("retryCursor",retryCursor<0?JSONObject.NULL:retryCursor).put("appRevision",appRevision(id));
    }
    JSONObject hourApps(long id,long requestedFrom,long requestedTo)throws Exception{
        JSONArray found=rows("SELECT start_wall,start_elapsed,end_elapsed,active,kind,clock_adjusted FROM sessions WHERE id=?",new String[]{""+id});
        if(found.length()==0)throw new IOException("记录不存在或已删除");JSONObject record=found.getJSONObject(0);
        if(!record.getString("kind").equals("discharge"))throw new IOException("应用使用时长仅用于放电记录");
        long start=record.getLong("start_wall"),end=start+Math.max(0,record.getLong("end_elapsed")-record.getLong("start_elapsed"));
        if(host.activeSession(id)&&record.optInt("active")==1){Core.Reading settled=host.settledReading(id);end=Math.min(end,settled==null?start:start+settled.elapsed-record.getLong("start_elapsed"));}
        long from=Math.max(start,requestedFrom),to=Math.max(from,Math.min(end,requestedTo));
        if(requestedTo<requestedFrom||requestedTo-requestedFrom>27*3600000L||requestedTo>ScreenUsage.nextDay(requestedFrom,TimeZone.getDefault()))throw new IOException("日期范围无效");
        List<ScreenUsage.AppSpan> spans=screenApps(id,from,to);List<ScreenUsage.Span> coverage=new ArrayList<>();
        for(ScreenUsage.AppSpan span:spans)coverage.add(new ScreenUsage.Span(span.start,span.end,true));
        List<ScreenUsage.Span> holes=ScreenUsage.holes(coverage,from,to);
        String key="apps:"+id+":"+from+":"+to;long now=SystemClock.elapsedRealtime();Long attempted=screenUsageReads.get(key);
        if(!owns(id)&&record.optInt("clock_adjusted")==0&&!holes.isEmpty()&&(attempted==null||now-attempted>=60000)){
            screenUsageReads.put(key,now);while(screenUsageReads.size()>64)screenUsageReads.remove(screenUsageReads.keySet().iterator().next());
            try{
                int user=(Integer)Class.forName("android.app.ActivityManager").getMethod("getCurrentUser").invoke(null);
                if(user==0){
                    long a=holes.get(0).start,b=holes.get(holes.size()-1).end;
                    List<Core.Event> events=queryUsage(ctx,Math.max(0,a-86400000L),b);
                    List<ScreenUsage.Span> known=ScreenUsage.fromEvents(events,a,b);
                    Core.Timeline history=new Core.Timeline();history.seed(false,true);ScreenUsage.Builder captured=new ScreenUsage.Builder();history.split(a,b,events,captured);
                    List<ScreenUsage.AppSpan> extra=new ArrayList<>();
                    for(ScreenUsage.Span gap:holes)for(ScreenUsage.Span confirmed:known){
                        long lo=Math.max(gap.start,confirmed.start),hi=Math.min(gap.end,confirmed.end);if(hi<=lo)continue;
                        for(ScreenUsage.AppSpan span:captured.apps){long x=Math.max(lo,span.start),y=Math.min(hi,span.end);if(y>x)extra.add(new ScreenUsage.AppSpan(x,y,span.pkg));}
                    }
                    if(!extra.isEmpty()){
                        db.beginTransaction();try{
                            saveScreenApps(id,extra);
                            db.execSQL("UPDATE sessions SET app_revision=app_revision+1 WHERE id=?",new Object[]{id});
                            db.setTransactionSuccessful();
                        }finally{db.endTransaction();}
                        spans=screenApps(id,from,to);
                    }
                }
            }catch(Throwable error){FallbackDiagnostics.record("分时应用补读",error);host.observe(error);}
        }
        JSONArray segments=new JSONArray();JSONObject labels=new JSONObject();
        for(ScreenUsage.AppSpan span:spans){
            segments.put(new JSONArray().put(span.start).put(span.end).put(span.pkg));
            if(!labels.has(span.pkg)&&!span.pkg.equals(Core.OFF)){
                String label=span.pkg.equals(Core.OTHER)?"其他亮屏使用":span.pkg;
                if(!span.pkg.equals(Core.OTHER))try{label=ctx.getPackageManager().getApplicationLabel(ctx.getPackageManager().getApplicationInfo(span.pkg,0)).toString();}catch(Exception ignored){}
                labels.put(span.pkg,label);
            }
        }
        coverage.clear();for(ScreenUsage.AppSpan span:spans)coverage.add(new ScreenUsage.Span(span.start,span.end,true));
        return new JSONObject().put("ok",true).put("segments",segments).put("labels",labels).put("settledEnd",end)
            .put("complete",ScreenUsage.holes(coverage,from,to).isEmpty()).put("appRevision",appRevision(id));
    }
    long appRevision(long id){
        try(Cursor row=db.rawQuery("SELECT app_revision FROM sessions WHERE id=?",new String[]{""+id})){return row.moveToFirst()?row.getLong(0):0;}
    }
    void prepareViewedUsage(long id)throws Exception{
        if(owns(id)){capture(true,id);calculate(id);}
        legacyIntervalApps(id);
    }
    /** Legacy app totals are immutable; only missing foreground spans may be recovered for tooltips. */
    void legacyIntervalApps(long id)throws Exception{
        JSONArray records=rows("SELECT kind,fast_seconds,start_wall,start_elapsed,end_elapsed,clock_adjusted FROM sessions WHERE id=?",new String[]{""+id});
        if(records.length()==0)return;JSONObject record=records.getJSONObject(0);
        if(!record.getString("kind").equals("discharge")||record.optDouble("fast_seconds")>0||record.optInt("clock_adjusted")!=0)return;
        long from=record.getLong("start_wall"),to=from+record.getLong("end_elapsed")-record.getLong("start_elapsed");
        if(owns(id))try(Cursor c=db.rawQuery("SELECT start_x FROM usage_deferred WHERE session=?",new String[]{""+id})){c.moveToFirst();to=Math.min(to,from+c.getLong(0));}
        if(to<=from)return;
        List<ScreenUsage.Span> coverage=new ArrayList<>();for(ScreenUsage.AppSpan span:screenApps(id,from,to))coverage.add(new ScreenUsage.Span(span.start,span.end,true));
        List<ScreenUsage.Span> holes=ScreenUsage.holes(coverage,from,to);String key="legacy-top:"+id;long now=SystemClock.elapsedRealtime();
        if(holes.isEmpty()||now-screenUsageReads.getOrDefault(key,-60000L)<60000)return;
        screenUsageReads.put(key,now);while(screenUsageReads.size()>64)screenUsageReads.remove(screenUsageReads.keySet().iterator().next());
        try{
            int user=(Integer)Class.forName("android.app.ActivityManager").getMethod("getCurrentUser").invoke(null);if(user!=0)return;
            long a=holes.get(0).start,b=holes.get(holes.size()-1).end;List<Core.Event> events=queryUsage(ctx,Math.max(0,a-86400000L),b);
            List<ScreenUsage.Span> known=ScreenUsage.fromEvents(events,a,b);Core.Timeline timeline=new Core.Timeline();timeline.seed(false,true);
            ScreenUsage.Builder captured=new ScreenUsage.Builder();timeline.split(a,b,events,captured);List<ScreenUsage.AppSpan> extra=new ArrayList<>();
            for(ScreenUsage.Span hole:holes)for(ScreenUsage.Span confirmed:known){long lo=Math.max(hole.start,confirmed.start),hi=Math.min(hole.end,confirmed.end);if(hi<=lo)continue;for(ScreenUsage.AppSpan span:captured.apps){long x=Math.max(lo,span.start),y=Math.min(hi,span.end);if(y>x)extra.add(new ScreenUsage.AppSpan(x,y,span.pkg));}}
            if(!extra.isEmpty()){saveScreenApps(id,extra);db.execSQL("UPDATE sessions SET app_revision=app_revision+1 WHERE id=?",new Object[]{id});}
        }catch(Throwable error){FallbackDiagnostics.record("旧记录区间应用补读",error);}
    }
    JSONObject hourlyEnergy(long id,long requested,long requestedFrom)throws Exception{
        JSONArray found=rows("SELECT start_wall,start_elapsed,end_elapsed,active,kind,fast_seconds FROM sessions WHERE id=?",new String[]{""+id});
        if(found.length()==0)throw new IOException("记录不存在或已删除");JSONObject record=found.getJSONObject(0);
        if(!record.getString("kind").equals("discharge"))throw new IOException("分时耗电仅用于放电记录");
        boolean game=record.optInt("fast_seconds")>0;
        TimeZone zone=TimeZone.getDefault();long day=ScreenUsage.midnight(requested,zone),next=ScreenUsage.nextDay(day,zone);
        long start=record.getLong("start_wall"),end=start+Math.max(0,record.getLong("end_elapsed")-record.getLong("start_elapsed"));
        if(day>end||next<=start)throw new IOException("日期不在记录范围内");
        // The raw game trace can cross midnight before its next RM settlement.
        // Return an empty confirmed range for that day instead of a read error.
        if(game&&host.activeSession(id)&&record.optInt("active")==1){Core.Reading settled=host.settledReading(id);end=Math.max(Math.max(day,start),Math.min(end,settled==null?start:start+settled.elapsed-record.getLong("start_elapsed")));}
        long from=Math.max(Math.max(day,start),requestedFrom),to=Math.max(from,Math.min(next,end));
        if(from>Math.min(next,end))throw new IOException("分时耗电读取范围无效");
        JSONObject attribution=hourApps(id,from,to);
        HourlyEnergy hours=new HourlyEnergy(screenApps(id,from,to),from,to,zone);
        if(game)gameHourlyEnergy(hours,id,start,from,to,end);
        else{
        // Include the first sample after midnight: its measured interval may
        // straddle the requested day's end. No graph decimation or 15 W filter.
        long upper=end-start;
        try(Cursor c=db.rawQuery("SELECT x FROM samples WHERE session=? AND x>=? ORDER BY x LIMIT 1",new String[]{""+id,""+(to-start)})){
            if(c.moveToFirst())upper=c.getLong(0);
        }
        try(Cursor c=db.rawQuery("SELECT x,interval_ms,power,quality FROM samples WHERE session=? AND x>? AND x<=? ORDER BY x",new String[]{""+id,""+(from-start),""+upper})){
            while(c.moveToNext()){
                if(c.isNull(1)||c.isNull(2)||(c.getInt(3)&3)!=0)continue;
                long stop=start+c.getLong(0),ms=c.getLong(1);if(ms<=0)continue;
                hours.add(stop-ms,stop,c.getDouble(2));
            }
        }
        }
        JSONArray values=new JSONArray(),covered=new JSONArray(),apps=new JSONArray();JSONObject labels=new JSONObject();
        for(int h=0;h<24;h++){
            values.put(hours.wh[h]);covered.put(hours.covered[h]);JSONArray entries=new JSONArray();
            for(Map.Entry<String,Double> app:hours.apps.get(h).entrySet()){
                String pkg=app.getKey();entries.put(new JSONArray().put(pkg).put(app.getValue()));
                if(!labels.has(pkg)){
                    String label=pkg.equals(Core.OFF)?"息屏":pkg.equals(Core.OTHER)?"其他":pkg;
                    if(!pkg.equals(Core.OFF)&&!pkg.equals(Core.OTHER))try{label=ctx.getPackageManager().getApplicationLabel(ctx.getPackageManager().getApplicationInfo(pkg,0)).toString();}catch(Exception ignored){}
                    labels.put(pkg,label);
                }
            }
            apps.put(entries);
        }
        return new JSONObject().put("ok",true).put("rangeStart",from).put("through",to).put("hours",values).put("covered",covered).put("apps",apps).put("labels",labels)
            .put("appRevision",attribution.getLong("appRevision")).put("attributionComplete",attribution.getBoolean("complete"));
    }
    void gameHourlyEnergy(HourlyEnergy hours,long id,long start,long from,long to,long end){
        long exactStart=end-start;
        try(Cursor c=db.rawQuery("SELECT start_x FROM game_rm_intervals WHERE session=? ORDER BY x LIMIT 1",new String[]{""+id})){
            if(c.moveToFirst())exactStart=c.getLong(0);
        }
        // Older records have aggregate app totals but no minute energy history.
        // Reconstruct only their requested day from saved RM/voltage samples.
        long historicalEnd=Math.min(end,start+exactStart);
        if(from<historicalEnd){
            long seed=0;
            try(Cursor c=db.rawQuery("SELECT x FROM samples WHERE session=? AND x<=? ORDER BY x DESC LIMIT 1",new String[]{""+id,""+Math.max(0,from-start-90000)})){
                if(c.moveToFirst())seed=c.getLong(0);
            }
            HourlyEnergy.RmMinutes minute=new HourlyEnergy.RmMinutes(hours,start);
            long upper=Math.min(historicalEnd-start,to-start+90000);
            try(Cursor c=db.rawQuery("SELECT x,rm,voltage,quality FROM samples WHERE session=? AND x>=? AND x<=? ORDER BY x",new String[]{""+id,""+seed,""+upper})){
                while(c.moveToNext())minute.sample(c.getLong(0),c.isNull(1)?Double.NaN:c.getDouble(1),c.isNull(2)?Double.NaN:c.getDouble(2),c.getInt(3));
            }
            minute.finish();
        }
        long upper=end-start;
        try(Cursor c=db.rawQuery("SELECT x FROM game_rm_intervals WHERE session=? AND x>=? ORDER BY x LIMIT 1",new String[]{""+id,""+(to-start)})){
            if(c.moveToFirst())upper=c.getLong(0);
        }
        try(Cursor c=db.rawQuery("SELECT start_x,x,energy_mwh FROM game_rm_intervals WHERE session=? AND x>? AND x<=? ORDER BY x",new String[]{""+id,""+(from-start),""+upper})){
            while(c.moveToNext())if(!c.isNull(2)){
                long a=c.getLong(0),b=c.getLong(1);
                hours.add(start+a,start+b,Core.averagePower(c.getDouble(2),b-a));
            }
        }
    }
    void addUsage(long id,Map<String,Long> split,long dt,double loss,double energy) {
        for(Map.Entry<String,Long> part:split.entrySet()) {
            boolean chargeMeasured=Core.finite(loss);
            double mah=chargeMeasured?loss*part.getValue()/dt:0;
            boolean measured=Core.finite(energy);
            db.execSQL("INSERT INTO apps(session,pkg,ms,mah,energy_mwh,energy_ms,mah_ms) VALUES(?,?,?,?,?,?,?) ON CONFLICT(session,pkg) DO UPDATE SET ms=ms+excluded.ms,mah=mah+excluded.mah,energy_mwh=energy_mwh+excluded.energy_mwh,energy_ms=energy_ms+excluded.energy_ms,mah_ms=mah_ms+excluded.mah_ms",
                new Object[]{id,part.getKey(),part.getValue(),mah,measured?energy*part.getValue()/dt:0,measured?part.getValue():0,chargeMeasured?part.getValue():0});
        }
        host.statsChanged();
    }

    JSONArray apps(long id)throws Exception {
        JSONArray result=new JSONArray();double otherMah=0,otherEnergy=0;long otherMs=0,otherEnergyMs=0,otherMahMs=0;
        try(Cursor c=db.rawQuery("SELECT pkg,ms,mah,energy_mwh,energy_ms,mah_ms FROM apps WHERE session=? ORDER BY mah DESC",new String[]{""+id})) {
            while(c.moveToNext()) {
                String pkg=c.getString(0);long ms=c.getLong(1);double mah=c.getDouble(2);
                double energy=c.getDouble(3);long energyMs=c.getLong(4),mahMs=c.getLong(5);
                if(pkg.equals(Core.OTHER)||(!pkg.equals(Core.OFF)&&ms<=60000)){otherMs+=ms;otherMah+=mah;otherEnergy+=energy;otherEnergyMs+=energyMs;otherMahMs+=mahMs;continue;}
                String label=pkg.equals(Core.OFF)?"息屏待机":pkg;
                if(!pkg.equals(Core.OFF))try{label=ctx.getPackageManager().getApplicationLabel(ctx.getPackageManager().getApplicationInfo(pkg,0)).toString();}catch(Exception ignored){}
                result.put(appJson(pkg,label,ms,mah,energy,energyMs,mahMs));
            }
        }
        if(otherMs>0)result.put(appJson(Core.OTHER,"其他",otherMs,otherMah,otherEnergy,otherEnergyMs,otherMahMs));
        return result;
    }
    JSONArray drainSummary(long id)throws Exception {
        double[] mah=new double[2],energy=new double[2];long[] ms=new long[2],energyMs=new long[2],mahMs=new long[2];
        // Aggregate the original app rows before short apps are merged into Other.
        try(Cursor c=db.rawQuery("SELECT CASE WHEN pkg=? THEN 1 ELSE 0 END,SUM(ms),SUM(mah),SUM(energy_mwh),SUM(energy_ms),SUM(mah_ms) FROM apps WHERE session=? GROUP BY 1",
                new String[]{Core.OFF,""+id})) {
            while(c.moveToNext()) {int i=c.getInt(0);ms[i]=c.getLong(1);mah[i]=c.getDouble(2);energy[i]=c.getDouble(3);energyMs[i]=c.getLong(4);mahMs[i]=c.getLong(5);}
        }
        return new JSONArray().put(appJson("__screenon__","亮屏耗电",ms[0],mah[0],energy[0],energyMs[0],mahMs[0]))
            .put(appJson(Core.OFF,"息屏耗电",ms[1],mah[1],energy[1],energyMs[1],mahMs[1]));
    }
    static JSONObject appJson(String pkg,String label,long ms,double mah,double energy,long energyMs,long mahMs)throws Exception {
        return new JSONObject().put("pkg",pkg).put("label",label).put("ms",ms).put("mah",mah)
            .put("energyMwh",energy).put("energyMs",energyMs).put("mahMs",mahMs).put("avgPower",nullable(Core.averagePower(energy,energyMs)));
    }
    JSONArray rows(String sql,String[] args)throws JSONException{return store.rows(sql,args);}
}
