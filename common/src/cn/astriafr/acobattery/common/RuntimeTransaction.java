package cn.astriafr.acobattery.common;

import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.Properties;

/** Recoverable runtime replacement. Never copies, modifies or deletes record data. */
public final class RuntimeTransaction {
    public interface Control { void stop()throws Exception; void start()throws Exception; }
    public interface OwnerAlive { boolean alive(String token); }
    interface Access { void set(File file,boolean executable)throws IOException; }
    private final Access access;
    private final File base,runtime,stage,backup,boot,transaction;
    public RuntimeTransaction(File base,File boot){
        this(base,boot,(file,executable)->{
            if(!file.setReadable(false,false)||!file.setWritable(false,false)||!file.setExecutable(false,false)
                ||!file.setReadable(true,true)||!file.setWritable(true,true)||executable&&!file.setExecutable(true,true))throw new IOException("无法设置后台运行文件权限。");
        });
    }
    RuntimeTransaction(File base,File boot,Access access){
        this.access=access;
        this.base=base;this.boot=boot;runtime=new File(base,"runtime");stage=new File(runtime,"update-stage");
        backup=new File(base,"runtime.rollback");transaction=new File(base,"update.transaction");
    }
    private interface Action { void run()throws Exception; }
    private void locked(Action action)throws Exception{
        try(RandomAccessFile file=new RandomAccessFile(new File(base,"update.lock"),"rw")){
            FileLock lock;
            try{lock=file.getChannel().tryLock();}catch(OverlappingFileLockException busy){lock=null;}
            if(lock==null)throw new IOException("另一个初始化流程正在处理运行环境，请稍后重试。");
            try{action.run();}finally{lock.release();}
        }
    }
    private static void copy(File source,File target)throws IOException{
        Files.createDirectories(target.getParentFile().toPath());
        File next=new File(target.getPath()+".next");
        Files.copy(source.toPath(),next.toPath(),StandardCopyOption.REPLACE_EXISTING);
        // Atomic replacement preserves files already mapped by a running JVM.
        Files.move(next.toPath(),target.toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
    }
    private static void text(File file,String value)throws IOException{
        File next=new File(file.getPath()+".next");Files.write(next.toPath(),value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Files.move(next.toPath(),file.toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
    }
    private static void optional(File source,File target)throws IOException{if(source.isFile())copy(source,target);}
    private static void restore(File source,File target)throws IOException{if(source.isFile())copy(source,target);else Files.deleteIfExists(target.toPath());}
    private void permissions(File directory)throws IOException{
        for(String name:RuntimePayload.FILES){
            File file=new File(directory,name);boolean executable=name.endsWith(".sh")||name.endsWith("aco-launcher");
            access.set(file,executable);
        }
    }
    private void cleanBackup(){
        for(String name:RuntimePayload.FILES)try{Files.deleteIfExists(new File(backup,name+".next").toPath());}catch(IOException ignored){}
        for(String name:new String[]{"version","initialized","boot.entry","ready"})try{Files.deleteIfExists(new File(backup,name).toPath());Files.deleteIfExists(new File(backup,name+".next").toPath());}catch(IOException ignored){}
        RuntimePayload.discard(backup);
    }
    private Properties state()throws IOException{
        Properties state=new Properties();try(InputStream in=new FileInputStream(transaction)){state.load(in);}return state;
    }
    private void prepare(String version,String owner)throws Exception{
        for(String name:RuntimePayload.FILES)if(!new File(stage,name).isFile()||new File(stage,name).length()==0)throw new IOException("待更新的后台文件不完整。");
        cleanBackup();if(backup.exists())throw new IOException("无法准备后台恢复目录。");
        Files.createDirectories(new File(backup,"bin").toPath());
        for(String name:RuntimePayload.FILES)optional(new File(runtime,name),new File(backup,name));
        optional(new File(base,"runtime.version"),new File(backup,"version"));
        optional(new File(base,"initialized"),new File(backup,"initialized"));optional(boot,new File(backup,"boot.entry"));
        text(new File(backup,"ready"),"1");
        // Install the recovery-aware boot entry before suspending the old recorder.
        copy(new File(stage,"boot.sh"),boot);access.set(boot,true);
        Properties state=new Properties();state.setProperty("version",version);state.setProperty("owner",owner);
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();state.store(bytes,"ACol runtime transaction");
        text(transaction,new String(bytes.toByteArray(),java.nio.charset.StandardCharsets.ISO_8859_1));
        Files.deleteIfExists(new File(base,"update.installed").toPath());text(new File(base,"update.pending"),"1");
    }
    private void install()throws Exception{
        for(String name:RuntimePayload.FILES)copy(new File(stage,name),new File(runtime,name));
        permissions(runtime);text(new File(base,"update.installed"),"1");
    }
    private void rollback(Control control)throws Exception{
        if(!transaction.isFile())return;
        if(!new File(backup,"ready").isFile())throw new IOException("缺少完整的后台恢复文件，已保留更新状态。");
        control.stop();
        for(String name:RuntimePayload.FILES)restore(new File(backup,name),new File(runtime,name));
        restore(new File(backup,"version"),new File(base,"runtime.version"));
        restore(new File(backup,"initialized"),new File(base,"initialized"));restore(new File(backup,"boot.entry"),boot);
        if(boot.isFile())access.set(boot,true);
        if(new File(runtime,"bin/daemon.dex.jar").isFile())permissions(runtime);
        clearPending();
        // Retain the snapshot until restarting the restored recorder succeeds.
        if(new File(runtime,"bin/daemon.dex.jar").isFile())control.start();
        Files.deleteIfExists(transaction.toPath());cleanBackup();
    }
    private void clearPending()throws IOException{
        Files.deleteIfExists(new File(base,"update.pending").toPath());Files.deleteIfExists(new File(base,"update.installed").toPath());
    }
    public void apply(String version,String owner,Control control)throws Exception{
        locked(()->{
            if(transaction.exists())throw new IOException("上次后台更新尚未恢复，请重新打开应用。");
            try{
                prepare(version,owner);control.stop();install();control.start();
            }catch(Exception failure){
                try{
                    if(transaction.isFile())rollback(control);
                    else if(new File(backup,"ready").isFile()){restore(new File(backup,"boot.entry"),boot);if(boot.isFile())access.set(boot,true);cleanBackup();}
                }catch(Exception recovery){failure.addSuppressed(recovery);}
                throw failure;
            }
        });
    }
    public void commit(String owner)throws Exception{
        locked(()->{
            if(!transaction.isFile())return;
            Properties state=state();if(!owner.equals(state.getProperty("owner")))throw new IOException("后台更新会话不匹配，已取消提交。");
            text(new File(base,"runtime.version"),state.getProperty("version"));text(new File(base,"initialized"),"1");
            clearPending();Files.deleteIfExists(transaction.toPath());cleanBackup();RuntimePayload.discard(stage);
        });
    }
    public void recover(String owner,OwnerAlive alive,Control control)throws Exception{
        locked(()->{
            if(!transaction.isFile())return;
            String previous=state().getProperty("owner","");
            if(!owner.equals(previous)&&alive.alive(previous))throw new IOException("后台正在更新，请等待当前初始化完成。");
            rollback(control);
        });
    }
    public void discard()throws Exception{locked(()->{clearPending();Files.deleteIfExists(transaction.toPath());cleanBackup();});}
}
