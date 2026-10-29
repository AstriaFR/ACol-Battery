package cn.astriafr.acobattery.common;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;
import org.json.*;

/** A data-only ZIP format. Executables, owner identity and operation flags are never imported. */
public final class DataArchive {
    public static final String PACKAGE="cn.astriafr.acobattery",FORMAT="ACOL_DATA";
    public static final long MAX_BYTES=4L*1024*1024*1024;
    public static final Set<String> APP_DIRS=new LinkedHashSet<>(Arrays.asList("files","shared_prefs","databases","app_webview"));
    public static final Set<String> ROOT_KEEP=new HashSet<>(Arrays.asList("runtime","runtime.version","owner.cert","owner.user","daemon.lock","daemon.pid","supervisor.pid","initialized","startup.attempt","update.lock","update.pending","update.installed","update.transaction","runtime.rollback","all-data-clear.pending","database.rebuild.pending","screen.event","screen.event.next","cleanup.request","transfer","transfer.pending"));
    public interface Guard{void check()throws Exception;}
    private DataArchive(){}
    static void validName(String name)throws IOException{
        if(name.isEmpty()||name.startsWith("/")||name.indexOf('\\')>=0||name.indexOf(':')>=0||name.indexOf('\0')>=0)throw new IOException("数据包包含无效路径。");
        String[] parts=name.split("/",-1);for(String part:parts)if(part.isEmpty()||part.equals(".")||part.equals(".."))throw new IOException("数据包包含越界路径。");
        if(parts.length<2)throw new IOException("数据包目录无效。");
        if(parts[0].equals("root")){if(ROOT_KEEP.contains(parts[1]))throw new IOException("数据包包含受保护的运行文件。");}
        else if(parts[0].equals("app")){
            if(!APP_DIRS.contains(parts[1])||parts.length<3)throw new IOException("数据包应用目录无效。");
            if(parts[1].equals("shared_prefs")&&(parts[2].startsWith("startup.xml")||parts[2].startsWith("maintenance.xml")))throw new IOException("数据包包含初始化操作状态。");
        }else throw new IOException("数据包类型无效。");
    }
    static String hex(byte[] bytes){StringBuilder out=new StringBuilder();for(byte b:bytes)out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString();}
    public static void copyTree(File source,File target,Guard guard)throws Exception{
        guard.check();if(Files.isSymbolicLink(source.toPath()))return;
        if(source.isDirectory()){
            Files.createDirectories(target.toPath());File[] files=source.listFiles();if(files==null)throw new IOException("无法读取目录："+source.getName());
            for(File child:files)copyTree(child,new File(target,child.getName()),guard);
        }else if(source.isFile()){
            Files.createDirectories(target.getParentFile().toPath());byte[] bytes=new byte[65536];
            try(InputStream in=new FileInputStream(source);OutputStream out=new FileOutputStream(target)){int n;while((n=in.read(bytes))!=-1){guard.check();out.write(bytes,0,n);}}
        }
    }
    public static void removeTree(File base,File target)throws IOException{
        Path root=base.toPath().toAbsolutePath().normalize(),path=target.toPath().toAbsolutePath().normalize();
        if(path.equals(root)||!path.startsWith(root))throw new IOException("临时数据清理路径无效。");
        if(!Files.isSymbolicLink(path)&&Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS))try(DirectoryStream<Path> items=Files.newDirectoryStream(path)){for(Path item:items)removeTree(base,item.toFile());}
        Files.deleteIfExists(path);
    }
    public static JSONObject pack(File snapshot,File output,String version,Guard guard)throws Exception{
        JSONObject entries=new JSONObject();long[] total={0};
        try(ZipOutputStream zip=new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(output)))){
            zip.setLevel(6);packDirectory(snapshot,snapshot,zip,entries,total,guard);
            JSONObject manifest=new JSONObject().put("format",FORMAT).put("schema",1).put("package",PACKAGE).put("version",version).put("created",System.currentTimeMillis()).put("bytes",total[0]).put("entries",entries);
            zip.putNextEntry(new ZipEntry("manifest.json"));zip.write(manifest.toString().getBytes(StandardCharsets.UTF_8));zip.closeEntry();return manifest;
        }
    }
    static void packDirectory(File base,File folder,ZipOutputStream zip,JSONObject entries,long[] total,Guard guard)throws Exception{
        File[] files=folder.listFiles();if(files==null)throw new IOException("无法读取备份快照。");Arrays.sort(files,Comparator.comparing(File::getName));
        for(File file:files){
            guard.check();if(Files.isSymbolicLink(file.toPath()))throw new IOException("备份快照包含异常链接。");
            if(file.isDirectory()){packDirectory(base,file,zip,entries,total,guard);continue;}if(!file.isFile())continue;
            String name=base.toPath().relativize(file.toPath()).toString().replace(File.separatorChar,'/');validName(name);if(entries.length()>=100000)throw new IOException("备份文件数量过多。");
            MessageDigest hash=MessageDigest.getInstance("SHA-256");long size=0;byte[] buffer=new byte[65536];zip.putNextEntry(new ZipEntry(name));
            try(InputStream in=new FileInputStream(file)){int n;while((n=in.read(buffer))!=-1){guard.check();size+=n;total[0]+=n;if(total[0]>MAX_BYTES)throw new IOException("备份数据超过支持的大小。");hash.update(buffer,0,n);zip.write(buffer,0,n);}}
            zip.closeEntry();entries.put(name,new JSONObject().put("size",size).put("sha256",hex(hash.digest())));
        }
    }
    public static JSONObject inspect(File archive,File extract,Guard guard)throws Exception{
        if(!archive.isFile()||archive.length()>MAX_BYTES)throw new IOException("数据包不存在或大小超出支持范围。");
        try(ZipFile zip=new ZipFile(archive)){
            ZipEntry header=zip.getEntry("manifest.json");if(header==null||header.getSize()<0||header.getSize()>32*1024*1024)throw new IOException("这不是 ACol 电池数据包。");
            ByteArrayOutputStream raw=new ByteArrayOutputStream();try(InputStream in=zip.getInputStream(header)){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1){guard.check();if(raw.size()+n>32*1024*1024)throw new IOException("数据包清单过大。");raw.write(b,0,n);}}
            JSONObject manifest=new JSONObject(new String(raw.toByteArray(),StandardCharsets.UTF_8));
            if(!FORMAT.equals(manifest.optString("format"))||manifest.optInt("schema")!=1||!PACKAGE.equals(manifest.optString("package")))throw new IOException("数据包格式或所属应用不匹配。");
            JSONObject entries=manifest.getJSONObject("entries");if(entries.length()>100000)throw new IOException("数据包文件数量过多。");
            Set<String> found=new HashSet<>();long total=0;Enumeration<? extends ZipEntry> files=zip.entries();int headers=0;
            while(files.hasMoreElements()){
                guard.check();ZipEntry entry=files.nextElement();String name=entry.getName();if(name.equals("manifest.json")){if(++headers>1)throw new IOException("数据包清单重复。");continue;}
                validName(name);if(entry.isDirectory()||!found.add(name)||!entries.has(name))throw new IOException("数据包包含重复或未登记的文件。");
                JSONObject expected=entries.getJSONObject(name);long wanted=expected.getLong("size");if(wanted<0||wanted>MAX_BYTES||!expected.getString("sha256").matches("[0-9a-f]{64}"))throw new IOException("数据包校验信息无效。");
                File target=extract==null?null:new File(extract,name);if(target!=null){if(!target.getCanonicalPath().startsWith(extract.getCanonicalPath()+File.separator))throw new IOException("数据包解压路径无效。");Files.createDirectories(target.getParentFile().toPath());if(Files.isSymbolicLink(target.toPath()))throw new IOException("数据包目标路径异常。");}
                MessageDigest hash=MessageDigest.getInstance("SHA-256");long size=0;byte[] bytes=new byte[65536];
                try(InputStream in=zip.getInputStream(entry);OutputStream out=target==null?new OutputStream(){public void write(int b){}public void write(byte[] b,int off,int len){}}:new FileOutputStream(target)){
                    int n;while((n=in.read(bytes))!=-1){guard.check();size+=n;total+=n;if(size>wanted||total>MAX_BYTES)throw new IOException("数据包展开大小异常。");hash.update(bytes,0,n);out.write(bytes,0,n);}
                }
                if(size!=wanted||!hex(hash.digest()).equals(expected.getString("sha256")))throw new IOException("数据包校验失败，文件损坏："+name);
            }
            if(found.size()!=entries.length()||headers!=1||total!=manifest.getLong("bytes"))throw new IOException("数据包内容不完整。");
            if(!entries.has("root/records.db"))throw new IOException("数据包缺少充放电数据库。");return manifest;
        }
    }
}
