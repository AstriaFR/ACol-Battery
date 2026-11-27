package cn.astriafr.acobattery.daemon;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

final class OplusVotes {
    private static final Path DEVICE_TREE=Paths.get("/proc/device-tree/soc");
    private static final String WIRED_DT="oplus,wired";
    private static final String VOOC_DT="oplus,vooc";
    private static final String PPS_DT="oplus,pps_charge";
    private static final String UFCS_DT="oplus,ufcs_charge";
    private static final String[] WIRED_COOL_DOWN_TYPES={"normal","pdqc","vooc"};
    private static final int WIRED_COOL_DOWN_TABLE_LIMIT=31;
    private static final int DISCHARGE_VOLTAGE_MIN_MV=2500,DISCHARGE_VOLTAGE_MAX_MV=3500;
    private static final int FLOAT_VOLTAGE_MAX_MV=4550;
    private static final Range FLOAT_VOLTAGE_RANGE=new Range(4200,FLOAT_VOLTAGE_MAX_MV);
    private static final Path DEC_CV=Paths.get("/proc/charger/dec_cv_cc");
    private static final Path DEC_DELTA=Paths.get("/sys/class/oplus_chg/common/dec_delta");
    private static final Path CHARGING_ENABLE=Paths.get("/sys/class/oplus_chg/battery/mmi_charging_enable");
    private static final Path BCC_CURRENT=Paths.get(BatteryService.BAT,"bcc_current");
    private static final Path PLC_CONTROL=Paths.get("/sys/class/oplus_chg/common/plc");
    private static final Pattern EFFECTIVE=Pattern.compile("^\\w+: effective=(\\S+) .* v=(-?\\d{1,10})[ \\t]*$",Pattern.MULTILINE);
    private static final Pattern MIN_CURRENT=Pattern.compile("^\\w+: effective=(\\S+) type=Min v=(-?\\d{1,9})[ \\t]*$",Pattern.MULTILINE);
    private static final Pattern CURRENT_CLIENT=Pattern.compile("^\\w+: ([^:\\r\\n]+):[ \\t]*en=([01]) v=(-?\\d{1,9})[ \\t]*$",Pattern.MULTILINE);
    private static final Pattern FV_DEC_VALUE=Pattern.compile("\\bfv_dec=(\\d{1,5})(?:,|$)");
    private static final Pattern DEC_DELTA_VALUE=Pattern.compile("^delta=(-?\\d{1,5})$");
    private static final Pattern PLC_STATUS=Pattern.compile("^status=([0-6])$");
    private static final int PLC_UNSUPPORTED=0,PLC_NOT_ALLOWED=1,PLC_ENABLED=3,PLC_WAIT=4,PLC_UNKNOWN=6;
    enum Node {
        WIRED_FCC(5000), WIRED_ICL(3000), PPS_CURR(8000), UFCS_CURR(15000), VOOC_CURR(15000),
        GAUGE_UPDATE(20000), GAUGE_TERM_VOLTAGE(DISCHARGE_VOLTAGE_MAX_MV), GAUGE_SHUTDOWN_VOLTAGE(DISCHARGE_VOLTAGE_MAX_MV),
        FV_MAX(FLOAT_VOLTAGE_MAX_MV), FV_MIN(FLOAT_VOLTAGE_MAX_MV), FV_DEC, DEC_DELTA(100), COOL_DOWN(30),
        CHG_DISABLE, WIRED_CHARGE_SUSPEND, PLC, UFCS_DISABLE(1), TARGET_TERM_VOLTAGE, TARGET_SHUTDOWN_VOLTAGE;

        final Path directory=Paths.get("/proc/oplus-votable",name());
        final int maximum;
        Node(){this(0);}
        Node(int maximum){this.maximum=maximum;}
        boolean floatingVoltage(){return this==FV_MAX||this==FV_MIN;}
        boolean dischargeVoltage(){return this==GAUGE_TERM_VOLTAGE||this==GAUGE_SHUTDOWN_VOLTAGE;}
        boolean chargingMode(){return this==CHG_DISABLE||this==WIRED_CHARGE_SUSPEND||this==PLC;}
    }

