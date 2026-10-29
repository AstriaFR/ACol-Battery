package cn.astriafr.acobattery.daemon;

import android.app.KeyguardManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
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
import cn.astriafr.acobattery.common.DatabaseRebuild;
import java.io.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.lang.reflect.Method;
import java.util.*;

public final class Main {
    static final String SOCKET="cn.astriafr.aco_battery_app.v1";
    static final String BAT="/sys/class/oplus_chg/battery/";
    static final String PS="/sys/class/power_supply/";
    static final PowerInputs POWER_INPUTS=new PowerInputs(new File(PS),Main::read);
    JSONObject settings;
    volatile boolean recordingEnabled,stopping;
    String recordingProblem="";
    boolean fastEnabled;long fastScreenOffAt=-1;
    String stopReason="recording_stopped";
    long ownerNext=SystemClock.elapsedRealtime()+3600000;int ownerMissing;
    long settingsVersion=SystemClock.elapsedRealtime();
    int cellCount;
    long lastCellProbe=-60000;
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
    final String boot;
    final Core.FullGate fullGate=new Core.FullGate();
    final Core.Depletion depletion=new Core.Depletion();
    final Core.Timeline timeline=new Core.Timeline();
    final Core.Sampling sampling=new Core.Sampling();
    Core.Reading previous, latest, uiReading;
    Core.Reading fastStatsPrevious;
    long fastStatsNext=Long.MAX_VALUE;
    long statsVersion,statsCacheId=-1,statsCacheVersion=-1;
    JSONArray statsAppsCache,statsDrainCache;
    final LinkedHashMap<String,Long> screenUsageReads=new LinkedHashMap<>(16,.75f,true);
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
    String usageError="", runtimeError="";
    long usageCheckedAt,usageErrorAt;
    FrameworkWatch framework;
    String[] header;
    RandomAccessFile lockFile;
    FileLock lock;
    long rangeId=-1,rangeEnd=-1,rangeCount;
    JSONObject rangeValues;
    final PerformanceCollector performance;
    final PowerIndicator powerIndicator;
    long performanceSession=-1,performanceMetaSession=-1;
    final Core.Cadence performanceCadence=new Core.Cadence(3000);

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
            report.put("reading",readingJson(readHardware(ctx,null,Core.detectCellCount(read(BAT+"aging_ffc_data")))));
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
        app.moduleWatch=watchModule(args[1]);app.monitorScreen();
        StartupFailure.clear(Owner.BASE,attempt);

