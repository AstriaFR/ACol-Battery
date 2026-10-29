package cn.astriafr.acobattery.common;

import java.io.*;
import java.nio.file.*;
import java.util.UUID;
import java.util.zip.ZipFile;
import org.json.JSONObject;

public final class DatabaseRebuildTest {
    static final DatabaseRebuild.Guard OK=phase->{};
    static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    static void write(File file,String text)throws Exception{Files.write(file.toPath(),text.getBytes("UTF-8"));}
    static String read(File file)throws Exception{return new String(Files.readAllBytes(file.toPath()),"UTF-8");}
    static final class Crash extends Error{}
    static void externalBackup(File root)throws Exception{
        File dir=new File(root,"external");check(dir.mkdir(),"Cannot create external test directory");
        File db=new File(dir,"records.db"),wal=new File(dir,"records.db-wal");write(db,"original-db");write(wal,"original-wal");
        String token=UUID.randomUUID().toString();JSONObject prepared=DatabaseRebuild.prepareExternal(dir,"records.db",token,()->{},OK);
        File zip=new File(dir,"rebuild-export-"+token+".zip");check(prepared.getLong("size")==zip.length(),"Archive size mismatch");
        try(ZipFile archive=new ZipFile(zip)){check(archive.getEntry("records.db")!=null&&archive.getEntry("records.db-wal")!=null,"Archive missing database files");}
        check(read(db).equals("original-db")&&read(wal).equals("original-wal"),"Preparing backup modified database");
        DatabaseRebuild.cancelExternal(dir,token);check(!zip.exists()&&db.exists()&&wal.exists(),"Cancellation did not preserve the original database");
        token=UUID.randomUUID().toString();DatabaseRebuild.prepareExternal(dir,"records.db",token,()->{},OK);write(db,"changed-db");
        try{DatabaseRebuild.commitExternal(dir,"records.db",token,()->{},OK);throw new AssertionError("Changed database accepted");}catch(IOException expected){}
        check(read(db).equals("changed-db")&&wal.exists(),"Rejected commit modified the database");DatabaseRebuild.cancelExternal(dir,token);
        token=UUID.randomUUID().toString();DatabaseRebuild.prepareExternal(dir,"records.db",token,()->{},OK);
        try{DatabaseRebuild.commitExternal(dir,"records.db",token,()->{},phase->{if(phase.equals("reset:records.db-wal"))throw new Crash();});throw new AssertionError("Interruption not simulated");}catch(Crash expected){}
        try{DatabaseRebuild.checkPending(dir);throw new AssertionError("Interrupted reset did not block startup");}catch(IOException expected){}
        String retry=UUID.randomUUID().toString();DatabaseRebuild.prepareExternal(dir,"records.db",retry,()->{},OK);
        check(read(db).equals("changed-db")&&read(wal).equals("original-wal"),"Interrupted reset did not recover original files");
        DatabaseRebuild.commitExternal(dir,"records.db",retry,()->{},OK);
        check(!db.exists()&&!wal.exists()&&!new File(dir,"rebuild-export-"+retry+".zip").exists(),"Successful reset retained live database or temporary archive");
    }
    static void cleanup(File root,File file)throws Exception{
        check(file.getCanonicalPath().equals(root.getCanonicalPath())||file.getCanonicalPath().startsWith(root.getCanonicalPath()+File.separator),"Cleanup target rejected");
        if(file.isDirectory())for(File child:file.listFiles())cleanup(root,child);Files.delete(file.toPath());
    }
    public static void main(String[] args)throws Exception{
        File root=Files.createTempDirectory("database-rebuild-").toFile();
        try{
            externalBackup(root);
            File db=new File(root,"records.db"),wal=new File(root,"records.db-wal"),settings=new File(root,"ui-preferences.json");
            write(db,"database-original");write(wal,"wal-original");write(settings,"settings-original");int[] stopped={0};
            File backup=DatabaseRebuild.rebuild(root,"records.db",()->stopped[0]++,OK);
            check(stopped[0]==1&&!db.exists()&&!wal.exists(),"Reset ran before stopping or retained live files");
            check(read(new File(backup,"records.db")).equals("database-original")&&read(new File(backup,"records.db-wal")).equals("wal-original"),"Complete backup missing");
            check(read(settings).equals("settings-original"),"Settings were modified");DatabaseRebuild.checkPending(root);
            write(db,"second-db");write(wal,"second-wal");
            try{DatabaseRebuild.rebuild(root,"records.db",()->{},phase->{if(phase.equals("copy"))throw new IOException("disk full");});throw new AssertionError("Backup failure ignored");}
            catch(IOException expected){}
            check(read(db).equals("second-db")&&read(wal).equals("second-wal"),"Failed backup touched originals");
            check(!new File(root,DatabaseRebuild.PENDING).exists(),"Failed backup committed a reset");
            try{DatabaseRebuild.rebuild(root,"records.db",()->{},phase->{if(phase.equals("reset:records.db-wal"))throw new Crash();});throw new AssertionError("Crash not simulated");}
            catch(Crash expected){}
            check(!db.exists()&&wal.exists(),"Crash did not interrupt reset between files");
            try{DatabaseRebuild.checkPending(root);throw new AssertionError("Interrupted reset allowed normal opening");}catch(IOException expected){}
            File recovered=DatabaseRebuild.rebuild(root,"records.db",()->{},OK);
            check(read(new File(recovered,"records.db")).equals("second-db")&&read(new File(recovered,"records.db-wal")).equals("second-wal"),"Retry did not restore a coherent original before rebuilding");
            check(!new File(root,DatabaseRebuild.PENDING).exists(),"Successful retry kept reset marker");
            check(db.mkdir(),"Cannot create test database directory");File directoryBackup=DatabaseRebuild.rebuild(root,"records.db",()->{},OK);
            check(!db.exists()&&new File(directoryBackup,"records.db").isDirectory(),"Empty invalid database path was not backed up safely");
            check(db.mkdir(),"Cannot create nonempty DB directory");write(new File(db,"keep"),"do not remove");
            try{DatabaseRebuild.rebuild(root,"records.db",()->{},OK);throw new AssertionError("Nonempty database path was deleted");}catch(IOException expected){}
            check(read(new File(db,"keep")).equals("do not remove"),"Unknown directory contents were touched");
            Files.delete(new File(db,"keep").toPath());Files.delete(db.toPath());write(db,"live-original");
            StartupFailure.write(new File(root,DatabaseRebuild.PENDING),new JSONObject().put("backup","../outside").toString());
            try{DatabaseRebuild.rebuild(root,"records.db",()->{},OK);throw new AssertionError("Traversal backup accepted");}catch(IOException expected){}
            check(read(db).equals("live-original"),"Invalid recovery state touched live data");
        }finally{cleanup(root,root);}
        System.out.println("Database rebuild: verified DB/WAL backup, stop-first, untouched settings, backup failure, interruption blocking/retry, safe directory handling and path validation passed");
    }
}