    private static final Node[] CHARGING_MODES={Node.CHG_DISABLE,Node.WIRED_CHARGE_SUSPEND,Node.PLC};
    private OplusVotes(){}

    static Node editable(String name){
        for(Node node:Node.values())if(node.maximum>0&&node.name().equals(name))return node;
        throw new IllegalArgumentException("投票节点无效。");
    }

    static synchronized JSONObject snapshot()throws JSONException {
        JSONObject result=new JSONObject();
        for(Node node:Node.values()){
            try{
                JSONObject item;
                synchronized(node){item=read(node);}
                Range range=node.dischargeVoltage()?new Range(DISCHARGE_VOLTAGE_MIN_MV,node.maximum)
                    :node.floatingVoltage()?FLOAT_VOLTAGE_RANGE:node==Node.COOL_DOWN?new Range(0,node.maximum):null;
                if(range!=null)item.put("min",range.min).put("max",range.max);
                result.put(node.name(),item);
            }
            catch(NoSuchFileException missing){result.put(node.name(),new JSONObject().put("missing",true));}
            catch(IOException unreadable){result.put(node.name(),new JSONObject());}
        }
        return result;
    }

    private static JSONObject read(Node node)throws IOException,JSONException {
        JSONObject result=new JSONObject();
        if(node.chargingMode()){
            Boolean enabled=modeEnabled(node);
            if(enabled==null)return result.put("missing",true);
            return result.put("forced",enabled);
        }
        if(node==Node.FV_DEC){
            if(Files.notExists(DEC_CV))return result.put("missing",true);
            return result.put("effective",readNumber(DEC_CV,FV_DEC_VALUE));
        }
        if(node==Node.DEC_DELTA){
            if(Files.notExists(DEC_DELTA))return result.put("missing",true);
            int value=readNumber(DEC_DELTA,DEC_DELTA_VALUE);
            return result.put("effective",value).put("forced",value!=0).put("forceValue",value)
                .put("min",-node.maximum).put("max",node.maximum);
        }
        Path status=node.directory.resolve("status");
        Path activePath=node.directory.resolve("force_active"),valuePath=node.directory.resolve("force_val");
        if(Files.notExists(status))return result.put("missing",true);
        if(node.maximum>0&&(Files.notExists(activePath)||Files.notExists(valuePath)))result.put("controlMissing",true);
        Long effective=effectiveValue(status);
        if(effective!=null)result.put("effective",effective);
        try{
            String active=read(activePath);
            if(active.equals("0")||active.equals("1")){
                long value=Long.parseLong(read(valuePath));
                if(value>=0)result.put("forced",active.equals("1")).put("forceValue",value);
            }
        }catch(IOException|NumberFormatException ignored){}
        return result;
    }

    static Long readEffectiveValue(Node node){
        try{return effectiveValue(node.directory.resolve("status"));}
        catch(IOException unreadable){return null;}
    }

    static int readForceValue(Node node)throws IOException {
        Path directory=node.directory;
        String active=read(directory.resolve("force_active"));
        if("0".equals(active))return -1;
        Integer level=BatteryService.integer(read(directory.resolve("force_val")));
        if(!"1".equals(active)||level==null||level<0)throw new IOException("无法读取 "+node.name()+" 覆盖状态。");
        return level;
    }

    static void releaseLegacyForce(Node node,int applied,int pending)throws IOException {
        if(Files.notExists(node.directory.resolve("force_active")))return;
        int force=readForceValue(node);
        if(force==applied||force==pending)apply(node,null,null);
    }

    static void releaseLegacyBcc(int applied,int pending,int alternate)throws IOException {
        if(applied==0&&pending==0&&alternate==0)return;
        boolean owned=false;
        for(Node node:new Node[]{Node.UFCS_CURR,Node.PPS_CURR,Node.VOOC_CURR}){
            Path status=node.directory.resolve("status");
            if(Files.notExists(status))continue;
            Matcher client=CURRENT_CLIENT.matcher(read(status));
            while(client.find()){
                if(!"BCC_VOTER".equals(client.group(1))||!"1".equals(client.group(2)))continue;
                int current=Integer.parseInt(client.group(3));
                if(current<=0)continue;
                if(current!=applied&&current!=pending&&current!=alternate)return;
                owned=true;
            }
        }
        if(owned)write(BCC_CURRENT,"0");
    }