        System.err.println("AColBattery App 2.0 Android="+Build.VERSION.RELEASE+" SDK="+Build.VERSION.SDK_INT+" capabilities="+app.capabilities);
        Thread server=new Thread(app::serve,"curve-ipc");server.setDaemon(true);server.start();
        Thread collector=new Thread(()->{
            try {app.collect();System.exit("framework_restarted".equals(app.stopReason)?75:0);}
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
        DatabaseRebuild.checkPending(data);
        db=SafeDatabase.open(new File(data,"records.db"));
        db.execSQL("PRAGMA foreign_keys=ON");
        db.execSQL("CREATE TABLE IF NOT EXISTS sessions(id INTEGER PRIMARY KEY AUTOINCREMENT,kind TEXT NOT NULL,start_wall INTEGER,start_elapsed INTEGER,boot TEXT,end_elapsed INTEGER,pct_start REAL,pct_end REAL,rm_start REAL,rm_end REAL,active INTEGER NOT NULL,reason TEXT,missed INTEGER DEFAULT 0,anomalies INTEGER DEFAULT 0)");
        db.execSQL("CREATE TABLE IF NOT EXISTS samples(session INTEGER NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,x INTEGER NOT NULL,rm REAL,pct REAL,current REAL,voltage REAL,power REAL,quality INTEGER NOT NULL,interval_ms INTEGER,PRIMARY KEY(session,x)) WITHOUT ROWID");
        db.execSQL("CREATE TABLE IF NOT EXISTS apps(session INTEGER NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,pkg TEXT NOT NULL,ms INTEGER NOT NULL,mah REAL NOT NULL,energy_mwh REAL NOT NULL DEFAULT 0,energy_ms INTEGER NOT NULL DEFAULT 0,mah_ms INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(session,pkg)) WITHOUT ROWID");
        db.execSQL("CREATE TABLE IF NOT EXISTS screen_spans(session INTEGER NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,start_wall INTEGER NOT NULL,end_wall INTEGER NOT NULL,screen_on INTEGER NOT NULL,PRIMARY KEY(session,start_wall,end_wall)) WITHOUT ROWID");
        db.execSQL("CREATE TABLE IF NOT EXISTS screen_app_spans(session INTEGER NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,start_wall INTEGER NOT NULL,end_wall INTEGER NOT NULL,pkg TEXT NOT NULL,PRIMARY KEY(session,start_wall,end_wall)) WITHOUT ROWID");
        db.execSQL("CREATE TABLE IF NOT EXISTS game_rm_intervals(session INTEGER NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,x INTEGER NOT NULL,start_x INTEGER NOT NULL,energy_mwh REAL,PRIMARY KEY(session,x)) WITHOUT ROWID");
        db.execSQL("CREATE TABLE IF NOT EXISTS runtime(key TEXT PRIMARY KEY,value TEXT NOT NULL) WITHOUT ROWID");
        db.execSQL("CREATE TABLE IF NOT EXISTS performance_samples(session INTEGER NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,x INTEGER NOT NULL,data TEXT NOT NULL,PRIMARY KEY(session,x)) WITHOUT ROWID");
        db.execSQL("CREATE TABLE IF NOT EXISTS performance_profiles(session INTEGER PRIMARY KEY REFERENCES sessions(id) ON DELETE CASCADE,meta TEXT NOT NULL)");
        loadSettings();
        correctDischargePower();
        ensureSampleColumns();
        fastEnabled=recordingEnabled&&"1".equals(runtimeValue("fast_discharge"));configureSampling(false);
        loadCellProfile();
        powerIndicator=new PowerIndicator(ctx,"1".equals(runtimeValue("power_indicator")));
        if(reset)Files.delete(resetFlag.toPath());
        header=read(BAT+"battery_log_head").split(",",-1);
        performance=new PerformanceCollector((id,start,row,meta)->{
            synchronized(mutex){
                if(stopping||!recordingEnabled||!fastEnabled||state!=0||session!=id||startElapsed!=start)return;
                db.beginTransaction();try{
                    if(performanceMetaSession!=id)db.execSQL("INSERT OR IGNORE INTO performance_profiles(session,meta) VALUES(?,?)",new Object[]{id,meta.toString()});
                    db.execSQL("INSERT OR IGNORE INTO performance_samples(session,x,data) VALUES(?,?,?)",new Object[]{id,row.getLong(0),row.toString()});
                    db.setTransactionSuccessful();
                }finally{db.endTransaction();}
                performanceMetaSession=id;
            }
        });
    }

    static JSONObject decodeSettings(JSONArray p)throws Exception {
        if(p.length()!=8&&p.length()!=12&&p.length()!=13&&p.length()!=14)throw new IOException("设置项数量无效");
        long[] v={5,30,560,1,300,450,50,60,2,30,10,1,2,2};for(int i=0;i<p.length();i++)v[i]=p.getLong(i);
        // The old game interval controlled both charts. Keep it for both on upgrade.
        if(p.length()>=12&&p.length()<14)v[13]=v[10];
        if(v[0]<0||v[0]>40||v[1]<0||v[1]>80)throw new IOException("删除范围 0–40%，隐藏范围 0–80%，仅限整数");
        if(v[2]<1||v[2]>2000||v[3]<0||v[3]>1)throw new IOException("容量范围 0.01–20.00 Ah");
        if(v[4]<220||v[5]>480||v[4]>=v[5])throw new IOException("电压范围 2.20–4.80 V，下限须小于上限");
        if(v[6]<0||v[7]>80||v[6]>=v[7])throw new IOException("温度范围 −20–60℃，下限须小于上限");
        if(v[8]<1||v[8]>10||v[9]<1||v[9]>120||p.length()>9&&!p.get(9).toString().matches("[0-9]+")||v[10]<1||v[10]>60||v[11]<0||v[11]>1)throw new IOException("亮屏间隔 1–10 分钟，息屏间隔 1–120 分钟，游戏采样间隔 1–60 秒，仅限整数");
        if(v[12]<1||v[12]>10||p.length()>=13&&!p.get(12).toString().matches("[0-9]+"))throw new IOException("充电间隔 1–10 秒，仅限整数");
        if(v[13]<1||v[13]>60||p.length()==14&&!p.get(13).toString().matches("[0-9]+"))throw new IOException("性能采样间隔 1–60 秒，仅限整数");
        return new JSONObject().put("deletePct",v[0]).put("hidePct",v[1]).put("capacityAh",v[2]/100.0)
            .put("capacityAuto",v[3]==1).put("voltageMin",v[4]/100.0).put("voltageMax",v[5]/100.0)
            .put("tempMin",v[6]-20).put("tempMax",v[7]-20).put("screenOnMinutes",v[8]).put("screenOffMinutes",v[9]).put("fastSeconds",v[10]).put("fastAutoOff",v[11]==1).put("chargeSeconds",v[12]).put("performanceSeconds",v[13]);
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
            if(c.moveToFirst())try {settings=decodeSettings(new JSONArray(c.getString(0)));}
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
        sampling.fastMs=fastEnabled?settings.optLong("fastSeconds",10)*1000:0;
        performanceCadence.configure(settings.optLong("performanceSeconds",2)*1000,reschedule?SystemClock.elapsedRealtime():0);
        if(reschedule&&recordingEnabled){
            long now=SystemClock.elapsedRealtime(),nextInterval=interval();
            if(nextInterval!=oldInterval)sampling.next=Math.max(now,(previous==null?now:previous.elapsed)+nextInterval);
            // A longer interval is intentional, not missing samples. Preserve an
            // already elapsed old plan when shortening without splitting a record.
            sampling.expected=Math.max(sampling.expected,nextInterval);
            if(fastEnabled&&state==0&&session>=0){
                db.execSQL("UPDATE sessions SET fast_seconds=?,performance_seconds=? WHERE id=?",new Object[]{settings.optInt("fastSeconds",10),settings.optInt("performanceSeconds",2),session});
            }
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
        if(enabled&&!recordingEnabled)throw new IOException("请先开启记录总开关。");
        Core.Reading r=hardwareReading(header);latest=r;
        if(enabled&&r.online)throw new IOException("请先拔出充电器，再开启游戏模式。");
        if(session>=0){append(r);finish(reason);}
        rememberFast(enabled);candidate=null;candidateAt=-1;sampling.takeScreenSample();
        state=r.online?1:0;stableOnline=r.online;
        if(recordingEnabled){begin(r);sampling.sampled(state,r.elapsed);}
        settingsVersion++;wakeCollector();
        return new JSONObject().put("ok",true).put("settings",settings).put("fastEnabled",fastEnabled).put("session",session);
    }
    void loadCellProfile() {
        try(Cursor c=db.rawQuery("SELECT value FROM runtime WHERE key='cell_count_v1'",null)) {
            if(c.moveToFirst()){int value=Integer.parseInt(c.getString(0));if(value==1||value==2)cellCount=value;}
        }catch(Exception e){System.err.println("Cell profile will be detected again: "+e);}
        resolveCellProfile();
    }
    void resolveCellProfile() {
        long now=SystemClock.elapsedRealtime();if(cellCount>0||now-lastCellProbe<60000)return;lastCellProbe=now;
        int detected=Core.detectCellCount(read(BAT+"aging_ffc_data"));if(detected==0)return;
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
        if((count!=12&&count!=13&&count!=14)||(p.getInt(count)!=0&&p.getInt(count)!=1))throw new IOException("删除确认参数无效");
        JSONArray values=new JSONArray();for(int i=0;i<count;i++)values.put(p.get(i));
        if(count==12)values.put(settings.optLong("chargeSeconds",2));
        if(values.length()==13)values.put(settings.optLong("performanceSeconds",2));
        JSONObject next=decodeSettings(values);int removed=0;
        db.beginTransaction();
        try {
            db.execSQL("INSERT OR REPLACE INTO runtime(key,value) VALUES('module_settings',?)",new Object[]{values.toString()});
            if(p.getInt(count)==1)removed=db.delete("sessions",smallRecordWhere(next),null);
            db.setTransactionSuccessful();
        } finally {db.endTransaction();}
        settings=next;settingsVersion++;rangeId=-1;rangeValues=null;
        configureSampling(true);powerIndicator.state.refresh();wakeCollector();
        return new JSONObject().put("ok",true).put("settings",settings).put("removed",removed);
    }
    void correctDischargePower() {
        db.beginTransaction();
        try {
            if(db.getVersion()==3) {
                db.execSQL("UPDATE samples SET power=power/2 WHERE session IN (SELECT id FROM sessions WHERE kind='discharge') AND power IS NOT NULL");
                db.execSQL("UPDATE apps SET energy_mwh=energy_mwh/2 WHERE session IN (SELECT id FROM sessions WHERE kind='discharge')");
                System.err.println("Corrected existing discharge power and app energy by factor 0.5");
            }
            if(db.getVersion()<4)db.setVersion(4);
            db.setTransactionSuccessful();
        } finally {db.endTransaction();}
    }
    void ensureSampleColumns() {
        db.beginTransaction();
        try {
            Set<String> columns=new HashSet<>();
            try(Cursor c=db.rawQuery("PRAGMA table_info(samples)",null)) {while(c.moveToNext())columns.add(c.getString(1));}
            for(String name:new String[]{"cell1","cell2","temp"})if(!columns.contains(name))db.execSQL("ALTER TABLE samples ADD COLUMN "+name+" REAL");
            Set<String> sessionColumns=new HashSet<>();
            try(Cursor c=db.rawQuery("PRAGMA table_info(sessions)",null)){while(c.moveToNext())sessionColumns.add(c.getString(1));}
            if(!sessionColumns.contains("cell_count"))db.execSQL("ALTER TABLE sessions ADD COLUMN cell_count INTEGER NOT NULL DEFAULT 2");
            if(!sessionColumns.contains("tail_cycles"))db.execSQL("ALTER TABLE sessions ADD COLUMN tail_cycles INTEGER");
            if(!sessionColumns.contains("fast_seconds"))db.execSQL("ALTER TABLE sessions ADD COLUMN fast_seconds INTEGER NOT NULL DEFAULT 0");
            if(!sessionColumns.contains("performance_seconds")){
                db.execSQL("ALTER TABLE sessions ADD COLUMN performance_seconds INTEGER NOT NULL DEFAULT 0");
                db.execSQL("UPDATE sessions SET performance_seconds=fast_seconds WHERE fast_seconds>0");
            }
            if(!sessionColumns.contains("stats_basis"))db.execSQL("ALTER TABLE sessions ADD COLUMN stats_basis TEXT NOT NULL DEFAULT 'rm'");
            if(!sessionColumns.contains("app_revision"))db.execSQL("ALTER TABLE sessions ADD COLUMN app_revision INTEGER NOT NULL DEFAULT 0");
            if(!sessionColumns.contains("clock_adjusted"))db.execSQL("ALTER TABLE sessions ADD COLUMN clock_adjusted INTEGER NOT NULL DEFAULT 0");
            Set<String> appColumns=new HashSet<>();
            try(Cursor c=db.rawQuery("PRAGMA table_info(apps)",null)){while(c.moveToNext())appColumns.add(c.getString(1));}
            if(!appColumns.contains("mah_ms")){
                db.execSQL("ALTER TABLE apps ADD COLUMN mah_ms INTEGER NOT NULL DEFAULT 0");
                db.execSQL("UPDATE apps SET mah_ms=ms");
            }
            db.setVersion(12);db.setTransactionSuccessful();
        } finally {db.endTransaction();}
    }
    static String read(String path) {
        try {return new String(Files.readAllBytes(Paths.get(path)),StandardCharsets.UTF_8).trim();}
        catch(Exception e){return "";}
    }
    static String diagnosticRead(String path) {
        try{return new String(Files.readAllBytes(Paths.get(path)),StandardCharsets.UTF_8).trim();}
        catch(Exception e){FallbackDiagnostics.record("读取 "+path,e);return "";}
    }
    static double num(String s) {try{return Double.parseDouble(s.trim());}catch(Exception e){return Double.NaN;}}
    static Double nullable(double v){return Core.finite(v)?v:null;}
    static double field(Map<String,String> m,String k) {return num(m.getOrDefault(k,""));}
    static boolean online() {
        return POWER_INPUTS.online();
    }
    static Core.Reading readHardware(String[] knownHeader) {
        return readHardware(null,knownHeader,Core.detectCellCount(read(BAT+"aging_ffc_data")));
    }
    Core.Reading hardwareReading(String[] knownHeader){resolveCellProfile();return readHardware(ctx,knownHeader,cellCount);}
    Core.Reading powerDisplayReading(){
        Core.Reading fresh=latest;if(uiReading!=null&&(fresh==null||uiReading.elapsed>fresh.elapsed))fresh=uiReading;
        if(fresh!=null&&SystemClock.elapsedRealtime()-fresh.elapsed<=1000)return fresh;
        resolveCellProfile();return readGauge(ctx,cellCount,false);
    }
    static Core.Reading readGauge(Context ctx,int cells) {
        return readGauge(ctx,cells,true);
    }
    static Core.Reading readGauge(Context ctx,int cells,boolean withTemperature) {
        Core.Reading r=new Core.Reading();r.elapsed=SystemClock.elapsedRealtime();r.wall=System.currentTimeMillis();
        r.cellCount=cells;
        // Reading bcc_parms requests a new ADSP gauge response. GET_BATT_VOL alone
        // can return a stale BCC cache on some drivers.
        String bcc=diagnosticRead(BAT+"bcc_parms");Core.applyBcc(r,bcc);
        if(!withTemperature)r.temp=Double.NaN;
        if(!Core.finite(r.voltage())||!Core.finite(r.current)||withTemperature&&!Core.finite(r.temp)){
            FallbackDiagnostics.record("BCC 电压、电流、温度校验","缺少有效优先读数；bcc_parms="+(bcc.length()>512?bcc.substring(0,512):bcc));
            double voltage=Double.NaN,current=Double.NaN,temp=Double.NaN;
            if(ctx!=null){
                try {
                    Intent battery=ctx.registerReceiver(null,new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
                    if(battery!=null){
                        voltage=battery.getIntExtra(BatteryManager.EXTRA_VOLTAGE,-1);
                        int rawTemp=battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE,Integer.MIN_VALUE);
                        if(withTemperature&&rawTemp!=Integer.MIN_VALUE)temp=rawTemp/10.0;
                    }else FallbackDiagnostics.record("Android 电池广播","ACTION_BATTERY_CHANGED 未返回电池状态");
                }catch(Exception e){FallbackDiagnostics.record("Android 电池广播",e);System.err.println("Android battery broadcast unavailable: "+e);}
                try {
                    BatteryManager manager=ctx.getSystemService(BatteryManager.class);
                    if(manager!=null){long raw=manager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);if(raw!=Long.MIN_VALUE)current=raw;else FallbackDiagnostics.record("Android CURRENT_NOW","接口未提供电流数据");}
                }catch(Exception e){FallbackDiagnostics.record("Android CURRENT_NOW",e);System.err.println("Android battery current unavailable: "+e);}
            }
            if(!Core.finite(voltage)||voltage<=0)voltage=num(diagnosticRead(PS+"battery/voltage_now"))/1000;
            if(withTemperature&&!Core.finite(temp))temp=num(diagnosticRead(PS+"battery/temp"))/10;
            Core.applySystem(r,voltage,current,temp);
        }
        return r;
    }
    static void readRemainingCapacity(Context ctx,Core.Reading r) {
        String raw=diagnosticRead(BAT+"battery_rm");r.rm=num(raw);r.rmSource="";
        if(Core.finite(r.rm)&&r.rm>=0){r.rmSource="battery_rm";return;}
        r.rm=Double.NaN;
        FallbackDiagnostics.record("battery_rm 回退","未获得有效非负容量；原始值="+(raw.isEmpty()?"<空>":raw));
        if(ctx==null)return;
        try {
            BatteryManager manager=ctx.getSystemService(BatteryManager.class);
            if(manager==null){FallbackDiagnostics.record("Android CHARGE_COUNTER","BatteryManager 服务不可用");return;}
            long counter=manager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER);r.rm=Core.remainingMah(counter);
            if(Core.finite(r.rm))r.rmSource="android";
            else FallbackDiagnostics.record("Android CHARGE_COUNTER","接口未提供有效剩余容量；返回值="+counter);
        }catch(Exception e){FallbackDiagnostics.record("Android CHARGE_COUNTER",e);System.err.println("Android battery remaining capacity unavailable: "+e);}
    }
    static Core.Reading readHardware(Context ctx,String[] knownHeader,int cells) {
        Core.Reading r=readGauge(ctx,cells);
        String[] head=knownHeader==null?read(BAT+"battery_log_head").split(",",-1):knownHeader;
        String[] values=read(BAT+"battery_log_content").split(",",-1);
        Map<String,String> m=new HashMap<>();
        if(values.length==head.length)for(int i=0;i<head.length;i++)m.put(head[i].trim(),values[i].trim());
        readRemainingCapacity(ctx,r);r.pct=num(diagnosticRead(PS+"battery/capacity"));
        if(!Core.finite(r.pct)||r.pct<0||r.pct>100)FallbackDiagnostics.record("系统电量校验","capacity 未提供 0–100 范围内的电量");
        if(cells==0)FallbackDiagnostics.record("电芯数量识别","aging_ffc_data="+diagnosticRead(BAT+"aging_ffc_data"));
        r.fv=field(m,"fv_mv");
        r.online=online();r.full=field(m,"batt_full")==1;r.enabled=field(m,"mmi_chg")==1;
        r.source="cells="+cells+"; voltage="+r.voltageSource+"; current="+r.currentSource+"; remaining="+r.rmSource+"; root capacity";
        if(!r.valid())r.error="剩余容量 / 系统电量读取失败";
        else if(cells==0)r.error="电芯数量待识别，稍后重试";
        else if(!Core.finite(r.voltage())||!Core.finite(r.current))r.error="电压或电流读取失败，保留缺口";
        else if(!Core.finite(r.temp))r.error="温度读取失败，保留缺口";
        return r;
    }
    static Integer integer(String value){try{return Integer.valueOf(value.trim());}catch(Exception e){return null;}}
    static Object jsonValue(Object value){return value==null?JSONObject.NULL:value;}
    static String optionalText(String value){return value.isEmpty()?null:value;}
    Integer readCycleCount(){
        Integer cycles=integer(read(PS+"battery/cycle_count"));
        if(cycles==null||cycles<0)try{
            Intent intent=ctx.registerReceiver(null,new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            int value=intent==null?-1:intent.getIntExtra(BatteryManager.EXTRA_CYCLE_COUNT,-1);cycles=value>=0?value:null;
        }catch(Exception ignored){cycles=null;}
        return cycles;
    }
    JSONObject batteryInfoSnapshot()throws Exception {
        Integer fcc=integer(read(BAT+"battery_fcc")),soh=integer(read(BAT+"battery_soh"));
        Integer cutoff=BatteryDetails.termVoltage(read("/proc/oplus-votable/GAUGE_TERM_VOLTAGE/status")),design=integer(read(BAT+"design_capacity"));
        String type=read(BAT+"battery_type");byte[] coefficients=new byte[0];
        String tree="/proc/device-tree/soc/oplus,mms_gauge/";
        String[] paths=type.matches("[A-Za-z0-9_.-]+")?new String[]{tree+type+"/deep_spec,term_coeff",tree+"deep_spec,term_coeff"}:new String[]{tree+"deep_spec,term_coeff"};
        for(String path:paths)try{coefficients=Files.readAllBytes(Paths.get(path));break;}catch(IOException ignored){}
        List<int[]> table=BatteryDetails.coefficients(coefficients);
        JSONArray relationships=new JSONArray();
        for(int[] row:table)relationships.put(new JSONObject().put("cutoffMv",row[0]).put("fccOffset",row[1]).put("sohOffset",row[2]));
        Double rawSoh=BatteryDetails.rawSoh(soh,cutoff,table);
        Integer rawFcc=BatteryDetails.rawFcc(fcc,rawSoh,cutoff,table,design);
        String[] keys=read(BAT+"battery_log_head").split(",",-1),values=read(BAT+"battery_log_content").split(",",-1);
        Integer qmax=null;if(keys.length==values.length)for(int i=1;i<keys.length;i++)if("batt_qmax".equals(keys[i].trim()))qmax=integer(values[i]);
        Integer cycles=readCycleCount();
        return new JSONObject().put("cycles",jsonValue(cycles)).put("fcc",jsonValue(fcc)).put("rawFcc",jsonValue(rawFcc))
            .put("soh",jsonValue(soh)).put("rawSoh",jsonValue(rawSoh)).put("qmax",jsonValue(BatteryDetails.normalizeQmax(qmax,fcc,design)))
            .put("cutoffMv",jsonValue(cutoff)).put("designCapacity",jsonValue(design))
            .put("serial",jsonValue(optionalText(read(BAT+"battery_sn"))))
            .put("manufactureDate",jsonValue(optionalText(read(BAT+"battery_manu_date"))))
            .put("batteryType",jsonValue(optionalText(type))).put("coefficients",relationships);
    }
    static JSONObject readingJson(Core.Reading r)throws JSONException {
        JSONObject o=new JSONObject();
        o.put("elapsed",r.elapsed).put("wall",r.wall).put("rm",nullable(r.rm)).put("pct",nullable(r.pct));
        o.put("current",nullable(r.chargingCurrent())).put("voltage",nullable(r.voltage()));
        o.put("cellCount",r.cellCount).put("displayCurrent",nullable(r.compensatedCurrent()));
        o.put("voltageSource",r.voltageSource).put("currentSource",r.currentSource).put("rmSource",r.rmSource);
        o.put("vmax",nullable(r.vmax)).put("vmin",nullable(r.vmin)).put("power",nullable(r.power()));
        o.put("cell1",nullable(r.cell1)).put("cell2",nullable(r.cell2));
        o.put("online",r.online).put("driverFull",r.full).put("enabled",r.enabled).put("fv",nullable(r.fv));
        o.put("temp",nullable(r.temp)).put("source",r.source).put("error",r.error);return o;
    }

    void wakeCollector(){
        if((capabilities&16)!=0)interruptWait();
        else if(moduleWatch)try{Owner.write(new File(data,"screen.event"),Long.toString(SystemClock.elapsedRealtime()));}
        catch(IOException e){screenError="采样事件通知不可用，屏幕切换可能延迟";}
        else screenError="采样事件通知不可用，屏幕切换可能延迟";
    }
    void screenChanged(long now,boolean first,boolean changed){
        if((first&&screenState.known)||changed)fastScreenOffAt=fastEnabled&&!sampling.interactive?now:-1;
        if(changed)wakeCollector();
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
    void collect() throws Exception {
        synchronized(mutex) {
            Core.Reading r=hardwareReading(header);latest=r;stableOnline=r.online;
            String problem=RecordingGate.problem(r.rm,r.pct);
            if(!problem.isEmpty()){
                RecordingGate.pause(data,problem);recordingProblem=problem;recordingEnabled=false;
            }
            if(!recordingEnabled&&!recordingProblem.isEmpty())rememberFast(false);
            if(fastEnabled&&r.online)rememberFast(false);
            if(recordingEnabled)recover(r);else {
                state=r.online?1:0;
                // A persisted pause must never resurrect a previous session.
                db.execSQL("UPDATE sessions SET active=0,reason='recording_stopped' WHERE active=1");
            }
            sampling.sampled(state,r.elapsed);
        }
        releaseWait();
        while(!stopping) {
            long due;
            synchronized(mutex) {
                due=recordingEnabled?(candidateAt>=0?candidateAt+3000:sampling.next):SystemClock.elapsedRealtime()+3600000;
                due=Math.min(due,ownerNext);
                due=Math.min(due,fastAutoDeadline());
                due=Math.min(due,powerIndicator.state.deadline(sampling.interactive));
                if(recordingEnabled&&fastEnabled&&state==0&&session>=0)due=Math.min(due,fastStatsNext);
                if(recordingEnabled&&fastEnabled&&state==0&&session>=0&&candidateAt<0)due=Math.min(due,performanceSession==session?performanceCadence.next:0);
                if(!moduleWatch)due=Math.min(due,SystemClock.elapsedRealtime()+60000);
            }
            int event=waitEvent(Math.max(1,due-SystemClock.elapsedRealtime()));
            if(event==2)POWER_INPUTS.invalidate();
            if(event<0){Thread.sleep(1000);runtimeError="定时唤醒失败";}
            try {
                synchronized(mutex) {
                    if(!framework.healthy()){
                        System.err.println("Framework services disconnected; restarting recorder.");
                        stopReason="framework_restarted";stopping=true;screenListenerReady=false;break;
                    }
                    if(SystemClock.elapsedRealtime()>=ownerNext)checkOwner();
                    if(stopping)break;
                    if(!recordingEnabled){sampling.takeScreenSample();continue;}
                    if(SystemClock.elapsedRealtime()>=fastAutoDeadline()){setFast(false,"fast_idle_timeout");continue;}
                    long now=SystemClock.elapsedRealtime();boolean plugged=online();
                    boolean screenSample=sampling.takeScreenSample();
                    Core.Reading screenReading=screenSample?hardwareReading(header):null;
                    if(screenReading!=null)latest=screenReading;
                    if(plugged!=stableOnline) {
                        if(candidateAt<0){candidate=screenReading!=null?screenReading:hardwareReading(header);candidateAt=now;}
                        if(now-candidateAt>=3000) {
                            append(candidate);finish(plugged?"plugged":"unplugged");
                            if(plugged&&fastEnabled)rememberFast(false);
                            stableOnline=plugged;state=plugged?1:0;begin(candidate);
                            candidate=null;candidateAt=-1;sampling.sampled(state,now);
                        }
                    } else {
                        candidateAt=-1;candidate=null;
                        if(screenSample||now>=sampling.next) {
                            Core.Reading r=screenReading!=null?screenReading:hardwareReading(header);latest=r;
                            if(state==2) {
                                if(!r.full&&Core.finite(r.current)&&r.current<-50){state=1;begin(r);}
                            } else {
                                append(r);
                                if(screenSample&&state==0){
                                    if(fastEnabled)sampleFastStats(r,true);
                                    // The display callback is authoritative at this boundary even if
                                    // UsageStats publishes its matching screen event a little later.
                                    if(timelineReady)timeline.apply(new Core.Event(r.wall,sampling.interactive?15:16,null));
                                }
                                if(state==1 && fullGate.update(r,sampling.expected)) {finish("full_tail_confirmed");state=2;}
                            }
                            sampling.sampled(state,now);
                        }else if(fastEnabled&&state==0&&now>=fastStatsNext){
                            // Independent minute cadence, even when the fast interval does not divide 60.
                            Core.Reading r=hardwareReading(header);latest=r;sampleFastStats(r,false);
                        }
                    }
                    if(candidateAt<0)samplePerformance(now);
                }
            } finally {
                try{synchronized(mutex){
                    long displayNow=SystemClock.elapsedRealtime();
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
        framework.close();performance.close();powerIndicator.close();
        synchronized(mutex) {
            // Updating code or reconnecting framework proxies suspends ownership,
            // not the charge/discharge session. Completed samples are already durable.
            if(!Core.resumableStop(stopReason))finish(stopReason);
            db.close();lock.release();lockFile.close();
        }
    }
    JSONObject setRecording(boolean enabled)throws Exception {
        if(stopping)throw new IOException("后台正在结束，请稍后重试。");
        recordingProblem=read(new File(data,RecordingGate.PROBLEM).getPath());
        if(enabled==recordingEnabled)return recordingResult();
        return setRecording(enabled,hardwareReading(header));
    }
    JSONObject recordingResult()throws Exception{return new JSONObject().put("ok",true).put("recordingEnabled",recordingEnabled).put("recordingProblem",recordingProblem).put("settings",settings);}
    JSONObject setRecording(boolean enabled,Core.Reading r)throws Exception {
        if(stopping)throw new IOException("后台正在结束，请稍后重试。");latest=r;
        File flag=new File(data,"recording.disabled");
        if(!enabled){
            if(!flag.exists()&&!flag.createNewFile())throw new IOException("停止状态保存失败。");
            // Seal the old interval before pausing. A transition at the button
            // boundary is split instead of attributing it to the old session.
            if(r.valid()){
                if(session>=0&&r.online!=stableOnline){append(candidate!=null?candidate:r);finish(r.online?"plugged":"unplugged");}
                else append(r);
            }
            finish("recording_stopped");recordingEnabled=false;if(fastEnabled)rememberFast(false);
        }else{
            recordingProblem=RecordingGate.problem(r.rm,r.pct);
            RecordingGate.enable(data,r.rm,r.pct);
            recordingEnabled=true;state=r.online?1:0;stableOnline=r.online;begin(r);sampling.sampled(state,r.elapsed);
        }
        candidate=null;candidateAt=-1;sampling.takeScreenSample();settingsVersion++;wakeCollector();
        return recordingResult();
    }
    void requestStop(String reason)throws Exception {
        synchronized(mutex){
            if(stopping)return;
            if(session>=0){
                Core.Reading r=hardwareReading(header);latest=r;append(r);
                if(Core.resumableStop(reason)){
                    if(fastStatsPrevious!=null)sampleFastStats(r,true);
                }else finish(reason);
            }
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
        session=-1;previous=null;fullGate.reset();depletion.reset();timelineReady=false;
        fastStatsPrevious=null;fastStatsNext=Long.MAX_VALUE;recoveryBoundary=false;
        JSONObject progress=null;
        try{String value=runtimeValue("session_progress_v1");if(!value.isEmpty())progress=new JSONObject(value);}
        catch(JSONException e){System.err.println("Recorder checkpoint unavailable; using committed samples.");}
        db.beginTransaction();
        try {
            try(Cursor c=db.rawQuery("SELECT id,boot,kind,start_elapsed,end_elapsed,start_wall,fast_seconds,clock_adjusted FROM sessions WHERE active=1 ORDER BY id DESC",null)) {
                while(c.moveToNext()) {
                    long id=c.getLong(0);
                    if(session<0&&Core.resumeSession(c.getString(1),boot,c.getString(2),c.getInt(6)>0,
                            r.online,fastEnabled,c.getLong(3),c.getLong(4),r.elapsed)){
                        Core.Reading saved=lastSample(id,c.getLong(3),c.getLong(5));
                        if(saved!=null&&saved.elapsed>=c.getLong(3)&&saved.elapsed<=c.getLong(4)&&saved.elapsed<=r.elapsed){
                            session=id;startElapsed=c.getLong(3);startWall=c.getLong(5);clockAdjusted=c.getInt(7)!=0;state=r.online?1:0;
                            if(progress!=null&&progress.optLong("session",-1)==id&&boot.equals(progress.optString("boot"))){
                                Core.Reading checkpoint=restoreReading(progress.optJSONObject("previous"));
                                if(checkpoint!=null&&checkpoint.elapsed==saved.elapsed&&checkpoint.elapsed<=r.elapsed)saved=checkpoint;
                                depletion.restore(progress.optDouble("credit",0));
                                Core.Reading stats=restoreReading(progress.optJSONObject("fastPrevious"));
                                if(fastEnabled&&stats!=null&&stats.elapsed>=startElapsed&&stats.elapsed<=r.elapsed){
                                    fastStatsPrevious=stats;fastStatsNext=progress.optLong("fastNext",stats.elapsed+60000);
                                    if(fastStatsNext<=stats.elapsed||fastStatsNext>stats.elapsed+60000)fastStatsNext=stats.elapsed+60000;
                                }
                                if(fastEnabled&&!sampling.interactive){
                                    long offAt=progress.optLong("screenOffAt",-1);
                                    if(offAt>=0&&offAt<=r.elapsed)fastScreenOffAt=offAt;
                                }
                            }
                            previous=saved;recoveryBoundary=true;
                            sampling.expected=progress!=null&&progress.optLong("session",-1)==id
                                ?Math.max(1000,Math.min(3600000,progress.optLong("expectedMs",interval()))):interval();
                            // Older collectors did not persist the minute accounting anchor.
                            // Start that anchor here instead of double-counting an old minute.
                            if(fastEnabled&&fastStatsPrevious==null){fastStatsPrevious=r;fastStatsNext=r.elapsed+60000;}
                            continue;
                        }
                    }
                    db.execSQL("UPDATE sessions SET active=0,reason=? WHERE id=?",new Object[]{boot.equals(c.getString(1))?"collector_restart":"reboot",id});
                    db.delete("sessions","id=? AND "+smallRecordWhere(settings),new String[]{""+id});
                }
            }
            if(session>=0)append(r);
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
        if(session>=0)return;
        try(Cursor c=db.rawQuery("SELECT value FROM runtime WHERE key='full_tail_boot'",null)) {
            if(c.moveToFirst()&&r.online&&r.full&&boot.equals(c.getString(0))){state=2;return;}
        }
        state=r.online?1:0;begin(r);
    }
    Core.Reading lastSample(long id,long start,long wall){
        try(Cursor c=db.rawQuery("SELECT x,rm,pct,current,voltage,cell1,cell2,temp FROM samples WHERE session=? ORDER BY x DESC LIMIT 1",new String[]{""+id})){
            if(!c.moveToFirst())return null;
            Core.Reading r=new Core.Reading();r.elapsed=start+c.getLong(0);r.wall=wall+c.getLong(0);
            r.rm=c.isNull(1)?Double.NaN:c.getDouble(1);r.pct=c.isNull(2)?Double.NaN:c.getDouble(2);
            r.current=c.isNull(3)?Double.NaN:-c.getDouble(3);r.vmin=r.vmax=c.isNull(4)?Double.NaN:c.getDouble(4);
            r.cell1=c.isNull(5)?Double.NaN:c.getDouble(5);r.cell2=c.isNull(6)?Double.NaN:c.getDouble(6);
            r.temp=c.isNull(7)?Double.NaN:c.getDouble(7);r.cellCount=cellCount;return r;
        }
    }
    static JSONObject checkpointReading(Core.Reading r)throws JSONException {
        if(r==null)return null;
        return new JSONObject().put("elapsed",r.elapsed).put("wall",r.wall).put("rm",nullable(r.rm)).put("pct",nullable(r.pct))
            .put("current",nullable(r.current)).put("vmin",nullable(r.vmin)).put("vmax",nullable(r.vmax))
            .put("cell1",nullable(r.cell1)).put("cell2",nullable(r.cell2)).put("cellCount",r.cellCount);
    }
    static Core.Reading restoreReading(JSONObject o){
        if(o==null||!o.has("elapsed")||!o.has("wall"))return null;
        Core.Reading r=new Core.Reading();r.elapsed=o.optLong("elapsed",-1);r.wall=o.optLong("wall",-1);
        if(r.elapsed<0||r.wall<0)return null;
        r.rm=o.optDouble("rm",Double.NaN);r.pct=o.optDouble("pct",Double.NaN);r.current=o.optDouble("current",Double.NaN);
        r.vmin=o.optDouble("vmin",Double.NaN);r.vmax=o.optDouble("vmax",Double.NaN);
        r.cell1=o.optDouble("cell1",Double.NaN);r.cell2=o.optDouble("cell2",Double.NaN);r.cellCount=o.optInt("cellCount",0);return r;
    }
    void checkpoint(Core.Reading r)throws JSONException {
        if(session<0||state!=0)return;
        JSONObject progress=new JSONObject().put("session",session).put("boot",boot).put("previous",checkpointReading(r))
            .put("fastPrevious",checkpointReading(fastStatsPrevious)).put("fastNext",fastStatsNext)
            .put("credit",depletion.checkpoint()).put("screenOffAt",fastScreenOffAt).put("expectedMs",interval());
        db.execSQL("INSERT OR REPLACE INTO runtime(key,value) VALUES('session_progress_v1',?)",new Object[]{progress.toString()});
    }
    void begin(Core.Reading r)throws Exception {
        previous=null;fullGate.reset();depletion.reset();timelineReady=false;recoveryBoundary=false;
        fastStatsPrevious=state==0&&fastEnabled?r:null;
        fastStatsNext=fastStatsPrevious!=null?r.elapsed+60000:Long.MAX_VALUE;
        db.execSQL("DELETE FROM runtime WHERE key='full_tail_boot'");
        startElapsed=r.elapsed;startWall=r.wall;clockAdjusted=false;
        try(SQLiteStatement insert=db.compileStatement("INSERT INTO sessions(kind,start_wall,start_elapsed,boot,end_elapsed,pct_start,pct_end,rm_start,rm_end,cell_count,fast_seconds,performance_seconds,stats_basis,active,reason) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,1,'recording')")) {
            Object[] args={state==1?"charge":"discharge",r.wall,r.elapsed,boot,r.elapsed,nullable(r.pct),nullable(r.pct),nullable(r.rm),nullable(r.rm),r.cellCount,state==0&&fastEnabled?settings.optInt("fastSeconds",10):0,state==0&&fastEnabled?settings.optInt("performanceSeconds",2):0,state==0&&fastEnabled?"rm_minute":"rm"};
            for(int i=0;i<args.length;i++) {
                Object v=args[i];int n=i+1;
                if(v==null)insert.bindNull(n);
                else if(v instanceof Double)insert.bindDouble(n,(Double)v);
                else if(v instanceof Number)insert.bindLong(n,((Number)v).longValue());
                else insert.bindString(n,v.toString());
            }
            session=insert.executeInsert();
        }
        append(r);
    }
    void finish(String reason) {
        Integer cycles=session>=0&&"full_tail_confirmed".equals(reason)?readCycleCount():null;
        db.beginTransaction();
        try {
            if(fastStatsPrevious!=null&&previous!=null&&session>=0)try{sampleFastStats(previous,true);}
            catch(Exception e){System.err.println("Final minute statistics skipped: "+e.getClass().getSimpleName());}
            if("full_tail_confirmed".equals(reason))
                db.execSQL("INSERT OR REPLACE INTO runtime(key,value) VALUES('full_tail_boot',?)",new Object[]{boot});
            if(session>=0) {
                db.execSQL("UPDATE sessions SET active=0,reason=?,tail_cycles=? WHERE id=?",new Object[]{reason,cycles,session});
                db.delete("sessions","id=? AND "+smallRecordWhere(settings),new String[]{""+session});
            }
            db.setTransactionSuccessful();
        } finally {db.endTransaction();}
        rangeId=-1;rangeValues=null;
        session=-1;previous=null;fullGate.reset();depletion.reset();timelineReady=false;recoveryBoundary=false;
        fastStatsPrevious=null;fastStatsNext=Long.MAX_VALUE;
    }
    void append(Core.Reading r)throws Exception {
        latest=r;if(session<0)return;
        long dt=previous==null?0:r.elapsed-previous.elapsed;
        if(previous!=null&&dt<=0)return;
        int quality=0;long missed=0;
        boolean resumed=recoveryBoundary;
        if(resumed)quality|=9; // Bit 8 labels a resumed boundary; short discharge intervals are still ignored.
        if(previous!=null&&dt>sampling.maxGap()){quality|=1;missed=Math.max(0,dt/sampling.expected-1);}
        if(!r.valid() || !r.error.isEmpty())quality|=4;
        boolean fast=state==0&&fastEnabled;
        double loss=0;
        if(state==0&&previous!=null&&!fast) {
            loss=depletion.consume(previous.rm,r.rm,dt,sampling.maxGap());
            if(!Core.finite(loss))quality|=2;
        }
        double energy=state==0&&previous!=null&&(quality&3)==0
            ?Core.dischargeEnergyMwh(previous.voltage(),r.voltage(),loss):Double.NaN;
        double recordedPower=state==1?r.power():fast?-r.power():Core.averagePower(energy,dt);
        db.beginTransaction();
        try {
            db.execSQL("INSERT OR IGNORE INTO samples(session,x,rm,pct,current,voltage,power,quality,interval_ms,cell1,cell2,temp) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                new Object[]{session,r.elapsed-startElapsed,nullable(r.rm),nullable(r.pct),state==1||fast?nullable(r.chargingCurrent()):null,nullable(r.voltage()),nullable(recordedPower),quality,state==0&&previous!=null?dt:null,nullable(r.cell1),nullable(r.cell2),nullable(r.temp)});
            if(fast)sampleFastStats(r,resumed,resumed);
            else if(state==0&&previous!=null)attribute(previous,r,loss,(quality&3)!=0,energy);
            db.execSQL("UPDATE sessions SET end_elapsed=?,pct_end=?,rm_end=?,missed=missed+?,anomalies=anomalies+? WHERE id=?",
                new Object[]{r.elapsed,nullable(r.pct),nullable(r.rm),missed,(quality&6)!=0?1:0,session});
            // Minute checkpoints suffice for fast accounting. Avoid adding a WAL
            // page write to every second of charging or game graph sampling.
            if(!fast||previous==null||resumed)checkpoint(r);
            db.setTransactionSuccessful();
        } finally {db.endTransaction();}
        updateRanges(r,recordedPower);
        previous=r;recoveryBoundary=false;sampling.expected=interval();
        if(fast)samplePerformance(r.elapsed);
    }
    void samplePerformance(long now){
        if(!recordingEnabled||!fastEnabled||state!=0||session<0||stopping)return;
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
        if(a==null||session<0||r.elapsed<=a.elapsed||!flush&&r.elapsed<fastStatsNext)return;
        long dt=r.elapsed-a.elapsed;boolean gap=resumed||dt>90000;
        double loss=depletion.consume(a.rm,r.rm,dt,90000);
        double energy=gap?Double.NaN:Core.dischargeEnergyMwh(a.voltage(),r.voltage(),loss);
        boolean ownTransaction=!db.inTransaction();if(ownTransaction)db.beginTransaction();
        try{
            attribute(a,r,loss,gap,energy);
            // Preserve the same minute RM result already assigned to apps;
            // hourly usage charts must not use instantaneous power samples.
            db.execSQL("INSERT OR IGNORE INTO game_rm_intervals(session,x,start_x,energy_mwh) VALUES(?,?,?,?)",
                new Object[]{session,r.elapsed-startElapsed,a.elapsed-startElapsed,nullable(energy)});
            db.execSQL("UPDATE sessions SET end_elapsed=?,pct_end=?,rm_end=? WHERE id=? AND end_elapsed<=?",
                new Object[]{r.elapsed,nullable(r.pct),nullable(r.rm),session,r.elapsed});
            fastStatsPrevious=r;
            while(fastStatsNext<=r.elapsed)fastStatsNext+=60000;
            checkpoint(previous);
            if(ownTransaction)db.setTransactionSuccessful();
        }finally{if(ownTransaction)db.endTransaction();}
    }
    void updateRanges(Core.Reading r,double recordedPower)throws Exception {
        long x=r.elapsed-startElapsed;if(rangeId!=session||x<=rangeEnd||rangeValues==null)return;
        JSONObject next=new JSONObject(rangeValues.toString());
        String[] keys={"rm","pct","current","voltage","power","temp"};
        double[] values={r.rm,r.pct,state==1?r.chargingCurrent():Double.NaN,r.voltage(),recordedPower,r.temp};
        if(state==0&&!fastEnabled) {
            if(previous!=null&&r.elapsed-previous.elapsed<20000)Arrays.fill(values,Double.NaN);
            else if(recordedPower>15||recordedPower<0)values[4]=Double.NaN;
        }
        for(int i=0;i<keys.length;i++)if(Core.finite(values[i])) {
            JSONArray bounds=next.getJSONArray(keys[i]);
            bounds.put(0,bounds.isNull(0)?values[i]:Math.min(bounds.getDouble(0),values[i]));
            bounds.put(1,bounds.isNull(1)?values[i]:Math.max(bounds.getDouble(1),values[i]));
        }
        rangeValues=next;rangeCount++;rangeEnd=x;
    }

    static List<Core.Event> queryUsage(Context ctx,long start,long end)throws Exception {
        UsageStatsManager manager=ctx.getSystemService(UsageStatsManager.class);
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
                FallbackDiagnostics.record("应用使用事件读取",cause);usageError=diagnostic;usageErrorAt=System.currentTimeMillis();framework.observe(e);
                timelineReady=false;split=Collections.singletonMap(Core.OTHER,dt);
            }
        }
        if(screenSpans!=null){saveScreenSpans(session,screenSpans.spans);saveScreenApps(session,screenSpans.apps);}
        addUsage(split,dt,loss,gap?Double.NaN:energy);
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
        if(id==session&&record.optInt("active")==1){Core.Reading settled=fastEnabled?fastStatsPrevious:previous;cutoff=settled==null?recordStart:Math.min(recordEnd,recordStart+settled.elapsed-record.getLong("start_elapsed"));}
        long from=Math.max(Math.max(day,recordStart),requestedFrom>0?requestedFrom:day),to=Math.max(from,Math.min(Math.min(next,cutoff),requestedTo>0?requestedTo:next));
        List<ScreenUsage.Span> spans=screenSpans(id,from,to),missing=ScreenUsage.holes(spans,from,to);
        String key=id+":"+from+":"+to+":"+zone.getID();long now=SystemClock.elapsedRealtime();Long attempted=screenUsageReads.get(key);
        // UsageStats uses wall time. After a clock change only already captured,
        // normalized spans are trustworthy; querying chart timestamps is unsafe.
        if(record.optInt("clock_adjusted")==0&&!missing.isEmpty()&&(attempted==null||now-attempted>=60000)){
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
            }catch(Throwable error){if(framework!=null)framework.observe(error);}
            if(!extra.isEmpty()){saveScreenSpans(id,extra);spans.addAll(extra);spans=ScreenUsage.normalized(spans,from,to);}
        }
        ScreenUsage.Hours hours=ScreenUsage.hours(spans,from,to,zone);JSONArray on=new JSONArray(),covered=new JSONArray(),segments=new JSONArray();
        for(int hour=0;hour<24;hour++){on.put(hours.on[hour]);covered.put(hours.covered[hour]);}
        for(ScreenUsage.Span span:spans)segments.put(new JSONArray().put(span.start).put(span.end).put(span.on?1:0));
        return new JSONObject().put("ok",true).put("dayStart",day).put("dayEnd",next).put("rangeStart",from).put("rangeEnd",to).put("settledEnd",cutoff).put("segments",segments)
            .put("hours",on).put("covered",covered).put("totalMs",hours.totalOn).put("coveredMs",hours.totalCovered).put("unknownMs",Math.max(0,to-from-hours.totalCovered));
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
    JSONObject hourApps(long id,long requestedFrom,long requestedTo)throws Exception{
        JSONArray found=rows("SELECT start_wall,start_elapsed,end_elapsed,active,kind,clock_adjusted FROM sessions WHERE id=?",new String[]{""+id});
        if(found.length()==0)throw new IOException("记录不存在或已删除");JSONObject record=found.getJSONObject(0);
        if(!record.getString("kind").equals("discharge"))throw new IOException("应用使用时长仅用于放电记录");
        long start=record.getLong("start_wall"),end=start+Math.max(0,record.getLong("end_elapsed")-record.getLong("start_elapsed"));
        if(id==session&&record.optInt("active")==1){Core.Reading settled=fastEnabled?fastStatsPrevious:previous;end=Math.min(end,settled==null?start:start+settled.elapsed-record.getLong("start_elapsed"));}
        long from=Math.max(start,requestedFrom),to=Math.max(from,Math.min(end,requestedTo));
        if(requestedTo<requestedFrom||requestedTo-requestedFrom>27*3600000L||requestedTo>ScreenUsage.nextDay(requestedFrom,TimeZone.getDefault()))throw new IOException("日期范围无效");
        List<ScreenUsage.AppSpan> spans=screenApps(id,from,to);List<ScreenUsage.Span> coverage=new ArrayList<>();
        for(ScreenUsage.AppSpan span:spans)coverage.add(new ScreenUsage.Span(span.start,span.end,true));
        List<ScreenUsage.Span> holes=ScreenUsage.holes(coverage,from,to);
        String key="apps:"+id+":"+from+":"+to;long now=SystemClock.elapsedRealtime();Long attempted=screenUsageReads.get(key);
        if(record.optInt("clock_adjusted")==0&&!holes.isEmpty()&&(attempted==null||now-attempted>=60000)){
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
            }catch(Throwable error){FallbackDiagnostics.record("分时应用补读",error);if(framework!=null)framework.observe(error);}
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
        if(game&&id==session&&record.optInt("active")==1)end=Math.max(Math.max(day,start),Math.min(end,fastStatsPrevious==null?start:start+fastStatsPrevious.elapsed-record.getLong("start_elapsed")));
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
    void addUsage(Map<String,Long> split,long dt,double loss,double energy) {
        for(Map.Entry<String,Long> part:split.entrySet()) {
            boolean chargeMeasured=Core.finite(loss);
            double mah=chargeMeasured?loss*part.getValue()/dt:0;
            boolean measured=Core.finite(energy);
            db.execSQL("INSERT INTO apps(session,pkg,ms,mah,energy_mwh,energy_ms,mah_ms) VALUES(?,?,?,?,?,?,?) ON CONFLICT(session,pkg) DO UPDATE SET ms=ms+excluded.ms,mah=mah+excluded.mah,energy_mwh=energy_mwh+excluded.energy_mwh,energy_ms=energy_ms+excluded.energy_ms,mah_ms=mah_ms+excluded.mah_ms",
                new Object[]{session,part.getKey(),part.getValue(),mah,measured?energy*part.getValue()/dt:0,measured?part.getValue():0,chargeMeasured?part.getValue():0});
        }
        statsVersion++;
    }

    volatile DailyUsage dailyUsage;
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
                            }else synchronized(mutex){answer=dispatch(req);}
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
        long id=args==null?0:Math.max(0,args.optLong(0,0)),after=args==null?0:Math.max(0,args.optLong(1,0));
        long began=SystemClock.elapsedRealtime(),lastList=0,lastSettings=-1;
        // A bounded lease also closes an orphaned stream if a renderer dies before receiving its PID.
        while(SystemClock.elapsedRealtime()-began<300000) {
            long started=SystemClock.elapsedRealtime();JSONObject packet;boolean more;
            JSONObject frequencies=null;
            if(showFrequencies)try{frequencies=performance.frequencies();}catch(Exception ignored){}
            synchronized(mutex) {
                JSONObject previous=null;boolean changed=id!=session;
                if(changed&&id>0)previous=live(new JSONArray().put(id).put(after).put(0).put(0).put(session+1)).put("recordId",id);
                if(changed){id=session;after=id>0?Math.max(0,latest.elapsed-startElapsed-1):0;}
                packet=live(new JSONArray().put(Math.max(0,id)).put(after).put(0).put(0).put(session+1));
                if(previous!=null)packet.put("previous",previous);
                if(lastList==0||changed||lastSettings!=settingsVersion||started-lastList>=15000) {
                    JSONObject normal=dispatch(new JSONObject().put("action","list").put("args",new JSONArray().put(0).put(0)));
                    JSONObject all=dispatch(new JSONObject().put("action","list").put("args",new JSONArray().put(1).put(0)));
                    packet.put("records",normal.getJSONArray("records")).put("total",normal.getInt("total"));
                    packet.put("recordsAll",all.getJSONArray("records")).put("totalAll",all.getInt("total"));
                    packet.put("listVersion",settingsVersion);
                    lastList=started;lastSettings=settingsVersion;
                }
                JSONArray points=packet.optJSONArray("points");
                if(points!=null&&points.length()>0)after=points.getJSONArray(points.length()-1).getLong(0);
                more=!packet.isNull("nextAfter")&&packet.has("nextAfter");
            }
            if(showFrequencies)packet.put("frequencies",frequencies==null?JSONObject.NULL:frequencies);
            socket.getOutputStream().write((packet.toString()+"\n").getBytes(StandardCharsets.UTF_8));
            if(!more)Thread.sleep(Math.max(100,1000-(SystemClock.elapsedRealtime()-started)));
        }
    }
    void watchBatteryInfo(LocalSocket socket,boolean snapshot)throws Exception {
        long began=SystemClock.elapsedRealtime(),slowAt=-1;double rm=Double.NaN,temp=Double.NaN;String rmSource="";
        JSONObject metadata=null;if(snapshot)synchronized(mutex){metadata=batteryInfoSnapshot();}
        while(SystemClock.elapsedRealtime()-began<300000){
            long started=SystemClock.elapsedRealtime();JSONObject packet;
            synchronized(mutex){
                resolveCellProfile();boolean slow=slowAt<0||started-slowAt>=10000;
                Core.Reading reading=readGauge(ctx,cellCount,slow);
                if(slow){readRemainingCapacity(ctx,reading);rm=reading.rm;rmSource=reading.rmSource;temp=reading.temp;slowAt=started;}
                reading.rm=rm;reading.rmSource=rmSource;reading.temp=temp;
                JSONObject status=new JSONObject().put("ok",true).put("state",state).put("session",session).put("cellCount",cellCount)
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
        if(action.equals("cache_records")){
            if(p.length()<1||p.length()>12)throw new IOException("缓存检查参数无效");
            String[] ids=new String[p.length()];StringJoiner placeholders=new StringJoiner(",");
            for(int i=0;i<ids.length;i++){long id=p.getLong(i);if(id<1)throw new IOException("缓存记录无效");ids[i]=""+id;placeholders.add("?");}
            JSONArray existing=new JSONArray();try(Cursor c=db.rawQuery("SELECT id FROM sessions WHERE id IN ("+placeholders+")",ids)){while(c.moveToNext())existing.put(c.getLong(0));}
            return o.put("ids",existing);
        }
        if(action.equals("live"))return live(p);
        if(action.equals("status")) {
            return status();
        }
        if(action.equals("diagnostics"))return o.put("native",waitDiagnostics()).put("entries",FallbackDiagnostics.snapshot());
        if(action.equals("error_logs"))return new ErrorLogStore(new File(data,"error-logs")).list(p.optInt(0,0),40).put("ok",true);
        if(action.equals("clear_error_logs")){new ErrorLogStore(new File(data,"error-logs")).clear();return o;}
        if(action.equals("error_log"))return o.put("report",new ErrorLogStore(new File(data,"error-logs")).read(p.optString(0)));
        if(action.equals("list")) {
            boolean all=p.optString(0,"0").equals("1");int offset=Math.max(0,Math.min(1_000_000,p.optInt(1,0)));
            int year=p.optInt(2,0),month=p.optInt(3,0);long from=0,to=0;
            if(year>=1970&&year<=9999&&month>=1&&month<=12){
                Calendar c=Calendar.getInstance();c.clear();c.set(year,month-1,1,0,0,0);from=c.getTimeInMillis();c.add(Calendar.MONTH,1);to=c.getTimeInMillis();
            }
            String filter=RecordFilter.where(all,settings.optInt("hidePct",30),p.optInt(4,0),from,to);
            o.put("records",rows("SELECT *,ABS(pct_end-pct_start) AS delta_pct FROM sessions"+filter+" ORDER BY id DESC LIMIT 100 OFFSET ?",new String[]{""+offset}));
            try(Cursor c=db.rawQuery("SELECT COUNT(*) FROM sessions"+filter,null)){c.moveToFirst();o.put("total",c.getInt(0));}return o.put("listVersion",settingsVersion);
        }
        long id=p.optLong(0,-1);if(id<1)throw new IOException("无效记录 ID");
        if(action.equals("hour_apps"))return hourApps(id,p.optLong(1,0),p.optLong(2,0));
        if(action.equals("hour_energy"))return hourlyEnergy(id,p.optLong(1,0),p.optLong(2,0));
        if(action.equals("screen_usage"))return screenUsage(id,p.optLong(1,0),p.optLong(2,0),p.optLong(3,Long.MAX_VALUE),p.optInt(4,0));
        if(action.equals("screen_off_spans"))return screenOffSpans(id);
        if(action.equals("performance"))return performancePoints(id,p.optLong(1,0));
        if(action.equals("delete")) {
            boolean current=session==id;Core.Reading r=current?hardwareReading(header):null;
            db.beginTransaction();try {
            db.execSQL("DELETE FROM sessions WHERE id=?",new Object[]{id});
            if(current){
                session=-1;previous=null;fullGate.reset();depletion.reset();timelineReady=false;rangeId=-1;rangeValues=null;
                candidate=null;candidateAt=-1;latest=r;stableOnline=r.online;state=r.online?1:0;
                if(r.online&&fastEnabled)rememberFast(false);
                if(recordingEnabled&&!stopping){begin(r);sampling.sampled(state,r.elapsed);}
            }
            db.setTransactionSuccessful();}finally{db.endTransaction();}
            if(current)wakeCollector();
            settingsVersion++; // Refresh streamed lists after deleting an ended record, too.
            return o.put("listVersion",settingsVersion).put("session",session);
        }
        if(!action.equals("detail"))throw new IOException("未知操作");
        JSONArray records=rows("SELECT *,ABS(pct_end-pct_start) AS delta_pct FROM sessions WHERE id=?",new String[]{""+id});
        if(records.length()==0)throw new IOException("记录不存在或已删除");
        o.put("record",records.getJSONObject(0));o.put("apps",apps(id));
        long from=Math.max(0,p.optLong(1,0)),to=Math.max(from+1,p.optLong(2,Long.MAX_VALUE));
        to=Math.min(to,records.getJSONObject(0).getLong("end_elapsed")-records.getJSONObject(0).getLong("start_elapsed"));
        int budget=Math.max(240,Math.min(6000,p.optInt(3,1200)));
        boolean charge=records.getJSONObject(0).getString("kind").equals("charge");
        boolean raw=charge||records.getJSONObject(0).optInt("fast_seconds",0)>0;
        if(!charge)o.put("drainSummary",drainSummary(id));
        o.put("raw",raw);
        if(raw)rawPoints(o,id,from,to,p.optLong(4,-1));
        else o.put("points",points(id,from,to,budget));
        signalRanges(o,id,from,to,raw);
        o.put("columns",new JSONArray(new String[]{"x","rm","pct","current","voltage","power","quality","interval_ms","cell1","cell2","temp"}));
        return o;
    }
    JSONObject performancePoints(long id,long cursor)throws Exception{
        JSONObject out=new JSONObject().put("ok",true);long end;
        try(Cursor c=db.rawQuery("SELECT end_elapsed-start_elapsed,active,performance_seconds FROM sessions WHERE id=?",new String[]{""+id})){
            if(!c.moveToFirst())throw new IOException("记录不存在或已删除");end=c.getLong(0);out.put("active",c.getInt(1)!=0).put("intervalMs",c.getLong(2)*1000);
        }
        try(Cursor c=db.rawQuery("SELECT meta FROM performance_profiles WHERE session=?",new String[]{""+id})){
            out.put("meta",c.moveToFirst()?new JSONObject(c.getString(0)):JSONObject.NULL);
        }
        JSONArray points=new JSONArray();long after=Math.max(0,cursor)-1;boolean more=false;
        try(Cursor c=db.rawQuery("SELECT x,data FROM performance_samples WHERE session=? AND x>? ORDER BY x LIMIT 8001",new String[]{""+id,""+after})){
            while(c.moveToNext()){
                if(points.length()==8000){more=true;break;}
                end=Math.max(end,c.getLong(0));points.put(new JSONArray(c.getString(1)));after=c.getLong(0);
            }
        }
        return out.put("points",points).put("end",end).put("cursor",after+1).put("more",more);
    }
    static boolean cutoffAvailable(){
        boolean available=true;
        for(String node:new String[]{"GAUGE_TERM_VOLTAGE","GAUGE_SHUTDOWN_VOLTAGE"})
            for(String part:new String[]{"status","force_active","force_val"})
                if(!new File("/proc/oplus-votable/"+node+"/"+part).canRead()){
                    available=false;FallbackDiagnostics.record("截止电压接口 /proc/oplus-votable/"+node+"/"+part,"节点不存在或不可读");
                }
        return available;
    }
    JSONObject status()throws Exception {
        JSONObject o=new JSONObject().put("ok",true).put("version","2.0").put("resumeProtocol",1).put("fastEnabled",fastEnabled)
            .put("androidSdk",Build.VERSION.SDK_INT).put("androidVersion",Build.VERSION.RELEASE).put("screenEventApi",screenEventApi);
        o.put("powerDisplayEnabled",powerIndicator.state.enabled).put("powerDisplayError",powerIndicator.error());
        o.put("retentionThresholdPct",settings.optInt("deletePct",5)).put("hideThresholdPct",settings.optInt("hidePct",30)).put("settings",settings).put("recordingEnabled",recordingEnabled).put("recordingProblem",recordingProblem);
        o.put("cutoffAvailable",cutoffAvailable()).put("recordingEnabled",recordingEnabled).put("state",state).put("session",session).put("capabilities",capabilities).put("cellCount",cellCount);
        o.put("tailProgress",fullGate.progress(SystemClock.elapsedRealtime()));
        o.put("interactive",screenState.known?sampling.interactive:JSONObject.NULL).put("sampleIntervalMs",interval());
        boolean frameworkHealthy=framework.healthy();
        o.put("frameworkHealthy",frameworkHealthy).put("screenListener",screenListenerReady&&frameworkHealthy).put("screenError",screenError);
        o.put("screenFallback",!screenListenerReady).put("screenStateKnown",screenState.known).put("screenStateSource",screenState.source);
        o.put("usageCheckedAt",usageCheckedAt).put("usageErrorAt",usageErrorAt).put("usageActive",recordingEnabled&&state==0);
        o.put("usageError",usageError).put("error",runtimeError).put("profile","OPLUS BCC19 / "+(cellCount>0?cellCount+"S":"detecting"));
        Core.Reading shown=latest;
        if(sampling.interactive) {
            if(uiReading==null||(latest!=null&&latest.elapsed>uiReading.elapsed))uiReading=latest;
            if(uiReading==null||SystemClock.elapsedRealtime()-uiReading.elapsed>=1000)uiReading=hardwareReading(header);
            shown=uiReading;
        }
        o.put("cellCount",cellCount);if(shown!=null)o.put("reading",readingJson(shown));return o;
    }
    JSONObject live(JSONArray p)throws Exception {
        JSONObject o=new JSONObject().put("ok",true).put("status",status());
        boolean list=p.optInt(2,0)==1||p.optLong(4,0)-1!=session;
        if(list) {
            JSONObject q=new JSONObject().put("action","list").put("args",new JSONArray().put(p.optInt(3,0)).put(0));
            JSONObject history=dispatch(q);o.put("records",history.getJSONArray("records")).put("total",history.getInt("total"));
        }
        if(session>0) {
            JSONArray active=rows("SELECT * FROM sessions WHERE id=?",new String[]{""+session});
            if(active.length()>0)o.put("activeRecord",active.getJSONObject(0));
        }
        long id=p.optLong(0,0);if(id<1)return o;
        JSONArray records=rows("SELECT * FROM sessions WHERE id=?",new String[]{""+id});
        if(records.length()==0)return o.put("record",JSONObject.NULL);
        JSONObject record=records.getJSONObject(0);o.put("record",record);
        long end=record.getLong("end_elapsed")-record.getLong("start_elapsed"),after=p.optLong(1,0);
        rawPoints(o,id,0,end,after);
        boolean changed=o.getJSONArray("points").length()>0;
        if(rangeId==id&&rangeEnd==end&&rangeValues!=null)o.put("ranges",rangeValues).put("rawCount",rangeCount);
        else if(changed)signalRanges(o,id,0,end,record.getString("kind").equals("charge")||record.optInt("fast_seconds",0)>0);
        if(id==session&&record.optString("stats_basis").equals("rm_minute")){
            // One-minute statistics should not re-query packages and rebuild arrays on every 1 s stream tick.
            if(statsCacheId!=id||statsCacheVersion!=statsVersion){
                statsAppsCache=apps(id);statsDrainCache=drainSummary(id);statsCacheId=id;statsCacheVersion=statsVersion;
            }
            o.put("statsVersion",statsVersion).put("apps",statsAppsCache).put("drainSummary",statsDrainCache);
        }else if(record.getString("kind").equals("discharge")&&(changed||record.getInt("active")==0))
            o.put("apps",apps(id)).put("drainSummary",drainSummary(id));
        return o;
    }
    /** Charging is transmitted without decimation. Pages limit IPC size, not data fidelity. */
    void rawPoints(JSONObject out,long id,long from,long to,long after)throws Exception {
        JSONArray result=new JSONArray();boolean more=false;long last=-1;
        try(Cursor c=db.rawQuery("SELECT x,rm,pct,current,voltage,power,quality,interval_ms,cell1,cell2,temp FROM samples WHERE session=? AND x>=? AND x<=? AND x>? ORDER BY x LIMIT 12001",
            new String[]{""+id,""+from,""+to,""+after})) {
            while(c.moveToNext()) {
                if(result.length()==12000){more=true;break;}
                JSONArray row=new JSONArray();
                for(int i=0;i<11;i++)row.put(c.isNull(i)?JSONObject.NULL:(i==0||i==6||i==7?c.getLong(i):c.getDouble(i)));
                result.put(row);last=c.getLong(0);
            }
        }
        out.put("points",result).put("nextAfter",more?last:JSONObject.NULL);
    }
    /** Full-record bounds keep vertical scales stable when the time window changes. */
    void signalRanges(JSONObject out,long id,long from,long to,boolean charge)throws Exception {
        String[] keys={"rm","pct","current","voltage","power","temp"};
        StringBuilder sql=new StringBuilder("SELECT COUNT(*),SUM(CASE WHEN x>=? AND x<=? THEN 1 ELSE 0 END),MAX(x)");
        for(String key:keys) {
            String value=key;
            if(!charge) {
                String allowed="(interval_ms IS NULL OR interval_ms>=20000)";
                if(key.equals("power"))allowed+=" AND power>=0 AND power<=15";
                value="CASE WHEN "+allowed+" THEN "+key+" END";
            }
            sql.append(",MIN(").append(value).append("),MAX(").append(value).append(")");
        }
        sql.append(" FROM samples WHERE session=?");JSONObject ranges=new JSONObject();
        try(Cursor c=db.rawQuery(sql.toString(),new String[]{""+from,""+to,""+id})) {
            c.moveToFirst();out.put("rawCount",c.getLong(0)).put("windowCount",c.getLong(1));
            for(int i=0;i<keys.length;i++) {
                JSONArray bounds=new JSONArray();
                for(int j=0;j<2;j++){int column=3+i*2+j;bounds.put(c.isNull(column)?JSONObject.NULL:c.getDouble(column));}
                ranges.put(keys[i],bounds);
            }
            rangeId=id;rangeEnd=c.isNull(2)?-1:c.getLong(2);rangeCount=c.getLong(0);rangeValues=ranges;
        }
        out.put("ranges",ranges);
    }
    JSONArray rows(String sql,String[] args)throws JSONException {
        JSONArray result=new JSONArray();
        try(Cursor c=db.rawQuery(sql,args)) {
            String[] keys=c.getColumnNames();
            while(c.moveToNext()) {
                JSONObject row=new JSONObject();
                for(int i=0;i<keys.length;i++) {
                    if(c.isNull(i))row.put(keys[i],JSONObject.NULL);
                    else if(c.getType(i)==Cursor.FIELD_TYPE_INTEGER)row.put(keys[i],c.getLong(i));
                    else if(c.getType(i)==Cursor.FIELD_TYPE_FLOAT)row.put(keys[i],c.getDouble(i));
                    else row.put(keys[i],c.getString(i));
                }
                result.put(row);
            }
        }return result;
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
    /** Union of per-bucket extrema across all five signals; endpoints and gaps are retained. */
    JSONArray points(long id,long from,long to,int budget)throws Exception {
        long end;
        try(Cursor c=db.rawQuery("SELECT MAX(x) FROM samples WHERE session=?",new String[]{""+id})){c.moveToFirst();end=c.isNull(0)?1:c.getLong(0);}
        to=Math.min(to,end+1);long width=Math.max(1,(to-from)/Math.max(1,budget/14));
        long queryFrom=from,queryTo=to;
        // Keep the nearest eligible sample outside either edge so a zoom window
        // between two discharge samples can still draw their connecting segment.
        String eligible="(interval_ms IS NULL OR interval_ms>=20000)";
        try(Cursor c=db.rawQuery("SELECT (SELECT x FROM samples WHERE session=? AND x<? AND "+eligible+" ORDER BY x DESC LIMIT 1),(SELECT x FROM samples WHERE session=? AND x>? AND "+eligible+" ORDER BY x LIMIT 1)",
                new String[]{""+id,""+from,""+id,""+to})) {
            c.moveToFirst();if(!c.isNull(0))queryFrom=c.getLong(0);if(!c.isNull(1))queryTo=c.getLong(1);
        }
        JSONArray result=new JSONArray();TreeMap<Long,double[]> bucket=new TreeMap<>();
        double[][] lo=new double[6][],hi=new double[6][];long group=-1;double[] first=null,last=null;
        try(Cursor c=db.rawQuery("SELECT x,rm,pct,current,voltage,power,quality,interval_ms,cell1,cell2,temp FROM samples WHERE session=? AND x>=? AND x<=? ORDER BY x",new String[]{""+id,""+queryFrom,""+queryTo})) {
            while(c.moveToNext()) {
                double[] point=new double[11];for(int i=0;i<11;i++)point[i]=c.isNull(i)?Double.NaN:c.getDouble(i);
                if(Core.finite(point[7])&&point[7]<20000)continue;
                if(point[5]>15||point[5]<0)point[5]=Double.NaN;
                long g=((long)point[0]-from)/width;
                if(first!=null&&g!=group) {flushBucket(result,bucket,first,last,lo,hi);bucket.clear();Arrays.fill(lo,null);Arrays.fill(hi,null);first=last=null;}
                group=g;if(first==null)first=point;
                if(last!=null) {
                    boolean split=false;
                    for(int i=1;i<=5;i++)if(Core.finite(last[i])!=Core.finite(point[i])){split=true;break;}
                    if(Core.finite(last[10])!=Core.finite(point[10]))split=true;
                    if(split){bucket.put((long)last[0],last);bucket.put((long)point[0],point);}
                }
                last=point;
                for(int i=0;i<6;i++){
                    int column=i<5?i+1:10;
                    if(Core.finite(point[column])){
                        if(lo[i]==null||point[column]<lo[i][column])lo[i]=point;
                        if(hi[i]==null||point[column]>hi[i][column])hi[i]=point;
                    }
                }
                if(((int)point[6]&3)!=0)bucket.put((long)point[0],point);
            }
        }
        if(first!=null)flushBucket(result,bucket,first,last,lo,hi);return result;
    }
    static void flushBucket(JSONArray out,TreeMap<Long,double[]> rows,double[] first,double[] last,double[][] lo,double[][] hi)throws JSONException {
        if(first==null)return;
        rows.put((long)first[0],first);rows.put((long)last[0],last);
        for(int i=0;i<lo.length;i++){if(lo[i]!=null)rows.put((long)lo[i][0],lo[i]);if(hi[i]!=null)rows.put((long)hi[i][0],hi[i]);}
        for(double[] p:rows.values()){JSONArray row=new JSONArray();for(double v:p)row.put(Core.finite(v)?v:JSONObject.NULL);out.put(row);}
    }
}
