package cn.astriafr.acobattery.daemon;
import android.content.*;
import android.os.*;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.Function;
import cn.astriafr.acobattery.common.*;
/** Battery interfaces, fallback sources and battery-information snapshots. */
final class BatteryService {
    final Context ctx;
    private final Integer designCapacityMah;
    BatteryService(Context ctx){
        this.ctx=ctx;
        designCapacityMah=readDesignCapacityMah(read(BAT+"design_capacity"),new File(PS));
    }

    Integer designCapacityMah(){return designCapacityMah;}

    static final String BAT="/sys/class/oplus_chg/battery/";
    static final String PS="/sys/class/power_supply/";
    static final PowerInputs POWER_INPUTS=new PowerInputs(new File(PS),path->new String(Files.readAllBytes(Paths.get(path)),StandardCharsets.UTF_8));
    static final SystemPower SYSTEM_POWER=new SystemPower();
    static volatile String powerInputDiagnostics="";
    private static File alternateSupply;
    private static long nextBatteryScan;
    static String read(String path) {
        try {return new String(Files.readAllBytes(Paths.get(path)),StandardCharsets.UTF_8).trim();}
        catch(Exception e){return "";}
    }
    static double num(String s) {try{return Double.parseDouble(s.trim());}catch(Exception e){return Double.NaN;}}
    static Double nullable(double v){return Core.finite(v)?v:null;}
    private static boolean internalBattery(File supply){
        return "Battery".equalsIgnoreCase(read(new File(supply,"type").getPath()))
            &&!"Device".equalsIgnoreCase(read(new File(supply,"scope").getPath()))
            &&!"0".equals(read(new File(supply,"present").getPath()));
    }
    static Integer readDesignCapacityMah(String vendorValue,File powerSupplies){
        Integer vendorMah=integer(vendorValue);
        if(vendorMah!=null&&vendorMah>=1000&&vendorMah<=20000)return vendorMah;

        File primary=new File(powerSupplies,"battery");
        Integer standardMah=internalBattery(primary)?readStandardDesignCapacityMah(primary):null;
        if(standardMah!=null)return standardMah;
        try{
            return readStandardDesignCapacityMah(discoverAlternateBattery(powerSupplies));
        }catch(SecurityException ignored){return null;}
    }
    private static Integer readStandardDesignCapacityMah(File supply){
        if(supply==null)return null;
        Integer microampHours=integer(read(new File(supply,"charge_full_design").getPath()));
        if(microampHours==null||microampHours<1_000_000||microampHours>20_000_000)return null;
        return (microampHours+500)/1000;
    }
    static File discoverAlternateBattery(File root){
        File[] entries=root.listFiles();
        if(entries==null)return null;
        File found=null;
        boolean ambiguous=false;
        for(File entry:entries){
            String name=entry.getName();
            if(name.equals("battery")||!internalBattery(entry))continue;
            if(name.equals("main_battery"))return entry;
            if(found==null)found=entry;
            else ambiguous=true;
        }
        return ambiguous?null:found;
    }
    static int detectCellCount(){
        return detectCellCount(read(BAT+"aging_ffc_data"),new File(PS),new File(BAT).isDirectory());
    }
    static int detectCellCount(String vendor,File root,boolean vendorGauge){
        int count=Core.detectCellCount(vendor);
        if(count!=0)return count;
        File primary=new File(root,"battery"),alternate=discoverAlternateBattery(root);
        if(!internalBattery(primary))primary=null;
        int first=reportedCells(primary),second=reportedCells(alternate);
        if(first>0||second>0){
            if(first>2||second>2||first>0&&second>0&&first!=second)return 0;
            return Math.max(first,second);
        }
        first=designCells(primary,vendorGauge);second=designCells(alternate,vendorGauge);
        return first!=0&&second!=0&&first!=second?0:Math.max(first,second);
    }
    private static int reportedCells(File supply){
        Integer count=supply==null?null:integer(read(new File(supply,"number_of_serial_cells").getPath()));
        return count==null?0:Math.max(0,count);
    }
    private static int designCells(File supply,boolean vendorGauge){
        if(supply==null)return 0;
        double min=num(read(new File(supply,"voltage_min_design").getPath()))/1000;
        double max=num(read(new File(supply,"voltage_max_design").getPath()))/1000;
        double now=num(read(new File(supply,"voltage_now").getPath()))/1000;
        // A per-cell voltage can also be reported by a dual-cell vendor gauge.
        // Only infer from a consistent set of pack properties on the same supply.
        if(now<min||now>max||!Core.finite(now))return 0;
        if(!vendorGauge&&min>=2200&&min<=3900&&max>=4000&&max<=4800)return 1;
        if(min>=4400&&min<=7800&&max>=8000&&max<=9600)return 2;
        return 0;
    }
    static synchronized File alternateBattery(){
        long now=SystemClock.elapsedRealtime();
        if(nextBatteryScan==0||now>=nextBatteryScan){
            alternateSupply=discoverAlternateBattery(new File(PS));
            nextBatteryScan=now+60000;
        }
        return alternateSupply;
    }
    static String alternateReading(String node){
        File supply=alternateBattery();
        return supply==null?"":read(new File(supply,node).getPath());
    }
    static long batteryProperty(Context ctx,int property){
        if(ctx==null)return Long.MIN_VALUE;
        try{
            BatteryManager manager=ctx.getSystemService(BatteryManager.class);
            return manager==null?Long.MIN_VALUE:manager.getLongProperty(property);
        }catch(Exception ignored){return Long.MIN_VALUE;}
    }
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
        powerInputDiagnostics=result.known?"":result.diagnostics;
        if(!result.known)FallbackDiagnostics.record("充电连接状态不可用",result.diagnostics);
        return result;
    }
    static Core.Reading readHardware(String[] knownHeader) {
        return readHardware(null,knownHeader,detectCellCount());
    }
    static Core.Reading readGauge(Context ctx,int cells) {
        return readGauge(ctx,cells,true);
    }
    static Core.Reading readGauge(Context ctx,int cells,boolean withTemperature) {
        Core.Reading r=new Core.Reading();r.elapsed=SystemClock.elapsedRealtime();r.wall=System.currentTimeMillis();
        r.cellCount=cells;r.onlineKnown=false;
        // Reading bcc_parms requests a new ADSP gauge response. GET_BATT_VOL alone
        // can return a stale BCC cache on some drivers.
        Core.applyBcc(r,read(BAT+"bcc_parms"));
        if(!withTemperature)r.temp=Double.NaN;
        if(!Core.finite(r.displayVoltage())||!Core.finite(r.current)||withTemperature&&!Core.finite(r.temp)){
            double voltage=Double.NaN,current=Double.NaN,temp=Double.NaN;
            if(ctx!=null){
                try {
                    Intent battery=ctx.registerReceiver(null,new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
                    if(battery!=null){
                        voltage=battery.getIntExtra(BatteryManager.EXTRA_VOLTAGE,-1);
                        int rawTemp=battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE,Integer.MIN_VALUE);
                        if(withTemperature&&rawTemp!=Integer.MIN_VALUE)temp=rawTemp/10.0;
                    }
                }catch(Exception ignored){}
                if(!Core.finite(r.current)){
                    long raw=batteryProperty(ctx,BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
                    if(raw!=Long.MIN_VALUE)current=raw;
                }
            }
            applyGaugeFallback(r,voltage,current,temp,withTemperature,
                node->read(PS+"battery/"+node),BatteryService::alternateReading);
            List<String> missing=new ArrayList<>(3);
            if(!Core.finite(r.displayVoltage()))missing.add("电压");
            if(!Core.finite(r.current))missing.add("电流");
            if(withTemperature&&!Core.finite(r.temp))missing.add("温度");
            if(!missing.isEmpty())FallbackDiagnostics.record("电池读数缺失",String.join("、",missing)+"在可用接口中均未取得有效读数。");
        }
        return r;
    }
    static void applyGaugeFallback(Core.Reading r,double voltage,double current,double temp,boolean withTemperature,
            Function<String,String> fixed,Function<String,String> alternate){
        if(!Core.finite(r.displayVoltage())&&!Core.finite(Core.systemVoltage(r.cellCount,voltage))){
            voltage=num(fixed.apply("voltage_now"))/1000;
            if(!Core.finite(Core.systemVoltage(r.cellCount,voltage)))voltage=num(alternate.apply("voltage_now"))/1000;
        }
        if(withTemperature&&!Core.batteryTemperature(r.temp)&&!Core.batteryTemperature(temp)){
            temp=num(fixed.apply("temp"))/10;
            if(!Core.batteryTemperature(temp))temp=num(alternate.apply("temp"))/10;
        }
        Core.applySystem(r,voltage,current,withTemperature?temp:Double.NaN);
    }
    static void readRemainingCapacity(Context ctx,Core.Reading r) {
        r.rm=num(read(BAT+"battery_rm"));r.rmSource="";
        if(Core.finite(r.rm)&&r.rm>=0){r.rmSource="battery_rm";return;}
        r.rm=Double.NaN;
        long counter=batteryProperty(ctx,BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER);
        r.rm=Core.remainingMah(counter);
        if(Core.finite(r.rm)){r.rmSource="android";return;}
        Integer charge=integer(read(PS+"battery/charge_now"));
        if(charge==null||charge<0)charge=integer(alternateReading("charge_now"));
        if(charge!=null)r.rm=Core.remainingMah(charge);
        if(Core.finite(r.rm))r.rmSource="charge_now";
        else FallbackDiagnostics.record("剩余容量缺失","battery_rm、CHARGE_COUNTER 和 charge_now 均未提供有效读数。");
    }
    static void readPercentage(Context ctx,Core.Reading r){
        r.pct=num(read(PS+"battery/capacity"));
        if(Core.validPercentage(r.pct)){r.pctSource="capacity";return;}
        r.pct=batteryProperty(ctx,BatteryManager.BATTERY_PROPERTY_CAPACITY);
        if(Core.validPercentage(r.pct)){r.pctSource="android";return;}
        if(ctx!=null)try{
            Intent battery=ctx.registerReceiver(null,new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if(battery!=null){
                r.pct=Core.levelPercentage(battery.getIntExtra(BatteryManager.EXTRA_LEVEL,-1),battery.getIntExtra(BatteryManager.EXTRA_SCALE,-1));
                if(Core.validPercentage(r.pct)){r.pctSource="battery_broadcast";return;}
            }
        }catch(Exception ignored){}
        r.pct=num(alternateReading("capacity"));
        if(Core.validPercentage(r.pct)){r.pctSource="alternate_capacity";return;}
        r.pct=Double.NaN;
        FallbackDiagnostics.record("系统电量校验","电池节点和系统接口均未提供有效电量。");
    }
    static Core.Reading readHardware(Context ctx,String[] knownHeader,int cells) {
        return readHardware(ctx,knownHeader,cells,null);
    }
    static Core.Reading readHardware(Context ctx,String[] knownHeader,int cells,java.util.function.Consumer<String[]> rememberHeader) {
        Core.Reading r=readGauge(ctx,cells);
        readRemainingCapacity(ctx,r);readPercentage(ctx,r);
        if(cells==0)FallbackDiagnostics.record("电芯数量待确认","厂商和标准电池节点均未能确认电芯数量。");
        PowerInputs.Result power=online(ctx);r.online=power.online;r.onlineKnown=power.known;r.onlineSource=power.source;
        String[] chargeHeader=ChargingLog.apply(r,knownHeader,BAT,path->new String(Files.readAllBytes(Paths.get(path)),StandardCharsets.UTF_8));
        if(rememberHeader!=null&&chargeHeader!=null)rememberHeader.accept(chargeHeader);
        r.source="cells="+cells+"; voltage="+r.voltageSource+"; current="+r.currentSource+"; remaining="+r.rmSource+"; capacity="+r.pctSource;
        if(!r.onlineKnown)r.error="充电连接状态暂不可用，记录采样等待重试";
        else if(!r.valid())r.error="剩余容量 / 系统电量读取失败";
        else if(!Core.finite(r.displayVoltage())||!Core.finite(r.current))r.error="电压或电流读取失败，保留缺口";
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
        Integer cutoff=BatteryDetails.termVoltage(read("/proc/oplus-votable/GAUGE_TERM_VOLTAGE/status")),design=designCapacityMah();
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
        o.put("current",jsonValue(nullable(r.chargingCurrent()))).put("voltage",jsonValue(nullable(r.displayVoltage())));
        o.put("cellCount",r.cellCount).put("displayCurrent",nullable(r.compensatedCurrent()));
        o.put("voltageSource",r.voltageSource).put("currentSource",r.currentSource).put("rmSource",r.rmSource).put("pctSource",r.pctSource).put("onlineSource",r.onlineSource);
        o.put("vmax",nullable(r.vmax)).put("vmin",nullable(r.vmin)).put("power",nullable(r.power()));
        o.put("cell1",nullable(r.cell1)).put("cell2",nullable(r.cell2));
        o.put("onlineKnown",r.onlineKnown).put("online",r.onlineKnown?r.online:JSONObject.NULL).put("driverFull",r.full).put("enabled",r.enabled).put("fv",nullable(r.fv));
        o.put("temp",jsonValue(nullable(r.temp))).put("source",r.source).put("error",r.error);return o;
    }

    static boolean highDesignCapacity(Integer designCapacityMah){
        return designCapacityMah!=null&&designCapacityMah>=7400;
    }

    static int cutoffMinMv(Integer designCapacityMah){return highDesignCapacity(designCapacityMah)?2500:2600;}

    static boolean cutoffAvailable(){
        for(String node:new String[]{"GAUGE_TERM_VOLTAGE","GAUGE_SHUTDOWN_VOLTAGE"})
            for(String part:new String[]{"status","force_active","force_val"})
                if(!new File("/proc/oplus-votable/"+node+"/"+part).canRead())return false;
        return true;
    }
}