    static void releaseLegacyPause()throws IOException {
        if(!Files.notExists(CHARGING_ENABLE)&&!readSwitch(CHARGING_ENABLE))writePhysicalPause(false);
    }

    private static void writePhysicalPause(boolean pause)throws IOException {
        write(CHARGING_ENABLE,pause?"0":"1");
        if(readSwitch(CHARGING_ENABLE)==pause)throw new IOException("充电暂停状态读回不一致。");
    }

    private static Long effectiveValue(Path status)throws IOException {
        Matcher vote=EFFECTIVE.matcher(read(status));
        if(!vote.find())return null;
        long value=Long.parseLong(vote.group(2));
        return value>=0?value:null;
    }

    static synchronized void apply(Node node,Integer value,GaugeUpdate gaugeUpdate)throws IOException {
        if(value!=null){
            Range range=node.dischargeVoltage()?new Range(DISCHARGE_VOLTAGE_MIN_MV,node.maximum):
                node.floatingVoltage()?FLOAT_VOLTAGE_RANGE:new Range(node==Node.DEC_DELTA?-node.maximum:0,node.maximum);
            if(value<range.min||value>range.max||node==Node.GAUGE_UPDATE&&(value<500||value%100!=0))
                throw new IOException("设置值超出范围。");
        }
        if(node==Node.DEC_DELTA){
            int target=value==null?0:value;
            write(DEC_DELTA,Integer.toString(target));
            if(readNumber(DEC_DELTA,DEC_DELTA_VALUE)!=target)throw new IOException("浮充降压修正读回不一致。");
            return;
        }
        if(node==Node.GAUGE_UPDATE){gaugeUpdate.applyManually(value,()->applyForce(node,value));return;}
        synchronized(node){applyForce(node,value);}
    }

    static synchronized void restoreIfChanged(Node node,int value,GaugeUpdate gaugeUpdate)throws IOException {
        synchronized(node){
            if(node==Node.DEC_DELTA){
                if(readNumber(DEC_DELTA,DEC_DELTA_VALUE)!=value)apply(node,value,gaugeUpdate);
                return;
            }
            String target=Integer.toString(value);
            if(read(node.directory.resolve("force_active")).equals("1")&&
                read(node.directory.resolve("force_val")).equals(target)){
                try{
                    verifyForce(node,true,target);
                    return;
                }catch(IOException changed){/* Reapply when the effective vote differs. */}
            }
            apply(node,value,gaugeUpdate);
        }
    }

    private static void applyForce(Node node,Integer value)throws IOException {
        boolean enable=value!=null&&(value>0||node==Node.COOL_DOWN);
        boolean restoringShutdown=!enable&&node==Node.GAUGE_SHUTDOWN_VOLTAGE;
        Path active=node.directory.resolve("force_active"), setting=node.directory.resolve("force_val");
        String before=read(active), previous=read(setting);
        if(!before.equals("0")&&!before.equals("1"))throw new IOException("无法识别强制覆盖状态。");
        String target=enable?value.toString():previous;
        try{
            if(enable){
                if(!previous.equals(target))writeForce(node,setting,target);
                writeForce(node,active,"1");
            }else if(before.equals("1")||restoringShutdown)writeForce(node,active,"0");
            if(!enable&&node.dischargeVoltage()&&!previous.equals("0"))write(setting,"0");
            if(!read(active).equals(enable?"1":"0")||enable&&!read(setting).equals(target))
                throw new IOException("写入读回不一致。");
            if(!enable&&node.dischargeVoltage()&&!read(setting).equals("0"))throw new IOException("写入读回不一致。");
            if(node.dischargeVoltage())verifyDischargeVoltage(node,enable,target);
            else verifyForce(node,enable,target);
        }catch(IOException e){
            if(node.dischargeVoltage()){
                try{
                    if(!read(setting).equals(previous))write(setting,previous);
                    if(!before.equals("0")||!read(active).equals(before))write(active,before);
                    if(!read(active).equals(before)||!read(setting).equals(previous))throw new IOException();
                    verifyDischargeVoltage(node,before.equals("1"),previous);
                }catch(IOException reset){e.addSuppressed(reset);throw new IOException("修改失败，无法确认原值已恢复。",e);}
                throw e;
            }
            try{
                writeForce(node,active,"0");
                if(!read(active).equals("0"))throw new IOException();
            }catch(IOException reset){throw new IOException("修改失败，无法确认是否已解除覆盖。",reset);}
            throw new IOException("驱动未能应用该设置，已关闭强制覆盖。",e);
        }
    }

