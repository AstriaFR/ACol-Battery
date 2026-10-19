package cn.astriafr.acobattery.common;

import java.io.*;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;

/** Extract our signed APK's payload without a root manager's BusyBox. */
public final class RuntimePayload {
    public static final List<String> FILES=Collections.unmodifiableList(Arrays.asList(
        "bin/daemon.dex.jar","bin/libcurvewait.so","bin/aco-launcher","boot.sh","service.sh","cutoff.sh"));
    private RuntimePayload(){}
    public static void extract(File archive,File directory,String expectedHash)throws Exception{
        MessageDigest hash=MessageDigest.getInstance("SHA-256");byte[] buffer=new byte[8192];
        try(InputStream in=new FileInputStream(archive)){int n;while((n=in.read(buffer))!=-1)hash.update(buffer,0,n);}
        StringBuilder actual=new StringBuilder();for(byte b:hash.digest())actual.append(String.format(Locale.ROOT,"%02x",b&255));
        if(!actual.toString().equals(expectedHash))throw new IOException("后台安装文件校验失败，请重新安装 App。");
        String boundary=directory.getCanonicalPath()+File.separator;Set<String> found=new HashSet<>();long total=0;
        try(ZipInputStream zip=new ZipInputStream(new FileInputStream(archive))){
            ZipEntry entry;
            while((entry=zip.getNextEntry())!=null){
                String name=entry.getName();
                if(entry.isDirectory()){
                    if(!"bin/".equals(name))throw new IOException("后台安装文件包含无效目录。");
                    continue;
                }
                if(!FILES.contains(name)||!found.add(name))throw new IOException("后台安装文件内容无效。");
                File file=new File(directory,name);
                if(!file.getCanonicalPath().startsWith(boundary))throw new IOException("后台安装文件路径无效。");
                File parent=file.getParentFile();
                if(!parent.isDirectory()&&!parent.mkdirs())throw new IOException("无法准备后台安装文件，请检查存储空间。");
                try(OutputStream out=new FileOutputStream(file)){
                    int n;long size=0;
                    while((n=zip.read(buffer))!=-1){
                        size+=n;total+=n;
                        if(size>8*1024*1024||total>32*1024*1024)throw new IOException("后台安装文件大小异常。");
                        out.write(buffer,0,n);
                    }
                    if(size==0)throw new IOException("后台安装文件不完整。");
                }
            }
        }
        if(found.size()!=FILES.size())throw new IOException("后台安装文件不完整，请重新安装 App。");
    }
    /** Only our fixed payload names are ever removed. */
    public static void discard(File directory){
        for(String name:FILES)try{Files.deleteIfExists(new File(directory,name).toPath());}catch(IOException ignored){}
        try{Files.deleteIfExists(new File(directory,"bin").toPath());Files.deleteIfExists(directory.toPath());}catch(IOException ignored){}
    }
}
