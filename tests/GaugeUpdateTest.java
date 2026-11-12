package cn.astriafr.acobattery.daemon;

import java.nio.file.Files;
import java.nio.file.Path;

/** Exercise the real override and rollback code with isolated fake proc nodes. */
public final class GaugeUpdateTest {
    static void check(boolean ok,String message){
        if(!ok)throw new AssertionError(message);
    }

    static void state(Path active,Path value,String enabled,String interval)throws Exception{
        check(Files.readString(active).equals(enabled)&&Files.readString(value).equals(interval),
            "Expected force_active="+enabled+" and force_val="+interval);
    }

    public static void main(String[] args)throws Exception{
        Path dir=Files.createTempDirectory("acol-gauge-update-");
        Path active=dir.resolve("force_active"),value=dir.resolve("force_val"),journal=dir.resolve("gauge-update.state");
        try{
            Files.writeString(active,"0");Files.writeString(value,"10000");
            GaugeUpdate first=new GaugeUpdate(dir,journal.toFile(),"boot-a");
            first.apply(2000);
            state(active,value,"1","2000");
            check(!Files.exists(journal),"Successful overrides do not leave a restore journal");

            Files.writeString(journal,"boot-a 0 10000 2000"); // A completed write from the previous version.
            GaugeUpdate restarted=new GaugeUpdate(dir,journal.toFile(),"boot-a");
            restarted.recoverPending();
            state(active,value,"1","2000");
            check(!Files.exists(journal),"A completed override must survive a recorder restart");
            restarted.apply(1500);
            state(active,value,"1","1500");
            restarted.restoreDriverDefault();
            state(active,value,"0","1500");

            // The process died after force_val changed and before force_active changed.
            Files.writeString(journal,"boot-a 0 10000 500");
            Files.writeString(value,"500");
            new GaugeUpdate(dir,journal.toFile(),"boot-a").recoverPending();
            state(active,value,"0","10000");

            if(System.getProperty("os.name","").startsWith("Windows")){
                Files.setAttribute(active,"dos:readonly",true);
                try{
                    first.apply(1500);
                    state(active,value,"0","10000");
                    check(!Files.exists(journal),"A fully rolled-back failure must clear the journal");
                }finally{Files.setAttribute(active,"dos:readonly",false);}
            }

            // Respect an interval changed by another client or carried by a new kernel boot.
            first.apply(500);
            Files.writeString(journal,"boot-a 0 10000 500");
            Files.writeString(value,"3000");
            restarted.recoverPending();
            state(active,value,"1","3000");
            check(!Files.exists(journal),"External changes release ownership");
            first.apply(500);
            Files.writeString(journal,"boot-a 0 10000 500");
            new GaugeUpdate(dir,journal.toFile(),"boot-b").recoverPending();
            state(active,value,"1","500");
            check(!Files.exists(journal),"Discard journal from an earlier boot");

            restarted.apply(1500);
            new GaugeUpdate(dir,journal.toFile(),"boot-a").recoverPending();
            state(active,value,"1","1500");
            check(!Files.exists(journal),"A disabled switch must retain the applied interval across restarts");

            // Explicit defaults release forcing even if the driver was forced before ACol took over.
            restarted.apply(2000);
            restarted.restoreDriverDefault();
            state(active,value,"0","2000");
            check(!Files.exists(journal),"Driver default must clear the journal");

            if(System.getProperty("os.name","").startsWith("Windows")){
                Files.writeString(active,"1");
                Files.setAttribute(active,"dos:readonly",true);
                try{
                    restarted.restoreDriverDefault();
                    state(active,value,"1","2000");
                    check(Files.exists(journal),"An interrupted default reset must be retryable");
                }finally{Files.setAttribute(active,"dos:readonly",false);}
                new GaugeUpdate(dir,journal.toFile(),"boot-a").recoverPending();
                state(active,value,"0","2000");
                check(!Files.exists(journal),"Retry must release forcing");
            }
        }finally{
            Files.deleteIfExists(dir.resolve("gauge-update.state.next"));
            Files.deleteIfExists(journal);
            Files.deleteIfExists(active);
            Files.deleteIfExists(value);
            Files.delete(dir);
        }
        System.out.println("Gauge override: restart, interrupted write, retention, rollback, ownership and driver reset passed");
    }
}
