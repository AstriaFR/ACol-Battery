package cn.astriafr.acobattery.daemon;

import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteDatabaseCorruptException;
import android.database.sqlite.SQLiteCantOpenDatabaseException;
import java.io.File;
import java.io.IOException;

/** Stop on corruption; Android's default handler deletes the original database. */
final class SafeDatabase {
    static void initializeForStandaloneProcess(){
        // Before ActivityThread.systemMain() creates an Application, SQLite caches its
        // default WAL compatibility settings without contacting SettingsProvider.
        SQLiteDatabase.createInMemory(new SQLiteDatabase.OpenParams.Builder()
            .addOpenFlags(SQLiteDatabase.NO_LOCALIZED_COLLATORS).build()).close();
    }

    static SQLiteDatabase.OpenParams parameters(int flags){
        return new SQLiteDatabase.OpenParams.Builder()
            .setOpenFlags(flags)
            .setJournalMode("WAL").setSynchronousMode("NORMAL")
            .setErrorHandler(database->{throw new SQLiteDatabaseCorruptException("记录数据库已损坏，已停止启动并保留原文件。");}).build();
    }
    static SQLiteDatabase open(File file){
        return SQLiteDatabase.openDatabase(file,parameters(SQLiteDatabase.CREATE_IF_NECESSARY|SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING));
    }
    static SQLiteDatabase openRecords(File file)throws IOException{
        inspectExisting(file);
        SQLiteDatabase db=open(file);
        try{
            requireSupportedVersion(db);
            return db;
        }catch(IOException|RuntimeException failure){
            db.close();
            throw failure;
        }
    }

    static void requireSupportedVersion(SQLiteDatabase db)throws IOException{
        int version=db.getVersion();
        if(version>RecordStore.SCHEMA_VERSION)
            throw new IOException("记录数据库版本 "+version+" 高于当前支持的版本 "+RecordStore.SCHEMA_VERSION+"，请安装支持该数据库版本的 ACol 电池。");
    }

    static void inspectExisting(File file)throws IOException{
        if(!file.exists())return;
        if(file.isDirectory())throw new SQLiteCantOpenDatabaseException("数据库路径是目录："+file.getPath());
        try(SQLiteDatabase db=SQLiteDatabase.openDatabase(file,parameters(SQLiteDatabase.OPEN_READONLY));android.database.Cursor row=db.rawQuery("PRAGMA schema_version",null)){
            if(!row.moveToFirst())throw new SQLiteDatabaseCorruptException("无法读取原数据库结构，已保留原文件。");
            requireSupportedVersion(db);
        }
    }
}
