package cn.astriafr.acobattery.daemon;

import android.content.Context;
import android.os.Looper;
import android.net.LocalSocket;
import android.net.Credentials;
import android.system.Os;
import android.system.OsConstants;
import android.system.ErrnoException;
import org.json.*;
import cn.astriafr.acobattery.common.FriendlyError;
import java.io.*;
import java.nio.channels.*;
import java.nio.file.Files;

public final class Admin {
    static Context context()throws Exception{
        Looper.prepareMainLooper();Class<?> at=Class.forName("android.app.ActivityThread");
        return (Context)at.getMethod("getSystemContext").invoke(at.getMethod("systemMain").invoke(null));
    }
    static JSONObject status(){try{return AppBridge.api("status",new JSONArray());}catch(Exception e){return null;}}
    static boolean ready(JSONObject status){return status!=null&&status.optBoolean("ok",false)&&status.optBoolean("frameworkHealthy",true);}
    static void waitStopped()throws Exception{
        for(int i=0;i<100;i++){
            try(RandomAccessFile f=new RandomAccessFile(new File(Owner.BASE,"daemon.lock"),"rw")){
                try(FileLock lock=f.getChannel().tryLock()){if(lock!=null)return;}
            }catch(OverlappingFileLockException ignored){}
            Thread.sleep(100);
        }
        throw new IOException("后台尚未完成当前记录，请稍后重试。");
    }
    static void ensure()throws Exception{
        JSONObject current=status();if(ready(current))return;
        // A living collector reconnects framework services and resumes its session.
        if(current!=null)for(int i=0;i<100;i++){Thread.sleep(100);if(ready(status()))return;}
        Process p=new ProcessBuilder(new File(Owner.RUNTIME,"bin/aco-launcher").getPath(),new File(Owner.RUNTIME,"service.sh").getPath()).redirectErrorStream(true).start();
        while(p.getInputStream().read()!=-1){}
        if(p.waitFor()!=0)throw new IOException("后台启动失败。");
        for(int i=0;i<100;i++){if(ready(status()))return;Thread.sleep(100);}
        String diagnostics="";
        try{byte[] bytes=Files.readAllBytes(new File(Owner.BASE,"daemon.log").toPath());diagnostics=new String(bytes,Math.max(0,bytes.length-16384),Math.min(bytes.length,16384),java.nio.charset.StandardCharsets.UTF_8);}catch(IOException ignored){}
        throw new IOException(FriendlyError.fromOutput(diagnostics,"记录服务未能启动，请重试初始化。"));
    }
    static void stopSupervisorForUpdate()throws Exception {
        File file=new File(Owner.BASE,"supervisor.pid");if(!file.exists())return;
        int pid;
        try{pid=Integer.parseInt(Owner.read(file));}catch(NumberFormatException stale){return;}
        if(pid<=1||pid==android.os.Process.myPid())return;
        File command=new File("/proc/"+pid+"/cmdline");if(!command.exists())return;
        boolean supervisor=false;
        for(String argument:Owner.read(command).split("\u0000"))if(argument.equals(new File(Owner.RUNTIME,"service.sh").getPath()))supervisor=true;
        if(!supervisor)return; // A stale PID file must never target an unrelated process.
        try{Os.kill(pid,OsConstants.SIGTERM);}
        catch(ErrnoException exited){if(exited.errno!=OsConstants.ESRCH)throw exited;}
    }
    static void stopForUpdate()throws Exception {
        // Quiesce the launcher as well: an old supervisor may otherwise wake from
        // its retry delay and start the old collector between the lock check and unzip.
        stopSupervisorForUpdate();
        JSONObject current=status();
        if(current!=null&&current.optInt("resumeProtocol",0)>=1){
            try{AppBridge.api("shutdown",new JSONArray().put("service_updated"));}
            catch(Exception ignored){} // It can commit and exit before delivering its reply.
        }else{
            // Older collectors interpret shutdown as finish/delete. Stop only the
            // authenticated recorder process, leaving its committed active row intact.
            // SQLite WAL rolls back any in-flight transaction on the next open.
            LocalSocket connected;
            try{connected=AppBridge.connect("status",new JSONArray());}
            catch(IOException absent){waitStopped();return;}
            try(LocalSocket socket=connected){
                Credentials peer=socket.getPeerCredentials();int pid=peer.getPid();
                if(peer.getUid()!=0||pid<=1||pid==android.os.Process.myPid())throw new IOException("无法确认旧后台身份，已取消更新。");
                String command=Owner.read(new File("/proc/"+pid+"/cmdline"));
                boolean recorder=false;
                for(String argument:command.split("\u0000"))if(argument.equals(Main.class.getName()))recorder=true;
                if(!recorder)throw new IOException("旧后台进程身份不匹配，已取消更新。");
                Os.kill(pid,OsConstants.SIGTERM);
            }
        }
        waitStopped();
    }
    public static void main(String[] args)throws Exception{
        if(android.os.Process.myUid()!=0)throw new SecurityException("root required");
        Context ctx=context();String action=args.length==0?"":args[0];
        try{
            if(action.equals("ensure")){ensure();System.out.println("READY");}
            else if(action.equals("stop-update")){
                stopForUpdate();
                // The daemon can exit after committing before its reply arrives.
                // The exclusive database lock, not an IPC reply, proves shutdown.
                System.out.println("STOPPED");
            }else if(action.equals("cleanup")){
                if(Owner.check(ctx)==1){Files.deleteIfExists(new File(Owner.BASE,"cleanup.request").toPath());System.out.println("REINSTALLED");System.exit(0);}
                if(status()!=null)try{AppBridge.api("shutdown",new JSONArray().put("app_uninstalled"));}catch(Exception ignored){}
                waitStopped();Owner.cleanup();System.out.println("CLEANED");
            }else if(action.equals("boot")){
                int first=Owner.check(ctx);
                if(first<0){System.out.println("UNKNOWN");System.exit(2);}
                if(first==0){
                    Thread.sleep(15000);int second=Owner.check(ctx);
                    if(second!=0){System.out.println("UNKNOWN");System.exit(2);}
                    Owner.markCleanup();waitStopped();Owner.cleanup();System.out.println("CLEANED");
                }else{
                    ensure();System.out.println("READY");
                }
            }
            else throw new IOException("未知初始化操作。");
        }catch(Exception e){System.err.println(FriendlyError.message(e));System.exit(1);}
        System.exit(0);
    }
}
