package cn.astriafr.acobattery.daemon;

import android.content.Context;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.SystemClock;
import org.json.*;
import cn.astriafr.acobattery.common.FriendlyError;
import cn.astriafr.acobattery.common.OperationTimeout;
import cn.astriafr.acobattery.common.RecordingGate;
import cn.astriafr.acobattery.common.ProcessResponse;
import cn.astriafr.acobattery.common.StartupFailure;
import cn.astriafr.acobattery.common.StartupFeedback;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** One su session multiplexes commands and visible-page streams. */
public final class AppBridge {
    static final String ROOT="/data/adb/aco_battery_app/runtime/";
    static final ConcurrentHashMap<Integer,LocalSocket> streams=new ConcurrentHashMap<>();
    static final AtomicInteger tokens=new AtomicInteger(1000000000);
    static synchronized void send(JSONObject value){System.out.println(value.toString());System.out.flush();}
    static LocalSocket connect(String action,JSONArray args)throws Exception{
        return connect(action,args,OperationTimeout.MAX_MS);
    }
    static LocalSocket connect(String action,JSONArray args,int timeout)throws Exception{
        return connect(action,args,timeout,0);
    }
    private static LocalSocket connect(String action,JSONArray args,int timeout,long deadline)throws Exception{
        OperationTimeout.requireStartBefore(deadline,SystemClock.elapsedRealtime());
        LocalSocket socket=new LocalSocket();
        try{
            socket.connect(new LocalSocketAddress(Main.SOCKET,LocalSocketAddress.Namespace.ABSTRACT));
            socket.setSoTimeout(Math.max(1,Math.min(OperationTimeout.MAX_MS,timeout)));
            JSONObject request=new JSONObject().put("action",action).put("args",args);
            if(deadline>0)request.put(OperationTimeout.START_BEFORE,deadline);
            OperationTimeout.requireStartBefore(deadline,SystemClock.elapsedRealtime());
            socket.getOutputStream().write((request.toString()+"\n").getBytes(StandardCharsets.UTF_8));
            return socket;
        }catch(Exception failure){
            try{socket.close();}catch(IOException close){failure.addSuppressed(close);}
            throw failure;
        }
    }
    static JSONObject api(String action,JSONArray args)throws Exception{
        return api(action,args,OperationTimeout.MAX_MS);
    }
    static JSONObject api(String action,JSONArray args,int timeout)throws Exception{
        return api(action,args,timeout,0);
    }
    private static JSONObject api(String action,JSONArray args,int timeout,long deadline)throws Exception{
        try(LocalSocket socket=connect(action,args,timeout,deadline)){
            return new JSONObject(Main.boundedLine(new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.UTF_8)),8388608));
        }
    }
    static String[] parse(String command){
        if(command==null||command.length()>6144)throw new IllegalArgumentException("命令无效");
        String base=ROOT+"bin/curve-client ",control="/system/bin/sh "+ROOT+"control.sh ";
        String route;
        if(command.startsWith("/system/bin/sh "+base)){route="api";command=command.substring(("/system/bin/sh "+base).length());}
        else if(command.startsWith(base)){route="api";command=command.substring(base.length());}
        else if(command.startsWith(control)){route="api";command=command.substring(control.length());}
        else throw new IllegalArgumentException("不支持的操作");
        String[] words=command.trim().split(" +");
        if(words.length<1)throw new IllegalArgumentException("参数无效");
        String action=words[0];
        int limit=action.equals("set_monitor_appearance")?19:action.equals("set_settings")?18:17;
        if(words.length>limit)throw new IllegalArgumentException("参数无效");
        if("api".equals(route)){
            if(!java.util.Arrays.asList("status","list","detail","detail_preview","detail_sample","export_points","charge_integral","performance","screen_usage","screen_off_spans","hour_apps","interval_apps","settle_usage","hour_energy","daily_usage","battery_history","battery_history_detail","battery_history_delete","battery_history_record","battery_history_trend","cache_records","delete","live","watch","stop_watch","settings","default_settings","reset_settings","set_setting","reset_setting","grow_chart_ranges","set_settings","set_recording","set_discharge_notification","set_cell_count","set_fast","game_metrics","set_game_metrics","set_power_display","monitor_appearance","set_monitor_appearance","set_monitor_order","reset_monitor_appearance","reset_monitor_threshold","oplus_votes","cool_down_details","set_oplus_vote","reset_oplus_votes","set_charging_mode","set_advanced_auto_restore","error_logs","error_log","clear_error_logs").contains(action))throw new IllegalArgumentException("未知操作");
            if(action.equals("daily_usage")){
                if(words.length!=4||!words[1].matches("[0-9]{1,20}")||!words[2].matches("[0-9]{1,20}")||!words[3].matches("[A-Za-z0-9_-]{1,4096}"))throw new IllegalArgumentException("使用情况参数无效");
            }else if(action.equals("set_oplus_vote")){
                if(words.length!=3||!words[2].matches("(?:-?[0-9]{1,5}|stock)"))throw new IllegalArgumentException("参数无效。");
                OplusVotes.editable(words[1]);
            }else if(action.equals("set_charging_mode")){
                if(words.length!=2||!words[1].matches("[0-3]"))throw new IllegalArgumentException("充电模式无效。");
            }else if(action.equals("set_advanced_auto_restore")){
                if(words.length!=2||!words[1].matches("[01]"))throw new IllegalArgumentException("参数无效。");
            }else if(action.equals("set_monitor_order")){
                if(words.length!=2||!words[1].matches("[1-8]{8}"))throw new IllegalArgumentException("性能监视器显示顺序无效。");
            }else if(action.equals("set_cell_count")){
                if(words.length!=2||!words[1].matches("[12]"))throw new IllegalArgumentException("请选择单电芯或双电芯。");
            }else if(action.equals("set_setting")){
                if(words.length!=4||!words[1].matches("[0-9]{1,2}")||!words[2].matches(
                        words[1].equals("10")||words[1].equals("13")?"[0-9]{1,2}(?:\\.[0-9])?":"[0-9]{1,5}")
                        ||!words[3].matches("[01]"))throw new IllegalArgumentException("设置参数无效");
            }else if(action.equals("error_log")){
                if(words.length!=2||!words[1].matches("[0-9]{13,20}-[0-9a-f-]{36}"))throw new IllegalArgumentException("日志参数无效");
            }else if(action.equals("reset_settings")){
                if(words.length!=1)throw new IllegalArgumentException("恢复默认设置参数无效");
            }else for(int i=1;i<words.length;i++){
                boolean decimalInterval=action.equals("set_settings")&&(i==11||i==14);
                if(!words[i].matches(decimalInterval?"[0-9]{1,2}(?:\\.[0-9])?":"[0-9]{1,20}"))throw new IllegalArgumentException("参数无效");
            }
        }
        String[] result=new String[words.length+1];result[0]=route;System.arraycopy(words,0,result,1,words.length);return result;
    }
    static void handle(JSONObject request){
        String id=request.optString("id");boolean streaming=request.optBoolean("stream");
        try{
            if("initialize".equals(request.optString("action"))){
                String owner=Admin.ownerToken(android.os.Process.myPid());
                long deadline=OperationTimeout.deadline(request.optInt("timeoutMs",OperationTimeout.MAX_MS));
                JSONObject current=Admin.status(deadline);
                send(new JSONObject().put("id",id).put("event","startup_phase").put("message",Admin.ready(current)?"正在连接后台…":"正在启动后台…"));
                JSONObject healthy=Admin.ensure(current,deadline);
                if(new File(Owner.BASE,RecordingGate.DISABLED).exists()&&healthy.optBoolean("recordingEnabled")){
                    JSONObject paused=api("set_recording",new JSONArray().put(0),OperationTimeout.remaining(deadline));
                    if(!paused.optBoolean("ok"))throw new IOException(paused.optString("error","无法暂停记录。"));
                    healthy=Admin.status(deadline);if(!Admin.ready(healthy))throw new IOException("后台连接尚未就绪。");
                }
                if(request.optBoolean("commit")){
                    if(!healthy.optBoolean("recorderReady"))throw new IOException("新版记录后台尚未完成启动检查。");
                    OperationTimeout.remaining(deadline);
                    Admin.transaction().commit(owner);
                }else if(!new File(Owner.BASE,"initialized").exists()){
                    java.nio.file.Files.write(new File(Owner.BASE,"initialized").toPath(),new byte[0]);
                }
                if(!StartupFeedback.notices(null,healthy).isEmpty()){
                    try{
                        int remaining=OperationTimeout.remaining(deadline);
                        if(remaining>500)healthy.put("fallbackDiagnostics",api("diagnostics",new JSONArray(),Math.min(1000,remaining-250)));
                        else healthy.put("diagnosticsError","初始化已完成，剩余时间不足，跳过附加后台日志读取。");
                    }
                    catch(Exception diagnosticError){healthy.put("diagnosticsError",diagnosticError.toString());}
                }
                send(new JSONObject().put("id",id).put("event","startup_ready").put("status",healthy));
                return;
            }
            if("install_boot_entry".equals(request.optString("action"))&&id.startsWith("native-")){
                JSONObject result=new JSONObject().put("ok",true);
                try{Owner.installBootEntry();}
                catch(IOException|SecurityException|UnsupportedOperationException error){
                    result.put("bootEntryError",error.toString());
                }
                send(new JSONObject().put("id",id).put("event","result").put("errno",0).put("stdout",result.toString()).put("stderr",""));
                return;
            }
            String[] words=parse(request.getString("command"));String action=words[1];JSONArray args=new JSONArray();for(int i=2;i<words.length;i++)args.put(words[i]);
            if(action.equals("stop_watch")){
                int token=args.getInt(0);LocalSocket socket=streams.remove(token);if(socket!=null)socket.close();
                send(new JSONObject().put("id",id).put("event","result").put("errno",0).put("stdout","{\"ok\":true}\n").put("stderr",""));return;
            }
            if(streaming){
                if(!action.equals("watch"))throw new IllegalArgumentException("仅支持实时订阅");
                int token=tokens.incrementAndGet();
                try(LocalSocket socket=connect(action,args)){
                    if(args.optInt(2,0)==Main.BATTERY_INFO_WATCH_MODE)socket.setSoTimeout(Main.BATTERY_INFO_STREAM_TIMEOUT_MS);
                    streams.put(token,socket);send(new JSONObject().put("id",id).put("event","stdout").put("data",new JSONObject().put("streamPid",token).toString()+"\n"));
                    BufferedReader in=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.UTF_8));String line;
                    while((line=Main.boundedLine(in,8388608))!=null)send(new JSONObject().put("id",id).put("event","stdout").put("data",line+"\n"));
                }finally{streams.remove(token);}
                send(new JSONObject().put("id",id).put("event","exit").put("code",0));
            }else{
                long deadline=request.optLong(OperationTimeout.START_BEFORE,0);
                JSONObject result=api(action,args,OperationTimeout.MAX_MS,deadline);
                send(new JSONObject().put("id",id).put("event","result").put("errno",0).put("stdout",result.toString()+"\n").put("stderr",""));
            }
        }catch(Throwable e){
            String message=FriendlyError.message(e);
            try{
                JSONObject error=new JSONObject().put("id",id).put("event",streaming?"error":"result").put("errno",1).put("stdout","").put("stderr",message).put("message",message);
                if("initialize".equals(request.optString("action")))error.put("diagnostics",StartupFailure.details(e));
                send(error);
            }catch(Exception ignored){}
        }
    }
    public static void main(String[] args)throws Exception{
        if(android.os.Process.myUid()!=0)throw new SecurityException("root required");
        send(new JSONObject().put("event","ready"));
        BufferedReader in=new BufferedReader(new InputStreamReader(System.in,StandardCharsets.UTF_8));String line;
        while((line=Main.boundedLine(in,8192))!=null){
            JSONObject request=new JSONObject(line);Thread t=new Thread(()->handle(request),"aco-command");t.setDaemon(true);t.start();
        }
        for(LocalSocket socket:streams.values())try{socket.close();}catch(IOException ignored){}
        System.exit(0); // Foreground bridge exits; detached recorder continues.
    }
}
