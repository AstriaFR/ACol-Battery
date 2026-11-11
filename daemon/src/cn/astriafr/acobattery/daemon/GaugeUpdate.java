package cn.astriafr.acobattery.daemon;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

/** Optional OPLUS gauge publishing interval. Called under the recorder mutex. */
final class GaugeUpdate {
    private static final Path NODE=Paths.get("/proc/oplus-votable/GAUGE_UPDATE");
    private final Path active,value;
    private final File journal;
    private final String boot;
    private long applied=-1;

    GaugeUpdate(File data,String boot){this(NODE,new File(data,"gauge-update.state"),boot);}

    GaugeUpdate(Path node,File journal,String boot){
        active=node.resolve("force_active");
        value=node.resolve("force_val");
        this.journal=journal;
        this.boot=boot;
    }

    private static final class State {
        final boolean active;
        final long value;
        State(boolean active,long value){this.active=active;this.value=value;}
        boolean matches(boolean enabled,long interval){return active==enabled&&value==interval;}
    }

    private static final class Change {
        final State original;
        final long interval;
        Change(State original,long interval){this.original=original;this.interval=interval;}
    }

    void apply(long intervalMs){
        if(boot.isEmpty())return;
        try {
            Change pending=readJournal();
            State before=read();
            if(pending!=null){
                if(applied==intervalMs&&pending.interval==intervalMs&&before.matches(true,intervalMs))return;
                restore(pending,before);
                before=read();
            }
            if(before.matches(true,intervalMs))return;
            Change change=new Change(before,intervalMs);
            // Save the pre-write state before either proc node can change.
            Owner.write(journal,boot+" "+(before.active?1:0)+" "+before.value+" "+intervalMs);
            try {
                if(before.value!=intervalMs)write(value,intervalMs);
                write(active,1);
                if(!read().matches(true,intervalMs))throw new IOException();
                applied=intervalMs;
            }catch(IOException|RuntimeException ignored){
                try{restore(change,read());}catch(IOException|RuntimeException ignoredRollback){}
            }
        }catch(IOException|RuntimeException ignored){}
    }

    void restore(){
        if(boot.isEmpty())return;
        try {
            Change change=readJournal();
            if(change!=null)restore(change,read());
        }catch(IOException|RuntimeException ignored){}
    }

    private Change readJournal()throws IOException {
        String[] fields;
        try{fields=Owner.read(journal).split(" ",-1);}
        catch(NoSuchFileException missing){return null;}
        if(!boot.equals(fields[0])){
            Files.deleteIfExists(journal.toPath());
            return null;
        }
        if(fields.length!=4)throw new IOException();
        return new Change(parseState(fields[1],fields[2]),parseValue(fields[3]));
    }

    private State read()throws IOException {
        String enabled=new String(Files.readAllBytes(active),StandardCharsets.US_ASCII).trim();
        String interval=new String(Files.readAllBytes(value),StandardCharsets.US_ASCII).trim();
        return parseState(enabled,interval);
    }

    private static State parseState(String enabled,String interval)throws IOException {
        if(!enabled.equals("0")&&!enabled.equals("1"))throw new IOException();
        return new State(enabled.equals("1"),parseValue(interval));
    }

    private static long parseValue(String interval)throws IOException {
        if(!interval.matches("[0-9]{1,10}"))throw new IOException();
        long result=Long.parseLong(interval);
        if(result>0xffffffffL)throw new IOException();
        return result;
    }

    private void restore(Change change,State current)throws IOException {
        applied=-1;
        State original=change.original;
        // The target value can be visible before force_active has been written.
        boolean ours=current.value==change.interval&&(current.active||!original.active);
        if(ours||current.matches(original.active,original.value)){
            if(!original.active&&current.active)write(active,0);
            if(current.value!=original.value)write(value,original.value);
            // Re-run the callback when restoring a previously active override.
            if(original.active)write(active,1);
            if(!read().matches(original.active,original.value))throw new IOException();
        }
        Files.deleteIfExists(journal.toPath());
    }

    private static void write(Path node,long number)throws IOException {
        Files.write(node,Long.toString(number).getBytes(StandardCharsets.US_ASCII),
            StandardOpenOption.WRITE,StandardOpenOption.TRUNCATE_EXISTING);
    }
}
