package cn.astriafr.acobattery.daemon;

import android.content.Context;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteStatement;
import android.hardware.display.DisplayManager;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Looper;
import android.os.Build;
import android.os.Handler;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.Display;
import org.json.*;
import cn.astriafr.acobattery.common.FriendlyError;
import cn.astriafr.acobattery.common.RecordingGate;
import cn.astriafr.acobattery.common.OperationTimeout;
import cn.astriafr.acobattery.common.StartupFailure;
import cn.astriafr.acobattery.common.FallbackDiagnostics;
import cn.astriafr.acobattery.common.ErrorLogStore;
import cn.astriafr.acobattery.common.AllDataReset;
import cn.astriafr.acobattery.common.BatteryHistorySnapshot;
import java.io.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static cn.astriafr.acobattery.daemon.BatteryService.*;
import static cn.astriafr.acobattery.daemon.DeferredStats.queryUsage;

public final class Main {
    static final String SOCKET="cn.astriafr.aco_battery_app.v1";
    JSONObject settings;
    volatile boolean recordingEnabled,stopping;
    String recordingProblem="";
    String readingRecoveryProblem="";
    boolean recorderReady;
    boolean fastEnabled;long fastScreenOffAt=-1;
    String stopReason="recording_stopped";
    long ownerNext=SystemClock.elapsedRealtime()+3600000;int ownerMissing;
    long settingsVersion=SystemClock.elapsedRealtime();
    int cellCount;
    long lastCellProbe=-60000;
    static native int initWait();
    static native String waitDiagnostics();
    static native int waitEvent(long delay);
    static native void releaseWait();
    static native void interruptWait();
    static native boolean watchModule(String path);
    final Object mutex=new Object();
    final Context ctx;
    final File module, data;
    final SQLiteDatabase db;
    final RecordStore store;
    final BatteryService battery;
    final String boot;
    Core.FullGate fullGate=new Core.FullGate();
    Core.Depletion depletion=new Core.Depletion();
    Core.Timeline timeline=new Core.Timeline();
    Core.Sampling sampling=new Core.Sampling();
    Core.Reading previous, latest, uiReading;
    Core.Reading fastStatsPrevious;
    long fastStatsNext=Long.MAX_VALUE;
    final String statsEpoch=android.os.Process.myPid()+":"+SystemClock.elapsedRealtime();
    long statsVersion;
    final LinkedHashMap<Long,JSONObject> usageSnapshots=new LinkedHashMap<>();
    long session=-1, startElapsed, startWall, candidateAt=-1;
    Core.Reading candidate;
    int state=-1, capabilities;
    boolean stableOnline;
    boolean recoveryBoundary;
    boolean clockAdjusted;
    boolean timelineReady;
    boolean moduleWatch,screenListenerReady;
    String screenError="";
    String screenEventApi="unavailable";
    DisplayManager displays;
    DisplayManager.DisplayListener displayListener;
    BroadcastReceiver screenReceiver;Handler screenHandler;
    String registeredScreenApi="unavailable";
    final ScreenState screenState;
    ScreenState createScreenState(){return new ScreenState(sampling,()->{
        PowerManager power=ctx.getSystemService(PowerManager.class);return power==null?null:power.isInteractive();
    },()->{
        DisplayManager manager=ctx.getSystemService(DisplayManager.class);Display display=manager==null?null:manager.getDisplay(Display.DEFAULT_DISPLAY);
        if(display==null)return null;int value=display.getState();if(value==Display.STATE_UNKNOWN)return null;
        return value==Display.STATE_ON||value==Display.STATE_VR;
    },error->{FallbackDiagnostics.record("屏幕状态读取",error);if(framework!=null)framework.observe(error);});}
    final Runnable screenPoll=new Runnable(){public void run(){
        synchronized(mutex){
            if(stopping||screenListenerReady)return;
            refreshScreen(false);screenHandler.postDelayed(this,ScreenState.POLL_MS);
        }
    }};
    volatile String usageError="";
    String runtimeError="";
    boolean powerReady,powerGap;long powerRetryAt;
    String powerError="";
    long usageCheckedAt;
    volatile long usageErrorAt;
    FrameworkWatch framework;
    String[] header;
    RandomAccessFile lockFile;
    FileLock lock;
    final PerformanceCollector performance;
    final PowerIndicator powerIndicator;
    final DeferredStats deferred;
    long performanceSession=-1,performanceMetaSession=-1;
    String performanceMetaText="";
    final Core.Cadence performanceCadence=new Core.Cadence(2000);

    public static void main(String[] args) {
        boolean recorder=args.length==2&&!args[0].startsWith("--")&&new File(args[1]).getAbsoluteFile().equals(Owner.BASE.getAbsoluteFile());
        String attempt=recorder?StartupFailure.attempt(Owner.BASE):"";
        try{run(args,attempt);}
        catch(Throwable failure){
            if(recorder&&!new File(Owner.BASE,AllDataReset.PENDING).exists())FallbackDiagnostics.record("后台异常退出",failure);
            if(recorder&&!new File(Owner.BASE,AllDataReset.PENDING).exists())try{StartupFailure.record(Owner.BASE,attempt,failure);}catch(Throwable reportError){failure.addSuppressed(reportError);}
            StartupFailure.print(failure,System.err);System.exit(1);
        }
    }
    static void run(String[] args,String attempt)throws Exception {
        if(android.os.Process.myUid()!=0) { System.err.println("root required");System.exit(1); }
        if(args.length>0 && args[0].equals("--client")) { client(Arrays.copyOfRange(args,1,args.length));return; }
        Looper.prepareMainLooper();
        Class<?> activityThread=Class.forName("android.app.ActivityThread");
        Object thread=activityThread.getMethod("systemMain").invoke(null);
        Context ctx=(Context)activityThread.getMethod("getSystemContext").invoke(thread);
        if(args.length>0 && args[0].equals("--probe")) {
            System.load(args[1]+"/bin/libcurvewait.so");
            JSONObject report=new JSONObject();
            report.put("androidSdk",Build.VERSION.SDK_INT).put("androidVersion",Build.VERSION.RELEASE);
            report.put("nativeCapabilities",initWait()).put("nativeDiagnostics",waitDiagnostics());releaseWait();
            report.put("cutoffAvailable",cutoffAvailable());
            Core.Reading probeReading=readHardware(ctx,null,BatteryService.detectCellCount());
            report.put("powerInputDiagnostics",powerInputDiagnostics);
            report.put("reading",readingJson(probeReading)).put("powerInputFallback",probeReading.onlineKnown&&!"sysfs".equals(probeReading.onlineSource));
            DisplayManager manager=null;
            DisplayManager.DisplayListener listener=new DisplayManager.DisplayListener(){public void onDisplayAdded(int id){}public void onDisplayRemoved(int id){}public void onDisplayChanged(int id){}};
            try{
                manager=ctx.getSystemService(DisplayManager.class);
                if(manager==null)throw new IOException("显示服务不可用");
                report.put("screenEventApi",ScreenEvents.register(manager,listener,new Handler(Looper.getMainLooper())));
                report.put("screenListener",true);
            }catch(Throwable e){FallbackDiagnostics.record("屏幕监听注册",e);report.put("screenListener",false).put("screenError",FriendlyError.message(e));}
            finally{if(manager!=null)try{manager.unregisterDisplayListener(listener);}catch(Throwable ignored){}}
            // Usage events are optional and checked by normal attribution after startup.
            report.put("fallbackDiagnostics",FallbackDiagnostics.snapshot());System.out.println(report);System.exit(0);
        }
        if(args.length!=2)throw new IllegalArgumentException("module and data directory required");
        System.err.println("BatteryCurves: opening database");
        Main app=new Main(ctx,new File(args[0]),new File(args[1]));
        System.load(args[0]+"/bin/libcurvewait.so");app.capabilities=initWait();
        app.framework=new FrameworkWatch(app::wakeCollector);app.framework.start();
        app.usageError=app.framework.usageError();
        if(!app.usageError.isEmpty()){app.usageErrorAt=System.currentTimeMillis();FallbackDiagnostics.record("应用使用统计服务",app.usageError);}
        app.moduleWatch=watchModule(args[1]);app.monitorScreen();

        System.err.println("AColBattery App 2.4 Android="+Build.VERSION.RELEASE+" SDK="+Build.VERSION.SDK_INT+" capabilities="+app.capabilities);
        Thread server=new Thread(app::serve,"curve-ipc");server.setDaemon(true);server.start();
        Thread collector=new Thread(()->{
            try {app.collect(attempt);System.exit("framework_restarted".equals(app.stopReason)?75:0);}
            catch(Throwable e){
                if(app.framework!=null)app.framework.close();
                app.powerIndicator.close();
                try{StartupFailure.record(Owner.BASE,attempt,e);}catch(Throwable reportError){e.addSuppressed(reportError);}
                StartupFailure.print(e,System.err);System.exit(1);
            }
        },"curve-sampler");collector.start();
        Looper.loop();
    }

    Main(Context ctx,File module,File data) throws Exception {
        this.ctx=ctx;this.module=module;this.data=data;data.mkdirs();
        if(DataTransfer.PENDING.exists())throw new IOException("数据备份或恢复尚未结束，请打开应用完成处理。");
        if(new File(data,AllDataReset.PENDING).exists())throw new IOException("清除全部数据未完成，请重新打开应用继续清除。");
        FallbackDiagnostics.setSink((title,report)->{try{new ErrorLogStore(new File(data,"error-logs")).append("runtime",title,report);}catch(Exception ignored){}});
        screenState=createScreenState();
        recordingEnabled=!new File(data,"recording.disabled").exists();
        recordingProblem=read(new File(data,RecordingGate.PROBLEM).getPath());
        lockFile=new RandomAccessFile(new File(data,"daemon.lock"),"rw");
        lock=lockFile.getChannel().tryLock();
        if(lock==null){System.exit(73);throw new IOException("already running");}
        File resetFlag=new File(module,"reset-data");
        boolean reset=resetFlag.isFile();
        if(reset) {
            for(String name:new String[]{"records.db-wal","records.db-shm","records.db-journal","records.db"})
                Files.deleteIfExists(new File(data,name).toPath());
            System.err.println("Previous curve records removed; starting a fresh database");
        }
        boot=read("/proc/sys/kernel/random/boot_id");
        // OPLUS Android 17's default sync-mode resolver expects an app-bound array.
        // Supply both modes explicitly so a root app_process needs no app binding.
        store=new RecordStore(data);db=store.db;battery=new BatteryService(ctx);
        loadSettings();
        store.upgrade();
        deferred=new DeferredStats(ctx,store,new DeferredStats.Host(){
            public boolean activeSession(long id){synchronized(mutex){return Main.this.activeSession(id);}}
            public Core.Reading settledReading(long id){synchronized(mutex){return Main.this.settledReading(id);}}
            public long startWall(){return startWall;}
            public boolean clockAdjusted(){return clockAdjusted;}
            public boolean interactive(){return sampling.interactive;}
            public double credit(){return depletion.checkpoint();}
            public void usageError(Throwable error){usageError=error==null?"":error.toString();usageErrorAt=error==null?0:System.currentTimeMillis();}
            public void observe(Throwable error){if(framework!=null)framework.observe(error);}
            public void statsChanged(){synchronized(mutex){statsVersion++;}}
            public void eventsReady(){if(!stopping)wakeCollector();}
        });
        store.backfillHistory();
        fastEnabled="1".equals(runtimeValue("fast_discharge"));configureSampling(false);
        loadCellProfile();
        powerIndicator=new PowerIndicator(ctx,"1".equals(runtimeValue("power_indicator")));
        if(reset)Files.delete(resetFlag.toPath());
        performance=new PerformanceCollector((id,start,wall,row,meta)->{
            synchronized(mutex){
                if(stopping||!fastEnabled||gameTrack.session!=id||gameTrack.startElapsed!=start)return;
                String metaText=meta.toString();
                db.beginTransaction();try{
                    if(performanceMetaSession!=id||!metaText.equals(performanceMetaText))db.execSQL("INSERT INTO performance_profiles(session,meta) VALUES(?,?) ON CONFLICT(session) DO UPDATE SET meta=excluded.meta",new Object[]{id,metaText});
                    db.execSQL("INSERT OR IGNORE INTO performance_samples(session,x,data,wall) VALUES(?,?,?,?)",new Object[]{id,row.getLong(0),row.toString(),wall});
                    db.setTransactionSuccessful();
                }finally{db.endTransaction();}
                performanceMetaSession=id;
                performanceMetaText=metaText;
            }
        });
    }

