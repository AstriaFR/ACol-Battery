package cn.astriafr.acobattery.daemon;

import java.util.HashMap;
import java.util.Map;

/** Optional tail data, read only after external power is confirmed connected. */
final class ChargingLog {
    interface Reader {String read(String path)throws Exception;}
    private static String read(Reader reader,String path){
        try{String value=reader.read(path);return value==null?"":value;}catch(Exception ignored){return "";}
    }
    private static Map<String,String> fields(String[] head,String[] values){
        Map<String,String> result=new HashMap<>();
        if(head.length==values.length)for(int i=0;i<head.length;i++)result.put(head[i].trim(),values[i].trim());
        return result;
    }
    static String[] apply(Core.Reading r,String[] knownHeader,String directory,Reader reader){
        if(!r.onlineKnown||!r.online)return knownHeader;
        String[] head=knownHeader==null?read(reader,directory+"battery_log_head").split(",",-1):knownHeader;
        String[] values=read(reader,directory+"battery_log_content").split(",",-1);
        Map<String,String> data=fields(head,values);
        if(knownHeader!=null&&(!data.containsKey("fv_mv")||!data.containsKey("batt_full")||!data.containsKey("mmi_chg"))){
            head=read(reader,directory+"battery_log_head").split(",",-1);data=fields(head,values);
        }
        Core.applyTail(r,Core.parse(data.getOrDefault("fv_mv","")),
            Core.parse(data.getOrDefault("batt_full","")),Core.parse(data.getOrDefault("mmi_chg","")));
        return head;
    }
}
