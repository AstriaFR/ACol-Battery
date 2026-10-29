package cn.astriafr.acobattery.daemon;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import java.io.*;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;

final class Owner {
    static final File BASE=new File("/data/adb/aco_battery_app");
    static final File RUNTIME=new File(BASE,"runtime");
    static final File BOOT=new File("/data/adb/service.d/aco_battery_app.sh");
    static final String PACKAGE="cn.astriafr.acobattery";
    static String read(File file)throws IOException{return new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8).trim();}
    static void write(File file,String text)throws IOException{
        File tmp=new File(file.getPath()+".next");Files.write(tmp.toPath(),text.getBytes(StandardCharsets.UTF_8));
        if(!tmp.renameTo(file))throw new IOException("state write failed");
    }
    /** 1 installed, 0 confirmed absent, -1 unavailable. Never infer absence from a shell error. */
    static int check(Context context){
        try{
            int user=Integer.parseInt(read(new File(BASE,"owner.user")));
            if(user<0||user>100000)return -1;
            PackageManager pm=context.getPackageManager();
            PackageInfo info=(PackageInfo)pm.getClass().getMethod("getPackageInfoAsUser",String.class,int.class,int.class)
                .invoke(pm,PACKAGE,PackageManager.GET_SIGNING_CERTIFICATES,user);
            if(info==null)return -1;
            String expected=read(new File(BASE,"owner.cert"));
            Signature[] signatures=info.signingInfo==null?info.signatures:info.signingInfo.getApkContentsSigners();
            if(signatures==null||signatures.length==0)return -1;
            byte[] digest=MessageDigest.getInstance("SHA-256").digest(signatures[0].toByteArray());
            StringBuilder actual=new StringBuilder();for(byte b:digest)actual.append(String.format("%02x",b&255));
            return expected.equals(actual.toString())?1:-1;
        }catch(InvocationTargetException e){return e.getCause() instanceof PackageManager.NameNotFoundException?0:-1;}
        catch(Exception e){return -1;}
    }
    static void markCleanup()throws IOException{
        write(new File(BASE,"cleanup.request"),"uninstalled\n");
        if(BOOT.exists()&&!BOOT.delete())throw new IOException("启动脚本移除失败");
    }
    static void removeTree(File file)throws IOException{
        String target=file.getCanonicalPath();
        if(!(target.equals(RUNTIME.getCanonicalPath())||target.startsWith(RUNTIME.getCanonicalPath()+"/")))throw new IOException("cleanup target rejected");
        if(file.isDirectory()){
            File[] children=file.listFiles();if(children==null)throw new IOException("cleanup unavailable");
            for(File child:children)removeTree(child);
        }
        if(file.exists()&&!file.delete())throw new IOException("cleanup failed");
    }
    static void cleanup()throws IOException{
        try{new cn.astriafr.acobattery.common.RuntimeTransaction(BASE,BOOT).discard();}
        catch(Exception e){throw new IOException("无法清理后台更新恢复文件。",e);}
        if(BOOT.exists()&&!BOOT.delete())throw new IOException("boot cleanup failed");
        removeTree(RUNTIME);
        for(String name:new String[]{"cleanup.request","daemon.pid","supervisor.pid","initialized","runtime.version","screen.event","screen.event.next","startup.attempt","startup.failure"})Files.deleteIfExists(new File(BASE,name).toPath());
        // records.db, WAL/SHM, settings, cell identification and owner identity stay.
    }
}