    private static void verifyForce(Node node,boolean enabled,String target)throws IOException{
        Matcher effective=EFFECTIVE.matcher(read(node.directory.resolve("status")));
        if(!effective.find()||enabled!=effective.group(1).equals("DEBUG_FORCE_CLIENT")||
            enabled&&!effective.group(2).equals(target))throw new IOException("投票生效值校验失败。");
    }

    private static void verifyDischargeVoltage(Node node,boolean enabled,String target)throws IOException{
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
        while(true){
            try{
                verifyForce(node,enabled,target);
                return;
            }catch(IOException pending){
                if(System.nanoTime()-deadline>=0)throw pending;
            }
            try{Thread.sleep(50);}catch(InterruptedException error){Thread.currentThread().interrupt();throw new IOException("电压读回中断。",error);}
        }
    }

    static synchronized String restoreAll(GaugeUpdate gaugeUpdate){
        StringBuilder failed=new StringBuilder();
        for(Node node:Node.values()){
            if(node.maximum==0&&!node.chargingMode())continue;
            try{
                synchronized(node){
                    if(!isAdjusted(node))continue;
                    if(node.chargingMode())writeChargingMode(node,false);
                    else apply(node,null,gaugeUpdate);
                }
            }
            catch(IOException e){
                if(failed.length()>0)failed.append("、");
                failed.append(node.name());
            }
        }
        return failed.toString();
    }

    private static boolean isAdjusted(Node node)throws IOException {
        if(node==Node.DEC_DELTA)return !Files.notExists(DEC_DELTA)&&readNumber(DEC_DELTA,DEC_DELTA_VALUE)!=0;
        if(node.chargingMode())return Boolean.TRUE.equals(modeEnabled(node));
        Path active=node.directory.resolve("force_active");
        return !Files.notExists(active)&&!read(active).equals("0");
    }

    private static Boolean modeEnabled(Node node)throws IOException {
        switch(node){
            case CHG_DISABLE:
                return Files.notExists(CHARGING_ENABLE)?null:!readSwitch(CHARGING_ENABLE);
            case WIRED_CHARGE_SUSPEND:
                Path active=node.directory.resolve("force_active"),value=node.directory.resolve("force_val");
                if(Files.notExists(active)||Files.notExists(value)||Files.notExists(node.directory.resolve("status")))return null;
                // The driver can suspend ordinary wired input during fast charging on its own.
                return readSwitch(active)&&readSwitch(value);
            case PLC:
                if(Files.notExists(PLC_CONTROL))return null;
                int status=plcStatus();
                return status==PLC_UNSUPPORTED?null:plcActive(status);
            default:throw new IllegalArgumentException();
        }
    }

