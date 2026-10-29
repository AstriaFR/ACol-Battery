package cn.astriafr.acobattery.common;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;
import org.json.JSONObject;

/** Explicit database reset: verified backup first; interrupted resets block normal opening. */
public final class DatabaseRebuild {
    public static final String PENDING="database.rebuild.pending";
    public interface Stop {void stop()throws Exception;}
    public interface Guard {void check(String phase)throws Exception;}
    private DatabaseRebuild(){}
    static String[] names(String database){
        if(!database.matches("[a-zA-Z0-9._-]+\\.db"))throw new IllegalArgumentException("数据库文件名无效。");
        return new String[]{database,database+"-wal",database+"-shm",database+"-journal"};
    }
    static File child(File directory,String name)throws IOException{
        File file=new File(directory,name);
        if(Files.isSymbolicLink(file.toPath())||!file.getCanonicalFile().getParentFile().equals(directory.getCanonicalFile()))throw new IOException("数据库路径异常，已取消重建。");
        return file;
    }
    static byte[] digest(File file,Guard guard)throws Exception{
        MessageDigest hash=MessageDigest.getInstance("SHA-256");byte[] bytes=new byte[32768];
        try(InputStream in=new FileInputStream(file)){int count;while((count=in.read(bytes))!=-1){guard.check("verify");hash.update(bytes,0,count);}}
        return hash.digest();
    }
    static void copy(File from,File to,Guard guard)throws Exception{
        MessageDigest hash=MessageDigest.getInstance("SHA-256");byte[] bytes=new byte[32768];
        try(InputStream in=new FileInputStream(from);FileOutputStream out=new FileOutputStream(to)){
            int count;while((count=in.read(bytes))!=-1){guard.check("copy");out.write(bytes,0,count);hash.update(bytes,0,count);}out.getFD().sync();
        }
        if(!Arrays.equals(hash.digest(),digest(to,guard)))throw new IOException("数据库备份校验失败，原文件未清理。");
    }
    static void remove(File file)throws IOException{
        // Never recurse into a database path that unexpectedly contains other files.
        if(file.exists())Files.delete(file.toPath());
    }
    static JSONObject load(File file)throws Exception{
        if(!file.isFile()||file.length()>16384)throw new IOException("数据库备份状态异常，请先手动备份数据。");
        return new JSONObject(new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8));
    }
    public static void checkPending(File directory)throws IOException{
        if(new File(directory,PENDING).exists())throw new IOException("数据库重建未完成，原文件已有备份，请重新点击重建数据库。");
    }
    static void recover(File directory,String database,Guard guard)throws Exception{
        File marker=new File(directory,PENDING);if(!marker.exists())return;
        String name=load(marker).getString("backup");
        if(!name.matches("db-[0-9]+-[0-9a-f-]{36}"))throw new IOException("数据库备份路径异常，已取消重建。");
        File backups=child(directory,"database-backups"),backup=child(backups,name);
        JSONObject manifest=load(child(backup,"complete.json"));
        if(!database.equals(manifest.getString("database")))throw new IOException("数据库备份类型不匹配。");
        JSONObject entries=manifest.getJSONObject("entries");
        // Validate every source before changing any live file.
        for(String entry:names(database)){
            String type=entries.getString(entry);File source=child(backup,entry);
            if(type.equals("file")){if(!source.isFile()||!hex(digest(source,guard)).equals(manifest.getJSONObject("hashes").getString(entry)))throw new IOException("原数据库备份校验失败，已停止恢复。");}
            else if(type.equals("directory")){if(!source.isDirectory()||source.list()==null||source.list().length!=0)throw new IOException("原数据库备份目录异常。");}
            else if(!type.equals("absent"))throw new IOException("原数据库备份状态无效。");
            File target=child(directory,entry);if(target.isDirectory()&&(target.list()==null||target.list().length!=0))throw new IOException("数据库路径是非空目录，请先手动检查。");
        }
        for(String entry:names(database)){
            guard.check("restore:"+entry);String type=entries.getString(entry);File target=child(directory,entry);remove(target);
            if(type.equals("file"))copy(child(backup,entry),target,guard);
            else if(type.equals("directory")&&!target.mkdir())throw new IOException("数据库目录恢复失败。");
        }
        Files.delete(marker.toPath());
    }
    static String hex(byte[] bytes){StringBuilder text=new StringBuilder();for(byte b:bytes)text.append(String.format(Locale.ROOT,"%02x",b&255));return text.toString();}
    static String stagedName(String token)throws IOException{
        if(!token.matches("[0-9a-f-]{36}"))throw new IOException("数据库备份标识无效。");
        return "db-0-"+token;
    }
    static void removeTree(File dir)throws IOException{
        if(!dir.exists())return;
        if(Files.isSymbolicLink(dir.toPath()))throw new IOException("数据库备份路径异常。");
        File[] files=dir.listFiles();if(files==null)throw new IOException("无法读取临时备份。");
        for(File file:files){if(file.isDirectory())removeTree(file);else Files.delete(file.toPath());}
        Files.delete(dir.toPath());
    }
    public static JSONObject prepareExternal(File directory,String database,String token,Stop stop,Guard guard)throws Exception{
        names(database);guard.check("stop");stop.stop();recover(directory,database,guard);
        File backups=child(directory,"database-backups");Files.createDirectories(backups.toPath());
        File backup=child(backups,stagedName(token));if(!backup.mkdir())throw new IOException("无法建立临时数据库备份。");
        File archive=child(directory,"rebuild-export-"+token+".zip");boolean complete=false;
        try{
            JSONObject entries=new JSONObject(),hashes=new JSONObject();
            for(String entry:names(database)){
                guard.check("backup:"+entry);File source=child(directory,entry),target=child(backup,entry);
                if(source.isFile()){copy(source,target,guard);entries.put(entry,"file");hashes.put(entry,hex(digest(target,guard)));}
                else if(!source.exists())entries.put(entry,"absent");
                else throw new IOException("数据库路径不是普通文件，请先手动检查。");
            }
            JSONObject manifest=new JSONObject().put("database",database).put("entries",entries).put("hashes",hashes);
            StartupFailure.write(child(backup,"complete.json"),manifest.toString());
            try(FileOutputStream file=new FileOutputStream(archive);ZipOutputStream zip=new ZipOutputStream(file)){
                for(String entry:names(database))if(entries.getString(entry).equals("file")){
                    zip.putNextEntry(new ZipEntry(entry));try(InputStream in=new FileInputStream(child(backup,entry))){byte[] buf=new byte[32768];int n;while((n=in.read(buf))!=-1){guard.check("archive");zip.write(buf,0,n);}}zip.closeEntry();
                }
                zip.putNextEntry(new ZipEntry("complete.json"));zip.write(manifest.toString().getBytes(StandardCharsets.UTF_8));zip.closeEntry();zip.finish();file.getFD().sync();
            }
            String hash=hex(digest(archive,guard));complete=true;
            return new JSONObject().put("ok",true).put("size",archive.length()).put("sha256",hash);
        }finally{if(!complete){Files.deleteIfExists(archive.toPath());removeTree(backup);}}
    }
    public static void cancelExternal(File directory,String token)throws Exception{
        if(new File(directory,PENDING).exists())return;
        File backups=child(directory,"database-backups"),backup=child(backups,stagedName(token));
        Files.deleteIfExists(child(directory,"rebuild-export-"+token+".zip").toPath());removeTree(backup);
    }
    public static void commitExternal(File directory,String database,String token,Stop stop,Guard guard)throws Exception{
        guard.check("stop");stop.stop();checkPending(directory);
        File backup=child(child(directory,"database-backups"),stagedName(token));JSONObject manifest=load(child(backup,"complete.json"));
        if(!database.equals(manifest.getString("database")))throw new IOException("数据库备份类型不匹配。");
        JSONObject entries=manifest.getJSONObject("entries"),hashes=manifest.getJSONObject("hashes");
        for(String entry:names(database)){
            guard.check("verify:"+entry);File current=child(directory,entry),copy=child(backup,entry);String type=entries.getString(entry);
            if(type.equals("absent")){if(current.exists())throw new IOException("数据库已发生变化，请重新备份。");}
            else if(type.equals("file")){String expected=hashes.getString(entry);if(!current.isFile()||!copy.isFile()||!expected.equals(hex(digest(current,guard)))||!expected.equals(hex(digest(copy,guard))))throw new IOException("数据库已发生变化，请重新备份。");}
            else throw new IOException("数据库备份状态异常。");
        }
        StartupFailure.write(new File(directory,PENDING),new JSONObject().put("backup",backup.getName()).toString());
        for(String entry:names(database)){guard.check("reset:"+entry);remove(child(directory,entry));}
        Files.delete(new File(directory,PENDING).toPath());
        try{cancelExternal(directory,token);}catch(Exception ignored){} // Cleanup must not turn a committed reset into a failed reset.
    }
    public static File rebuild(File directory,String database,Stop stop,Guard guard)throws Exception{
        names(database);guard.check("stop");stop.stop();recover(directory,database,guard);
        File backups=child(directory,"database-backups");Files.createDirectories(backups.toPath());
        File backup=child(backups,"db-"+System.currentTimeMillis()+"-"+UUID.randomUUID());if(!backup.mkdir())throw new IOException("无法建立数据库备份目录。");
        boolean committed=false;
        try{
            JSONObject entries=new JSONObject(),hashes=new JSONObject();
            for(String entry:names(database)){
                guard.check("backup:"+entry);File source=child(directory,entry),target=child(backup,entry);
                if(source.isFile()){copy(source,target,guard);entries.put(entry,"file");hashes.put(entry,hex(digest(target,guard)));}
                else if(source.isDirectory()){
                    if(source.list()==null||source.list().length!=0)throw new IOException("数据库路径是非空目录，请先手动检查。");
                    if(!target.mkdir())throw new IOException("数据库目录备份失败。");entries.put(entry,"directory");
                }else if(source.exists())throw new IOException("数据库文件类型异常。");else entries.put(entry,"absent");
            }
            JSONObject manifest=new JSONObject().put("database",database).put("entries",entries).put("hashes",hashes);
            StartupFailure.write(child(backup,"complete.json"),manifest.toString());
            guard.check("before-reset");StartupFailure.write(new File(directory,PENDING),new JSONObject().put("backup",backup.getName()).toString());committed=true;
            for(String entry:names(database)){guard.check("reset:"+entry);remove(child(directory,entry));}
            Files.delete(new File(directory,PENDING).toPath());return backup;
        }finally{
            // Incomplete backup leaves the originals untouched. A committed backup
            // and marker must survive errors so the next explicit retry can recover.
            if(!committed){File[] files=backup.listFiles();if(files!=null)for(File file:files)remove(file);remove(backup);}
        }
    }
}
