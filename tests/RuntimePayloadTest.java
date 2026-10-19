package cn.astriafr.acobattery.common;

import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;

public final class RuntimePayloadTest {
    static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    static File archive(Path root,List<String> names,int bytes)throws Exception{
        File file=Files.createTempFile(root,"payload-",".zip").toFile();
        try(ZipOutputStream zip=new ZipOutputStream(new FileOutputStream(file))){
            byte[] data=new byte[bytes];Arrays.fill(data,(byte)37);
            for(String name:names){zip.putNextEntry(new ZipEntry(name));zip.write(data);zip.closeEntry();}
        }
        return file;
    }
    static String hash(File file)throws Exception{
        byte[] digest=MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file.toPath()));
        StringBuilder out=new StringBuilder();for(byte b:digest)out.append(String.format("%02x",b&255));return out.toString();
    }
    static void rejected(File zip,Path root,String sha)throws Exception{
        File output=Files.createTempDirectory(root,"extract-").toFile();
        try{
            try{RuntimePayload.extract(zip,output,sha);throw new AssertionError("Invalid payload accepted");}
            catch(IOException expected){}
        }finally{RuntimePayload.discard(output);}
        check(!output.exists(),"Partial extraction was not removed");
    }
    public static void main(String[] args)throws Exception{
        Path root=Files.createTempDirectory("acol-payload-test-");
        try{
            File valid=archive(root,RuntimePayload.FILES,11);File output=Files.createTempDirectory(root,"extract-").toFile();
            RuntimePayload.extract(valid,output,hash(valid));
            for(String name:RuntimePayload.FILES){byte[] data=Files.readAllBytes(new File(output,name).toPath());check(data.length==11&&data[0]==37,"Payload bytes changed");}
            RuntimePayload.discard(output);check(!output.exists(),"Extracted cache not removed");
            rejected(valid,root,"wrong-hash");
            List<String> missing=new ArrayList<>(RuntimePayload.FILES);missing.remove("service.sh");File zip=archive(root,missing,11);rejected(zip,root,hash(zip));
            List<String> unsafe=new ArrayList<>(RuntimePayload.FILES);unsafe.add("../escaped");zip=archive(root,unsafe,11);rejected(zip,root,hash(zip));
            check(!root.resolve("escaped").toFile().exists(),"Path traversal escaped extraction");
            unsafe.set(unsafe.size()-1,"/absolute-file");zip=archive(root,unsafe,11);rejected(zip,root,hash(zip));
            zip=archive(root,RuntimePayload.FILES,0);rejected(zip,root,hash(zip));
            zip=archive(root,Collections.singletonList("bin/daemon.dex.jar"),8*1024*1024+1);rejected(zip,root,hash(zip));
            if(args.length==2){
                output=Files.createTempDirectory(root,"real-payload-").toFile();
                RuntimePayload.extract(new File(args[0]),output,args[1]);RuntimePayload.discard(output);
            }
            System.out.println("RuntimePayload: exact extraction, hash, missing/empty/oversized entries, traversal and cache cleanup passed");
        }finally{
            try(java.util.stream.Stream<Path> files=Files.walk(root)){files.sorted(Comparator.reverseOrder()).forEach(path->{try{Files.delete(path);}catch(IOException e){throw new UncheckedIOException(e);}});}
        }
    }
}
