package cn.astriafr.acobattery.daemon;

import android.database.sqlite.SQLiteDatabase;
import android.os.SystemClock;
import java.io.IOException;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONException;
import org.json.JSONObject;

/** Saves the user's manual driver settings independently of the recording modes. */
final class AdvancedRestore {
    static final String KEY="advanced_restore_v1";
    private static final long INTERVAL=30*60_000L, BOOT_DELAY=60_000L;

    private final SQLiteDatabase db;
    private final String boot;
    private final GaugeUpdate gaugeUpdate;
    private final Runnable wake;
    private final EnumMap<OplusVotes.Node,Integer> targets=new EnumMap<>(OplusVotes.Node.class);
    private final ExecutorService worker=Executors.newSingleThreadExecutor(task->new Thread(task,"aco-advanced-restore"));
    private final AtomicBoolean queued=new AtomicBoolean();
    private volatile boolean screenOn,closed;
    private volatile Schedule schedule=new Schedule(false,false,Long.MAX_VALUE);
    private int mode;

    private static final class Schedule {
        final boolean enabled,bootPending;
        final long next;
        Schedule(boolean enabled,boolean bootPending,long next){
            this.enabled=enabled;this.bootPending=bootPending;this.next=next;
        }
    }

    AdvancedRestore(SQLiteDatabase db,String boot,GaugeUpdate gaugeUpdate,String saved,Runnable wake){
        this.db=db;this.boot=boot;this.gaugeUpdate=gaugeUpdate;this.wake=wake;
        if(saved.isEmpty())return;
        try{
            JSONObject state=new JSONObject(saved),votes=state.optJSONObject("votes");
            int savedMode=state.optInt("mode");
            if(savedMode>=1&&savedMode<=3)mode=savedMode;
            if(votes!=null)for(OplusVotes.Node node:OplusVotes.Node.values()){
                if(node.maximum>0&&votes.has(node.name()))targets.put(node,votes.getInt(node.name()));
            }
            boolean enabled=state.optBoolean("enabled");
            boolean sameBoot=Core.sameBoot(state.optString("boot"),boot);
            long now=SystemClock.elapsedRealtime();
            boolean bootPending=enabled&&hasTargets()&&!boot.isEmpty()&&(!sameBoot||state.optBoolean("bootPending"));
            long next=Long.MAX_VALUE;
            if(enabled&&hasTargets())next=sameBoot?state.optLong("next",now+INTERVAL):now+(bootPending?BOOT_DELAY:INTERVAL);
            schedule=new Schedule(enabled,bootPending,next);
            if(bootPending&&!sameBoot)saveQuietly();
        }catch(JSONException ignored){
            targets.clear();mode=0;
        }
    }

    boolean enabled(){return schedule.enabled;}
    synchronized boolean hasTargets(){return mode!=0||!targets.isEmpty();}
    boolean screen(boolean on){
        if(screenOn==on)return false;
        screenOn=on;
        return schedule.next!=Long.MAX_VALUE;
    }

    long deadline(){
        Schedule current=schedule;
        return !queued.get()&&!closed&&(screenOn||current.bootPending)?current.next:Long.MAX_VALUE;
    }

    long bootDeadline(){
        Schedule current=schedule;
        return !queued.get()&&!closed&&current.bootPending?current.next:Long.MAX_VALUE;
    }

    private void save()throws JSONException{
        JSONObject votes=new JSONObject();
        for(Map.Entry<OplusVotes.Node,Integer> target:targets.entrySet())
            votes.put(target.getKey().name(),target.getValue());
        Schedule current=schedule;
        JSONObject state=new JSONObject().put("enabled",current.enabled).put("boot",boot)
            .put("next",current.next).put("bootPending",current.bootPending).put("mode",mode).put("votes",votes);
        db.execSQL("INSERT OR REPLACE INTO runtime(key,value) VALUES(?,?)",new Object[]{KEY,state.toString()});
    }

    private void saveQuietly(){
        try{save();}catch(JSONException|RuntimeException ignored){}
    }

    synchronized void setEnabled(boolean enabled)throws IOException,JSONException{
        ensureOpen();
        if(schedule.enabled==enabled)return;
        schedule=new Schedule(enabled,false,enabled&&hasTargets()?SystemClock.elapsedRealtime()+INTERVAL:Long.MAX_VALUE);
        save();wake.run();
    }

    private void ensureOpen()throws IOException{
        if(closed)throw new IOException("后台正在结束，请稍后重试。");
    }

    private void targetsChanged()throws JSONException{
        Schedule current=schedule;
        if(!current.enabled||!hasTargets())schedule=new Schedule(current.enabled,false,Long.MAX_VALUE);
        else if(current.next==Long.MAX_VALUE)schedule=new Schedule(true,false,SystemClock.elapsedRealtime()+INTERVAL);
        save();wake.run();
    }

    synchronized void apply(OplusVotes.Node node,Integer value)throws IOException,JSONException{
        ensureOpen();
        if(node==OplusVotes.Node.GAUGE_SHUTDOWN_VOLTAGE&&value==null){
            // Persist the manual release before the driver write or its fallible readback.
            targets.remove(node);
            targetsChanged();
        }
        OplusVotes.apply(node,value,gaugeUpdate);
        if(value!=null&&(value!=0||node==OplusVotes.Node.COOL_DOWN)){
            targets.put(node,value);targetsChanged();
        }else if(targets.remove(node)!=null)targetsChanged();
    }

    synchronized void setMode(int requested)throws IOException,JSONException{
        ensureOpen();
        if(requested==0&&mode!=0){mode=0;targetsChanged();}
        OplusVotes.setChargingMode(requested);
        if(requested!=0){mode=requested;targetsChanged();}
    }

    synchronized String reset()throws IOException,JSONException{
        ensureOpen();
        targets.clear();mode=0;targetsChanged();
        return OplusVotes.restoreAll(gaugeUpdate);
    }

    void checkIfDue(long now){
        if(closed||now<deadline()||!queued.compareAndSet(false,true))return;
        try{
            worker.execute(()->{
                try{check();}
                finally{queued.set(false);if(!closed)wake.run();}
            });
        }catch(RejectedExecutionException ignored){queued.set(false);}
    }

    private void check(){
        Schedule next;
        boolean startingUp;
        synchronized(this){
            Schedule current=schedule;
            long now=SystemClock.elapsedRealtime();
            startingUp=current.bootPending;
            if(closed||!current.enabled||now<current.next||!screenOn&&!startingUp)return;
            schedule=next=new Schedule(true,false,now+INTERVAL);
            saveQuietly();
        }
        for(OplusVotes.Node node:OplusVotes.Node.values()){
            synchronized(this){
                if(closed||schedule!=next||!screenOn&&!startingUp)return;
                Integer value=targets.get(node);
                if(value==null)continue;
                try{OplusVotes.restoreIfChanged(node,value,gaugeUpdate);}
                catch(IOException|RuntimeException ignored){}
            }
        }
        synchronized(this){
            if(mode!=0&&!closed&&schedule==next&&(screenOn||startingUp)){
                try{OplusVotes.setChargingMode(mode);}catch(IOException|RuntimeException ignored){}
            }
        }
    }

    void close(){
        closed=true;
        worker.shutdownNow();
        synchronized(this){/* Finish any pending write before the database closes. */}
    }
}
