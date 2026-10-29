package cn.astriafr.acobattery.daemon;

import android.database.Cursor;
import android.database.sqlite.*;
import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;

/** Run only on the phone against a dedicated path inside the independent test app. */
public final class SafeDatabaseDeviceTest {
    static void check(boolean value,String text){if(!value)throw new AssertionError(text);}
    public static void main(String[] args)throws Exception{
        check(args.length==1,"A dedicated test path is required");
        File root=new File(args[0]);check(root.getCanonicalPath().equals("/data/adb/aco_battery_init_test/database-safety-test"),"Test path rejected");
        check(!root.exists(),"Refusing to overwrite previous test files");check(root.mkdir(),"Test directory unavailable");
        try{
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
