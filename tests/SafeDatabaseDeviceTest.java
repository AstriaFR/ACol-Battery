package cn.astriafr.acobattery.daemon;

import android.database.Cursor;
import android.database.sqlite.*;
import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;

/** Run only on the phone against a dedicated path inside the independent test app. */
public final class SafeDatabaseDeviceTest {
    static void check(boolean value,String text){if(!value)throw new AssertionError(text);}
    static long scalar(SQLiteDatabase db,String sql){try(Cursor row=db.rawQuery(sql,null)){check(row.moveToFirst(),"Missing scalar");return row.getLong(0);}}
    static void compact(File root)throws Exception{
        File base=new File(root,"compact");check(base.mkdir(),"Cannot create compaction fixture");File file=new File(base,"records.db");
        try{
            DataTransfer.compactDatabase(base);check(!file.exists(),"Compaction created a missing database");
            long before;
            try(SQLiteDatabase db=SafeDatabase.open(file)){
                // Some Android builds default to FULL auto-vacuum. Exercise retained free pages explicitly.
                db.execSQL("PRAGMA auto_vacuum=NONE");db.execSQL("VACUUM");
                db.execSQL("PRAGMA foreign_keys=ON");db.setVersion(16);
                db.execSQL("CREATE TABLE sessions(id INTEGER PRIMARY KEY AUTOINCREMENT,active INTEGER NOT NULL)");
                db.execSQL("CREATE TABLE samples(id INTEGER PRIMARY KEY,session INTEGER REFERENCES sessions(id),payload BLOB)");
                db.execSQL("CREATE INDEX sample_session ON samples(session)");
                db.execSQL("CREATE TABLE runtime(key TEXT PRIMARY KEY,value TEXT)");
                db.execSQL("INSERT INTO sessions VALUES(42,1)");db.execSQL("INSERT INTO runtime VALUES('module_settings','preserved')");
                db.beginTransaction();try{
                    for(int i=0;i<1024;i++)db.execSQL("INSERT INTO samples VALUES(?,42,zeroblob(4096))",new Object[]{i});
                    db.setTransactionSuccessful();
                }finally{db.endTransaction();}
                db.execSQL("DELETE FROM samples WHERE id>=4");check(scalar(db,"PRAGMA freelist_count")>0,"Fixture has no reclaimable pages");
                try(Cursor c=db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)",null)){check(c.moveToFirst()&&c.getInt(0)==0,"Fixture checkpoint failed");}
                before=file.length();
            }
            DataTransfer.compactDatabase(base);
            check(file.length()<before/2,"Live database was not compacted");
            check(!new File(base,"records.db-wal").exists()||new File(base,"records.db-wal").length()==0,"WAL not truncated");
            try(SQLiteDatabase db=SafeDatabase.open(file)){
                check(db.getVersion()==16,"Schema version changed");check(scalar(db,"SELECT id FROM sessions")==42&&scalar(db,"SELECT active FROM sessions")==1,"Active record identity changed");
                check(scalar(db,"SELECT COUNT(*) FROM samples")==4&&scalar(db,"SELECT SUM(length(payload)) FROM samples")==16384,"Sample data changed");
                check(scalar(db,"SELECT COUNT(*) FROM runtime WHERE key='module_settings' AND value='preserved'")==1,"Settings changed");
                check(scalar(db,"PRAGMA freelist_count")==0,"Free pages retained");
                try(Cursor c=db.rawQuery("PRAGMA foreign_key_check",null)){check(!c.moveToFirst(),"Foreign keys damaged");}
            }
            long compacted=file.length();DataTransfer.compactDatabase(base);check(file.length()==compacted,"Repeated compaction grew database");
            Files.delete(file.toPath());byte[] invalid="invalid database retained".getBytes("UTF-8");Files.write(file.toPath(),invalid);
            try{DataTransfer.compactDatabase(base);throw new AssertionError("Corrupt database was compacted");}catch(SQLiteDatabaseCorruptException expected){}
            check(Arrays.equals(invalid,Files.readAllBytes(file.toPath())),"Failed compaction changed corrupt original");
            System.out.println("Compaction: live file shrinks, WAL truncated, IDs/settings/samples preserved and repeat: PASS");
        }finally{cn.astriafr.acobattery.common.DataArchive.removeTree(root,base);}
    }
    public static void main(String[] args)throws Exception{
        check(args.length==1,"A dedicated test path is required");
        File root=new File(args[0]);String path=root.getCanonicalPath();check(path.equals("/data/adb/aco_battery_init_test/database-safety-test")||path.matches("/data/local/tmp/acol-database-maintenance-test-[a-zA-Z0-9-]+"),"Test path rejected");
        check(!root.exists(),"Refusing to overwrite previous test files");check(root.mkdir(),"Test directory unavailable");
        try{
            compact(root);
            File healthy=new File(root,"healthy.db");
            try(SQLiteDatabase db=SafeDatabase.open(healthy)){db.execSQL("CREATE TABLE sentinel(value INTEGER)");db.execSQL("INSERT INTO sentinel VALUES(42)");}
            byte[] healthyBytes=Files.readAllBytes(healthy.toPath());SafeDatabase.inspectExisting(healthy);check(Arrays.equals(healthyBytes,Files.readAllBytes(healthy.toPath())),"Read-only preflight changed healthy data");
            try(SQLiteDatabase db=SafeDatabase.open(healthy);Cursor row=db.rawQuery("SELECT value FROM sentinel",null)){check(row.moveToFirst()&&row.getInt(0)==42,"Healthy data did not survive reopen");}
            System.out.println("Healthy create / reopen / read: PASS");
            File broken=new File(root,"broken.db");byte[] original="not-a-sqlite-database\n".getBytes("UTF-8");Files.write(broken.toPath(),original);
            for(int i=0;i<2;i++){
                boolean failed=false;
                try{SafeDatabase.inspectExisting(broken);throw new AssertionError("Corrupt preflight succeeded");}catch(SQLiteDatabaseCorruptException expected){}
                check(Arrays.equals(original,Files.readAllBytes(broken.toPath())),"Preflight changed corrupt original");
                try(SQLiteDatabase db=SafeDatabase.open(broken)){db.rawQuery("PRAGMA schema_version",null).close();}
                catch(SQLiteDatabaseCorruptException expected){failed=true;}
                check(failed,"Corrupt database did not stop opening");check(Arrays.equals(original,Files.readAllBytes(broken.toPath())),"Corrupt original was deleted or rewritten");
            }
            System.out.println("Corruption fails twice / original bytes preserved: PASS");
            File directory=new File(root,"directory.db");check(directory.mkdir(),"Cannot create invalid DB path");boolean failed=false;
            try{SafeDatabase.inspectExisting(directory);throw new AssertionError("Directory preflight succeeded");}catch(SQLiteCantOpenDatabaseException expected){}
            try(SQLiteDatabase db=SafeDatabase.open(directory)){throw new AssertionError("Directory opened as database");}
            catch(SQLiteCantOpenDatabaseException expected){failed=true;}
            check(failed&&directory.isDirectory(),"Unopenable path was changed");System.out.println("Invalid database path fails / directory retained: PASS");
        }finally{
            for(File file:root.listFiles()){check(file.getCanonicalFile().getParentFile().equals(root.getCanonicalFile()),"Cleanup escaped test directory");Files.delete(file.toPath());}
            Files.delete(root.toPath());
        }
    }
}
