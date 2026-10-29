package cn.astriafr.acobattery.common;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Called only after explicit confirmation and exclusive recorder shutdown. */
public final class AllDataReset {
    public static final String PENDING="all-data-clear.pending";
    public interface Guard{void check()throws Exception;}
    private AllDataReset(){}
    public static void clear(File base,Set<String> keep,Guard guard)throws Exception{
        Path root=base.toPath().toAbsolutePath().normalize();
        if(Files.isSymbolicLink(root)||!Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS)||!base.getCanonicalFile().toPath().equals(root)||Files.isSymbolicLink(root.resolve(PENDING)))throw new IOException("数据目录异常，已取消清除。");
        for(String name:keep){
            if(!name.matches("[a-zA-Z0-9._-]+")||name.equals(".")||name.equals("..")||Files.isSymbolicLink(root.resolve(name)))throw new IOException("运行文件路径异常，已取消清除。");
        }
        try(FileOutputStream marker=new FileOutputStream(root.resolve(PENDING).toFile())){marker.write(1);marker.getFD().sync();}
        File[] children=base.listFiles();if(children==null)throw new IOException("无法读取应用数据目录。");
        for(File child:children)if(!keep.contains(child.getName())&&!child.getName().equals(PENDING))remove(root,child.toPath(),guard);
        guard.check();Files.delete(root.resolve(PENDING));
    }
    private static void remove(Path root,Path target,Guard guard)throws Exception{
        guard.check();target=target.toAbsolutePath().normalize();
        if(target.equals(root)||!target.startsWith(root))throw new IOException("清除路径超出应用目录。");
        if(!Files.isSymbolicLink(target)&&Files.isDirectory(target,LinkOption.NOFOLLOW_LINKS)){
            try(DirectoryStream<Path> children=Files.newDirectoryStream(target)){for(Path child:children)remove(root,child,guard);}
        }
        Files.deleteIfExists(target);
    }
}
