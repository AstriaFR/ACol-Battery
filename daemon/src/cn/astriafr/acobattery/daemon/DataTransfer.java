package cn.astriafr.acobattery.daemon;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.database.sqlite.SQLiteDatabase;
import android.system.Os;
import org.json.*;
import java.io.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import cn.astriafr.acobattery.common.*;

/** Detached data transfer. The temporary native UI never opens WebView storage. */
final class DataTransfer {
    static final File HOME=new File(Owner.BASE,"transfer"),PENDING=new File(Owner.BASE,"transfer.pending");
    static File job(String token)throws Exception{
        if(!token.matches("[0-9a-f-]{36}")||Files.isSymbolicLink(HOME.toPath()))throw new IOException("数据任务目录异常。");
        File dir=new File(HOME,token);if(Files.isSymbolicLink(dir.toPath())||!dir.getCanonicalFile().getParentFile().equals(HOME.getCanonicalFile()))throw new IOException("数据任务路径异常。");return dir;
    }
    static JSONObject read(File file)throws Exception{if(!file.isFile()||file.length()>2*1024*1024)throw new IOException("数据任务状态不存在或异常。");return new JSONObject(new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8));}
    static void state(File dir,JSONObject data,String phase,String message)throws Exception{data.put("phase",phase).put("message",message);Owner.write(new File(dir,"state.json"),data.toString());}
    static boolean running()throws Exception{
        Files.createDirectories(HOME.toPath());try(RandomAccessFile file=new RandomAccessFile(new File(HOME,"job.lock"),"rw");FileLock lock=file.getChannel().tryLock()){return lock==null;}catch(OverlappingFileLockException busy){return true;}
    }
    static PackageInfo owner(Context ctx)throws Exception{
        if(Owner.check(ctx)!=1)throw new IOException("无法确认应用所属用户或签名。");int user=Integer.parseInt(Owner.read(new File(Owner.BASE,"owner.user")));
        PackageInfo info=(PackageInfo)ctx.getPackageManager().getClass().getMethod("getPackageInfoAsUser",String.class,int.class,int.class).invoke(ctx.getPackageManager(),Owner.PACKAGE,PackageManager.GET_SIGNING_CERTIFICATES,user);
        if(info.applicationInfo==null||info.applicationInfo.uid/100000!=user)throw new IOException("应用用户不匹配。");return info;
    }
    static void command(String... args)throws Exception{
        Process process=new ProcessBuilder(args).redirectErrorStream(true).start();ProcessResponse result=ProcessResponse.read(process,OperationTimeout.deadline(),65536);
        if(result.code!=0)throw new IOException("系统操作失败："+String.join(" ",args)+"\n"+result.text);
    }
    static void launch(int user)throws Exception{command("/system/bin/am","start","--user",""+user,"-f","0x30000000","-n",Owner.PACKAGE+"/.MainActivity");}
    static void waitUiStopped(int uid)throws Exception{
        long deadline=OperationTimeout.deadline();
        for(;;){
            boolean alive=false;File[] processes=new File("/proc").listFiles();if(processes==null)throw new IOException("无法确认前台数据文件是否已释放。");
            for(File process:processes)if(process.getName().matches("[0-9]+"))try{String text=Owner.read(new File(process,"status"));for(String line:text.split("\n"))if(line.startsWith("Uid:")&&Integer.parseInt(line.substring(4).trim().split("\\s+")[0])==uid){alive=true;break;}}catch(IOException|NumberFormatException ignored){}
            if(!alive)return;Thread.sleep(Math.min(100,OperationTimeout.remaining(deadline)));
        }
    }
    static void waitUiReady(File app,String token)throws Exception{
        long deadline=OperationTimeout.deadline();File ready=new File(app,"no_backup/acol-transfer.ready");
        for(;;){if(ready.isFile()&&Owner.read(ready).equals(token))return;Thread.sleep(Math.min(100,OperationTimeout.remaining(deadline)));}
    }
    static void launcher()throws Exception{command(new File(Owner.RUNTIME,"bin/aco-launcher").getPath(),new File(Owner.RUNTIME,"transfer.sh").getPath());}
    static JSONObject start(String mode,String token)throws Exception{
        if(!mode.equals("backup")&&!mode.equals("restore"))throw new IOException("数据操作无效。");
        if(PENDING.exists()||running()||new File(Owner.BASE,"update.transaction").exists())throw new IOException("已有后台任务正在进行，请稍后重试。");
        PackageInfo info=owner(Admin.context());File app=new File(info.applicationInfo.dataDir).getCanonicalFile(),dir=job(token);Files.createDirectories(dir.toPath());
        if(new File(dir,"state.json").exists())throw new IOException("数据任务已存在。");
        JSONObject data=new JSONObject().put("token",token).put("mode",mode).put("created",System.currentTimeMillis()).put("app",app.getPath()).put("uid",info.applicationInfo.uid).put("version",info.versionName).put("safe",false);
        state(dir,data,"queued","正在准备数据任务");Owner.write(PENDING,token);
        try{launcher();}catch(Exception failure){Files.deleteIfExists(PENDING.toPath());throw failure;}return new JSONObject().put("ok",true);
    }
    static JSONObject status(String token)throws Exception{
        File file=new File(job(token),"state.json");if(!file.isFile()&&(!PENDING.exists()||!Owner.read(PENDING).equals(token)))return new JSONObject().put("ok",true).put("phase","failed").put("safe",true).put("message","任务未启动，现有数据未更改。");
        JSONObject data=read(file);boolean alive=running();String phase=data.optString("phase");
        return data.put("ok",true).put("running",alive).put("orphaned",!alive&&!Arrays.asList("done","failed").contains(phase)&&System.currentTimeMillis()-data.optLong("created")>5000);
    }
    static void unpend(String token)throws Exception{if(PENDING.exists()&&Owner.read(PENDING).equals(token))Files.delete(PENDING.toPath());}
    static void ownership(File file,int uid)throws Exception{
        if(Files.isSymbolicLink(file.toPath()))throw new IOException("恢复数据包含异常链接。");
        Os.chown(file.getPath(),uid,uid);Os.chmod(file.getPath(),file.isDirectory()?0700:0600);
        if(file.isDirectory()){File[] children=file.listFiles();if(children==null)throw new IOException("无法设置恢复数据权限。");for(File child:children)ownership(child,uid);}
    }
    static void fixApp(File app,int uid)throws Exception{
        for(String name:DataArchive.APP_DIRS){File file=new File(app,name);if(file.exists())ownership(file,uid);}
        command("/system/bin/restorecon","-RF",app.getPath());
    }
    static void snapshot(File app,File snapshot)throws Exception{
        File roots=new File(snapshot,"root"),apps=new File(snapshot,"app");Files.createDirectories(roots.toPath());Files.createDirectories(apps.toPath());
        File[] files=Owner.BASE.listFiles();if(files==null)throw new IOException("无法读取后台数据。");
        for(File file:files)if(!DataArchive.ROOT_KEEP.contains(file.getName()))DataArchive.copyTree(file,new File(roots,file.getName()),()->{});
        for(String name:DataArchive.APP_DIRS){
            File source=new File(app,name),target=new File(apps,name);if(!source.exists())continue;
            if(name.equals("shared_prefs")){
                Files.createDirectories(target.toPath());File[] prefs=source.listFiles();if(prefs==null)throw new IOException("无法读取设置文件。");
                for(File pref:prefs)if(!pref.getName().startsWith("startup.xml")&&!pref.getName().startsWith("maintenance.xml"))DataArchive.copyTree(pref,new File(target,pref.getName()),()->{});
            }else DataArchive.copyTree(source,target,()->{});
        }
    }
    static void validateDatabase(File incoming)throws Exception{
        File file=new File(incoming,"root/records.db");SafeDatabase.inspectExisting(file);
        try(SQLiteDatabase db=SafeDatabase.open(file)){
            try(android.database.Cursor check=db.rawQuery("PRAGMA quick_check(1)",null)){if(!check.moveToFirst()||!"ok".equalsIgnoreCase(check.getString(0)))throw new IOException("备份数据库校验失败。");}
            for(String name:new String[]{"sessions","samples","runtime"})try(android.database.Cursor tables=db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name=?",new String[]{name})){if(!tables.moveToFirst())throw new IOException("备份数据库缺少必要数据表。");}
            db.execSQL("UPDATE sessions SET active=0,reason='backup_restored' WHERE active=1");db.execSQL("DELETE FROM runtime WHERE key IN ('session_progress_v1','full_tail_boot','cell_count_v1')");
            try(android.database.Cursor checkpoint=db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)",null)){if(!checkpoint.moveToFirst()||checkpoint.getInt(0)!=0)throw new IOException("备份数据库尚未完成写入。");}
        }
    }
    static void run()throws Exception{
        if(!PENDING.isFile())return;String token=Owner.read(PENDING);File dir=job(token);Files.createDirectories(HOME.toPath());
        try(RandomAccessFile lockFile=new RandomAccessFile(new File(HOME,"job.lock"),"rw");FileLock lock=lockFile.getChannel().tryLock()){
            if(lock==null)return;JSONObject data=read(new File(dir,"state.json"));Context ctx=Admin.context();PackageInfo info=owner(ctx);File app=new File(info.applicationInfo.dataDir).getCanonicalFile();int user=info.applicationInfo.uid/100000;DataRestore restore=null;boolean safe=false;
            try{
                if(!app.getPath().equals(data.getString("app")))throw new IOException("应用数据目录发生变化。");
                restore=new DataRestore(Owner.BASE,app,dir,token);
                if(data.optBoolean("recover")||!data.optString("phase").equals("queued")){
                    boolean committed=restore.committed();data.put("restored",committed);
                    state(dir,data,"recovering","正在恢复中断的数据操作");Admin.stopForUpdate();restore.recover();fixApp(app,info.applicationInfo.uid);restore.clean();safe=true;unpend(token);Admin.ensure();
                    state(dir,data.put("safe",true),committed?"done":"failed",committed?"数据已恢复。":"上次操作中断，已恢复操作前的数据。");return;
                }
                Files.deleteIfExists(new File(app,"no_backup/acol-transfer.ready").toPath());
                state(dir,data,"stopping_ui","正在释放前台数据文件");command("/system/bin/am","force-stop","--user",""+user,Owner.PACKAGE);waitUiStopped(info.applicationInfo.uid);launch(user);waitUiReady(app,token);
                String mode=data.getString("mode");File incoming=new File(dir,"incoming");
                if(mode.equals("restore")){
                    state(dir,data,"validating","正在校验并准备恢复数据");File input=new File(app,"cache/acol-input-"+token+".zip");
                    JSONObject meta=DataArchive.inspect(input,null,()->{});if(Owner.BASE.getUsableSpace()<meta.getLong("bytes")*2+input.length())throw new IOException("存储空间不足，未覆盖现有数据。");
                    Files.createDirectories(incoming.toPath());DataArchive.inspect(input,incoming,()->{});validateDatabase(incoming);
                }
                state(dir,data,"stopping","正在暂停独立后台");Admin.stopForUpdate();
                try(RandomAccessFile databaseLock=new RandomAccessFile(new File(Owner.BASE,"daemon.lock"),"rw");FileLock exclusive=databaseLock.getChannel().tryLock()){
                    if(exclusive==null)throw new IOException("后台尚未释放数据文件。");
                    if(mode.equals("backup")){
                        state(dir,data,"snapshot","正在建立完整数据快照");File snap=new File(dir,"snapshot");snapshot(app,snap);
                    }else{
                        state(dir,data,"restoring","正在覆盖恢复数据");restore.prepare(()->{});restore.apply(()->{});fixApp(app,info.applicationInfo.uid);restore.commit();data.put("restored",true);safe=true;
                    }
                }
                unpend(token);Admin.ensure();
                if(mode.equals("backup")){
                    state(dir,data,"packing","后台已恢复，正在压缩数据包");File output=new File(dir,"backup.acoldata");JSONObject meta=DataArchive.pack(new File(dir,"snapshot"),output,data.optString("version"),()->{});
                    DataArchive.inspect(output,null,()->{});File exported=new File(app,"cache/acol-backup-"+token+".zip");DataArchive.copyTree(output,exported,()->{});ownership(exported,info.applicationInfo.uid);command("/system/bin/restorecon",exported.getPath());data.put("backupCreated",meta.getLong("created")).put("bytes",output.length());
                }else restore.clean();
                safe=true;state(dir,data.put("safe",true),"done",mode.equals("backup")?"数据包已生成，请选择保存位置。":"数据已恢复。");
            }catch(Throwable failure){
                String error=StartupFailure.details(failure);
                boolean committed=data.optBoolean("restored");
                if(restore!=null)try{committed=restore.committed();}catch(Throwable check){error+="\n读取恢复提交状态失败：\n"+StartupFailure.details(check);}
                try{
                    if(restore!=null){restore.recover();fixApp(app,info.applicationInfo.uid);restore.clean();}safe=true;unpend(token);Admin.ensure();
                }catch(Throwable recovery){error+="\n恢复处理失败：\n"+StartupFailure.details(recovery);}
                state(dir,data.put("safe",safe).put("restored",committed).put("error",error),safe?"failed":"recovery_required",
                    committed?"数据已恢复，但后续处理未完成，请查看错误报告。":safe?"操作未完成，已保留操作前的数据。":"操作中断，请继续恢复处理。");
            }finally{
                // The progress Activity may have been closed while the detached worker continued.
                try{launch(user);}catch(Exception ignored){}
            }
        }
    }
    static JSONObject handle(String[] args)throws Exception{
        String action=args[0];
        if(action.equals("transfer-run")){
            try{run();}catch(Throwable failure){
                if(PENDING.isFile())try{
                    String token=Owner.read(PENDING);File directory=job(token);JSONObject data=read(new File(directory,"state.json"));boolean unchanged=!new File(directory,"journal.json").exists();
                    if(unchanged){unpend(token);try{Admin.ensure();}catch(Exception ignored){}}
                    state(directory,data.put("safe",unchanged).put("error",StartupFailure.details(failure)),unchanged?"failed":"recovery_required",unchanged?"数据任务未能完成，原有数据未被覆盖。":"无法继续数据任务，请查看错误报告后重试恢复处理。");
                }catch(Exception ignored){}
                throw failure;
            }
            return new JSONObject().put("ok",true);
        }
        if(action.equals("transfer-start")&&args.length==3)return start(args[1],args[2]);
        if(args.length!=2)throw new IOException("数据任务参数无效。");String token=args[1];File dir=job(token);
        if(action.equals("transfer-status"))return status(token);
        if(action.equals("transfer-recover")){
            if(running())return new JSONObject().put("ok",true);JSONObject data=read(new File(dir,"state.json"));data.put("recover",true);Owner.write(new File(dir,"state.json"),data.toString());Owner.write(PENDING,token);launcher();return new JSONObject().put("ok",true);
        }
        if(action.equals("transfer-clean")){
            JSONObject data=read(new File(dir,"state.json"));if(running()||!data.optBoolean("safe")||PENDING.exists())throw new IOException("数据任务尚未结束。");DataArchive.removeTree(HOME,dir);return new JSONObject().put("ok",true);
        }
        throw new IOException("数据任务操作无效。");
    }
}
