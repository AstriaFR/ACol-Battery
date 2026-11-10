package cn.astriafr.acobattery;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Comparator;
import java.util.regex.Pattern;

/** Small, disposable list snapshots outside the application backup. */
final class RecordListCache {
    private static final int MAX_BYTES=32768,MAX_ENTRIES=8;
    private static final Pattern KEY=Pattern.compile("(all|charge|discharge|game)\\.[0-9]{1,4}\\.[0-9]{1,2}\\.[01]\\.[0-9]{1,2}");
    private final File directory;
    private long generation;

    RecordListCache(File cacheDirectory){directory=new File(cacheDirectory,"record-lists-v1");}
    synchronized long generation(){return generation;}

    synchronized String read(String key){
        if(!valid(key))return "";
        File file=new File(directory,key+".json");
        try{
            if(file.length()==0||file.length()>MAX_BYTES)return "";
            return new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8);
        }catch(IOException ignored){return "";}
    }

    synchronized void write(String key,String json,long expectedGeneration){
        if(expectedGeneration!=generation||!valid(key)||json==null||json.length()>MAX_BYTES)return;
        byte[] bytes=json.getBytes(StandardCharsets.UTF_8);
        if(bytes.length==0||bytes.length>MAX_BYTES)return;
        File target=new File(directory,key+".json"),pending=new File(directory,"snapshot.next");
        try{
            Files.createDirectories(directory.toPath());
            Files.write(pending.toPath(),bytes);
            Files.move(pending.toPath(),target.toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
            File[] entries=directory.listFiles((dir,name)->name.endsWith(".json"));
            if(entries!=null&&entries.length>MAX_ENTRIES){
                Arrays.sort(entries,Comparator.comparingLong(File::lastModified));
                for(int i=0;i<entries.length-MAX_ENTRIES;i++)Files.deleteIfExists(entries[i].toPath());
            }
        }catch(IOException ignored){}
        finally{try{Files.deleteIfExists(pending.toPath());}catch(IOException ignored){}}
    }

    synchronized void clear(){
        generation++;
        File[] entries=directory.listFiles((dir,name)->name.endsWith(".json")||name.equals("snapshot.next"));
        if(entries!=null)for(File entry:entries)try{Files.deleteIfExists(entry.toPath());}catch(IOException ignored){}
    }

    private static boolean valid(String key){return key!=null&&key.length()<=28&&KEY.matcher(key).matches();}
}
