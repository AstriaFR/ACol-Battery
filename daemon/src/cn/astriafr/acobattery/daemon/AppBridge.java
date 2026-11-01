package cn.astriafr.acobattery.daemon;

import android.content.Context;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
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
        LocalSocket socket=new LocalSocket();socket.connect(new LocalSocketAddress(Main.SOCKET,LocalSocketAddress.Namespace.ABSTRACT));socket.setSoTimeout(Math.max(1,Math.min(OperationTimeout.MAX_MS,timeout)));
        socket.getOutputStream().write((new JSONObject().put("action",action).put("args",args).toString()+"\n").getBytes(StandardCharsets.UTF_8));return socket;
    }
    static JSONObject api(String action,JSONArray args)throws Exception{
        return api(action,args,OperationTimeout.MAX_MS);
    }
    static JSONObject api(String action,JSONArray args,int timeout)throws Exception{
        try(LocalSocket socket=connect(action,args,timeout)){
            return new JSONObject(Main.boundedLine(new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.UTF_8)),8388608));
        }
    }
    static String[] parse(String command){
        if(command==null||command.length()>6144)throw new IllegalArgumentException("命令无效");
        String base=ROOT+"bin/curve-client ",control="/system/bin/sh "+ROOT+"control.sh ",cutoff="/system/bin/sh "+ROOT+"cutoff.sh ";
        String route;
        if(command.startsWith("/system/bin/sh "+base)){route="api";command=command.substring(("/system/bin/sh "+base).length());}
        else if(command.startsWith(base)){route="api";command=command.substring(base.length());}
        else if(command.startsWith(control)){route="api";command=command.substring(control.length());}
        else if(command.startsWith(cutoff)){route="cutoff";command=command.substring(cutoff.length());}
        else throw new IllegalArgumentException("不支持的操作");
        String[] words=command.trim().split(" +");
        if(words.length<1||words.length>17)throw new IllegalArgumentException("参数无效");
        String action=words[0];
        if("api".equals(route)){
            if(!java.util.Arrays.asList("status","list","detail","performance","screen_usage","screen_off_spans","hour_apps","hour_energy","daily_usage","battery_history","battery_history_detail","battery_history_delete","cache_records","delete","live","watch","stop_watch","settings","default_settings","set_settings","set_recording","set_fast","set_power_display","error_logs","error_log","clear_error_logs").contains(action))throw new IllegalArgumentException("未知操作");
            if(action.equals("daily_usage")){
                if(words.length!=4||!words[1].matches("[0-9]{1,20}")||!words[2].matches("[0-9]{1,20}")||!words[3].matches("[A-Za-z0-9_-]{1,4096}"))throw new IllegalArgumentException("使用情况参数无效");
            }else if(action.equals("error_log")){
                if(words.length!=2||!words[1].matches("[0-9]{13,20}-[0-9a-f-]{36}"))throw new IllegalArgumentException("日志参数无效");
            }else for(int i=1;i<words.length;i++)if(!words[i].matches("[0-9]{1,20}"))throw new IllegalArgumentException("参数无效");
        }else{
            boolean valid=words.length==1&&action.equals("status")||words.length==2&&action.equals("plan")&&words[1].equals("stock")
                ||words.length==4&&action.equals("plan")&&words[1].equals("set")&&words[2].matches("[0-9]{4}")&&words[3].matches("[0-9]{4}")
                ||words.length==2&&action.equals("commit")&&words[1].matches("[0-9a-f-]{36}");
            if(!valid)throw new IllegalArgumentException("截止电压操作无效");
        }
        String[] result=new String[words.length+1];result[0]=route;System.arraycopy(words,0,result,1,words.length);return result;
    }
    static void handle(JSONObject request){
        String id=request.optString("id");boolean streaming=request.optBoolean("stream");
        try{
            if("initialize".equals(request.optString("action"))){
                String owner=Admin.ownerToken(android.os.Process.myPid());
                try{
                    long deadline=OperationTimeout.deadline();JSONObject current=Admin.status(deadline);
                    send(new JSONObject().put("id",id).put("event","startup_phase").put("message",Admin.ready(current)?"正在连接后台…":"正在启动后台…"));
                    JSONObject healthy=Admin.ensure(current,deadline);
                    if(new File(Owner.BASE,RecordingGate.DISABLED).exists()){
                        JSONObject paused=api("set_recording",new JSONArray().put(0),OperationTimeout.remaining(deadline));
                        if(!paused.optBoolean("ok"))throw new IOException(paused.optString("error","无法暂停记录。"));
                        healthy=Admin.status(deadline);if(!Admin.ready(healthy))throw new IOException("后台连接尚未就绪。");
                    }
                    if(request.optBoolean("commit"))Admin.transaction().commit(owner);
                    java.nio.file.Files.write(new File(Owner.BASE,"initialized").toPath(),new byte[0]);
                    if(!StartupFeedback.notices(null,healthy).isEmpty()){
                        try{
                            int remaining=OperationTimeout.remaining(deadline);
                            if(remaining>500)healthy.put("fallbackDiagnostics",api("diagnostics",new JSONArray(),Math.min(1000,remaining-250)));
                            else healthy.put("diagnosticsError","初始化已完成，剩余时间不足，跳过附加后台日志读取。");
                        }
                        catch(Exception diagnosticError){healthy.put("diagnosticsError",diagnosticError.toString());}
                    }
                    send(new JSONObject().put("id",id).put("event","startup_ready").put("status",healthy));
                }catch(Exception failure){
                    try{Admin.transaction().recover(owner,Admin::ownerAlive,Admin.updateControl(true));}catch(Exception recovery){failure.addSuppressed(recovery);}
                    throw failure;
                }
                return;
            }
            String[] words=parse(request.getString("command"));String action=words[1];JSONArray args=new JSONArray();for(int i=2;i<words.length;i++)args.put(words[i]);
            if(words[0].equals("cutoff")){
                String[] command=new String[words.length+1];command[0]="/system/bin/sh";command[1]=ROOT+"cutoff.sh";System.arraycopy(words,1,command,2,words.length-1);
                long deadline=OperationTimeout.deadline();Process p=new ProcessBuilder(command).redirectErrorStream(true).start();
                ProcessResponse result=ProcessResponse.read(p,deadline,65536);int code=result.code;String text=result.text;
                send(new JSONObject().put("id",id).put("event","result").put("errno",code).put("stdout",code==0?text:"").put("stderr",code==0?"":text));return;
            }
            if(action.equals("stop_watch")){
                int token=args.getInt(0);LocalSocket socket=streams.remove(token);if(socket!=null)socket.close();
                send(new JSONObject().put("id",id).put("event","result").put("errno",0).put("stdout","{\"ok\":true}\n").put("stderr",""));return;
            }
            if(streaming){
                if(!action.equals("watch"))throw new IllegalArgumentException("仅支持实时订阅");
                int token=tokens.incrementAndGet();
                try(LocalSocket socket=connect(action,args)){
                    streams.put(token,socket);send(new JSONObject().put("id",id).put("event","stdout").put("data",new JSONObject().put("streamPid",token).toString()+"\n"));
                    BufferedReader in=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.UTF_8));String line;
                    while((line=Main.boundedLine(in,8388608))!=null)send(new JSONObject().put("id",id).put("event","stdout").put("data",line+"\n"));
                }finally{streams.remove(token);}
                send(new JSONObject().put("id",id).put("event","exit").put("code",0));
            }else send(new JSONObject().put("id",id).put("event","result").put("errno",0).put("stdout",api(action,args).toString()+"\n").put("stderr",""));
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
