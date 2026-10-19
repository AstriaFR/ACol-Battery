package cn.astriafr.acobattery.daemon;

import android.app.KeyguardManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
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
    long session=-1, startElapsed, candidateAt=-1;
    Core.Reading candidate;
    int state=-1, capabilities;
    boolean stableOnline;
    boolean recoveryBoundary;
    boolean timelineReady;
    boolean moduleWatch,screenListenerReady;
    String screenError="";
    String screenEventApi="unavailable";
    DisplayManager displays;
    DisplayManager.DisplayListener displayListener;
    String usageError="", runtimeError="";
    long usageCheckedAt,usageErrorAt;
    FrameworkWatch framework;
    String[] header;
    RandomAccessFile lockFile;
    FileLock lock;
    long rangeId=-1,rangeEnd=-1,rangeCount;
    JSONObject rangeValues;
    final PerformanceCollector performance;
    long performanceSession=-1,performanceMetaSession=-1;
    final Core.Cadence performanceCadence=new Core.Cadence(3000);

    public static void main(String[] args) throws Exception {
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
            report.put("nativeCapabilities",initWait());releaseWait();
            report.put("cutoffAvailable",cutoffAvailable());
            report.put("reading",readingJson(readHardware(ctx,null,Core.detectCellCount(read(BAT+"aging_ffc_data")))));
            DisplayManager manager=ctx.getSystemService(DisplayManager.class);
            DisplayManager.DisplayListener listener=new DisplayManager.DisplayListener(){public void onDisplayAdded(int id){}public void onDisplayRemoved(int id){}public void onDisplayChanged(int id){}};
            try{
                if(manager==null)throw new IOException("显示服务不可用");
                report.put("screenEventApi",ScreenEvents.register(manager,listener,new Handler(Looper.getMainLooper())));
                report.put("screenListener",true);
            }catch(Throwable e){report.put("screenListener",false).put("screenError",FriendlyError.message(e));}
            finally{if(manager!=null)manager.unregisterDisplayListener(listener);}
            try { report.put("usageEvents5m",queryUsage(ctx,System.currentTimeMillis()-300000,System.currentTimeMillis()).size()); }
            catch(Throwable e){report.put("usageError",e.toString());}
            System.out.println(report);System.exit(0);
        }
        if(args.length!=2)throw new IllegalArgumentException("module and data directory required");
        System.err.println("BatteryCurves: opening database");
        Main app=new Main(ctx,new File(args[0]),new File(args[1]));
        System.load(args[0]+"/bin/libcurvewait.so");app.capabilities=initWait();
        app.framework=new FrameworkWatch(Main::interruptWait);app.framework.start();
        app.moduleWatch=watchModule(args[1]);app.monitorScreen();

        System.err.println("AColBattery App 1.3.1 Android="+Build.VERSION.RELEASE+" SDK="+Build.VERSION.SDK_INT+" capabilities="+app.capabilities);
        Thread server=new Thread(app::serve,"curve-ipc");server.setDaemon(true);server.start();
        Thread collector=new Thread(()->{
            try {app.collect();System.exit("framework_restarted".equals(app.stopReason)?75:0);}
            catch(Throwable e){if(app.framework!=null)app.framework.close();e.printStackTrace();System.exit(1);}
        },"curve-sampler");collector.start();
        Looper.loop();
    }

    Main(Context ctx,File module,File data) throws Exception {
        this.ctx=ctx;this.module=module;this.data=data;data.mkdirs();
        recordingEnabled=!new File(data,"recording.disabled").exists();
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
        SQLiteDatabase.OpenParams params=new SQLiteDatabase.OpenParams.Builder()
            .setOpenFlags(SQLiteDatabase.CREATE_IF_NECESSARY|SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING)
            .setJournalMode("WAL").setSynchronousMode("NORMAL").build();
        db=SQLiteDatabase.openDatabase(new File(data,"records.db"),params);
        db.execSQL("PRAGMA foreign_keys=ON");
        db.execSQL("CREATE TABLE IF NOT EXISTS sessions(id INTEGER PRIMARY KEY AUTOINCREMENT,kind TEXT NOT NULL,start_wall INTEGER,start_elapsed INTEGER,boot TEXT,end_elapsed INTEGER,pct_start REAL,pct_end REAL,rm_start REAL,rm_end REAL,active INTEGER NOT NULL,reason TEXT,missed INTEGER DEFAULT 0,anomalies INTEGER DEFAULT 0)");
        db.execSQL("CREATE TABLE IF NOT EXISTS samples(session INTEGER NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,x INTEGER NOT NULL,rm REAL,pct REAL,current REAL,voltage REAL,power REAL,quality INTEGER NOT NULL,interval_ms INTEGER,PRIMARY KEY(session,x)) WITHOUT ROWID");
        db.execSQL("CREATE TABLE IF NOT EXISTS apps(session INTEGER NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,pkg TEXT NOT NULL,ms INTEGER NOT NULL,mah REAL NOT NULL,energy_mwh REAL NOT NULL DEFAULT 0,energy_ms INTEGER NOT NULL DEFAULT 0,mah_ms INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(session,pkg)) WITHOUT ROWID");
        db.execSQL("CREATE TABLE IF NOT EXISTS runtime(key TEXT PRIMARY KEY,value TEXT NOT NULL) WITHOUT ROWID");
        db.execSQL("CREATE TABLE IF NOT EXISTS performance_samples(session INTEGER NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,x INTEGER NOT NULL,data TEXT NOT NULL,PRIMARY KEY(session,x)) WITHOUT ROWID");
        db.execSQL("CREATE TABLE IF NOT EXISTS performance_profiles(session INTEGER PRIMARY KEY REFERENCES sessions(id) ON DELETE CASCADE,meta TEXT NOT NULL)");
        loadSettings();
        correctDischargePower();
        ensureSampleColumns();
        fastEnabled=recordingEnabled&&"1".equals(runtimeValue("fast_discharge"));configureSampling(false);
        loadCellProfile();
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
        long[] v={5,30,560,1,300,450,50,60,2,20,10,1,3,3};for(int i=0;i<p.length();i++)v[i]=p.getLong(i);
        // The old game interval controlled both charts. Keep it for both on upgrade.
        if(p.length()>=12&&p.length()<14)v[13]=v[10];
        if(v[0]<0||v[0]>40||v[1]<0||v[1]>80)throw new IOException("删除范围 0–40%，隐藏范围 0–80%，仅限整数");
        if(v[2]<1||v[2]>2000||v[3]<0||v[3]>1)throw new IOException("容量范围 0.01–20.00 Ah");
        if(v[4]<220||v[5]>480||v[4]>=v[5])throw new IOException("电压范围 2.20–4.80 V，下限须小于上限");
        if(v[6]<0||v[7]>80||v[6]>=v[7])throw new IOException("温度范围 −20–60℃，下限须小于上限");
        if(v[8]<1||v[8]>10||v[9]<1||v[9]>60||v[10]<1||v[10]>60||v[11]<0||v[11]>1)throw new IOException("亮屏间隔 1–10 分钟，息屏间隔 1–60 分钟，游戏采样间隔 1–60 秒，仅限整数");
        if(v[12]<1||v[12]>10||p.length()>=13&&!p.get(12).toString().matches("[0-9]+"))throw new IOException("充电间隔 1–10 秒，仅限整数");
        if(v[13]<1||v[13]>60||p.length()==14&&!p.get(13).toString().matches("[0-9]+"))throw new IOException("性能采样间隔 1–60 秒，仅限整数");
        return new JSONObject().put("deletePct",v[0]).put("hidePct",v[1]).put("capacityAh",v[2]/100.0)
            .put("capacityAuto",v[3]==1).put("voltageMin",v[4]/100.0).put("voltageMax",v[5]/100.0)
            .put("tempMin",v[6]-20).put("tempMax",v[7]-20).put("screenOnMinutes",v[8]).put("screenOffMinutes",v[9]).put("fastSeconds",v[10]).put("fastAutoOff",v[11]==1).put("chargeSeconds",v[12]).put("performanceSeconds",v[13]);
    }
    void loadSettings()throws Exception {
        settings=decodeSettings(new JSONArray("[5,30,560,1,300,450,50,60]"));
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
        sampling.screenOffMs=settings.optLong("screenOffMinutes",20)*60000;
        sampling.chargeMs=settings.optLong("chargeSeconds",3)*1000;
        sampling.fastMs=fastEnabled?settings.optLong("fastSeconds",10)*1000:0;
        performanceCadence.configure(settings.optLong("performanceSeconds",3)*1000,reschedule?SystemClock.elapsedRealtime():0);
        if(reschedule&&recordingEnabled){
            long now=SystemClock.elapsedRealtime(),nextInterval=interval();
            if(nextInterval!=oldInterval)sampling.next=Math.max(now,(previous==null?now:previous.elapsed)+nextInterval);
            // A longer interval is intentional, not missing samples. Preserve an
            // already elapsed old plan when shortening without splitting a record.
            sampling.expected=Math.max(sampling.expected,nextInterval);
            if(fastEnabled&&state==0&&session>=0){
                db.execSQL("UPDATE sessions SET fast_seconds=?,performance_seconds=? WHERE id=?",new Object[]{settings.optInt("fastSeconds",10),settings.optInt("performanceSeconds",3),session});
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
        settingsVersion++;interruptWait();
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
        if(count==12)values.put(settings.optLong("chargeSeconds",3));
        if(values.length()==13)values.put(settings.optLong("performanceSeconds",3));
        JSONObject next=decodeSettings(values);int removed=0;
        db.beginTransaction();
        try {
            db.execSQL("INSERT OR REPLACE INTO runtime(key,value) VALUES('module_settings',?)",new Object[]{values.toString()});
            if(p.getInt(count)==1)removed=db.delete("sessions",smallRecordWhere(next),null);
            db.setTransactionSuccessful();
        } finally {db.endTransaction();}
        settings=next;settingsVersion++;rangeId=-1;rangeValues=null;
        configureSampling(true);interruptWait();
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
            Set<String> appColumns=new HashSet<>();
            try(Cursor c=db.rawQuery("PRAGMA table_info(apps)",null)){while(c.moveToNext())appColumns.add(c.getString(1));}
            if(!appColumns.contains("mah_ms")){
                db.execSQL("ALTER TABLE apps ADD COLUMN mah_ms INTEGER NOT NULL DEFAULT 0");
                db.execSQL("UPDATE apps SET mah_ms=ms");
            }
            db.setVersion(11);db.setTransactionSuccessful();
        } finally {db.endTransaction();}
    }
    static String read(String path) {
        try {return new String(Files.readAllBytes(Paths.get(path)),StandardCharsets.UTF_8).trim();}
        catch(Exception e){return "";}
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
    static Core.Reading readGauge(Context ctx,int cells) {
        return readGauge(ctx,cells,true);
    }
    static Core.Reading readGauge(Context ctx,int cells,boolean withTemperature) {
        Core.Reading r=new Core.Reading();r.elapsed=SystemClock.elapsedRealtime();r.wall=System.currentTimeMillis();
        r.cellCount=cells;
        // Reading bcc_parms requests a new ADSP gauge response. GET_BATT_VOL alone
        // can return a stale BCC cache on some drivers.
        Core.applyBcc(r,read(BAT+"bcc_parms"));
        if(!withTemperature)r.temp=Double.NaN;
        if(!Core.finite(r.voltage())||!Core.finite(r.current)||withTemperature&&!Core.finite(r.temp)){
            double voltage=Double.NaN,current=Double.NaN,temp=Double.NaN;
            if(ctx!=null){
                try {
                    Intent battery=ctx.registerReceiver(null,new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
                    if(battery!=null){
                        voltage=battery.getIntExtra(BatteryManager.EXTRA_VOLTAGE,-1);
                        int rawTemp=battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE,Integer.MIN_VALUE);
                        if(withTemperature&&rawTemp!=Integer.MIN_VALUE)temp=rawTemp/10.0;
                    }
                }catch(Exception e){System.err.println("Android battery broadcast unavailable: "+e);}
                try {
                    BatteryManager manager=ctx.getSystemService(BatteryManager.class);
                    if(manager!=null){long raw=manager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);if(raw!=Long.MIN_VALUE)current=raw;}
                }catch(Exception e){System.err.println("Android battery current unavailable: "+e);}
            }
            if(!Core.finite(voltage)||voltage<=0)voltage=num(read(PS+"battery/voltage_now"))/1000;
            if(withTemperature&&!Core.finite(temp))temp=num(read(PS+"battery/temp"))/10;
            Core.applySystem(r,voltage,current,temp);
        }
        return r;
    }
    static Core.Reading readHardware(Context ctx,String[] knownHeader,int cells) {
        Core.Reading r=readGauge(ctx,cells);
        String[] head=knownHeader==null?read(BAT+"battery_log_head").split(",",-1):knownHeader;
        String[] values=read(BAT+"battery_log_content").split(",",-1);
        Map<String,String> m=new HashMap<>();
        if(values.length==head.length)for(int i=0;i<head.length;i++)m.put(head[i].trim(),values[i].trim());
        r.rm=num(read(BAT+"battery_rm"));r.pct=num(read(PS+"battery/capacity"));
        r.fv=field(m,"fv_mv");
        r.online=online();r.full=field(m,"batt_full")==1;r.enabled=field(m,"mmi_chg")==1;
        r.source="cells="+cells+"; voltage="+r.voltageSource+"; current="+r.currentSource+"; root battery_rm + capacity";
        if(!r.valid())r.error="battery_rm / capacity 读取失败";
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
        o.put("voltageSource",r.voltageSource).put("currentSource",r.currentSource);
        o.put("vmax",nullable(r.vmax)).put("vmin",nullable(r.vmin)).put("power",nullable(r.power()));
        o.put("cell1",nullable(r.cell1)).put("cell2",nullable(r.cell2));
        o.put("online",r.online).put("driverFull",r.full).put("enabled",r.enabled).put("fv",nullable(r.fv));
        o.put("temp",nullable(r.temp)).put("source",r.source).put("error",r.error);return o;
    }

    void monitorScreen() {
        try {
            if((capabilities&16)==0)throw new IOException("采样事件通知不可用");
            PowerManager power=ctx.getSystemService(PowerManager.class);
            displays=ctx.getSystemService(DisplayManager.class);
            if(power==null||displays==null)throw new IOException("显示或电源服务不可用");
            displayListener=new DisplayManager.DisplayListener() {
                public void onDisplayAdded(int id){}
                public void onDisplayRemoved(int id){}
                public void onDisplayChanged(int id) {
                    if(id!=Display.DEFAULT_DISPLAY)return;
                    synchronized(mutex) {
                        try{
                            long now=SystemClock.elapsedRealtime();
                            if(sampling.screen(power.isInteractive(),now)){fastScreenOffAt=fastEnabled&&!sampling.interactive?now:-1;interruptWait();}
                        }catch(Throwable e){framework.observe(e);screenError=FrameworkWatch.cause(e).toString();screenListenerReady=false;}
                    }
                }
            };
            Handler handler=new Handler(Looper.getMainLooper());
            screenEventApi=ScreenEvents.register(displays,displayListener,handler);
            sampling.interactive=power.isInteractive();fastScreenOffAt=fastEnabled&&!sampling.interactive?SystemClock.elapsedRealtime():-1;screenListenerReady=true;
        } catch(Throwable e) {
            framework.observe(e);
            sampling.interactive=true;screenError=e.toString();screenListenerReady=false;
        }
    }
    void collect() throws Exception {
        synchronized(mutex) {
            Core.Reading r=hardwareReading(header);latest=r;stableOnline=r.online;
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
                                if(screenSample&&fastEnabled&&state==0){
                                    sampleFastStats(r,true);
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
            } finally {releaseWait();}
        }
        if(displays!=null&&displayListener!=null)try{displays.unregisterDisplayListener(displayListener);}catch(RuntimeException ignored){}
        framework.close();performance.close();
        synchronized(mutex) {
            // Updating code or reconnecting framework proxies suspends ownership,
            // not the charge/discharge session. Completed samples are already durable.
            if(!Core.resumableStop(stopReason))finish(stopReason);
            db.close();lock.release();lockFile.close();
        }
    }
    JSONObject setRecording(boolean enabled)throws Exception {
        if(stopping)throw new IOException("后台正在结束，请稍后重试。");
        if(enabled==recordingEnabled)return new JSONObject().put("ok",true).put("recordingEnabled",enabled).put("settings",settings);
        Core.Reading r=hardwareReading(header);latest=r;
        File flag=new File(data,"recording.disabled");
        if(!enabled){
            if(!flag.exists()&&!flag.createNewFile())throw new IOException("停止状态保存失败。");
            // Seal the old interval before pausing. A transition at the button
            // boundary is split instead of attributing it to the old session.
            if(session>=0&&r.online!=stableOnline){append(candidate!=null?candidate:r);finish(r.online?"plugged":"unplugged");}
            else append(r);
            finish("recording_stopped");recordingEnabled=false;if(fastEnabled)rememberFast(false);
        }else{
            if(flag.exists()&&!flag.delete())throw new IOException("启动状态保存失败。");
            recordingEnabled=true;state=r.online?1:0;stableOnline=r.online;begin(r);sampling.sampled(state,r.elapsed);
        }
        candidate=null;candidateAt=-1;sampling.takeScreenSample();settingsVersion++;interruptWait();
        return new JSONObject().put("ok",true).put("recordingEnabled",recordingEnabled).put("settings",settings);
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
            stopReason=reason;stopping=true;interruptWait();
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
            try(Cursor c=db.rawQuery("SELECT id,boot,kind,start_elapsed,end_elapsed,start_wall,fast_seconds FROM sessions WHERE active=1 ORDER BY id DESC",null)) {
                while(c.moveToNext()) {
                    long id=c.getLong(0);
                    if(session<0&&Core.resumeSession(c.getString(1),boot,c.getString(2),c.getInt(6)>0,
                            r.online,fastEnabled,c.getLong(3),c.getLong(4),r.elapsed)){
                        Core.Reading saved=lastSample(id,c.getLong(3),c.getLong(5));
                        if(saved!=null&&saved.elapsed>=c.getLong(3)&&saved.elapsed<=c.getLong(4)&&saved.elapsed<=r.elapsed){
                            session=id;startElapsed=c.getLong(3);state=r.online?1:0;
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
        startElapsed=r.elapsed;
        try(SQLiteStatement insert=db.compileStatement("INSERT INTO sessions(kind,start_wall,start_elapsed,boot,end_elapsed,pct_start,pct_end,rm_start,rm_end,cell_count,fast_seconds,performance_seconds,stats_basis,active,reason) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,1,'recording')")) {
            Object[] args={state==1?"charge":"discharge",r.wall,r.elapsed,boot,r.elapsed,nullable(r.pct),nullable(r.pct),nullable(r.rm),nullable(r.rm),r.cellCount,state==0&&fastEnabled?settings.optInt("fastSeconds",10):0,state==0&&fastEnabled?settings.optInt("performanceSeconds",3):0,state==0&&fastEnabled?"rm_minute":"rm"};
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
        if(resumed)quality|=9; // Bit 8 retains this break even in the <10 s discharge filter.
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
                new Object[]{session,r.elapsed-startElapsed,nullable(r.rm),nullable(r.pct),state==1||fast?nullable(r.chargingCurrent()):null,nullable(r.voltage()),nullable(recordedPower),quality,state==0&&previous!=null?dt:null,nullable(r.cell1),nullable(r.cell2),state==1?nullable(r.temp):null});
            if(fast)sampleFastStats(r,resumed,resumed);
            else if(state==0&&previous!=null)attribute(previous,r,loss,(quality&3)!=0,energy);
            db.execSQL("UPDATE sessions SET end_elapsed=?,pct_end=?,rm_end=?,missed=missed+?,anomalies=anomalies+? WHERE id=?",
                new Object[]{r.elapsed,nullable(r.pct),nullable(r.rm),missed,(quality&6)!=0?1:0,session});
            // Minute checkpoints suffice for fast accounting. Avoid adding a WAL
            // page write to every second of charging or game graph sampling.
            if(!fast||previous==null||resumed)checkpoint(r);
            db.setTransactionSuccessful();
        } finally {db.endTransaction();}
        updateRanges(r,recordedPower,resumed);
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
            db.execSQL("UPDATE sessions SET end_elapsed=?,pct_end=?,rm_end=? WHERE id=? AND end_elapsed<=?",
                new Object[]{r.elapsed,nullable(r.pct),nullable(r.rm),session,r.elapsed});
            fastStatsPrevious=r;
            while(fastStatsNext<=r.elapsed)fastStatsNext+=60000;
            checkpoint(previous);
            if(ownTransaction)db.setTransactionSuccessful();
        }finally{if(ownTransaction)db.endTransaction();}
    }
    void updateRanges(Core.Reading r,double recordedPower,boolean boundary)throws Exception {
        long x=r.elapsed-startElapsed;if(rangeId!=session||x<=rangeEnd||rangeValues==null)return;
        JSONObject next=new JSONObject(rangeValues.toString());
        String[] keys={"rm","pct","current","voltage","power","temp"};
        double[] values={r.rm,r.pct,state==1?r.chargingCurrent():Double.NaN,r.voltage(),recordedPower,state==1?r.temp:Double.NaN};
        if(state==0&&!fastEnabled) {
            if(!boundary&&previous!=null&&r.elapsed-previous.elapsed<10000)Arrays.fill(values,Double.NaN);
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
        long dt=b.elapsed-a.elapsed;Map<String,Long> split;
        if(gap||Math.abs((b.wall-a.wall)-dt)>2000) {
            split=Collections.singletonMap(Core.OTHER,dt);timelineReady=false;
        } else {
            try {
                usageCheckedAt=System.currentTimeMillis();
                // UsageStats context is user 0. Avoid attributing another user's drain to stale user 0 apps.
                int user=(Integer)Class.forName("android.app.ActivityManager").getMethod("getCurrentUser").invoke(null);
                if(user!=0)throw new IOException("当前用户不是主用户；此区间计入其他");
                if(!timelineReady) {
                    timeline.seed(true,false);
                    for(Core.Event e:queryUsage(ctx,a.wall-86400000,a.wall))timeline.apply(e);
                    timelineReady=true;
                }
                split=timeline.split(a.wall,b.wall,queryUsage(ctx,a.wall,b.wall));usageError="";usageErrorAt=0;
            } catch(Throwable e) {
                Throwable cause=FrameworkWatch.cause(e);String diagnostic=cause.toString();
                if(!diagnostic.equals(usageError)){System.err.println("Application attribution unavailable: "+diagnostic);cause.printStackTrace();}
                usageError=diagnostic;usageErrorAt=System.currentTimeMillis();framework.observe(e);
                timelineReady=false;split=Collections.singletonMap(Core.OTHER,dt);
            }
        }
        addUsage(split,dt,loss,gap?Double.NaN:energy);
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
                        JSONObject req=new JSONObject(boundedLine(in,4096));
                        if("watch".equals(req.optString("action"))){watchFrontend(connection,req.optJSONArray("args"));return;}
                        JSONObject answer;
                        try {synchronized(mutex){answer=dispatch(req);}}
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
        long began=SystemClock.elapsedRealtime(),slowAt=-1;double rm=Double.NaN,temp=Double.NaN;
        JSONObject metadata=null;if(snapshot)synchronized(mutex){metadata=batteryInfoSnapshot();}
        while(SystemClock.elapsedRealtime()-began<300000){
            long started=SystemClock.elapsedRealtime();JSONObject packet;
            synchronized(mutex){
                resolveCellProfile();boolean slow=slowAt<0||started-slowAt>=10000;
                Core.Reading reading=readGauge(ctx,cellCount,slow);
                if(slow){rm=num(read(BAT+"battery_rm"));temp=reading.temp;slowAt=started;}
                reading.rm=rm;reading.temp=temp;
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
            socket.connect(new LocalSocketAddress(SOCKET,LocalSocketAddress.Namespace.ABSTRACT));socket.setSoTimeout(30000);
            socket.getOutputStream().write((req.toString()+"\n").getBytes(StandardCharsets.UTF_8));
            System.out.println(boundedLine(new InputStreamReader(socket.getInputStream(),StandardCharsets.UTF_8),8_000_000));
        } catch(Throwable e) {
            System.out.println("{\"ok\":false,\"error\":\"记录服务未运行，请确认模块已启用并重启手机\"}");
        }
    }
    JSONObject dispatch(JSONObject req)throws Exception {
        String action=req.getString("action");JSONArray p=req.optJSONArray("args");if(p==null)p=new JSONArray();
        JSONObject o=new JSONObject().put("ok",true);
        if(action.equals("set_recording"))return setRecording(p.optInt(0,0)==1);
        if(action.equals("set_fast")){if(p.length()!=1||(p.getInt(0)!=0&&p.getInt(0)!=1))throw new IOException("游戏模式参数无效");return setFast(p.getInt(0)==1,p.getInt(0)==1?"fast_started":"fast_stopped");}
        if(action.equals("shutdown")){requestStop("app_uninstalled".equals(p.optString(0))?"app_uninstalled":"service_updated");return o;}
        if(action.equals("settings"))return o.put("settings",settings).put("recordingEnabled",recordingEnabled).put("fastEnabled",fastEnabled);
        if(action.equals("set_settings"))return saveSettings(p);
        if(action.equals("live"))return live(p);
        if(action.equals("status")) {
            return status();
        }
        if(action.equals("list")) {
            boolean all=p.optString(0,"0").equals("1");int offset=Math.max(0,Math.min(1_000_000,p.optInt(1,0)));
            String filter=all?"":" WHERE active=1 OR fast_seconds>0 OR ABS(pct_end-pct_start)>"+settings.optInt("hidePct",30);
            o.put("records",rows("SELECT *,ABS(pct_end-pct_start) AS delta_pct FROM sessions"+filter+" ORDER BY id DESC LIMIT 100 OFFSET ?",new String[]{""+offset}));
            try(Cursor c=db.rawQuery("SELECT COUNT(*) FROM sessions"+filter,null)){c.moveToFirst();o.put("total",c.getInt(0));}return o.put("listVersion",settingsVersion);
        }
        long id=p.optLong(0,-1);if(id<1)throw new IOException("无效记录 ID");
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
            if(current)interruptWait();
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
        for(String node:new String[]{"GAUGE_TERM_VOLTAGE","GAUGE_SHUTDOWN_VOLTAGE"})
            for(String part:new String[]{"status","force_active","force_val"})
                if(!new File("/proc/oplus-votable/"+node+"/"+part).canRead())return false;
        return true;
    }
    JSONObject status()throws Exception {
        JSONObject o=new JSONObject().put("ok",true).put("version","1.3.1").put("resumeProtocol",1).put("fastEnabled",fastEnabled)
            .put("androidSdk",Build.VERSION.SDK_INT).put("androidVersion",Build.VERSION.RELEASE).put("screenEventApi",screenEventApi);
        o.put("retentionThresholdPct",settings.optInt("deletePct",5)).put("hideThresholdPct",settings.optInt("hidePct",30)).put("settings",settings).put("recordingEnabled",recordingEnabled);
        o.put("cutoffAvailable",cutoffAvailable()).put("recordingEnabled",recordingEnabled).put("state",state).put("session",session).put("capabilities",capabilities).put("cellCount",cellCount);
        o.put("tailProgress",fullGate.progress(SystemClock.elapsedRealtime()));
        o.put("interactive",sampling.interactive).put("sampleIntervalMs",interval());
        boolean frameworkHealthy=framework.healthy();
        o.put("frameworkHealthy",frameworkHealthy).put("screenListener",screenListenerReady&&frameworkHealthy).put("screenError",screenError);
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
                String allowed="(interval_ms IS NULL OR interval_ms>=10000 OR (quality&8)!=0)";
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
                if(pkg.equals(Core.OTHER)||(!pkg.equals(Core.OFF)&&ms<=120000)){otherMs+=ms;otherMah+=mah;otherEnergy+=energy;otherEnergyMs+=energyMs;otherMahMs+=mahMs;continue;}
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
        String eligible="(interval_ms IS NULL OR interval_ms>=10000 OR (quality&8)!=0)";
        try(Cursor c=db.rawQuery("SELECT (SELECT x FROM samples WHERE session=? AND x<? AND "+eligible+" ORDER BY x DESC LIMIT 1),(SELECT x FROM samples WHERE session=? AND x>? AND "+eligible+" ORDER BY x LIMIT 1)",
                new String[]{""+id,""+from,""+id,""+to})) {
            c.moveToFirst();if(!c.isNull(0))queryFrom=c.getLong(0);if(!c.isNull(1))queryTo=c.getLong(1);
        }
        JSONArray result=new JSONArray();TreeMap<Long,double[]> bucket=new TreeMap<>();
        double[][] lo=new double[5][],hi=new double[5][];long group=-1;double[] first=null,last=null;
        try(Cursor c=db.rawQuery("SELECT x,rm,pct,current,voltage,power,quality,interval_ms,cell1,cell2,temp FROM samples WHERE session=? AND x>=? AND x<=? ORDER BY x",new String[]{""+id,""+queryFrom,""+queryTo})) {
            while(c.moveToNext()) {
                double[] point=new double[11];for(int i=0;i<11;i++)point[i]=c.isNull(i)?Double.NaN:c.getDouble(i);
                if(Core.finite(point[7])&&point[7]<10000&&((int)point[6]&8)==0)continue;
                if(point[5]>15||point[5]<0)point[5]=Double.NaN;
                long g=((long)point[0]-from)/width;
                if(first!=null&&g!=group) {flushBucket(result,bucket,first,last,lo,hi);bucket.clear();Arrays.fill(lo,null);Arrays.fill(hi,null);first=last=null;}
                group=g;if(first==null)first=point;
                if(last!=null)for(int i=1;i<=5;i++)if(Core.finite(last[i])!=Core.finite(point[i])) {
                    bucket.put((long)last[0],last);bucket.put((long)point[0],point);break;
                }
                last=point;
                for(int i=0;i<5;i++)if(Core.finite(point[i+1])){
                    if(lo[i]==null||point[i+1]<lo[i][i+1])lo[i]=point;
                    if(hi[i]==null||point[i+1]>hi[i][i+1])hi[i]=point;
                }
                if(((int)point[6]&3)!=0)bucket.put((long)point[0],point);
            }
        }
        if(first!=null)flushBucket(result,bucket,first,last,lo,hi);return result;
    }
    static void flushBucket(JSONArray out,TreeMap<Long,double[]> rows,double[] first,double[] last,double[][] lo,double[][] hi)throws JSONException {
        if(first==null)return;
        rows.put((long)first[0],first);rows.put((long)last[0],last);
        for(int i=0;i<5;i++){if(lo[i]!=null)rows.put((long)lo[i][0],lo[i]);if(hi[i]!=null)rows.put((long)hi[i][0],hi[i]);}
        for(double[] p:rows.values()){JSONArray row=new JSONArray();for(double v:p)row.put(Core.finite(v)?v:JSONObject.NULL);out.put(row);}
    }
}