    static synchronized void setChargingMode(int option)throws IOException {
        if(option<0||option>CHARGING_MODES.length)throw new IOException("充电模式无效。");
        Node target=option==0?null:CHARGING_MODES[option-1];
        EnumSet<Node> active=EnumSet.noneOf(Node.class);
        IOException unknown=null;
        for(Node node:CHARGING_MODES){
            Boolean enabled;
            try{enabled=modeEnabled(node);}
            catch(IOException error){
                if(target!=null)throw error;
                if(unknown==null)unknown=error;else unknown.addSuppressed(error);
                continue;
            }
            if(enabled==null&&node==target)throw new IOException("充电模式接口不存在。");
            if(Boolean.TRUE.equals(enabled))active.add(node);
        }
        EnumSet<Node> changed=EnumSet.noneOf(Node.class);
        boolean enabling=false;
        try{
            for(Node node:active){
                if(node==target)continue;
                changed.add(node);
                writeChargingMode(node,false);
            }
            if(target!=null&&!active.contains(target)){
                enabling=true;
                writeChargingMode(target,true);
            }
        }catch(IOException error){
            boolean restored=true;
            // Try releasing a partially applied target first. An unreadable target
            // must not stop us from restoring the original restrictions.
            if(enabling){
                try{
                    boolean disable;
                    try{disable=Boolean.TRUE.equals(modeEnabled(target));}catch(IOException ignored){disable=true;}
                    if(disable)writeChargingMode(target,false);
                }catch(IOException cleanup){error.addSuppressed(cleanup);restored=false;}
            }
            for(Node node:changed){
                try{
                    boolean restore;
                    try{restore=!Boolean.TRUE.equals(modeEnabled(node));}catch(IOException ignored){restore=true;}
                    if(restore)writeChargingMode(node,true);
                }catch(IOException cleanup){error.addSuppressed(cleanup);restored=false;}
            }
            if(!restored)throw new IOException("切换充电限制失败，无法确认已恢复原来的状态。",error);
            throw error;
        }
        if(unknown!=null)throw new IOException("已解除可确认的限制，部分充电模式状态无法读取。",unknown);
    }

    private static void writeChargingMode(Node node,boolean enable)throws IOException {
        switch(node){
            case CHG_DISABLE:
                writePhysicalPause(enable);
                break;
            case WIRED_CHARGE_SUSPEND:applyForce(node,enable?1:null);break;
            case PLC:writePlc(enable);break;
            default:throw new IllegalArgumentException();
        }
        if(!Boolean.valueOf(enable).equals(modeEnabled(node)))throw new IOException("充电模式读回不一致。");
    }

    private static boolean readSwitch(Path path)throws IOException {
        String value=read(path);
        if(value.equals("0"))return false;
        if(value.equals("1"))return true;
        throw new IOException("无法读取充电模式状态。");
    }

    private static int plcStatus()throws IOException {
        int status=readNumber(PLC_CONTROL,PLC_STATUS);
        if(status==PLC_UNKNOWN)throw new IOException("无法读取旁路充电状态。");
        return status;
    }

    private static boolean plcActive(int status){return status==PLC_ENABLED||status==PLC_WAIT;}

    private static void writePlc(boolean enable)throws IOException {
        String caller="|callname=cn.astriafr.acobattery";
        if(enable&&plcStatus()==PLC_NOT_ALLOWED)throw new IOException("当前不允许开启旁路充电。");
        try{
            write(PLC_CONTROL,(enable?"switch=1":"switch=0")+caller);
            // The PLC driver disables bypass on delayed work.
            long until=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(2500);
            do{
                int status=plcStatus();
                if(enable?status==PLC_ENABLED:!plcActive(status))return;
                if(enable&&(status==PLC_UNSUPPORTED||status==PLC_NOT_ALLOWED))break;
                Thread.sleep(50);
            }while(System.nanoTime()-until<0);
            throw new IOException("旁路充电未切换到目标状态。");
        }catch(IOException|InterruptedException e){
            if(enable){
                // Even a failed enable can leave the driver's user request set.
                try{write(PLC_CONTROL,"switch=0"+caller);}
                catch(IOException reset){e.addSuppressed(reset);}
            }
            if(e instanceof InterruptedException)Thread.currentThread().interrupt();
            throw new IOException(enable?"未能开启旁路充电。":"未能关闭旁路充电。",e);
        }
    }

    private static int readNumber(Path path,Pattern pattern)throws IOException {
        Matcher match=pattern.matcher(read(path));
        if(!match.find())throw new IOException("无法读取参数："+path.getFileName());
        return Integer.parseInt(match.group(1));
    }

