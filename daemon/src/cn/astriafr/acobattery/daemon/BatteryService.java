package cn.astriafr.acobattery.daemon;
import android.content.*;
import android.os.*;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import cn.astriafr.acobattery.common.*;
/** Battery interfaces, fallback sources and battery-information snapshots. */
final class BatteryService {
    final Context ctx;
    BatteryService(Context ctx){this.ctx=ctx;}
    static final String BAT="/sys/class/oplus_chg/battery/";
    static final String PS="/sys/class/power_supply/";
    static final PowerInputs POWER_INPUTS=new PowerInputs(new File(PS),path->new String(Files.readAllBytes(Paths.get(path)),StandardCharsets.UTF_8));
    static final SystemPower SYSTEM_POWER=new SystemPower();
    static volatile String powerInputDiagnostics="";
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
    static PowerInputs.Result online(Context ctx) {
        PowerInputs.Result result=POWER_INPUTS.resolve(()->SYSTEM_POWER.read(SystemClock.elapsedRealtime(),()->{
            if(ctx==null)return null;
            Intent battery=ctx.registerReceiver(null,new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if(battery==null)throw new IOException("ACTION_BATTERY_CHANGED 返回空广播");
            if(!battery.hasExtra(BatteryManager.EXTRA_PLUGGED))throw new IOException("电池广播缺少 plugged 字段");
            return String.valueOf(battery.getIntExtra(BatteryManager.EXTRA_PLUGGED,-1));
        },()->{
            java.lang.Process process=new ProcessBuilder("/system/bin/dumpsys","-t","1","battery").redirectErrorStream(true).start();
            ProcessResponse output=ProcessResponse.read(process,System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(2),32768);
            if(output.code!=0)throw new IOException("dumpsys battery 退出码="+output.code+"；"+output.text.substring(0,Math.min(512,output.text.length())));
            return output.text;
        }));
        powerInputDiagnostics=result.diagnostics;
        if(!result.known)FallbackDiagnostics.record("充电连接状态不可用",result.diagnostics);
        else if(result.systemFallback)FallbackDiagnostics.record("充电连接状态回退 "+result.source,result.diagnostics+"已使用 "+result.source+" 的供电连接状态。");
        return result;
    }
    static Core.Reading readHardware(String[] knownHeader) {
        return readHardware(null,knownHeader,Core.detectCellCount(read(BAT+"aging_ffc_data")));
    }
    static Core.Reading readGauge(Context ctx,int cells) {
        return readGauge(ctx,cells,true);
    }
    static Core.Reading readGauge(Context ctx,int cells,boolean withTemperature) {
        Core.Reading r=new Core.Reading();r.elapsed=SystemClock.elapsedRealtime();r.wall=System.currentTimeMillis();
        r.cellCount=cells;r.onlineKnown=false;
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
        return readHardware(ctx,knownHeader,cells,null);
    }
    static Core.Reading readHardware(Context ctx,String[] knownHeader,int cells,java.util.function.Consumer<String[]> rememberHeader) {
        Core.Reading r=readGauge(ctx,cells);
        readRemainingCapacity(ctx,r);r.pct=num(diagnosticRead(PS+"battery/capacity"));
        if(!Core.finite(r.pct)||r.pct<0||r.pct>100)FallbackDiagnostics.record("系统电量校验","capacity 未提供 0–100 范围内的电量");
        if(cells==0)FallbackDiagnostics.record("电芯数量识别","aging_ffc_data="+diagnosticRead(BAT+"aging_ffc_data"));
        PowerInputs.Result power=online(ctx);r.online=power.online;r.onlineKnown=power.known;r.onlineSource=power.source;
        String[] chargeHeader=ChargingLog.apply(r,knownHeader,BAT,path->new String(Files.readAllBytes(Paths.get(path)),StandardCharsets.UTF_8));
        if(rememberHeader!=null&&chargeHeader!=null)rememberHeader.accept(chargeHeader);
        r.source="cells="+cells+"; voltage="+r.voltageSource+"; current="+r.currentSource+"; remaining="+r.rmSource+"; root capacity";
        if(!r.onlineKnown)r.error="充电连接状态暂不可用，记录采样等待重试";
        else if(!r.valid())r.error="剩余容量 / 系统电量读取失败";
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
        o.put("voltageSource",r.voltageSource).put("currentSource",r.currentSource).put("rmSource",r.rmSource).put("onlineSource",r.onlineSource);
        o.put("vmax",nullable(r.vmax)).put("vmin",nullable(r.vmin)).put("power",nullable(r.power()));
        o.put("cell1",nullable(r.cell1)).put("cell2",nullable(r.cell2));
        o.put("onlineKnown",r.onlineKnown).put("online",r.onlineKnown?r.online:JSONObject.NULL).put("driverFull",r.full).put("enabled",r.enabled).put("fv",nullable(r.fv));
        o.put("temp",nullable(r.temp)).put("source",r.source).put("error",r.error);return o;
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
}
