package cn.astriafr.acobattery.common;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.json.*;

public final class MaintenanceTest {
    static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    public static void main(String[] args)throws Exception{
        Path parent=Paths.get(args[0]).toAbsolutePath().normalize();Files.createDirectories(parent);Path task=Files.createTempDirectory(parent,"maintenance-");
        check(task.toRealPath().startsWith(parent.toRealPath()),"Temporary test directory escaped build output");
        try{
            ErrorLogStore logs=new ErrorLogStore(task.resolve("logs").toFile());
            logs.append("failure","读取失败","原始错误\n完整报告");logs.append("fallback","初始化降级","回退日志");
            JSONObject first=logs.list(0,1),second=logs.list(first.getInt("next"),1);
            check(first.getJSONArray("items").length()==1&&first.getBoolean("more"),"List must page metadata");
            check(second.getJSONArray("items").length()==1&&!second.getBoolean("more"),"Second page missing");
            String id=first.getJSONArray("items").getJSONObject(0).getString("id");check(!logs.read(id).isEmpty(),"Report detail missing");
            try{logs.read("../../outside");throw new AssertionError("Traversal accepted");}catch(IOException expected){}
            Path root=task.resolve("root");Files.createDirectories(root.resolve("runtime"));Files.write(root.resolve("runtime/program"),new byte[]{1});Files.write(root.resolve("records.db"),new byte[]{2});Files.createDirectories(root.resolve("database-backups/old"));Files.write(root.resolve("database-backups/old/records.db"),new byte[]{3});
            try{AllDataReset.clear(root.toFile(),Collections.singleton(".."),()->{});throw new AssertionError("Invalid preservation path accepted");}catch(IOException expected){}
            check(Files.exists(root.resolve("records.db")),"Preflight failure changed records");
            try{AllDataReset.clear(root.toFile(),Collections.singleton("runtime"),()->{throw new IOException("interrupted");});throw new AssertionError("Interruption not raised");}catch(IOException expected){}
            check(Files.exists(root.resolve(AllDataReset.PENDING)),"Interrupted cleanup must remain marked");
            Path outside=task.resolve("outside");Files.createDirectories(outside);Files.write(outside.resolve("keep"),new byte[]{4});
            try{Files.createSymbolicLink(root.resolve("link"),outside);}catch(IOException|UnsupportedOperationException|SecurityException unavailable){}
            AllDataReset.clear(root.toFile(),Collections.singleton("runtime"),()->{});
            check(Files.exists(root.resolve("runtime/program")),"Runtime was removed");
            check(!Files.exists(root.resolve("records.db"))&&!Files.exists(root.resolve("database-backups"))&&!Files.exists(root.resolve(AllDataReset.PENDING)),"User data or pending marker remains");
            check(Files.exists(outside.resolve("keep")),"Cleanup followed a link outside its root");
            System.out.println("Maintenance checks passed: paged log metadata, detail reads, path guards, interrupted cleanup retry and runtime preservation");
        }finally{
            try(java.util.stream.Stream<Path> paths=Files.walk(task)){for(Path path:(Iterable<Path>)paths.sorted(Comparator.reverseOrder())::iterator){if(!path.toAbsolutePath().normalize().startsWith(task))throw new IOException("Invalid test cleanup path");Files.deleteIfExists(path);}}
        }
    }
}
