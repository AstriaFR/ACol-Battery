package cn.astriafr.acobattery.common;

import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;
import org.json.*;

public final class DataTransferTest {
    static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    static void put(Path path,String text)throws Exception{Files.createDirectories(path.getParent());Files.write(path,text.getBytes(StandardCharsets.UTF_8));}
    static String text(Path path)throws Exception{return new String(Files.readAllBytes(path),StandardCharsets.UTF_8);}
    static void altered(File from,File to,String entryName,byte[] replacement)throws Exception{
        try(ZipFile source=new ZipFile(from);ZipOutputStream out=new ZipOutputStream(new FileOutputStream(to))){Enumeration<? extends ZipEntry> files=source.entries();while(files.hasMoreElements()){ZipEntry file=files.nextElement();out.putNextEntry(new ZipEntry(file.getName()));if(file.getName().equals(entryName))out.write(replacement);else try(InputStream in=source.getInputStream(file)){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)out.write(b,0,n);}out.closeEntry();}}
    }
    public static void main(String[] args)throws Exception{
        Path parent=Paths.get(args[0]).toAbsolutePath().normalize();Files.createDirectories(parent);Path test=Files.createTempDirectory(parent,"transfer-");check(test.toRealPath().startsWith(parent.toRealPath()),"Test escaped build output");
        try{
            Path snap=test.resolve("snapshot");put(snap.resolve("root/records.db"),"backup-records");put(snap.resolve("root/error-logs/log.txt"),"后台错误");put(snap.resolve("app/files/error-logs/example.txt"),"完整报告");put(snap.resolve("app/app_webview/Default/IndexedDB/day.bin"),"逐小时使用时间");put(snap.resolve("app/shared_prefs/user.xml"),"theme and choices");
            File archive=test.resolve("backup.acoldata").toFile();JSONObject manifest=DataArchive.pack(snap.toFile(),archive,"test",()->{});check(DataArchive.inspect(archive,null,()->{}).getLong("bytes")==manifest.getLong("bytes"),"Archive round trip failed");
            File broken=test.resolve("broken.zip").toFile();altered(archive,broken,"root/records.db","modified".getBytes(StandardCharsets.UTF_8));try{DataArchive.inspect(broken,null,()->{});throw new AssertionError("Corruption accepted");}catch(IOException expected){}
            for(String forbidden:new String[]{"root/../runtime/service.sh","root/runtime/evil","root/owner.cert","app/shared_prefs/maintenance.xml","app/../files/evil","app/code_cache/evil"})try{DataArchive.validName(forbidden);throw new AssertionError("Unsafe archive path accepted: "+forbidden);}catch(IOException expected){}
            Path root=test.resolve("root"),app=test.resolve("app");put(root.resolve("records.db"),"original-records");put(root.resolve("runtime/program"),"protected-program");put(root.resolve("recording.disabled"),"old setting");put(app.resolve("files/old.txt"),"original-app");put(app.resolve("app_webview/old.bin"),"original-hours");put(app.resolve("no_backup/acol-transfer.pending"),"operation-marker");
            String token=UUID.randomUUID().toString();Path job=root.resolve("transfer/"+token);Files.createDirectories(job);DataArchive.inspect(archive,job.resolve("incoming").toFile(),()->{});DataRestore restore=new DataRestore(root.toFile(),app.toFile(),job.toFile(),token);restore.prepare(()->{});
            int[] steps={0};try{restore.apply(()->{if(++steps[0]==3)throw new IOException("simulated interruption");});throw new AssertionError("Interruption missing");}catch(IOException expected){}
            restore.recover();restore.recover();check(text(root.resolve("records.db")).equals("original-records")&&text(app.resolve("app_webview/old.bin")).equals("original-hours"),"Interrupted overwrite did not roll back");restore.clean();
            String token2=UUID.randomUUID().toString();Path job2=root.resolve("transfer/"+token2);Files.createDirectories(job2);DataArchive.inspect(archive,job2.resolve("incoming").toFile(),()->{});DataRestore success=new DataRestore(root.toFile(),app.toFile(),job2.toFile(),token2);success.prepare(()->{});success.apply(()->{});success.commit();success.recover();success.clean();
            check(text(root.resolve("records.db")).equals("backup-records"),"Root data was not replaced");check(!Files.exists(root.resolve("recording.disabled")),"Obsolete root setting remained");check(text(app.resolve("app_webview/Default/IndexedDB/day.bin")).equals("逐小时使用时间"),"Hourly cache missing");check(!Files.exists(app.resolve("files/old.txt")),"Restore merged old app files instead of replacing");check(text(root.resolve("runtime/program")).equals("protected-program")&&text(app.resolve("no_backup/acol-transfer.pending")).equals("operation-marker"),"Runtime or operation marker replaced");
            if(args.length==3){File payload=test.resolve("payload").toFile();payload.mkdir();RuntimePayload.extract(new File(args[1]),payload,args[2]);check(new File(payload,"transfer.sh").isFile(),"Transfer worker missing from actual runtime payload");RuntimePayload.discard(payload);}
            System.out.println("Data transfer checks passed: archive round trip, corruption and path rejection, interrupted rollback, committed replacement and protected runtime");
        }finally{try(java.util.stream.Stream<Path> paths=Files.walk(test)){for(Path path:(Iterable<Path>)paths.sorted(Comparator.reverseOrder())::iterator){if(!path.toAbsolutePath().normalize().startsWith(test))throw new IOException("Test cleanup escaped temporary directory");Files.deleteIfExists(path);}}}
    }
}
