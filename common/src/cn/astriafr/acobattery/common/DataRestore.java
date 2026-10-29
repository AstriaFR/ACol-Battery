package cn.astriafr.acobattery.common;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.json.*;

/** Durable per-directory replacement; original directories remain available until commit. */
public final class DataRestore {
    final File root,app,job,appStage,journal;
    public DataRestore(File root,File app,File job,String token)throws IOException{
        if(!token.matches("[0-9a-f-]{36}"))throw new IOException("恢复任务无效。");
        this.root=root;this.app=app;this.job=job;appStage=new File(app,".acol-restore-"+token);journal=new File(job,"journal.json");
        for(File directory:new File[]{root,app,job})if(Files.isSymbolicLink(directory.toPath())||!directory.isDirectory())throw new IOException("恢复目录异常。");
        if(Files.isSymbolicLink(appStage.toPath()))throw new IOException("应用恢复目录异常。");
    }
    public File appStage(){return appStage;}
    File path(JSONObject entry,String role)throws Exception{
        boolean local=entry.getString("area").equals("app");String name=entry.getString("name");
        if(!name.matches("[^/\\\\]+")||name.equals(".")||name.equals("..")||local&&!DataArchive.APP_DIRS.contains(name)||!local&&DataArchive.ROOT_KEEP.contains(name))throw new IOException("恢复清单路径异常。");
        File parent=role.equals("target")?(local?app:root):role.equals("incoming")?(local?new File(appStage,"incoming"):new File(job,"incoming/root")):(local?new File(appStage,"rollback"):new File(job,"rollback/root"));
        File value=new File(parent,name);if(Files.isSymbolicLink(value.toPath())||!value.getCanonicalFile().getParentFile().equals(parent.getCanonicalFile()))throw new IOException("恢复目标包含异常链接。");return value;
    }
    JSONObject state()throws Exception{
        if(!journal.isFile()||journal.length()>2*1024*1024)throw new IOException("恢复事务清单异常。");return new JSONObject(new String(Files.readAllBytes(journal.toPath()),StandardCharsets.UTF_8));
    }
    void writeState(JSONObject data)throws Exception{StartupFailure.write(journal,data.toString());try(FileOutputStream sync=new FileOutputStream(journal,true)){sync.getFD().sync();}}
    public void prepare(DataArchive.Guard guard)throws Exception{
        if(journal.exists())throw new IOException("恢复任务已经开始，请先完成恢复处理。");
        Files.createDirectories(new File(appStage,"incoming").toPath());Files.createDirectories(new File(appStage,"rollback").toPath());Files.createDirectories(new File(job,"rollback/root").toPath());
        for(String name:DataArchive.APP_DIRS){File input=new File(job,"incoming/app/"+name),target=new File(appStage,"incoming/"+name);if(input.exists())DataArchive.copyTree(input,target,guard);else Files.createDirectories(target.toPath());}
        Set<String> names=new TreeSet<>();File[] current=root.listFiles(),incoming=new File(job,"incoming/root").listFiles();if(current==null||incoming==null)throw new IOException("恢复数据目录无法读取。");
        for(File value:current)if(!DataArchive.ROOT_KEEP.contains(value.getName()))names.add(value.getName());for(File value:incoming)if(!DataArchive.ROOT_KEEP.contains(value.getName()))names.add(value.getName());
        JSONArray entries=new JSONArray();for(String name:names)entries.put(new JSONObject().put("area","root").put("name",name));for(String name:DataArchive.APP_DIRS)entries.put(new JSONObject().put("area","app").put("name",name));
        for(int i=0;i<entries.length();i++){JSONObject entry=entries.getJSONObject(i);entry.put("hadOld",path(entry,"target").exists());path(entry,"incoming");path(entry,"backup");}
        writeState(new JSONObject().put("committed",false).put("entries",entries));
    }
    public void apply(DataArchive.Guard guard)throws Exception{
        JSONArray entries=state().getJSONArray("entries");
        for(int i=0;i<entries.length();i++){
            guard.check();JSONObject entry=entries.getJSONObject(i);File target=path(entry,"target"),incoming=path(entry,"incoming"),backup=path(entry,"backup");
            if(entry.getBoolean("hadOld"))Files.move(target.toPath(),backup.toPath(),StandardCopyOption.ATOMIC_MOVE);
            if(incoming.exists())Files.move(incoming.toPath(),target.toPath(),StandardCopyOption.ATOMIC_MOVE);
        }
    }
    public void commit()throws Exception{JSONObject data=state();data.put("committed",true);writeState(data);}
    public void recover()throws Exception{
        if(!journal.exists())return;JSONObject data=state();if(data.optBoolean("committed"))return;JSONArray entries=data.getJSONArray("entries");
        for(int i=entries.length()-1;i>=0;i--){
            JSONObject entry=entries.getJSONObject(i);File target=path(entry,"target"),backup=path(entry,"backup");
            if(backup.exists()){if(target.exists())DataArchive.removeTree(entry.getString("area").equals("app")?app:root,target);Files.move(backup.toPath(),target.toPath(),StandardCopyOption.ATOMIC_MOVE);}
            else if(!entry.getBoolean("hadOld")&&target.exists())DataArchive.removeTree(entry.getString("area").equals("app")?app:root,target);
        }
    }
    public boolean committed()throws Exception{return journal.exists()&&state().optBoolean("committed");}
    public void clean()throws Exception{
        if(appStage.exists())DataArchive.removeTree(app,appStage);File rollback=new File(job,"rollback");if(rollback.exists())DataArchive.removeTree(job,rollback);
    }
}
