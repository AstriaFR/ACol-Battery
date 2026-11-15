package cn.astriafr.acobattery.daemon;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/** Exercise the real override and rollback code with isolated fake proc nodes. */
public final class GaugeUpdateTest {
    static final class InterruptedWrite extends Error {}
    static void check(boolean ok,String message){
        if(!ok)throw new AssertionError(message);
    }

    static void state(Path active,Path value,String enabled,String interval)throws Exception{
        check(Files.readString(active).equals(enabled)&&Files.readString(value).equals(interval),
            "Expected force_active="+enabled+" and force_val="+interval);
    }

    static void manualInterruption(Path dir,Path active,Path value,Path journal)throws Exception{
        GaugeUpdate gauge=new GaugeUpdate(dir,journal.toFile(),"boot-a");
        Files.writeString(active,"1");Files.writeString(value,"1500");
        FileTime oldActivation=FileTime.fromMillis(1000);
        Files.setLastModifiedTime(active,oldActivation);
        try{
            gauge.applyManually(1000,()->{
                check(Files.exists(journal),"Manual changes must record recovery before writing the vote");
                Files.writeString(value,"1000");
                throw new InterruptedWrite();
            });
            throw new AssertionError("Manual interruption was not exercised");
        }catch(InterruptedWrite expected){}
        new GaugeUpdate(dir,journal.toFile(),"boot-a").recoverPending();
        state(active,value,"1","1000");
        check(Files.getLastModifiedTime(active).compareTo(oldActivation)>0&&!Files.exists(journal),"Interrupted manual update must rerun the driver callback");

        try{
            gauge.applyManually(null,()->{Files.writeString(active,"0");throw new InterruptedWrite();});
            throw new AssertionError("Manual reset interruption was not exercised");
        }catch(InterruptedWrite expected){}
        Files.setLastModifiedTime(active,oldActivation);
        new GaugeUpdate(dir,journal.toFile(),"boot-a").recoverPending();
        state(active,value,"0","1000");
        check(Files.getLastModifiedTime(active).compareTo(oldActivation)>0&&!Files.exists(journal),"Interrupted manual reset must rerun the driver callback");

        try{
            gauge.applyManually(2000,()->{Files.writeString(value,"2000");throw new IOException("Verification failed");});
            throw new AssertionError("Manual failure was not reported");
        }catch(IOException expected){}
        check(!Files.exists(journal),"A manual write that released its override after failure must not be replayed");
        state(active,value,"0","2000");
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

            // A process exit after force_val can leave force_active already on from the old override.
            Files.writeString(journal,"boot-a 1 2000 1500");
            Files.writeString(value,"1500");
            FileTime oldActivation=FileTime.fromMillis(1000);
            Files.setLastModifiedTime(active,oldActivation);
            restarted.recoverPending();
            state(active,value,"1","1500");
            check(Files.getLastModifiedTime(active).compareTo(oldActivation)>0,"Recovery must write force_active again to invoke the driver");
            check(!Files.exists(journal),"Reactivated override clears the journal");

            FutureTask<Void> queued=new FutureTask<>(()->{restarted.apply(500);return null;});
            Thread writer=new Thread(queued,"gauge-override-test");
            synchronized(OplusVotes.Node.GAUGE_UPDATE){
                writer.start();
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
                while(writer.getState()!=Thread.State.BLOCKED&&!queued.isDone()&&System.nanoTime()<deadline)Thread.sleep(1);
                check(writer.getState()==Thread.State.BLOCKED,"Automatic writes must wait for the vote node lock");
                state(active,value,"1","1500");
            }
            queued.get(2,TimeUnit.SECONDS);
            state(active,value,"1","500");

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
            manualInterruption(dir,active,value,journal);
        }finally{
            Files.deleteIfExists(dir.resolve("gauge-update.state.next"));
            Files.deleteIfExists(journal);
            Files.deleteIfExists(active);
            Files.deleteIfExists(value);
            Files.delete(dir);
        }
        System.out.println("Gauge override: automatic and manual writes, interruption, retention, rollback, ownership and driver reset passed");
    }
}