    private static final class Range {
        final int min,max;
        Range(int min,int max){this.min=min;this.max=max;}
    }

    static JSONObject coolDownDetails()throws JSONException {
        JSONObject result=new JSONObject();
        if(!Files.isReadable(Node.COOL_DOWN.directory.resolve("status")))return result;
        Path wired=DEVICE_TREE.resolve(WIRED_DT);
        String type=BatteryService.readBatteryType();
        if(type.matches("[a-zA-Z0-9_-]+")&&Files.isDirectory(wired.resolve(type)))wired=wired.resolve(type);
        JSONObject tables=new JSONObject();
        for(String mode:WIRED_COOL_DOWN_TYPES){
            tables.put(mode,readCoolDownTable(wired.resolve("oplus_spec,cool_down_"+mode+"_curr_ma")));
        }
        tables.put("pdqcVoltage",readCoolDownTable(wired.resolve("oplus_spec,cool_down_pdqc_vol_mv")));
        result.put("wired",tables);
        if(hasChargeProtocol(VOOC_DT,Node.VOOC_CURR)){
            result.put("vooc",new JSONObject()
                .put("dataWidth",deviceTreeInt(VOOC_DT+"/oplus,vooc_data_width",7))
                .put("tableType",deviceTreeInt(VOOC_DT+"/oplus,vooc_curr_table_type",2))
                .put("max",deviceTreeInt(VOOC_DT+"/oplus,vooc_curr_max",0)));
        }
        if(hasChargeProtocol(PPS_DT,Node.PPS_CURR))result.put("pps",true);
        if(hasChargeProtocol(UFCS_DT,Node.UFCS_CURR)){
            result.put("ufcs",new JSONObject().put("tableType",deviceTreeInt(UFCS_DT+"/oplus,curr_table_type",0)));
        }
        return result;
    }

    private static boolean hasChargeProtocol(String path,Node node){
        return Files.isReadable(DEVICE_TREE.resolve(path+"/compatible"))&&Files.isReadable(node.directory.resolve("status"));
    }

    private static JSONArray readCoolDownTable(Path path){
        JSONArray values=new JSONArray();
        try{
            ByteBuffer table=ByteBuffer.wrap(Files.readAllBytes(path));
            while(table.remaining()>=Integer.BYTES&&values.length()<WIRED_COOL_DOWN_TABLE_LIMIT)values.put(table.getInt());
        }catch(IOException ignored){}
        return values;
    }

    private static int deviceTreeInt(String property,int fallback){
        try{
            byte[] value=Files.readAllBytes(DEVICE_TREE.resolve(property));
            return value.length==Integer.BYTES?ByteBuffer.wrap(value).getInt():fallback;
        }catch(IOException ignored){return fallback;}
    }

    private static String read(Path path)throws IOException {
        return new String(Files.readAllBytes(path),StandardCharsets.US_ASCII).trim();
    }

    private static void write(Path path,String value)throws IOException {
        Files.write(path,value.getBytes(StandardCharsets.US_ASCII),StandardOpenOption.WRITE);
    }

    private static void writeForce(Node node,Path path,String value)throws IOException {
        try{write(path,value);}
        catch(AccessDeniedException denied){
            if(node!=Node.UFCS_DISABLE)throw denied;
            Set<PosixFilePermission> previous=Files.getPosixFilePermissions(path);
            Set<PosixFilePermission> writable=EnumSet.noneOf(PosixFilePermission.class);
            writable.addAll(previous);
            if(!writable.add(PosixFilePermission.OWNER_WRITE))throw denied;
            Files.setPosixFilePermissions(path,writable);
            IOException failure=null;
            try{write(path,value);}
            catch(IOException error){failure=error;throw error;}
            finally{
                try{Files.setPosixFilePermissions(path,previous);}
                catch(IOException cleanup){
                    if(failure==null)throw cleanup;
                    failure.addSuppressed(cleanup);
                }
            }
        }
    }
}