    /** Independent record state; all swaps run under mutex, including API and collector work. */
    static final class RecordTrack {
        long session=-1,startElapsed,startWall,fastStatsNext=Long.MAX_VALUE;
        Core.Reading previous,fastStatsPrevious;
        boolean clockAdjusted,timelineReady,recoveryBoundary,powerGap;
        int state=0;
        Core.FullGate fullGate=new Core.FullGate();
        Core.Depletion depletion=new Core.Depletion();
        Core.Timeline timeline=new Core.Timeline();
        Core.Sampling sampling=new Core.Sampling();
    }
    final RecordTrack gameTrack=new RecordTrack(),normalContext=new RecordTrack();
    boolean gameContext;
    interface TrackWork {void run()throws Exception;}
    void saveTrack(RecordTrack t){
        t.session=session;t.startElapsed=startElapsed;t.startWall=startWall;t.previous=previous;
        t.fastStatsPrevious=fastStatsPrevious;t.fastStatsNext=fastStatsNext;t.clockAdjusted=clockAdjusted;
        t.timelineReady=timelineReady;t.recoveryBoundary=recoveryBoundary;t.powerGap=powerGap;t.state=state;
        t.fullGate=fullGate;t.depletion=depletion;t.timeline=timeline;t.sampling=sampling;
    }
    void useTrack(RecordTrack t){
        session=t.session;startElapsed=t.startElapsed;startWall=t.startWall;previous=t.previous;
        fastStatsPrevious=t.fastStatsPrevious;fastStatsNext=t.fastStatsNext;clockAdjusted=t.clockAdjusted;
        timelineReady=t.timelineReady;recoveryBoundary=t.recoveryBoundary;powerGap=t.powerGap;state=t.state;
        fullGate=t.fullGate;depletion=t.depletion;timeline=t.timeline;sampling=t.sampling;
    }
    void withGame(TrackWork work)throws Exception {
        if(gameContext)throw new IllegalStateException("Nested game recorder");
        saveTrack(normalContext);
        gameTrack.sampling.interactive=sampling.interactive;gameTrack.sampling.screenOnMs=sampling.screenOnMs;
        gameTrack.sampling.screenOffMs=sampling.screenOffMs;gameTrack.sampling.fastMs=Core.gamePowerInterval(stableOnline,settings.optLong("fastSeconds",10),settings.optLong("chargeSeconds",2));
        useTrack(gameTrack);gameContext=true;
        try{work.run();}finally{saveTrack(gameTrack);useTrack(normalContext);gameContext=false;}
    }
    long preferredSession(){return gameTrack.session>0?gameTrack.session:session;}
    boolean activeSession(long id){return id>0&&(id==session||id==gameTrack.session);}
    Core.Reading settledReading(long id){return id==gameTrack.session?gameTrack.fastStatsPrevious:id==session?previous:null;}
    void recoverTracks(Core.Reading r)throws Exception {
        if(recordingEnabled){recover(r);sampling.sampled(state,r.elapsed);}
        else state=r.online?1:0;
        if(fastEnabled){withGame(()->{recover(r);sampling.sampled(0,r.elapsed);});}
        else{
            db.execSQL("UPDATE sessions SET active=0,reason=? WHERE active=1 AND fast_seconds>0",new Object[]{"fast_stopped"});
            settingsVersion++;
        }
    }
    void stopGame(Core.Reading r,String reason)throws Exception {
        if(gameTrack.session>=0)withGame(()->{if(r!=null&&r.onlineKnown)append(r);finish(reason);});
        else if(fastEnabled){
            db.execSQL("UPDATE sessions SET active=0,reason=? WHERE active=1 AND fast_seconds>0",new Object[]{reason});
            deferred.requestCapture();
        }
    }
    void recoverWhenReady(Core.Reading r)throws Exception {
        if(!r.onlineKnown){powerUnavailable(r.elapsed);return;}
        String problem=RecordingGate.problem(r.rm,r.pct);
        if(!problem.isEmpty()){
            readingRecoveryProblem=problem;powerRetryAt=r.elapsed+5000;return;
        }
        powerReady=true;stableOnline=r.online;powerError="";readingRecoveryProblem="";powerRetryAt=0;
        recoverTracks(r);
    }
    boolean sharesChargingSample(){return recordingEnabled&&state==1&&session>=0;}
    void collectGame(Core.Reading reading,boolean screenBoundary,long now)throws Exception {
        if(!fastEnabled||candidateAt>=0)return;
        boolean charging=stableOnline;
        if(gameTrack.session<0){
            Core.Reading first=reading!=null?reading:hardwareReading(header);requirePower(first);
            if(first.online!=stableOnline){candidate=first;candidateAt=now;return;}
            withGame(()->{begin(first);sampling.sampled(0,first.elapsed);});return;
        }
        // While an ordinary charging record is active, its reads are the sole
        // power source. Performance-only deadlines never trigger battery reads.
        if(charging&&sharesChargingSample()&&reading==null){withGame(()->samplePerformance(now));return;}
        boolean boundary=reading!=null&&gameTrack.previous!=null&&reading.online!=gameTrack.previous.online;
        if(!screenBoundary&&!boundary&&now<gameTrack.sampling.next&&(charging||now<gameTrack.fastStatsNext)){
            withGame(()->samplePerformance(now));return;
        }
        Core.Reading r=reading!=null?reading:hardwareReading(header);latest=r;requirePower(r);
        if(r.online!=stableOnline){candidate=r;candidateAt=now;return;}
        withGame(()->{
            if(screenBoundary||boundary||charging||now>=sampling.next){
                append(r);
                if(screenBoundary){sampleFastStats(r,true);if(timelineReady)timeline.apply(new Core.Event(r.wall,sampling.interactive?15:16,null));}
                sampling.sampled(0,r.elapsed);
            }else if(now>=fastStatsNext)sampleFastStats(r,false);
            samplePerformance(now);
        });
    }

