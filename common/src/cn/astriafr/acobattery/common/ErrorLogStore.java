package cn.astriafr.acobattery.common;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.json.*;

/** Small metadata and report bodies are stored separately for paged browsing. */
public final class ErrorLogStore {
    private final File directory;
    public ErrorLogStore(File directory){this.directory=directory;}
    private File child(String name)throws IOException{
        File file=new File(directory,name);
        if(Files.isSymbolicLink(directory.toPath())||Files.isSymbolicLink(file.toPath())||!file.getCanonicalFile().getParentFile().equals(directory.getCanonicalFile()))throw new IOException("错误日志路径异常。");
        return file;
    }
    private static void valid(String id)throws IOException{if(id==null||!id.matches("[0-9]{13,20}-[0-9a-f-]{36}"))throw new IOException("错误日志编号无效。");}
    public synchronized void append(String kind,String title,String report)throws Exception{
        synchronized(ErrorLogStore.class){
        if(report==null||report.trim().isEmpty())return;
        if(Files.isSymbolicLink(directory.toPath()))throw new IOException("错误日志目录异常。");
        Files.createDirectories(directory.toPath());long time=System.currentTimeMillis();String id=time+"-"+UUID.randomUUID();
        String text=StartupFeedback.compactReport(report);if(text.length()>524288)text=text.substring(0,524288)+"\n（报告过长，已截断）";
        StartupFailure.write(child(id+".txt"),text);
        try{StartupFailure.write(child(id+".json"),new JSONObject().put("id",id).put("time",time).put("kind",kind).put("title",title).toString());}
        catch(Exception failure){Files.deleteIfExists(child(id+".txt").toPath());throw failure;}
        }
    }
    public synchronized JSONObject list(int offset,int count)throws Exception{
        offset=Math.max(0,offset);count=Math.max(1,Math.min(50,count));JSONArray items=new JSONArray();
        if(!directory.exists())return new JSONObject().put("items",items).put("more",false).put("next",0);
        if(Files.isSymbolicLink(directory.toPath()))throw new IOException("错误日志目录异常。");
        String[] names=directory.list((dir,name)->name.matches("[0-9]{13,20}-[0-9a-f-]{36}\\.json"));if(names==null)throw new IOException("错误日志目录无法读取。");
        Arrays.sort(names,Collections.reverseOrder());int next=offset;
        for(;next<names.length&&items.length()<count;next++){
            File file=child(names[next]);if(file.length()>4096)continue;
            try{JSONObject meta=new JSONObject(new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8));valid(meta.getString("id"));items.put(meta);}catch(JSONException invalid){}
        }
        return new JSONObject().put("items",items).put("next",next).put("more",next<names.length);
    }
    public synchronized String read(String id)throws Exception{
        valid(id);File file=child(id+".txt");if(!file.isFile()||file.length()>2097152)throw new IOException("错误报告不存在或大小异常。");
        return new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8);
    }
    public synchronized void clear()throws IOException{
        synchronized(ErrorLogStore.class){
        if(!directory.exists())return;
        if(Files.isSymbolicLink(directory.toPath()))throw new IOException("错误日志目录异常。");
        String[] names=directory.list((dir,name)->name.matches("[0-9]{13,20}-[0-9a-f-]{36}\\.(json|txt)"));
        if(names==null)throw new IOException("错误日志目录无法读取。");
        for(String name:names)Files.deleteIfExists(child(name).toPath());
        }
    }
}
