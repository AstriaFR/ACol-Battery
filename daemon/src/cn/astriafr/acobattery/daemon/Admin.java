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
import cn.astriafr.acobattery.common.RuntimeTransaction;
import cn.astriafr.acobattery.common.OperationTimeout;
import cn.astriafr.acobattery.common.StartupFailure;
import cn.astriafr.acobattery.common.DatabaseRebuild;
import cn.astriafr.acobattery.common.AllDataReset;
import java.io.*;
import java.nio.channels.*;
import java.nio.file.Files;

public final class Admin {
    static Context context()throws Exception{
        Looper.prepareMainLooper();Class<?> at=Class.forName("android.app.ActivityThread");
        return (Context)at.getMethod("getSystemContext").invoke(at.getMethod("systemMain").invoke(null));
    }
    static JSONObject status(){return status(OperationTimeout.deadline());}
    static JSONObject status(long deadline){try{return AppBridge.api("status",new JSONArray(),OperationTimeout.remaining(deadline));}catch(Exception e){return null;}}
    static boolean ready(JSONObject status){return status!=null&&status.optBoolean("ok",false)&&status.optBoolean("frameworkHealthy",true);}
    static void waitStopped()throws Exception{
        waitStopped(OperationTimeout.deadline());
    }
    static void waitStopped(long deadline)throws Exception{
        for(int i=0;i<100;i++){
            OperationTimeout.remaining(deadline);
            try(RandomAccessFile f=new RandomAccessFile(new File(Owner.BASE,"daemon.lock"),"rw")){
                try(FileLock lock=f.getChannel().tryLock()){if(lock!=null)return;}
            }catch(OverlappingFileLockException ignored){}
            Thread.sleep(Math.min(100,OperationTimeout.remaining(deadline)));
        }
        throw new IOException("后台尚未完成当前记录，请稍后重试。");
    }
    static JSONObject ensure()throws Exception{long deadline=OperationTimeout.deadline();return ensure(status(deadline),deadline);}
    static JSONObject ensure(JSONObject current)throws Exception{return ensure(current,OperationTimeout.deadline());}
    static JSONObject ensure(JSONObject current,long deadline)throws Exception{
        if(DataTransfer.PENDING.exists())throw new IOException("数据备份或恢复尚未结束，请打开应用完成处理。");
        if(new File(Owner.BASE,AllDataReset.PENDING).exists())throw new IOException("清除全部数据未完成，请重新打开应用继续清除。");
        if(ready(current))return current;
        // A living collector reconnects framework services and resumes its session.
        if(current!=null)for(int i=0;i<100;i++){Thread.sleep(Math.min(100,OperationTimeout.remaining(deadline)));JSONObject next=status(deadline);if(ready(next))return next;}
        OperationTimeout.remaining(deadline);
        DatabaseRebuild.checkPending(Owner.BASE);
        // This process also starts a restored runtime during rollback. Inspect with
        // the new, non-destructive handler before any older recorder can open it.
        SafeDatabase.inspectExisting(new File(Owner.BASE,"records.db"));
        String attempt=StartupFailure.begin(Owner.BASE);
        try{
            Process p=new ProcessBuilder(new File(Owner.RUNTIME,"bin/aco-launcher").getPath(),new File(Owner.RUNTIME,"service.sh").getPath()).redirectErrorStream(true).start();
            if(!p.waitFor(OperationTimeout.remaining(deadline),java.util.concurrent.TimeUnit.MILLISECONDS)){p.destroy();throw new IOException("后台启动超时，请重试。");}
            if(p.exitValue()!=0)throw new IOException("后台启动失败。");
            for(int i=0;i<100;i++){
                StartupFailure.check(Owner.BASE,attempt);
                JSONObject next=status(deadline);if(ready(next))return next;
                StartupFailure.check(Owner.BASE,attempt);
                Thread.sleep(Math.min(100,OperationTimeout.remaining(deadline)));
            }
            throw new IOException("记录服务未能启动，请重试初始化。");
        }catch(Exception failure){
            // Prefer the actual fatal cause even when the waiting deadline expires.
            StartupFailure.check(Owner.BASE,attempt);throw failure;
        }
    }
    static RuntimeTransaction transaction(){return new RuntimeTransaction(Owner.BASE,new File("/data/adb/service.d/aco_battery_app.sh"));}
    static String ownerToken(int pid){
        try{
            String stat=Owner.read(new File("/proc/"+pid+"/stat"));String[] fields=stat.substring(stat.lastIndexOf(')')+2).split(" +");
            return pid+":"+fields[19]+":"+Owner.read(new File("/proc/sys/kernel/random/boot_id")).trim();
        }catch(Exception missing){return "";}
    }
    static boolean ownerAlive(String token){
        try{int end=token.indexOf(':');return end>0&&token.equals(ownerToken(Integer.parseInt(token.substring(0,end))));}catch(Exception invalid){return false;}
    }
    static RuntimeTransaction.Control updateControl(boolean restart){return new RuntimeTransaction.Control(){
        public void stop()throws Exception{stopForUpdate();}
        public void start()throws Exception{if(restart)ensure();}
    };}
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
        stopForUpdate(OperationTimeout.deadline());
    }
    static void stopForUpdate(long deadline)throws Exception {
        // Quiesce the launcher as well: an old supervisor may otherwise wake from
        // its retry delay and start the old collector between the lock check and unzip.
        stopSupervisorForUpdate();
        JSONObject current=status(deadline);
        if(current!=null&&current.optInt("resumeProtocol",0)>=1){
            try{AppBridge.api("shutdown",new JSONArray().put("service_updated"),OperationTimeout.remaining(deadline));}
            catch(Exception ignored){} // It can commit and exit before delivering its reply.
        }else{
            // Older collectors interpret shutdown as finish/delete. Stop only the
            // authenticated recorder process, leaving its committed active row intact.
            // SQLite WAL rolls back any in-flight transaction on the next open.
            LocalSocket connected;
            try{connected=AppBridge.connect("status",new JSONArray(),OperationTimeout.remaining(deadline));}
            catch(IOException absent){waitStopped(deadline);return;}
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
        waitStopped(deadline);
    }
    static void clearAllData(long deadline)throws Exception{
        if(DataTransfer.PENDING.exists()||DataTransfer.running())throw new IOException("数据备份或恢复正在进行，请稍后清除数据。");
        if(!Owner.BASE.getCanonicalPath().equals("/data/adb/aco_battery_app")||Files.isSymbolicLink(Owner.BASE.toPath()))throw new IOException("数据目录异常，已取消清除。");
        if(new File(Owner.BASE,"update.transaction").exists())throw new IOException("后台更新尚未结束，请稍后清除数据。");
        stopForUpdate(deadline);
        try(RandomAccessFile file=new RandomAccessFile(new File(Owner.BASE,"daemon.lock"),"rw");FileLock lock=file.getChannel().tryLock()){
            if(lock==null)throw new IOException("后台仍在使用数据，已取消清除。");
            AllDataReset.clear(Owner.BASE,new java.util.HashSet<>(java.util.Arrays.asList("runtime","runtime.version","owner.cert","owner.user","daemon.lock")),()->OperationTimeout.remaining(deadline));
        }
    }
    public static void main(String[] args)throws Exception{
        if(android.os.Process.myUid()!=0)throw new SecurityException("root required");
        String action=args.length==0?"":args[0];
        try{
            if(action.startsWith("transfer-")){System.out.println(DataTransfer.handle(args));}
            else if(action.equals("ensure")){ensure();System.out.println("READY");}
            else if(action.equals("clear-all-data")){
                clearAllData(OperationTimeout.deadline());System.out.println(new JSONObject().put("ok",true));
            }
            else if(action.equals("prepare-database-export")){
                if(args.length!=2)throw new IOException("数据库备份参数无效。");long deadline=OperationTimeout.deadline();
                System.out.println(DatabaseRebuild.prepareExternal(Owner.BASE,"records.db",args[1],()->stopForUpdate(deadline),phase->{OperationTimeout.remaining(deadline);}));
            }
            else if(action.equals("cancel-database-export")){
                if(args.length!=2)throw new IOException("数据库备份参数无效。");DatabaseRebuild.cancelExternal(Owner.BASE,args[1]);System.out.println(new JSONObject().put("ok",true));
            }
            else if(action.equals("rebuild-database-external")){
                if(args.length!=2)throw new IOException("数据库重建参数无效。");long deadline=OperationTimeout.deadline();
                DatabaseRebuild.commitExternal(Owner.BASE,"records.db",args[1],()->stopForUpdate(deadline),phase->{OperationTimeout.remaining(deadline);});System.out.println(new JSONObject().put("ok",true));
            }
            else if(action.equals("apply-update")){
                if(args.length!=3||!args[1].matches("[0-9a-f]{64}"))throw new IOException("后台更新参数无效。");
                String owner=ownerToken(Integer.parseInt(args[2]));if(owner.isEmpty())throw new IOException("无法确认后台更新会话。");
                transaction().apply(args[1],owner,updateControl(true));System.out.println("INSTALLED");
            }else if(action.equals("recover-update")){
                transaction().recover("",Admin::ownerAlive,updateControl(args.length<2||!args[1].equals("boot")));System.out.println("RECOVERED");
            }
            else if(action.equals("stop-update")){
                stopForUpdate();
                // The daemon can exit after committing before its reply arrives.
                // The exclusive database lock, not an IPC reply, proves shutdown.
                System.out.println("STOPPED");
            }else if(action.equals("cleanup")){
                Context ctx=context();
                if(Owner.check(ctx)==1){Files.deleteIfExists(new File(Owner.BASE,"cleanup.request").toPath());System.out.println("REINSTALLED");System.exit(0);}
                if(status()!=null)try{AppBridge.api("shutdown",new JSONArray().put("app_uninstalled"));}catch(Exception ignored){}
                waitStopped();Owner.cleanup();System.out.println("CLEANED");
            }else if(action.equals("boot")){
                Context ctx=context();
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
        }catch(Exception e){System.err.println(FriendlyError.message(e));StartupFailure.print(e,System.err);System.exit(1);}
        System.exit(0);
    }
}