    static JSONObject decodeSettings(JSONArray p)throws Exception {
        if(p.length()!=8&&p.length()!=12&&p.length()!=13&&p.length()!=14&&p.length()!=15)throw new IOException("设置项数量无效");
        long[] v={5,30,560,1,300,450,50,60,2,30,10,1,2,2,10001};for(int i=0;i<p.length();i++)if(i!=13)v[i]=p.getLong(i);
        // The old game interval controlled both charts. Keep it for both on upgrade.
        double performanceSeconds=p.length()>=14?p.getDouble(13):p.length()>=12?Math.min(20,v[10]):2;
        if(v[0]<0||v[0]>40||v[1]<0||v[1]>80)throw new IOException("删除范围 0–40%，隐藏范围 0–80%，仅限整数");
        if(v[2]<1||v[2]>2000||v[3]<0||v[3]>1)throw new IOException("容量范围 0.01–20.00 Ah");
        if(v[4]<220||v[5]>480||v[4]>=v[5])throw new IOException("电压范围 2.20–4.80 V，下限须小于上限");
        if(v[6]<0||v[7]>80||v[6]>=v[7])throw new IOException("温度范围 −20–60℃，下限须小于上限");
        if(v[8]<1||v[8]>10||v[9]<1||v[9]>120||p.length()>9&&!p.get(9).toString().matches("[0-9]+")||v[10]<1||v[10]>60||v[11]<0||v[11]>1)throw new IOException("亮屏间隔 1–10 分钟，息屏间隔 1–120 分钟，游戏采样间隔 1–60 秒，仅限整数");
        if(v[12]<1||v[12]>10||p.length()>=13&&!p.get(12).toString().matches("[0-9]+"))throw new IOException("充电间隔 1–10 秒，仅限整数");
        if(!Core.finite(performanceSeconds)||performanceSeconds<0.5||performanceSeconds>20||Math.abs(performanceSeconds*10-Math.round(performanceSeconds*10))>1e-7)throw new IOException("性能采样间隔 0.5–20.0 秒，步进 0.1 秒");
        if(v[14]<0||v[14]>10001)throw new IOException("默认能量量程范围 0–100 Wh");
        return new JSONObject().put("energyWh",v[14]==10001?JSONObject.NULL:v[14]/100.0).put("deletePct",v[0]).put("hidePct",v[1]).put("capacityAh",v[2]/100.0)
            .put("capacityAuto",v[3]==1).put("voltageMin",v[4]/100.0).put("voltageMax",v[5]/100.0)
            .put("tempMin",v[6]-20).put("tempMax",v[7]-20).put("screenOnMinutes",v[8]).put("screenOffMinutes",v[9]).put("fastSeconds",v[10]).put("fastAutoOff",v[11]==1).put("chargeSeconds",v[12]).put("performanceSeconds",performanceSeconds);
    }
    JSONObject defaultSettings()throws Exception {
        JSONObject defaults=decodeSettings(new JSONArray("[5,30,560,1,300,450,50,60]"));
        Integer design=integer(read(BAT+"design_capacity"));
        if(design!=null&&design>=1000&&design<=20000){
            double ah=Math.round((design/1000.0-0.5)*100)/100.0;
            if(ah>=0.01&&ah<=20)defaults.put("capacityAh",ah);
        }
        return defaults;
    }
    void loadSettings()throws Exception {
        settings=defaultSettings();
        try(Cursor c=db.rawQuery("SELECT value FROM runtime WHERE key='module_settings'",null)) {
            if(c.moveToFirst())try {
                JSONArray stored=new JSONArray(c.getString(0));
                // Clamp only previously valid whole-second intervals above the new cap.
                if(stored.length()>=14){double old=stored.getDouble(13);if(old>20&&old<=60&&old==Math.rint(old))stored.put(13,20);}
                settings=decodeSettings(stored);
            }
            catch(Exception e){System.err.println("Settings fallback to defaults: "+e);}
        }
    }
    String runtimeValue(String key){
        try(Cursor c=db.rawQuery("SELECT value FROM runtime WHERE key=?",new String[]{key})){return c.moveToFirst()?c.getString(0):"";}
    }
    void configureSampling(boolean reschedule){
        long oldInterval=interval();
        sampling.screenOnMs=settings.optLong("screenOnMinutes",2)*60000;
        sampling.screenOffMs=settings.optLong("screenOffMinutes",30)*60000;
        sampling.chargeMs=settings.optLong("chargeSeconds",2)*1000;
        sampling.fastMs=0;
        long gameInterval=Core.gamePowerInterval(stableOnline,settings.optLong("fastSeconds",10),settings.optLong("chargeSeconds",2));
        if(reschedule&&gameTrack.sampling.fastMs!=gameInterval)gameTrack.sampling.next=Math.max(SystemClock.elapsedRealtime(),(gameTrack.previous==null?0:gameTrack.previous.elapsed)+gameInterval);
        gameTrack.sampling.fastMs=gameInterval;
        if(reschedule)gameTrack.sampling.expected=Math.max(gameTrack.sampling.expected,gameInterval);
        performanceCadence.configure(Math.round(settings.optDouble("performanceSeconds",2)*1000),reschedule?SystemClock.elapsedRealtime():0);
        if(reschedule&&recordingEnabled){
            long now=SystemClock.elapsedRealtime(),nextInterval=interval();
            if(nextInterval!=oldInterval)sampling.next=Math.max(now,(previous==null?now:previous.elapsed)+nextInterval);
            // A longer interval is intentional, not missing samples. Preserve an
            // already elapsed old plan when shortening without splitting a record.
            sampling.expected=Math.max(sampling.expected,nextInterval);
        }
        if(reschedule&&fastEnabled&&gameTrack.session>=0){
            db.execSQL("UPDATE sessions SET fast_seconds=?,performance_seconds=? WHERE id=?",new Object[]{gameInterval/1000,settings.optDouble("performanceSeconds",2),gameTrack.session});
        }
    }
    void rememberFast(boolean enabled){
        db.execSQL("INSERT OR REPLACE INTO runtime(key,value) VALUES('fast_discharge',?)",new Object[]{enabled?"1":"0"});
        fastEnabled=enabled;fastScreenOffAt=enabled&&!sampling.interactive?SystemClock.elapsedRealtime():-1;configureSampling(false);
    }
    long fastAutoDeadline(){
        return fastEnabled&&settings.optBoolean("fastAutoOff",true)&&!sampling.interactive&&fastScreenOffAt>=0?fastScreenOffAt+600000:Long.MAX_VALUE;
    }
    JSONObject setFast(boolean enabled,String reason)throws Exception {
        if(stopping)throw new IOException("后台正在结束，请稍后重试。");
        if(enabled==fastEnabled)return new JSONObject().put("ok",true).put("settings",settings).put("fastEnabled",fastEnabled);
        Core.Reading r=hardwareReading(header);latest=r;
        if(enabled){
            requirePower(r);
            String problem=RecordingGate.problem(r.rm,r.pct);
            if(!problem.isEmpty())throw new IOException(problem+"，请稍后再开启。");
            if(!recordingEnabled){
                powerReady=true;stableOnline=r.online;state=r.online?1:0;
                candidate=null;candidateAt=-1;
            }else if(!powerReady){
                powerReady=true;stableOnline=r.online;recoverTracks(r);
            }
            if(r.online!=stableOnline)throw new IOException("正在确认充放电状态，请稍后重试。");
            powerError="";powerRetryAt=0;
            rememberFast(true);
            try{withGame(()->{begin(r);sampling.sampled(0,r.elapsed);});}
            catch(Exception failure){rememberFast(false);throw failure;}
        }else{
            stopGame(r,reason);rememberFast(false);
            if(!recordingEnabled){candidate=null;candidateAt=-1;powerRetryAt=0;readingRecoveryProblem="";powerReady=r.onlineKnown;stableOnline=r.online;}
        }
        settingsVersion++;wakeCollector();
        return new JSONObject().put("ok",true).put("settings",settings).put("fastEnabled",fastEnabled).put("session",preferredSession());
    }
    void loadCellProfile() {
        try(Cursor c=db.rawQuery("SELECT value FROM runtime WHERE key='cell_count_v1'",null)) {
            if(c.moveToFirst()){int value=Integer.parseInt(c.getString(0));if(value==1||value==2)cellCount=value;}
        }catch(Exception e){System.err.println("Cell profile will be detected again: "+e);}
        resolveCellProfile();
    }
    void resolveCellProfile() {
        long now=SystemClock.elapsedRealtime();if(cellCount>0||now-lastCellProbe<60000)return;lastCellProbe=now;
        int detected=BatteryService.detectCellCount();if(detected==0)return;
        db.beginTransaction();
        try {
            db.execSQL("INSERT OR REPLACE INTO runtime(key,value) VALUES('cell_count_v1',?)",new Object[]{""+detected});
            db.execSQL("UPDATE sessions SET cell_count=? WHERE active=1 AND cell_count=0",new Object[]{detected});
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
        cellCount=detected;settingsVersion++;System.err.println("Remembered battery cell count: "+cellCount);
    }
    static String smallRecordWhere(JSONObject value) {
        return "active=0 AND fast_seconds=0 AND pct_start IS NOT NULL AND pct_end IS NOT NULL AND ABS(pct_end-pct_start)<="+value.optInt("deletePct",5);
    }
    JSONObject saveSettings(JSONArray p)throws Exception {
        int count=p.length()-1;
        if((count!=12&&count!=13&&count!=14&&count!=15)||(p.getInt(count)!=0&&p.getInt(count)!=1))throw new IOException("删除确认参数无效");
        JSONArray values=new JSONArray();for(int i=0;i<count;i++)values.put(p.get(i));
        if(count==12)values.put(settings.optLong("chargeSeconds",2));
        if(values.length()==13)values.put(settings.optDouble("performanceSeconds",2));
        if(values.length()==14)values.put(settings.isNull("energyWh")?10001:Math.round(settings.optDouble("energyWh",0)*100));
        JSONObject next=decodeSettings(values);int removed=0;
        db.beginTransaction();
        try {
            db.execSQL("INSERT OR REPLACE INTO runtime(key,value) VALUES('module_settings',?)",new Object[]{values.toString()});
            if(p.getInt(count)==1)removed=db.delete("sessions",smallRecordWhere(next),null);
            db.setTransactionSuccessful();
        } finally {db.endTransaction();}
        settings=next;settingsVersion++;store.rangeId=-1;store.rangeValues=null;
        if(removed>0)usageSnapshots.clear();
        configureSampling(true);powerIndicator.state.refresh();wakeCollector();
        return new JSONObject().put("ok",true).put("settings",settings).put("removed",removed);
    }
    static final class PowerUnavailable extends IOException {PowerUnavailable(){super("暂时无法确认充电连接状态，请稍后重试。");}}
    static void requirePower(Core.Reading r)throws PowerUnavailable {
        if(!r.onlineKnown)throw new PowerUnavailable();
    }
    void powerUnavailable(long now){
        readingRecoveryProblem="";
        powerError="充电连接状态暂不可用，采样已暂停，后台正在重试。";powerRetryAt=now+5000;powerGap=true;
        candidate=null;candidateAt=-1;fullGate.interrupt();recoveryBoundary=true;gameTrack.powerGap=true;gameTrack.recoveryBoundary=true;
    }
    Core.Reading hardwareReading(String[] knownHeader){
        resolveCellProfile();Core.Reading r=readHardware(ctx,knownHeader,cellCount,updated->header=updated);
        // A successful foreground read also belongs to this charging record,
        // even when it falls between two scheduled recorder samples.
        if(state==1&&session>0&&r.onlineKnown&&r.online&&r.vendorLogRead&&!fullGate.vendorLogSeen){
            db.execSQL("INSERT OR REPLACE INTO runtime(key,value) VALUES('charge_log_session',?)",new Object[]{""+session});
            fullGate.observe(r);
        }
        return r;
    }
    Core.Reading powerDisplayReading(){
        Core.Reading fresh=latest;if(uiReading!=null&&(fresh==null||uiReading.elapsed>fresh.elapsed))fresh=uiReading;
        if(fresh!=null&&SystemClock.elapsedRealtime()-fresh.elapsed<=1000)return fresh;
        resolveCellProfile();return readGauge(ctx,cellCount,false);
    }
    void wakeCollector(){
        if((capabilities&16)!=0)interruptWait();
        else if(moduleWatch)try{Owner.write(new File(data,"screen.event"),Long.toString(SystemClock.elapsedRealtime()));}
        catch(IOException e){screenError="采样事件通知不可用，屏幕切换可能延迟";}
        else screenError="采样事件通知不可用，屏幕切换可能延迟";
    }
    void screenChanged(long now,boolean first,boolean changed){
        if((first&&screenState.known)||changed)fastScreenOffAt=fastEnabled&&!sampling.interactive?now:-1;
        boolean captureChanged=deferred.captureSchedule.screen(screenState.available&&sampling.interactive,now);
        if(changed||!first&&captureChanged)wakeCollector();
    }
    void refreshScreen(boolean initial){
        long now=SystemClock.elapsedRealtime();boolean first=!screenState.known;
        boolean changed=screenState.refresh(now,initial);screenChanged(now,first,changed);
    }
    void stopScreenFallback(){
        if(screenHandler!=null)screenHandler.removeCallbacks(screenPoll);
        if(screenReceiver!=null){try{ctx.unregisterReceiver(screenReceiver);}catch(Throwable ignored){}screenReceiver=null;}
    }
    void screenFallback(String error){
        if(error!=null&&!error.isEmpty())FallbackDiagnostics.record("屏幕状态回退",error);
        screenListenerReady=false;screenError=error;
        if(screenReceiver==null){
            BroadcastReceiver receiver=new BroadcastReceiver(){public void onReceive(Context context,Intent intent){
                String action=intent.getAction();if(!Intent.ACTION_SCREEN_ON.equals(action)&&!Intent.ACTION_SCREEN_OFF.equals(action))return;
                synchronized(mutex){
                    if(stopping||screenListenerReady)return;
                    long now=SystemClock.elapsedRealtime();boolean first=!screenState.known;
                    boolean changed=screenState.accept(Intent.ACTION_SCREEN_ON.equals(action),now,false,"broadcast");
                    screenChanged(now,first,changed);
                }
            }};
            try{
                IntentFilter filter=new IntentFilter();filter.addAction(Intent.ACTION_SCREEN_ON);filter.addAction(Intent.ACTION_SCREEN_OFF);
                ctx.registerReceiver(receiver,filter,null,screenHandler,Context.RECEIVER_EXPORTED);screenReceiver=receiver;
            }catch(Throwable e){FallbackDiagnostics.record("屏幕广播注册",e);if(framework!=null)framework.observe(e);}
        }
        screenEventApi=screenReceiver!=null?"screen_broadcast":"state_poll";
        screenHandler.removeCallbacks(screenPoll);screenHandler.postDelayed(screenPoll,ScreenState.POLL_MS);
    }
    void monitorScreen() {
        screenHandler=new Handler(Looper.getMainLooper());refreshScreen(true);
        try {
            displays=ctx.getSystemService(DisplayManager.class);
            if(displays==null)throw new IOException("显示服务不可用");
            displayListener=new DisplayManager.DisplayListener() {
                public void onDisplayAdded(int id){}
                public void onDisplayRemoved(int id){}
                public void onDisplayChanged(int id) {
                    if(id!=Display.DEFAULT_DISPLAY)return;
                    synchronized(mutex) {
                        if(stopping)return;refreshScreen(false);
                        if(screenState.available){screenListenerReady=true;screenEventApi=registeredScreenApi;screenError=(capabilities&16)!=0||moduleWatch?"":"采样事件通知不可用，屏幕切换可能延迟";stopScreenFallback();}
                        else screenFallback(screenState.error);
                    }
                }
            };
            registeredScreenApi=ScreenEvents.register(displays,displayListener,screenHandler);
            if(screenState.available){screenEventApi=registeredScreenApi;screenListenerReady=true;screenError=(capabilities&16)!=0||moduleWatch?"":"采样事件通知不可用，屏幕切换可能延迟";}
            else screenFallback(screenState.error);
        }catch(Throwable e){
            FallbackDiagnostics.record("屏幕监听注册",e);if(framework!=null)framework.observe(e);screenFallback(FrameworkWatch.cause(e).toString());
        }
    }
    void collect(String attempt) throws Exception {
        synchronized(mutex) {
            Core.Reading r=hardwareReading(header);latest=r;
            String problem=RecordingGate.problem(r.rm,r.pct);
            if(!problem.isEmpty()&&!fastEnabled){
                boolean resumable=false;
                if(recordingEnabled)try(Cursor saved=db.rawQuery("SELECT 1 FROM sessions WHERE active=1 AND fast_seconds=0 AND boot=? LIMIT 1",new String[]{boot})){
                    resumable=saved.moveToFirst();
                }
                if(!resumable){RecordingGate.pause(data,problem);recordingProblem=problem;recordingEnabled=false;}
            }
            if(!recordingEnabled){
                state=r.online?1:0;
                db.execSQL("UPDATE sessions SET active=0,reason='recording_stopped' WHERE active=1 AND (fast_seconds=0 OR ?=0)",new Object[]{fastEnabled?1:0});
            }
            if(recordingEnabled||fastEnabled)recoverWhenReady(r);
            else{powerReady=r.onlineKnown;if(powerReady)stableOnline=r.online;}
            sampling.sampled(state,r.elapsed);
            StartupFailure.clear(Owner.BASE,attempt);
            recorderReady=true;
        }
        releaseWait();
        while(!stopping) {
            long due;
            synchronized(mutex) {
                boolean collecting=recordingEnabled||fastEnabled;
                due=collecting&&candidateAt>=0?candidateAt+3000:recordingEnabled?sampling.next:SystemClock.elapsedRealtime()+3600000;
                if(powerRetryAt>0&&collecting)due=powerRetryAt;
                due=Math.min(due,ownerNext);
                due=Math.min(due,fastAutoDeadline());
                due=Math.min(due,powerIndicator.state.deadline(sampling.interactive));
                if(collecting)due=Math.min(due,deferred.captureSchedule.deadline);
                if(powerRetryAt==0&&fastEnabled&&candidateAt<0){
                    if(gameTrack.session<0)due=Math.min(due,0);
                    else if(!sharesChargingSample()){due=Math.min(due,gameTrack.sampling.next);if(!stableOnline)due=Math.min(due,gameTrack.fastStatsNext);}
                }
                if(powerRetryAt==0&&fastEnabled&&gameTrack.session>=0&&candidateAt<0)due=Math.min(due,performanceSession==gameTrack.session?performanceCadence.next:0);
                if(!moduleWatch)due=Math.min(due,SystemClock.elapsedRealtime()+60000);
            }
            int event=waitEvent(Math.max(1,due-SystemClock.elapsedRealtime()));
            if(event==2){POWER_INPUTS.invalidate();SYSTEM_POWER.invalidate();}
            if(event<0){Thread.sleep(1000);runtimeError="定时唤醒失败";}
            try {
                synchronized(mutex) {
                    if(!framework.healthy()){
                        System.err.println("Framework services disconnected; restarting recorder.");
                        stopReason="framework_restarted";stopping=true;screenListenerReady=false;break;
                    }
                    if(SystemClock.elapsedRealtime()>=ownerNext)checkOwner();
                    if(stopping)break;
                    if(!recordingEnabled&&!fastEnabled){sampling.takeScreenSample();continue;}
                    long now=SystemClock.elapsedRealtime();
                    if(now>=fastAutoDeadline()){setFast(false,"fast_idle_timeout");continue;}
                    if(powerRetryAt>now)continue;
                    PowerInputs.Result input=online(ctx);
                    if(!input.known){powerUnavailable(now);continue;}
                    if(powerRetryAt>0){sampling.next=now;gameTrack.sampling.next=now;}
                    powerError="";powerRetryAt=0;
                    if(!powerReady){
                        Core.Reading first=hardwareReading(header);latest=first;
                        recoverWhenReady(first);continue;
                    }
                    boolean plugged=input.online;
                    boolean screenSample=sampling.takeScreenSample();
                    Core.Reading screenReading=screenSample?hardwareReading(header):null;
                    Core.Reading turnReading=screenReading;
                    if(screenReading!=null){latest=screenReading;if(!screenReading.onlineKnown){powerUnavailable(now);continue;}if(screenReading.online!=plugged){candidateAt=-1;candidate=null;powerRetryAt=now+1000;continue;}}
                    if(plugged!=stableOnline) {
                        if(candidateAt<0){candidate=screenReading!=null?screenReading:hardwareReading(header);if(!candidate.onlineKnown){powerUnavailable(now);continue;}if(candidate.online!=plugged){candidate=null;powerRetryAt=now+1000;continue;}candidateAt=now;}
                        if(now-candidateAt>=3000) {
                            if(recordingEnabled){append(candidate);finish(plugged?"plugged":"unplugged");}
                            stableOnline=plugged;state=plugged?1:0;
                            if(recordingEnabled)begin(candidate);
                            turnReading=candidate;
                            candidate=null;candidateAt=-1;sampling.sampled(state,now);
                        }
                    } else {
                        candidateAt=-1;candidate=null;
                        if(recordingEnabled&&(screenSample||now>=sampling.next)) {
                            Core.Reading r=screenReading!=null?screenReading:hardwareReading(header);latest=r;turnReading=r;
                            if(!r.onlineKnown){powerUnavailable(now);continue;}
                            if(r.online!=stableOnline){candidate=r;candidateAt=now;continue;}
                            if(state==2) {
                                if(!r.full&&Core.finite(r.current)&&r.current<-50){state=1;begin(r);}
                            } else {
                                append(r);
                                if(screenSample&&state==0){
                                    // The display callback is authoritative at this boundary even if
                                    // UsageStats publishes its matching screen event a little later.
                                    if(timelineReady)timeline.apply(new Core.Event(r.wall,sampling.interactive?15:16,null));
                                }
                                if(state==1 && fullGate.update(r,sampling.expected)) {finish("full_tail_confirmed");state=2;}
                            }
                            sampling.sampled(state,now);
                        }
                    }
                    if(candidateAt<0)collectGame(turnReading,screenSample,now);
                }
            } catch(PowerUnavailable unavailable){
                synchronized(mutex){powerUnavailable(SystemClock.elapsedRealtime());}
            } finally {
                try{synchronized(mutex){
                    long displayNow=SystemClock.elapsedRealtime();
                    deferred.captureAsync(false,0);
                    if(!stopping&&powerIndicator.state.due(displayNow,sampling.interactive)){
                        long seconds=settings.optLong("fastSeconds",10);
                        try{powerIndicator.update(powerDisplayReading(),displayNow,seconds);}
                        catch(Throwable failure){powerIndicator.unavailable(failure,displayNow,seconds);}
                    }
                }}finally{releaseWait();}
            }
        }
        stopScreenFallback();
        if(displays!=null&&displayListener!=null)try{displays.unregisterDisplayListener(displayListener);}catch(RuntimeException ignored){}
        framework.close();performance.close();powerIndicator.close();usageWorker.shutdownNow();
        synchronized(mutex) {
            // Updating code or reconnecting framework proxies suspends ownership,
            // not the charge/discharge session. Completed samples are already durable.
            if(!Core.resumableStop(stopReason)){stopGame(null,stopReason);finish(stopReason);}
            if(!Core.resumableStop(stopReason))deferred.capture(true,0);
            db.close();lock.release();lockFile.close();
        }
    }
    JSONObject setRecording(boolean enabled)throws Exception {
        if(stopping)throw new IOException("后台正在结束，请稍后重试。");
        recordingProblem=read(new File(data,RecordingGate.PROBLEM).getPath());
        if(enabled==recordingEnabled)return recordingResult();
        return setRecording(enabled,hardwareReading(header));
    }
    JSONObject recordingResult()throws Exception{return new JSONObject().put("ok",true).put("recordingEnabled",recordingEnabled).put("fastEnabled",fastEnabled).put("recordingProblem",recordingProblem).put("settings",settings);}
    JSONObject setRecording(boolean enabled,Core.Reading r)throws Exception {
        if(stopping)throw new IOException("后台正在结束，请稍后重试。");latest=r;
        File flag=new File(data,"recording.disabled");
        if(!enabled){
            if(!flag.exists()&&!flag.createNewFile())throw new IOException("停止状态保存失败。");
            // Seal the old interval before pausing. A transition at the button
            // boundary is split instead of attributing it to the old session.
            if(r.valid()&&r.onlineKnown){
                if(session>=0&&r.online!=stableOnline){append(candidate!=null?candidate:r);finish(r.online?"plugged":"unplugged");}
                else append(r);
            }
            stopGame(r,"recording_stopped");finish("recording_stopped");
            db.execSQL("UPDATE sessions SET active=0,reason='recording_stopped' WHERE active=1 AND fast_seconds=0");
            recordingEnabled=false;if(fastEnabled)rememberFast(false);
            readingRecoveryProblem="";powerRetryAt=0;powerReady=r.onlineKnown;stableOnline=r.online;
        }else{
            requirePower(r);
            recordingProblem=RecordingGate.problem(r.rm,r.pct);
            RecordingGate.enable(data,r.rm,r.pct);
            recordingEnabled=true;
            if(!powerReady)recoverWhenReady(r);
            else{powerError="";powerRetryAt=0;state=r.online?1:0;stableOnline=r.online;begin(r);sampling.sampled(state,r.elapsed);}
        }
        candidate=null;candidateAt=-1;sampling.takeScreenSample();settingsVersion++;wakeCollector();
        return recordingResult();
    }
    void requestStop(String reason)throws Exception {
        synchronized(mutex){
            if(stopping)return;
            Core.Reading stopReading=session>=0||gameTrack.session>=0?hardwareReading(header):null;
            if(stopReading!=null)latest=stopReading;
            if(session>=0){
                Core.Reading r=stopReading;append(r);
                if(Core.resumableStop(reason)){
                    if(r.onlineKnown&&fastStatsPrevious!=null)sampleFastStats(r,true);
                }else finish(reason);
            }
            if(gameTrack.session>=0)withGame(()->{Core.Reading r=latest; if(r!=null&&r.onlineKnown)append(r);if(Core.resumableStop(reason)){if(r!=null&&r.onlineKnown)sampleFastStats(r,true);}else finish(reason);});
            if(!Core.resumableStop(reason))deferred.capture(true,0);
            stopReason=reason;stopping=true;wakeCollector();
        }
    }
    void checkOwner(){
        int installed=Owner.check(ctx);
        if(installed!=0){ownerMissing=0;ownerNext=SystemClock.elapsedRealtime()+3600000;return;}
        ownerNext=SystemClock.elapsedRealtime()+60000;
        if(++ownerMissing<2)return;
        try{Owner.markCleanup();requestStop("app_uninstalled");}
        catch(Exception e){System.err.println("Deferred uninstall cleanup: "+e.getClass().getSimpleName());ownerMissing=0;ownerNext=SystemClock.elapsedRealtime()+3600000;}
    }
    long interval(){return sampling.interval(state);}

    void recover(Core.Reading r)throws Exception {
        requirePower(r);
        session=-1;previous=null;fullGate.reset();depletion.reset();timelineReady=false;
        fastStatsPrevious=null;fastStatsNext=Long.MAX_VALUE;recoveryBoundary=false;
        JSONObject progress=null;
        try{String value=runtimeValue(gameContext?"game_progress_v1":"session_progress_v1");if(gameContext&&value.isEmpty())value=runtimeValue("session_progress_v1");if(!value.isEmpty())progress=new JSONObject(value);}
        catch(JSONException e){System.err.println("Recorder checkpoint unavailable; using committed samples.");}
        db.beginTransaction();
        try {
            try(Cursor c=db.rawQuery("SELECT id,boot,kind,start_elapsed,end_elapsed,start_wall,fast_seconds,clock_adjusted FROM sessions WHERE active=1 ORDER BY id DESC",null)) {
                while(c.moveToNext()) {
                    long id=c.getLong(0);
                    if((c.getInt(6)>0)!=gameContext)continue;
                    if(session<0&&Core.resumeSession(c.getString(1),boot,c.getString(2),c.getInt(6)>0,
                            r.online,gameContext,c.getLong(3),c.getLong(4),r.elapsed)){
                        Core.Reading saved=lastSample(id,c.getLong(3),c.getLong(5));
                        if(saved!=null&&saved.elapsed>=c.getLong(3)&&saved.elapsed<=c.getLong(4)&&saved.elapsed<=r.elapsed){
                            session=id;startElapsed=c.getLong(3);startWall=c.getLong(5);clockAdjusted=c.getInt(7)!=0;state=gameContext?0:r.online?1:0;
                            if(state==1)fullGate.vendorLogSeen=(""+id).equals(runtimeValue("charge_log_session"));
                            if(progress!=null&&progress.optLong("session",-1)==id&&boot.equals(progress.optString("boot"))){
                                Core.Reading checkpoint=restoreReading(progress.optJSONObject("previous"));
                                if(checkpoint!=null&&checkpoint.elapsed==saved.elapsed&&checkpoint.elapsed<=r.elapsed)saved=checkpoint;
                                depletion.restore(progress.optDouble("credit",0));
                                Core.Reading stats=restoreReading(progress.optJSONObject("fastPrevious"));
                                if(gameContext&&stats!=null&&stats.elapsed>=startElapsed&&stats.elapsed<=r.elapsed){
                                    fastStatsPrevious=stats;fastStatsNext=progress.optLong("fastNext",stats.elapsed+60000);
                                    if(fastStatsNext<=stats.elapsed||fastStatsNext>stats.elapsed+60000)fastStatsNext=stats.elapsed+60000;
                                }
                                if(gameContext&&!sampling.interactive){
                                    long offAt=progress.optLong("screenOffAt",-1);
                                    if(offAt>=0&&offAt<=r.elapsed)fastScreenOffAt=offAt;
                                }
                            }
                            previous=saved;recoveryBoundary=true;
                            sampling.restoreExpected(progress!=null&&progress.optLong("session",-1)==id
                                ?progress.optLong("expectedMs",interval()):interval());
                            // Older collectors did not persist the minute accounting anchor.
                            // Start that anchor here instead of double-counting an old minute.
                            if(gameContext&&fastStatsPrevious==null){fastStatsPrevious=r;fastStatsNext=r.elapsed+60000;}
                            if(state==0)deferred.register(id,gameContext?fastStatsPrevious:saved,startElapsed,true);
                            continue;
                        }
                    }
                    db.execSQL("UPDATE sessions SET active=0,reason=? WHERE id=?",new Object[]{boot.equals(c.getString(1))?"collector_restart":"reboot",id});
                    db.delete("sessions","id=? AND "+smallRecordWhere(settings),new String[]{""+id});
                    settingsVersion++;
                }
            }
            if(session>=0)append(r);
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
        if(session>=0)return;
        try(Cursor c=db.rawQuery("SELECT value FROM runtime WHERE key='full_tail_boot'",null)) {
            if(!gameContext&&c.moveToFirst()&&r.online&&(r.full||Core.FullGate.electricalCandidate(r))&&boot.equals(c.getString(0))){state=2;return;}
        }
        state=gameContext?0:r.online?1:0;begin(r);
    }
    Core.Reading lastSample(long id,long start,long wall){
        try(Cursor c=db.rawQuery("SELECT x,rm,pct,current,voltage,cell1,cell2,temp,online,quality FROM samples WHERE session=? ORDER BY x DESC LIMIT 1",new String[]{""+id})){
            if(!c.moveToFirst())return null;
            Core.Reading r=new Core.Reading();r.elapsed=start+c.getLong(0);r.wall=wall+c.getLong(0);
            r.rm=c.isNull(1)?Double.NaN:c.getDouble(1);r.pct=c.isNull(2)?Double.NaN:c.getDouble(2);
            r.current=c.isNull(3)?Double.NaN:-c.getDouble(3);r.vmin=r.vmax=c.isNull(4)?Double.NaN:c.getDouble(4);
            r.cell1=c.isNull(5)?Double.NaN:c.getDouble(5);r.cell2=c.isNull(6)?Double.NaN:c.getDouble(6);
            r.temp=c.isNull(7)?Double.NaN:c.getDouble(7);r.cellCount=(c.getInt(9)&Core.UNCLASSIFIED_CELLS)!=0?0:cellCount;
            r.online=!c.isNull(8)&&c.getInt(8)!=0;r.onlineKnown=!c.isNull(8);return r;
        }
    }
    static JSONObject checkpointReading(Core.Reading r)throws JSONException {
        if(r==null)return null;
        return new JSONObject().put("elapsed",r.elapsed).put("wall",r.wall).put("rm",nullable(r.rm)).put("pct",nullable(r.pct))
            .put("current",nullable(r.current)).put("vmin",nullable(r.vmin)).put("vmax",nullable(r.vmax))
            .put("cell1",nullable(r.cell1)).put("cell2",nullable(r.cell2)).put("cellCount",r.cellCount).put("online",r.online).put("onlineKnown",r.onlineKnown);
    }
    static Core.Reading restoreReading(JSONObject o){
        if(o==null||!o.has("elapsed")||!o.has("wall"))return null;
        Core.Reading r=new Core.Reading();r.elapsed=o.optLong("elapsed",-1);r.wall=o.optLong("wall",-1);
        if(r.elapsed<0||r.wall<0)return null;
        r.rm=o.optDouble("rm",Double.NaN);r.pct=o.optDouble("pct",Double.NaN);r.current=o.optDouble("current",Double.NaN);
        r.vmin=o.optDouble("vmin",Double.NaN);r.vmax=o.optDouble("vmax",Double.NaN);
        r.cell1=o.optDouble("cell1",Double.NaN);r.cell2=o.optDouble("cell2",Double.NaN);r.cellCount=o.optInt("cellCount",0);r.online=o.optBoolean("online",false);r.onlineKnown=o.optBoolean("onlineKnown",true);return r;
    }
    void checkpoint(Core.Reading r)throws JSONException {
        if(session<0||state!=0)return;
        JSONObject progress=new JSONObject().put("session",session).put("boot",boot).put("previous",checkpointReading(r))
            .put("fastPrevious",checkpointReading(fastStatsPrevious)).put("fastNext",fastStatsNext)
            .put("credit",depletion.checkpoint()).put("screenOffAt",fastScreenOffAt).put("expectedMs",interval());
        db.execSQL("INSERT OR REPLACE INTO runtime(key,value) VALUES(?,?)",new Object[]{gameContext?"game_progress_v1":"session_progress_v1",progress.toString()});
    }
    void begin(Core.Reading r)throws Exception {
        requirePower(r);
        previous=null;fullGate.reset();depletion.reset();timelineReady=false;recoveryBoundary=false;
        fastStatsPrevious=state==0&&gameContext?r:null;
        fastStatsNext=fastStatsPrevious!=null?r.elapsed+60000:Long.MAX_VALUE;
        if(!gameContext)db.execSQL("DELETE FROM runtime WHERE key='full_tail_boot'");
        startElapsed=r.elapsed;startWall=r.wall;clockAdjusted=false;
        try(SQLiteStatement insert=db.compileStatement("INSERT INTO sessions(kind,start_wall,start_elapsed,boot,end_elapsed,pct_start,pct_end,rm_start,rm_end,cell_count,fast_seconds,performance_seconds,stats_basis,active,reason) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,1,'recording')")) {
            Object[] args={state==1?"charge":"discharge",r.wall,r.elapsed,boot,r.elapsed,nullable(r.pct),nullable(r.pct),nullable(r.rm),nullable(r.rm),r.cellCount,state==0&&gameContext?sampling.fastMs/1000:0,state==0&&gameContext?settings.optDouble("performanceSeconds",2):0,state==0&&gameContext?"rm_minute":"rm"};
            for(int i=0;i<args.length;i++) {
                Object v=args[i];int n=i+1;
                if(v==null)insert.bindNull(n);
                else if(v instanceof Double)insert.bindDouble(n,(Double)v);
                else if(v instanceof Number)insert.bindLong(n,((Number)v).longValue());
                else insert.bindString(n,v.toString());
            }
            session=insert.executeInsert();
        }
        settingsVersion++;
        if(state==0)deferred.register(session,r,startElapsed,false);
        append(r);
    }
    void finish(String reason) {
        Integer cycles=null;JSONObject batterySnapshot=null;
        boolean tail=session>=0&&"full_tail_confirmed".equals(reason);
        if(tail){
            try{batterySnapshot=BatteryHistorySnapshot.capture(battery.batteryInfoSnapshot());cycles=integer(batterySnapshot.opt("cycles").toString());}
            catch(Exception error){FallbackDiagnostics.record("尾充电池信息快照",error);cycles=battery.readCycleCount();}
        }
        db.beginTransaction();
        try {
            if(!powerGap&&fastStatsPrevious!=null&&previous!=null&&session>=0)try{sampleFastStats(previous,true);}
            catch(Exception e){System.err.println("Final minute statistics skipped: "+e.getClass().getSimpleName());}
            if("full_tail_confirmed".equals(reason))
                db.execSQL("INSERT OR REPLACE INTO runtime(key,value) VALUES('full_tail_boot',?)",new Object[]{boot});
            if(session>=0) {
                if(tail)db.execSQL("INSERT OR IGNORE INTO battery_history(source_session,completed_wall,cycles,snapshot) VALUES(?,?,?,?)",new Object[]{session,previous!=null&&previous.wall>0?previous.wall:System.currentTimeMillis(),cycles,batterySnapshot==null?null:batterySnapshot.toString()});
                db.execSQL("UPDATE sessions SET active=0,reason=?,tail_cycles=? WHERE id=?",new Object[]{reason,cycles,session});
                db.delete("sessions","id=? AND "+smallRecordWhere(settings),new String[]{""+session});
            }
            db.setTransactionSuccessful();
        } finally {db.endTransaction();}
        if(session>=0)settingsVersion++;
        deferred.requestCapture();store.rangeId=-1;store.rangeValues=null;
        session=-1;previous=null;fullGate.reset();depletion.reset();timelineReady=false;recoveryBoundary=false;
        fastStatsPrevious=null;fastStatsNext=Long.MAX_VALUE;
    }
    void append(Core.Reading r)throws Exception {
        latest=r;if(!r.onlineKnown||session<0)return;
        long dt=previous==null?0:r.elapsed-previous.elapsed;
        if(previous!=null&&dt<=0)return;
        int quality=0;long missed=0;
        boolean resumed=recoveryBoundary||powerGap;
        boolean newClock=!clockAdjusted&&(Math.abs(r.wall-(startWall+r.elapsed-startElapsed))>2000);
        if(resumed)quality|=9; // Bit 8 labels a resumed boundary; short discharge intervals are still ignored.
        if(previous!=null&&dt>sampling.maxGap()){quality|=1;missed=Math.max(0,dt/sampling.expected-1);}
        if(!r.valid() || !r.error.isEmpty())quality|=4;
        if(r.cellCount==0)quality|=Core.UNCLASSIFIED_CELLS;
        boolean fast=state==0&&gameContext;
        boolean powerBoundary=fast&&previous!=null&&(!previous.onlineKnown||previous.online!=r.online);
        double loss=0;
        if(state==0&&previous!=null&&!fast) {
            if(powerGap){depletion.reset();loss=Double.NaN;}else loss=depletion.consume(previous.rm,r.rm,dt,sampling.maxGap());
            if(!Core.finite(loss))quality|=2;
        }
        double energy=state==0&&previous!=null&&(quality&3)==0
            ?Core.dischargeEnergyMwh(previous.voltage(),r.voltage(),loss):Double.NaN;
        double recordedPower=state==1?r.power():fast?-r.power():Core.averagePower(energy,dt);
        if(state==0&&!fast){
            MemoryStats memory=MemoryStats.parse(read("/proc/meminfo"));
            r.memoryUsedGB=memory==null?Double.NaN:memory.usedGB();r.memoryTotalGB=memory==null?Double.NaN:memory.totalGB();
        }
        db.beginTransaction();
        try {
            if(newClock)db.execSQL("UPDATE sessions SET clock_adjusted=1 WHERE id=?",new Object[]{session});
            db.execSQL("INSERT OR IGNORE INTO samples(session,x,rm,pct,current,voltage,power,quality,interval_ms,cell1,cell2,temp,online,wall,memory_used,memory_total) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                new Object[]{session,r.elapsed-startElapsed,nullable(r.rm),nullable(r.pct),state==1||fast?nullable(r.chargingCurrent()):null,nullable(r.displayVoltage()),nullable(recordedPower),quality,state==0&&previous!=null?dt:null,nullable(r.cell1),nullable(r.cell2),nullable(r.temp),r.online?1:0,r.wall,nullable(state==0&&!fast?r.memoryUsedGB:Double.NaN),nullable(state==0&&!fast?r.memoryTotalGB:Double.NaN)});
            if(state==1&&r.online&&r.vendorLogRead&&!fullGate.vendorLogSeen)
                db.execSQL("INSERT OR REPLACE INTO runtime(key,value) VALUES('charge_log_session',?)",new Object[]{""+session});
            if(powerBoundary){
                // Settle only the old side of the boundary. Charging RM gains
                // must neither become app drain nor suppress later discharge.
                Core.Reading ending=restoreReading(checkpointReading(r));
                ending.online=previous.online;ending.onlineKnown=previous.onlineKnown;
                sampleFastStats(ending,true,resumed);
                depletion.reset();timelineReady=false;fastStatsPrevious=r;fastStatsNext=r.elapsed+60000;
            }else if(fast)sampleFastStats(r,resumed,resumed);
            else if(state==0&&previous!=null&&!deferred.owns(session))attribute(previous,r,loss,(quality&3)!=0,energy);
            if(state==0&&!fast&&deferred.owns(session))deferred.raw(session,startElapsed,r,newClock?quality|16:quality,sampling.maxGap());
            db.execSQL("UPDATE sessions SET end_elapsed=?,pct_end=?,rm_end=?,missed=missed+?,anomalies=anomalies+? WHERE id=?",
                new Object[]{r.elapsed,nullable(r.pct),nullable(r.rm),missed,(quality&6)!=0?1:0,session});
            // Minute checkpoints suffice for fast accounting. Avoid adding a WAL
            // page write to every second of charging or game graph sampling.
            if(!fast||previous==null||resumed||powerBoundary)checkpoint(r);
            db.setTransactionSuccessful();
        } finally {db.endTransaction();}
        if(state==1)fullGate.observe(r);
        if(newClock)clockAdjusted=true;
        store.updateRanges(session,startElapsed,state,gameContext,previous,r,recordedPower);
        previous=r;recoveryBoundary=false;powerGap=false;sampling.expected=interval();
        if(fast)samplePerformance(r.elapsed);
    }
    void samplePerformance(long now){
        if(!gameContext||!fastEnabled||session<0||stopping)return;
        if(performanceSession!=session){performanceSession=session;performanceCadence.reset();}
        if(!performanceCadence.due(now))return;
        performanceCadence.sampled(now);
        performance.sample(session,startElapsed,sampling.interactive,performanceCadence.interval);
    }
    void sampleFastStats(Core.Reading r,boolean flush)throws Exception{
        sampleFastStats(r,flush,false);
    }
    void sampleFastStats(Core.Reading r,boolean flush,boolean resumed)throws Exception{
        Core.Reading a=fastStatsPrevious;
        if(!r.onlineKnown)return;
        if(a==null||session<0||r.elapsed<=a.elapsed||!flush&&r.elapsed<fastStatsNext)return;
        long dt=r.elapsed-a.elapsed;boolean gap=resumed||dt>90000;
        boolean discharge=Core.gameDischargeInterval(a,r);
        boolean delayed=deferred.owns(session);
        double loss=!delayed&&discharge&&!powerGap?depletion.consume(a.rm,r.rm,dt,90000):Double.NaN;
        if(powerGap||!discharge)depletion.reset();
        double energy=delayed||gap?Double.NaN:Core.dischargeEnergyMwh(a.voltage(),r.voltage(),loss);
        boolean ownTransaction=!db.inTransaction();if(ownTransaction)db.beginTransaction();
        try{
            if(delayed)deferred.raw(session,startElapsed,r,gap?9:0,90000,discharge);
            else if(discharge)attribute(a,r,loss,gap,energy);else timelineReady=false;
            // Preserve the same minute RM result already assigned to apps;
            // hourly usage charts must not use instantaneous power samples.
            db.execSQL("INSERT OR IGNORE INTO game_rm_intervals(session,x,start_x,energy_mwh) VALUES(?,?,?,?)",
                new Object[]{session,r.elapsed-startElapsed,a.elapsed-startElapsed,delayed?null:nullable(energy)});
            db.execSQL("UPDATE sessions SET end_elapsed=?,pct_end=?,rm_end=? WHERE id=? AND end_elapsed<=?",
                new Object[]{r.elapsed,nullable(r.pct),nullable(r.rm),session,r.elapsed});
            fastStatsPrevious=r;
            while(fastStatsNext<=r.elapsed)fastStatsNext+=60000;
            checkpoint(previous);
            if(ownTransaction)db.setTransactionSuccessful();
        }finally{if(ownTransaction)db.endTransaction();}
    }
    void attribute(Core.Reading a,Core.Reading b,double loss,boolean gap,double energy)throws Exception {
        long dt=b.elapsed-a.elapsed;Map<String,Long> split;ScreenUsage.Builder screenSpans=null;
        // Record charts use an elapsed-time axis. Keep captured usage on that
        // same axis after a wall-clock adjustment, without splitting the record.
        if(!clockAdjusted&&(Math.abs(a.wall-(startWall+a.elapsed-startElapsed))>2000||Math.abs(b.wall-(startWall+b.elapsed-startElapsed))>2000)){
            db.execSQL("UPDATE sessions SET clock_adjusted=1 WHERE id=? AND clock_adjusted=0",new Object[]{session});
            clockAdjusted=true;
        }
        if(gap||Math.abs((b.wall-a.wall)-dt)>2000) {
            split=Collections.singletonMap(Core.OTHER,dt);timelineReady=false;
        } else {
            try {
                usageCheckedAt=System.currentTimeMillis();
                // UsageStats context is user 0. Avoid attributing another user's drain to stale user 0 apps.
                int user=(Integer)Class.forName("android.app.ActivityManager").getMethod("getCurrentUser").invoke(null);
                if(user!=0)throw new IOException("当前用户不是主用户；此区间计入其他");
                if(!timelineReady) {
                    boolean on=screenState.known&&sampling.interactive;
                    if(screenState.known&&screenState.changedAt>a.elapsed&&screenState.changedAt<=b.elapsed)on=!on;
                    List<Core.Event> history=queryUsage(ctx,a.wall-86400000,a.wall);
                    if(!screenState.known&&history.stream().noneMatch(e->e.type==15||e.type==16||e.type==26||e.type==27))throw new IOException("缺少可确认的屏幕状态");
                    timeline.seed(on,false);
                    for(Core.Event e:history)timeline.apply(e);
                    timelineReady=true;
                }
                List<Core.Event> events=queryUsage(ctx,a.wall,b.wall);ScreenUsage.Builder captured=new ScreenUsage.Builder();
                split=timeline.split(a.wall,b.wall,events,captured);
                screenSpans=captured.onRecordClock(a.wall,b.wall,startWall+a.elapsed-startElapsed,startWall+b.elapsed-startElapsed);
                usageError="";usageErrorAt=0;
            } catch(Throwable e) {
                Throwable cause=FrameworkWatch.cause(e);String diagnostic=cause.toString();
                if(!diagnostic.equals(usageError)){System.err.println("Application attribution unavailable: "+diagnostic);cause.printStackTrace();}
                FallbackDiagnostics.record("应用使用事件读取",cause);usageError=diagnostic;usageErrorAt=System.currentTimeMillis();if(framework!=null)framework.observe(e);
                timelineReady=false;split=Collections.singletonMap(Core.OTHER,dt);
            }
        }
        if(screenSpans!=null){deferred.saveScreenSpans(session,screenSpans.spans);deferred.saveScreenApps(session,screenSpans.apps);}
        deferred.addUsage(session,split,dt,loss,gap?Double.NaN:energy);
    }
    volatile DailyUsage dailyUsage;
    final ExecutorService usageWorker=Executors.newSingleThreadExecutor(work->{
        Thread thread=new Thread(work,"aco-usage-settlement");thread.setDaemon(true);return thread;
    });
    final Set<Long> usageQueued=new HashSet<>();
    final LinkedHashMap<Long,Long> usageRequested=new LinkedHashMap<>();
    static boolean usageRead(String action){
        return Arrays.asList("screen_usage","screen_off_spans","hour_apps","hour_energy","interval_apps").contains(action);
    }
    JSONObject readUsage(JSONObject request)throws Exception{
        JSONArray args=request.optJSONArray("args");
        long id=args==null?-1:args.optLong(0,-1);
        synchronized(mutex){requestUsage(id);}
        for(int attempt=0;attempt<2;attempt++){
            long revision=deferred.appRevision(id);
            JSONObject answer=dispatch(request);
            if(revision==deferred.appRevision(id))return answer;
        }
        throw new IOException("应用统计正在更新，请稍后重试。");
    }
    void requestUsage(long id){
        if(id<1||stopping||usageQueued.contains(id)||usageQueued.size()>=8)return;
        long now=SystemClock.elapsedRealtime(),last=usageRequested.getOrDefault(id,Long.MIN_VALUE/2);
        if(now-last<2000)return;
        boolean pending=deferred.pendingSamples(id)||deferred.readyTail(id,System.currentTimeMillis());
        if(!pending&&(now-last<60000||!deferred.hasLegacyIntervals(id)))return;
        usageRequested.remove(id);usageRequested.put(id,now);
        if(usageRequested.size()>64)usageRequested.remove(usageRequested.keySet().iterator().next());
        usageQueued.add(id);
        try{usageWorker.execute(()->settleViewedUsage(id));}
        catch(RejectedExecutionException shuttingDown){usageQueued.remove(id);}
    }
    JSONObject usageSnapshot(long id)throws Exception{
        JSONObject snapshot=usageSnapshots.get(id);
        if(snapshot==null||snapshot.getLong("statsVersion")!=statsVersion){
            snapshot=new JSONObject().put("statsVersion",statsVersion).put("apps",deferred.apps(id)).put("drainSummary",deferred.drainSummary(id));
            usageSnapshots.remove(id);usageSnapshots.put(id,snapshot);
            if(usageSnapshots.size()>4)usageSnapshots.remove(usageSnapshots.keySet().iterator().next());
        }
        return snapshot;
    }
    void attachUsage(JSONObject packet,long id)throws Exception{
        JSONObject snapshot=usageSnapshot(id);
        packet.put("apps",snapshot.getJSONArray("apps")).put("drainSummary",snapshot.getJSONArray("drainSummary"))
            .put("usagePending",deferred.pending(id)||usageQueued.contains(id));
    }
    static void omitRepeatedUsage(JSONObject packet,Map<Long,Long> sent)throws JSONException{
        if(packet==null||!packet.has("apps"))return;
        JSONObject row=packet.optJSONObject("record");if(row==null)return;
        long id=row.getLong("id"),revision=packet.getLong("statsVersion");
        if(Objects.equals(sent.put(id,revision),revision)){packet.remove("apps");packet.remove("drainSummary");}
        if(sent.size()>4)sent.remove(sent.keySet().iterator().next());
    }
    void settleViewedUsage(long id){
        try{
            // One system event read is shared with periodic capture and other views.
            // Wait for it outside the sampler mutex, then plan attribution from SQLite.
            for(int pass=0;pass<2;pass++){
                FutureTask<List<Core.Event>> task;boolean includesRecord;
                synchronized(mutex){
                    if(stopping)return;
                    deferred.captureAsync(true,id);task=deferred.eventRead;
                    if(task==null)break;
                    includesRecord=false;
                    for(DeferredStats.Pending item:deferred.eventBatch)if(item.id==id){includesRecord=true;break;}
                }
                try{task.get(8,TimeUnit.SECONDS);}
                catch(TimeoutException slow){FallbackDiagnostics.record("应用事件结算","查询尚未返回，将在后续使用其结果");break;}
                catch(ExecutionException failed){/* Capture harvest reports the underlying cause. */}
                synchronized(mutex){if(stopping)return;deferred.captureAsync(false,0);}
                if(includesRecord)break;
            }
            DeferredStats.Settlement result=deferred.plan(id);
            synchronized(mutex){if(stopping)return;deferred.commit(result);}
            if(!stopping)deferred.legacyIntervalApps(id);
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
        catch(Exception error){if(!stopping)FallbackDiagnostics.record("应用耗电结算",error);}
        finally{synchronized(mutex){usageQueued.remove(id);}}
    }
    final java.util.concurrent.Semaphore ipcSlots=new java.util.concurrent.Semaphore(8);
    void serve() {
        try(LocalServerSocket server=new LocalServerSocket(SOCKET)) {
            while(true) {
                LocalSocket socket=server.accept();
                if(!ipcSlots.tryAcquire()){socket.close();continue;}
                Thread client=new Thread(()->{
                    try(LocalSocket connection=socket) {
                        connection.setSoTimeout(10000);
                        if(connection.getPeerCredentials().getUid()!=0)return;
                        BufferedReader in=new BufferedReader(new InputStreamReader(connection.getInputStream(),StandardCharsets.UTF_8));
                        JSONObject req=new JSONObject(boundedLine(in,8192));
                        if("watch".equals(req.optString("action"))){watchFrontend(connection,req.optJSONArray("args"));return;}
                        JSONObject answer;
                        try {
                            if("daily_usage".equals(req.optString("action"))){
                                DailyUsage history=dailyUsage;
                                if(history==null){synchronized(this){if(dailyUsage==null)dailyUsage=new DailyUsage(ctx);history=dailyUsage;}}
                                JSONArray args=req.optJSONArray("args");if(args==null||args.length()!=3)throw new IOException("使用情况参数无效");
                                answer=history.read(args.getLong(0),args.getLong(1),args.getString(2));
                            }else if(usageRead(req.optString("action")))answer=readUsage(req);
                            else synchronized(mutex){answer=dispatch(req);}
                        }
                        catch(Throwable e){answer=new JSONObject().put("ok",false).put("error",FriendlyError.message(e));}
                        connection.getOutputStream().write((answer.toString()+"\n").getBytes(StandardCharsets.UTF_8));
                    } catch(Exception e){System.err.println("IPC: "+e);}
                    finally {ipcSlots.release();}
                },"curve-frontend");
                client.setDaemon(true);client.start();
            }
        } catch(Exception e){e.printStackTrace();System.exit(1);}
    }
    void watchFrontend(LocalSocket socket,JSONArray args)throws Exception {
        if(args!=null&&args.optInt(2,0)==1){watchBatteryInfo(socket,args.optInt(3,1)==1);return;}
        boolean showFrequencies=args!=null&&args.optInt(2,0)==2;
        boolean summaryOnly=args!=null&&args.optInt(2,0)==3;
        boolean includeList=args==null||args.optInt(5,1)==1;
        long id=args==null?0:Math.max(0,args.optLong(0,0)),after=args==null?0:Math.max(0,args.optLong(1,0));
        long began=SystemClock.elapsedRealtime(),lastList=0,lastSettings=-1,normalId=-1,normalAfter=0;
        Map<Long,Long> sentUsage=new LinkedHashMap<>();
        // A bounded lease also closes an orphaned stream if a renderer dies before receiving its PID.
        while(SystemClock.elapsedRealtime()-began<300000) {
            long started=SystemClock.elapsedRealtime();JSONObject packet;boolean more;
            JSONObject frequencies=null;
            if(showFrequencies)try{frequencies=performance.frequencies();}catch(Exception ignored){}
            synchronized(mutex) {
                long preferred=preferredSession();
                JSONObject previous=null;boolean changed=id!=preferred;
                if(!summaryOnly&&changed&&id>0)previous=live(new JSONArray().put(id).put(after).put(0).put(0).put(preferred+1)).put("recordId",id);
                if(changed){id=preferred;long start=id==gameTrack.session?gameTrack.startElapsed:startElapsed;after=id>0?Math.max(0,latest.elapsed-start-1):0;}
                packet=live(new JSONArray().put(summaryOnly?0:Math.max(0,id)).put(after).put(0).put(0).put(preferred+1));
                if(previous!=null)packet.put("previous",previous);
                if(normalId>0&&normalId!=session){
                    packet.put("parallelPrevious",live(new JSONArray().put(normalId).put(normalAfter).put(0).put(0).put(preferred+1)));
                    normalId=-1;normalAfter=0;
                }
                if(!summaryOnly&&gameTrack.session>0&&session>0){
                    if(normalId!=session){normalId=session;normalAfter=Math.max(0,latest.elapsed-startElapsed-1);}
                    JSONObject parallel=live(new JSONArray().put(session).put(normalAfter).put(0).put(0).put(preferred+1));
                    JSONArray normalPoints=parallel.optJSONArray("points");
                    if(normalPoints!=null&&normalPoints.length()>0)normalAfter=normalPoints.getJSONArray(normalPoints.length()-1).getLong(0);
                    packet.put("parallel",parallel);
                }else{normalId=-1;normalAfter=0;}

                if(includeList&&(lastList==0||changed||lastSettings!=settingsVersion||started-lastList>=15000)) {
                    int small=args==null?0:args.optInt(4,0);
                    JSONObject page=dispatch(new JSONObject().put("action","list").put("args",new JSONArray().put(small).put(0)));
                    packet.put("records",page.getJSONArray("records")).put("total",page.getInt("total")).put("totalRecords",page.getInt("totalRecords")).put("listSmall",small);
                    packet.put("listVersion",settingsVersion);
                    lastList=started;lastSettings=settingsVersion;
                }
                JSONArray points=packet.optJSONArray("points");
                if(points!=null&&points.length()>0)after=points.getJSONArray(points.length()-1).getLong(0);
                more=!packet.isNull("nextAfter")&&packet.has("nextAfter");
            }
            omitRepeatedUsage(packet,sentUsage);
            for(String key:new String[]{"previous","parallel","parallelPrevious"})omitRepeatedUsage(packet.optJSONObject(key),sentUsage);
            if(showFrequencies)packet.put("frequencies",frequencies==null?JSONObject.NULL:frequencies);
            socket.getOutputStream().write((packet.toString()+"\n").getBytes(StandardCharsets.UTF_8));
            if(!more)Thread.sleep(Math.max(100,1000-(SystemClock.elapsedRealtime()-started)));
        }
    }
    void watchBatteryInfo(LocalSocket socket,boolean snapshot)throws Exception {
        long began=SystemClock.elapsedRealtime(),slowAt=-1;double rm=Double.NaN,temp=Double.NaN;String rmSource="";
        JSONObject metadata=null;if(snapshot)synchronized(mutex){metadata=battery.batteryInfoSnapshot();}
        while(SystemClock.elapsedRealtime()-began<300000){
            long started=SystemClock.elapsedRealtime();JSONObject packet;
            synchronized(mutex){
                resolveCellProfile();boolean slow=slowAt<0||started-slowAt>=10000;
                Core.Reading reading=readGauge(ctx,cellCount,slow);
                if(slow){readRemainingCapacity(ctx,reading);rm=reading.rm;rmSource=reading.rmSource;temp=reading.temp;slowAt=started;}
                reading.rm=rm;reading.rmSource=rmSource;reading.temp=temp;
                JSONObject status=new JSONObject().put("ok",true).put("state",state).put("session",preferredSession()).put("cellCount",cellCount)
                    .put("interactive",sampling.interactive).put("settings",settings).put("reading",readingJson(reading).put("slowElapsed",slowAt));
                packet=new JSONObject().put("ok",true).put("status",status);
                if(metadata!=null){packet.put("batteryInfo",metadata);metadata=null;}
            }
            socket.getOutputStream().write((packet.toString()+"\n").getBytes(StandardCharsets.UTF_8));
            Thread.sleep(Math.max(100,1000-(SystemClock.elapsedRealtime()-started)));
        }
    }
    static String boundedLine(Reader in,int limit)throws IOException {
        StringBuilder b=new StringBuilder();int ch;
        while((ch=in.read())!=-1&&ch!='\n'){if(b.length()>=limit)throw new IOException("request too large");b.append((char)ch);}
        return ch==-1&&b.length()==0?null:b.toString();
    }
    static void client(String[] args) {
        try(LocalSocket socket=new LocalSocket()) {
            JSONObject req=new JSONObject().put("action",args.length>0?args[0]:"status");
            JSONArray p=new JSONArray();for(int i=1;i<args.length;i++)p.put(args[i]);req.put("args",p);
            socket.connect(new LocalSocketAddress(SOCKET,LocalSocketAddress.Namespace.ABSTRACT));socket.setSoTimeout(OperationTimeout.MAX_MS);
            socket.getOutputStream().write((req.toString()+"\n").getBytes(StandardCharsets.UTF_8));
            System.out.println(boundedLine(new InputStreamReader(socket.getInputStream(),StandardCharsets.UTF_8),8_000_000));
        } catch(Throwable e) {
            System.out.println("{\"ok\":false,\"error\":\"记录服务未运行，请确认模块已启用并重启手机\"}");
        }
    }
    JSONObject dispatch(JSONObject req)throws Exception {
        String action=req.getString("action");JSONArray p=req.optJSONArray("args");if(p==null)p=new JSONArray();
        JSONObject o=new JSONObject().put("ok",true);
        if(action.equals("set_power_display")){
            if(stopping)throw new IOException("后台正在结束，请稍后重试。");
            if(p.length()!=1||(p.getInt(0)!=0&&p.getInt(0)!=1))throw new IOException("实时功耗显示参数无效");
            boolean enabled=p.getInt(0)==1,old=powerIndicator.state.enabled;
            powerIndicator.enabled(enabled,enabled?powerDisplayReading():null);
            try{db.execSQL("INSERT OR REPLACE INTO runtime(key,value) VALUES('power_indicator',?)",new Object[]{enabled?"1":"0"});}
            catch(RuntimeException failure){try{powerIndicator.enabled(old,old?powerDisplayReading():null);}catch(Exception rollback){failure.addSuppressed(rollback);}throw failure;}
            powerIndicator.state.sampled(SystemClock.elapsedRealtime(),settings.optLong("fastSeconds",10));
            settingsVersion++;wakeCollector();
            return o.put("powerDisplayEnabled",enabled).put("powerDisplayError",powerIndicator.error()).put("settings",settings);
        }
        if(action.equals("set_recording"))return setRecording(p.optInt(0,0)==1);
        if(action.equals("set_fast")){if(p.length()!=1||(p.getInt(0)!=0&&p.getInt(0)!=1))throw new IOException("游戏模式参数无效");return setFast(p.getInt(0)==1,p.getInt(0)==1?"fast_started":"fast_stopped");}
        if(action.equals("shutdown")){requestStop("app_uninstalled".equals(p.optString(0))?"app_uninstalled":"service_updated");return o;}
        if(action.equals("settings"))return o.put("settings",settings).put("recordingEnabled",recordingEnabled).put("recordingProblem",recordingProblem).put("fastEnabled",fastEnabled).put("powerDisplayEnabled",powerIndicator.state.enabled).put("powerDisplayError",powerIndicator.error());
        if(action.equals("default_settings"))return o.put("settings",defaultSettings());
        if(action.equals("set_settings"))return saveSettings(p);
        if(action.equals("cache_records"))return store.cacheRecords(p);
        if(action.equals("live"))return live(p);
        if(action.equals("status")) {
            return status();
        }
        if(action.equals("diagnostics"))return o.put("native",waitDiagnostics()).put("entries",FallbackDiagnostics.snapshot());
        if(action.equals("error_logs"))return new ErrorLogStore(new File(data,"error-logs")).list(p.optInt(0,0),40).put("ok",true);
        if(action.equals("clear_error_logs")){new ErrorLogStore(new File(data,"error-logs")).clear();return o;}
        if(action.equals("error_log"))return o.put("report",new ErrorLogStore(new File(data,"error-logs")).read(p.optString(0)));
        if(action.equals("battery_history_record")){
            if(p.length()!=0)throw new IOException("手动记录参数无效");
            return o.put("item",store.recordBatteryHistory(battery.batteryInfoSnapshot(),System.currentTimeMillis()));
        }
        if(Arrays.asList("battery_history","battery_history_detail","battery_history_delete","battery_history_trend").contains(action))return store.history(action,p);
        if(action.equals("list"))return store.list(p,settings.optInt("hidePct",30),settingsVersion);
        long id=p.optLong(0,-1);if(id<1)throw new IOException("无效记录 ID");
        if(action.equals("settle_usage")){
            requestUsage(id);
            attachUsage(o,id);
            return o.put("statsVersion",statsVersion).put("statsEpoch",statsEpoch).put("appRevision",deferred.appRevision(id)).put("settling",usageQueued.contains(id));
        }
        if(action.equals("hour_apps"))return deferred.hourApps(id,p.optLong(1,0),p.optLong(2,0));
        if(action.equals("interval_apps"))return deferred.intervalApps(id,p.optLong(1,0));
        if(action.equals("hour_energy"))return deferred.hourlyEnergy(id,p.optLong(1,0),p.optLong(2,0));
        if(action.equals("screen_usage"))return deferred.screenUsage(id,p.optLong(1,0),p.optLong(2,0),p.optLong(3,Long.MAX_VALUE),p.optInt(4,0));
        if(action.equals("screen_off_spans"))return deferred.screenOffSpans(id);
        if(action.equals("performance"))return store.performancePoints(id,p.optLong(1,0),p.optInt(2,8000));
        if(action.equals("export_points")){
            JSONArray records=store.rows("SELECT * FROM sessions WHERE id=?",new String[]{""+id});
            if(records.length()==0)throw new IOException("记录不存在或已删除");
            JSONObject record=records.getJSONObject(0);long end=record.getLong("end_elapsed")-record.getLong("start_elapsed");
            if(p.optLong(2,0)>0)end=Math.min(end,p.getLong(2)-1);
            store.rawPoints(o,id,0,end,p.optLong(1,0)-1);return o.put("record",record).put("end",end);
        }
        if(action.equals("delete")) {
            boolean current=activeSession(id),gameCurrent=id==gameTrack.session;Core.Reading r=current&&!gameCurrent?hardwareReading(header):null;
            db.beginTransaction();try {
                store.deleteSession(id);usageSnapshots.remove(id);
                if(gameCurrent){
                    withGame(()->{
                        session=-1;previous=null;fastStatsPrevious=null;fastStatsNext=Long.MAX_VALUE;
                        fullGate.reset();depletion.reset();timelineReady=false;recoveryBoundary=false;
                    });
                    rememberFast(false);store.rangeId=-1;store.rangeValues=null;
                }else if(current){
                    session=-1;previous=null;fullGate.reset();depletion.reset();timelineReady=false;store.rangeId=-1;store.rangeValues=null;
                    candidate=null;candidateAt=-1;latest=r;powerReady=r.onlineKnown;
                    if(r.onlineKnown){
                        stableOnline=r.online;state=r.online?1:0;
                    }else powerUnavailable(r.elapsed);
                    if(r.onlineKnown&&recordingEnabled&&!stopping){begin(r);sampling.sampled(state,r.elapsed);}
                }
                db.setTransactionSuccessful();
            }finally{db.endTransaction();}
            if(current)wakeCollector();
            settingsVersion++;
            return o.put("listVersion",settingsVersion).put("session",preferredSession());
        }

        if(action.equals("detail_sample")){
            long from=Math.max(0,p.optLong(1,0)),to=Math.max(from,p.optLong(2,0));
            JSONArray sample=store.nearestSample(id,from,to,p.optLong(3,from));
            return o.put("sample",sample==null?JSONObject.NULL:sample);
        }
        boolean preview=action.equals("detail_preview");
        if(!preview&&!action.equals("detail"))throw new IOException("未知操作");
        JSONArray records=store.rows("SELECT *,ABS(pct_end-pct_start) AS delta_pct FROM sessions WHERE id=?",new String[]{""+id});
        if(records.length()==0)throw new IOException("记录不存在或已删除");
        JSONObject record=records.getJSONObject(0);
        o.put("record",record).put("statsVersion",statsVersion).put("statsEpoch",statsEpoch);
        long from=Math.max(0,p.optLong(1,0)),to=Math.max(from+1,p.optLong(2,Long.MAX_VALUE));
        to=Math.min(to,records.getJSONObject(0).getLong("end_elapsed")-records.getJSONObject(0).getLong("start_elapsed"));
        int budget=Math.max(240,Math.min(6000,p.optInt(3,1200)));
        boolean charge=records.getJSONObject(0).getString("kind").equals("charge");
        boolean raw=charge||records.getJSONObject(0).optInt("fast_seconds",0)>0;
        if(!charge){
            attachUsage(o,id);
            if(!o.optBoolean("usagePending")&&!usageRequested.containsKey(id)&&deferred.hasLegacyIntervals(id))o.put("usagePending",true);
        }
        o.put("raw",raw);
        store.signalRanges(o,id,from,to,raw);
        boolean approximate=raw&&preview&&o.getLong("windowCount")>2400;
        if(raw&&!approximate)store.rawPoints(o,id,from,to,p.optLong(4,-1));
        else o.put("points",store.points(id,from,to,budget,raw));
        if(approximate)o.put("preview",true);
        o.put("columns",new JSONArray(new String[]{"x","rm","pct","current","voltage","power","quality","interval_ms","cell1","cell2","temp","energy","online","wall","ram","ramTotal"}));
        return o;
    }
    JSONObject status()throws Exception {
        JSONObject o=new JSONObject().put("ok",true).put("recorderReady",recorderReady&&!stopping).put("version","2.4").put("resumeProtocol",1).put("fastEnabled",fastEnabled)
            .put("androidSdk",Build.VERSION.SDK_INT).put("androidVersion",Build.VERSION.RELEASE).put("screenEventApi",screenEventApi);
        o.put("powerDisplayEnabled",powerIndicator.state.enabled).put("powerDisplayError",powerIndicator.error());
        o.put("retentionThresholdPct",settings.optInt("deletePct",5)).put("hideThresholdPct",settings.optInt("hidePct",30)).put("settings",settings).put("recordingEnabled",recordingEnabled).put("recordingProblem",recordingProblem);
        o.put("cutoffAvailable",cutoffAvailable()).put("recordingEnabled",recordingEnabled).put("state",state).put("session",preferredSession()).put("normalSession",session).put("gameSession",gameTrack.session).put("capabilities",capabilities).put("cellCount",cellCount).put("listVersion",settingsVersion);
        o.put("tailProgress",fullGate.progress(SystemClock.elapsedRealtime()));
        o.put("interactive",screenState.known?sampling.interactive:JSONObject.NULL).put("sampleIntervalMs",interval());
        o.put("gamePowerIntervalMs",Core.gamePowerInterval(stableOnline,settings.optLong("fastSeconds",10),settings.optLong("chargeSeconds",2)));
        boolean frameworkHealthy=framework.healthy();
        o.put("frameworkHealthy",frameworkHealthy).put("screenListener",screenListenerReady&&frameworkHealthy).put("screenError",screenError);
        o.put("screenFallback",!screenListenerReady).put("screenStateKnown",screenState.known).put("screenStateSource",screenState.source);
        o.put("usageCheckedAt",usageCheckedAt).put("usageErrorAt",usageErrorAt).put("usageActive",(recordingEnabled||fastEnabled)&&state==0);
        o.put("powerInputDiagnostics",powerInputDiagnostics);
        o.put("usageError",usageError).put("error",powerError.isEmpty()?runtimeError:powerError).put("readingRecoveryProblem",readingRecoveryProblem)
            .put("powerStateAvailable",powerError.isEmpty()&&(powerReady||!readingRecoveryProblem.isEmpty())).put("profile","OPLUS BCC19 / "+(cellCount>0?cellCount+"S":"detecting"));
        Core.Reading shown=latest;
        if(sampling.interactive) {
            if(uiReading==null||(latest!=null&&latest.elapsed>uiReading.elapsed))uiReading=latest;
            if(uiReading==null||SystemClock.elapsedRealtime()-uiReading.elapsed>=1000)uiReading=hardwareReading(header);
            shown=uiReading;
        }
        o.put("cellCount",cellCount);if(shown!=null)o.put("reading",readingJson(shown));return o;
    }
    JSONObject live(JSONArray p)throws Exception {
        JSONObject o=new JSONObject().put("ok",true).put("status",status()).put("statsEpoch",statsEpoch).put("statsVersion",statsVersion);
        boolean list=p.optInt(5,1)==1&&(p.optInt(2,0)==1||p.optLong(4,0)-1!=preferredSession());
        if(list) {
            JSONObject q=new JSONObject().put("action","list").put("args",new JSONArray().put(p.optInt(3,0)).put(0));
            JSONObject history=dispatch(q);o.put("records",history.getJSONArray("records")).put("total",history.getInt("total")).put("totalRecords",history.getInt("totalRecords"));
        }
        if(preferredSession()>0) {
            JSONArray active=store.rows("SELECT * FROM sessions WHERE id=?",new String[]{""+preferredSession()});
            if(active.length()>0)o.put("activeRecord",active.getJSONObject(0));
        }
        long id=p.optLong(0,0);if(id<1)return o;
        JSONArray records=store.rows("SELECT * FROM sessions WHERE id=?",new String[]{""+id});
        if(records.length()==0)return o.put("record",JSONObject.NULL);
        JSONObject record=records.getJSONObject(0);o.put("record",record);
        long end=record.getLong("end_elapsed")-record.getLong("start_elapsed"),after=p.optLong(1,0);
        store.rawPoints(o,id,0,end,after);
        boolean changed=o.getJSONArray("points").length()>0;
        if(store.rangeId==id&&store.rangeEnd==end&&store.rangeValues!=null)o.put("ranges",store.rangeValues).put("rawCount",store.rangeCount);
        else if(changed)store.signalRanges(o,id,0,end,record.getString("kind").equals("charge")||record.optInt("fast_seconds",0)>0);
        if(record.getString("kind").equals("discharge"))attachUsage(o,id);
        return o;
    }

}
