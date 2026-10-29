package cn.astriafr.acobattery.daemon;

import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteDatabaseCorruptException;
import android.database.sqlite.SQLiteCantOpenDatabaseException;
import java.io.File;

/** Stop on corruption; Android's default handler deletes the original database. */
final class SafeDatabase {
    static SQLiteDatabase.OpenParams parameters(int flags){
        return new SQLiteDatabase.OpenParams.Builder()
            .setOpenFlags(flags)
            .setJournalMode("WAL").setSynchronousMode("NORMAL")
            .setErrorHandler(database->{throw new SQLiteDatabaseCorruptException("记录数据库已损坏，已停止启动并保留原文件。");}).build();
    }
    static SQLiteDatabase open(File file){
        return SQLiteDatabase.openDatabase(file,parameters(SQLiteDatabase.CREATE_IF_NECESSARY|SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING));
    }
    static void inspectExisting(File file){
        if(!file.exists())return;
        if(file.isDirectory())throw new SQLiteCantOpenDatabaseException("数据库路径是目录："+file.getPath());
        try(SQLiteDatabase db=SQLiteDatabase.openDatabase(file,parameters(SQLiteDatabase.OPEN_READONLY));android.database.Cursor row=db.rawQuery("PRAGMA schema_version",null)){
            if(!row.moveToFirst())throw new SQLiteDatabaseCorruptException("无法读取原数据库结构，已保留原文件。");
        }
    }
}
