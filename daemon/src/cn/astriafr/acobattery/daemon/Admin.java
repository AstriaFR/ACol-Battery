package cn.astriafr.acobattery.daemon;

import android.content.Context;
import android.os.Looper;
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
        // A living collector reconnects by ending its segment and restarting itself.
        if(current!=null)for(int i=0;i<100;i++){Thread.sleep(100);if(ready(status()))return;}
        Process p=new ProcessBuilder(new File(Owner.RUNTIME,"bin/aco-launcher").getPath(),new File(Owner.RUNTIME,"service.sh").getPath()).redirectErrorStream(true).start();
        while(p.getInputStream().read()!=-1){}
        if(p.waitFor()!=0)throw new IOException("后台启动失败。");
        for(int i=0;i<100;i++){if(ready(status()))return;Thread.sleep(100);}
        String diagnostics="";
        try{byte[] bytes=Files.readAllBytes(new File(Owner.BASE,"daemon.log").toPath());diagnostics=new String(bytes,Math.max(0,bytes.length-16384),Math.min(bytes.length,16384),java.nio.charset.StandardCharsets.UTF_8);}catch(IOException ignored){}
        throw new IOException(FriendlyError.fromOutput(diagnostics,"记录服务未能启动，请重试初始化。"));
    }
    public static void main(String[] args)throws Exception{
        if(android.os.Process.myUid()!=0)throw new SecurityException("root required");
        Context ctx=context();String action=args.length==0?"":args[0];
        try{
            if(action.equals("ensure")){ensure();System.out.println("READY");}
            else if(action.equals("stop-update")){
                if(status()!=null)try{AppBridge.api("shutdown",new JSONArray().put("service_updated"));}catch(Exception ignored){}
                // The daemon can exit after committing before its reply arrives.
                // The exclusive database lock, not an IPC reply, proves shutdown.
                waitStopped();System.out.println("STOPPED");
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
