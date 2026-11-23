package cn.astriafr.acobattery.common;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;

/** Extract our signed APK's payload without a root manager's BusyBox. */
public final class RuntimePayload {
    public static final List<String> FILES=Collections.unmodifiableList(Arrays.asList(
        "bin/daemon.dex.jar","bin/libcurvewait.so","bin/aco-launcher","boot.sh","service.sh","transfer.sh"));
    private static final long MAX_FILE_BYTES=8*1024*1024, MAX_TOTAL_BYTES=32*1024*1024;
    private RuntimePayload(){}

    private static MessageDigest sha256()throws java.security.NoSuchAlgorithmException{
        return MessageDigest.getInstance("SHA-256");
    }
    private static String hex(byte[] digest){
        StringBuilder value=new StringBuilder();
        for(byte b:digest)value.append(String.format(Locale.ROOT,"%02x",b&255));
        return value.toString();
    }
    private static String contentsDigest(byte[][] entries)throws Exception{
        MessageDigest hash=sha256();
        for(byte[] entry:entries){
            if(entry==null)throw new IOException("后台安装文件不完整，请重新安装 App。");
            hash.update(entry);
        }
        return hex(hash.digest());
    }
    /** Returns the fingerprint of the verified entry bytes in FILES order. */
    public static String extract(File archive,File directory,String expectedHash)throws Exception{
        MessageDigest hash=sha256();byte[] buffer=new byte[8192];
        try(InputStream in=new FileInputStream(archive)){int n;while((n=in.read(buffer))!=-1)hash.update(buffer,0,n);}
        if(!hex(hash.digest()).equals(expectedHash))throw new IOException("后台安装文件校验失败，请重新安装 App。");
        String boundary=directory.getCanonicalPath()+File.separator;
        byte[][] entries=new byte[FILES.size()][];
        long total=0;
        try(ZipInputStream zip=new ZipInputStream(new FileInputStream(archive))){
            ZipEntry entry;
            while((entry=zip.getNextEntry())!=null){
                String name=entry.getName();
                if(entry.isDirectory()){
                    if(!"bin/".equals(name))throw new IOException("后台安装文件包含无效目录。");
                    continue;
                }
                int index=FILES.indexOf(name);
                if(index<0||entries[index]!=null)throw new IOException("后台安装文件内容无效。");
                File file=new File(directory,name);
                if(!file.getCanonicalPath().startsWith(boundary))throw new IOException("后台安装文件路径无效。");
                File parent=file.getParentFile();
                if(!parent.isDirectory()&&!parent.mkdirs())throw new IOException("无法准备后台安装文件，请检查存储空间。");
                try(OutputStream out=new FileOutputStream(file)){
                    int n;long size=0;
                    while((n=zip.read(buffer))!=-1){
                        size+=n;total+=n;
                        if(size>MAX_FILE_BYTES||total>MAX_TOTAL_BYTES)throw new IOException("后台安装文件大小异常。");
                        hash.update(buffer,0,n);
                        out.write(buffer,0,n);
                    }
                    if(size==0)throw new IOException("后台安装文件不完整。");
                }
                entries[index]=hash.digest();
            }
        }
        return contentsDigest(entries);
    }

    static void verifyContents(File directory,String expected)throws Exception{
        byte[][] entries=new byte[FILES.size()][];
        String boundary=directory.getCanonicalPath()+File.separator;
        MessageDigest hash=sha256();byte[] buffer=new byte[8192];long total=0;
        for(int i=0;i<FILES.size();i++){
            File file=new File(directory,FILES.get(i));
            if(!Files.isRegularFile(file.toPath(),LinkOption.NOFOLLOW_LINKS)||!file.getCanonicalPath().startsWith(boundary))
                throw new IOException("后台运行文件缺失或路径异常："+FILES.get(i));
            try(InputStream in=new FileInputStream(file)){
                int n;long size=0;while((n=in.read(buffer))!=-1){
                    size+=n;total+=n;
                    if(size>MAX_FILE_BYTES||total>MAX_TOTAL_BYTES)throw new IOException("后台运行文件大小异常。");
                    hash.update(buffer,0,n);
                }
                if(size==0)throw new IOException("后台运行文件内容为空："+FILES.get(i));
            }
            entries[i]=hash.digest();
        }
        if(!contentsDigest(entries).equals(expected))throw new IOException("后台运行文件内容校验失败。");
    }
    /** Only our fixed payload names are ever removed. */
    public static void discard(File directory){
        for(String name:FILES)try{Files.deleteIfExists(new File(directory,name).toPath());}catch(IOException ignored){}
        try{Files.deleteIfExists(new File(directory,"bin").toPath());Files.deleteIfExists(directory.toPath());}catch(IOException ignored){}
    }
}
