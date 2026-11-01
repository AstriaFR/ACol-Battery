package cn.astriafr.acobattery.daemon;

import java.util.*;

/** Root app_process may not own an AMS ProcessRecord: BatteryService dump is independent. */
final class SystemPower {
    interface Source { String read()throws Exception; }
    private PowerInputs.Result cached;private long next;
    synchronized void invalidate(){next=0;}
    static Boolean plugged(String raw){
        if(raw==null||!raw.matches("[0-9]{1,2}"))return null;
        int value=Integer.parseInt(raw);return value<=15?value!=0:null;
    }
    static Boolean dump(String text){
        if(text==null||text.contains("DUMP TIMEOUT")||text.contains("Permission Denial"))return null;
        Map<String,Boolean> states=new HashMap<>();
        for(String line:text.split("\n")){
            String[] pair=line.trim().split(":",2);if(pair.length!=2)continue;
            String key=pair[0].trim();if(!Arrays.asList("AC powered","USB powered","Wireless powered","Dock powered").contains(key))continue;
            String raw=pair[1].trim();if(!raw.equals("true")&&!raw.equals("false"))return null;
            Boolean value=Boolean.valueOf(raw);if(states.containsKey(key)&&states.get(key)!=value)return null;
            states.put(key,value);
        }
        // Accept no-power only from a complete standard snapshot, never from a partial dump.
        if(!states.keySet().containsAll(Arrays.asList("AC powered","USB powered","Wireless powered")))return null;
        return states.containsValue(true);
    }
    synchronized PowerInputs.Result read(long now,Source broadcast,Source service){
        if(cached!=null&&now<next)return cached;
        StringBuilder details=new StringBuilder();
        try{
            String raw=broadcast.read();Boolean value=plugged(raw);
            if(value!=null)return remember(now,new PowerInputs.Result(value,true,"android",""));
            details.append("Android 电池广播：").append(raw==null?"未返回广播或缺少 plugged":("无效 plugged="+raw)).append('\n');
        }catch(Exception error){details.append("Android 电池广播：").append(error).append('\n');}
        try{
            String text=service.read();Boolean value=dump(text);
            if(value!=null)return remember(now,new PowerInputs.Result(value,true,"battery_service",details.toString()));
            details.append("BatteryService 未返回完整有效的连接状态：").append(text==null?"<空>":text.substring(0,Math.min(1024,text.length()))).append('\n');
        }catch(Exception error){details.append("BatteryService：").append(error).append('\n');}
        return remember(now,PowerInputs.Result.unknown(details.toString()));
    }
    private PowerInputs.Result remember(long now,PowerInputs.Result result){cached=result;next=now+1000;return result;}
}
