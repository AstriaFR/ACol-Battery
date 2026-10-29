package cn.astriafr.acobattery.common;

import java.io.*;
import java.nio.file.*;
import org.json.JSONObject;

public final class DatabaseRebuildTest {
    static final DatabaseRebuild.Guard OK=phase->{};
    static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    static void write(File file,String text)throws Exception{Files.write(file.toPath(),text.getBytes("UTF-8"));}
    static String read(File file)throws Exception{return new String(Files.readAllBytes(file.toPath()),"UTF-8");}
    static final class Crash extends Error{}
    static void cleanup(File root,File file)throws Exception{
        check(file.getCanonicalPath().equals(root.getCanonicalPath())||file.getCanonicalPath().startsWith(root.getCanonicalPath()+File.separator),"Cleanup target rejected");
        if(file.isDirectory())for(File child:file.listFiles())cleanup(root,child);Files.delete(file.toPath());
    }
    public static void main(String[] args)throws Exception{
        File root=Files.createTempDirectory("database-rebuild-").toFile();
        try{
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
