package cn.astriafr.acobattery.daemon;

import android.app.Activity;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Handler;
import android.os.Looper;
import cn.astriafr.acobattery.common.DischargeNotice;
import cn.astriafr.acobattery.common.OperationTimeout;
import cn.astriafr.acobattery.common.ProcessResponse;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Publishes committed screen-on battery samples without polling the hardware. */
final class RecordNotification {
    static final String SETTING="dischargeNotificationEnabled", RUNTIME_KEY="discharge_notification";
    private static final long MIN_INTERVAL_MS=30_000, RECENT_MS=30*60_000;
    private static final long CANCEL_RETRY_MS=2_000;
    private static final int CANCEL_ATTEMPTS=3;
    static final int INVALID_QUALITY=1|2|4|8|Core.REBOOT_GAP|Core.DISCHARGE_REBASE;
    private static final String ELIGIBLE=
        " FROM samples s"
        +" JOIN usage_raw ending ON ending.session=s.session AND ending.x=s.x"
        +" JOIN usage_raw starting ON starting.session=s.session AND starting.x=s.x-s.interval_ms"
        +" WHERE s.session=? AND s.x<=? AND s.interval_ms>="+MIN_INTERVAL_MS
        +" AND s.power>=0 AND s.rm>=0 AND s.online=0 AND (s.quality&"+INVALID_QUALITY+")=0"
        +" AND ending.screen_on=1 AND starting.screen_on=1 AND ending.online=0 AND starting.online=0"
        +" AND (ending.quality&"+DeferredStats.SCREEN_INTERVAL_UNCERTAIN+")=0"
        +" AND ABS(ending.wall-starting.wall-s.interval_ms)<=2000";
    private final SQLiteDatabase db;
    private final Context context;
    private final File ownerFile;
    private final Handler events=new Handler(Looper.getMainLooper());
    private final Runnable restoreTask=this::restoreAfterTaskRemoval;
    private final ScheduledThreadPoolExecutor worker=new ScheduledThreadPoolExecutor(1,work->{
        Thread thread=new Thread(work,"aco-record-notice");thread.setDaemon(true);return thread;
    });
    private volatile boolean interactive;
    private volatile Request requested;
    // The notification belongs to the app and can outlive this recorder process.
    private volatile boolean noticeMayExist=true;
    private Cancellation cancellation;
    private long shownSession=-1,shownX=-1;
    private long processExitWall;
    private boolean watcherStarted;
    private DischargeTaskWatcher watcher;

    private static final class Request {
        final long session,x;
        final boolean charging,highCapacity;
        Request(long session,long x,boolean charging,boolean highCapacity){
            this.session=session;this.x=x;this.charging=charging;this.highCapacity=highCapacity;
        }
    }

    private final class Cancellation implements Runnable {
        private int attempts;
        private ScheduledFuture<?> retry;

        @Override public void run(){
            synchronized(RecordNotification.this){
                if(cancellation!=this)return;
                attempts++;
            }
            boolean withdrawn=false;
            try{withdrawn=!noticeMayExist||deliver("--ez","visible","false");}
            catch(Exception failure){
                android.util.Log.w("AColBattery","Record notification cancellation failed",failure);
            }
            if(withdrawn){
                shownSession=shownX=-1;
                noticeMayExist=false;
            }
            synchronized(RecordNotification.this){
                if(cancellation!=this)return;
                if(withdrawn)cancellation=null;
                else if(attempts<CANCEL_ATTEMPTS&&!worker.isShutdown())
                    retry=worker.schedule(this,CANCEL_RETRY_MS*attempts,TimeUnit.MILLISECONDS);
            }
        }
    }

    RecordNotification(Context context,SQLiteDatabase db,File data){
        this.context=context;this.db=db;ownerFile=new File(data,"owner.user");
        worker.setRemoveOnCancelPolicy(true);
    }
    synchronized void screen(boolean interactive){
        if(this.interactive==interactive)return;
        this.interactive=interactive;
        events.removeCallbacks(restoreTask);
        if(interactive&&processExitWall!=0)events.postDelayed(restoreTask,350);
        if(interactive&&cancellation!=null&&cancellation.attempts>=CANCEL_ATTEMPTS){
            discardCancellation();
            scheduleCancellation(requested);
        }
    }
    synchronized void sample(long session,long x,boolean charging,boolean highCapacity){
        Request before=requested;
        if(!interactive){
            if(before==null||before.session!=session)clear();
            return;
        }
        if(worker.isShutdown()||before!=null&&before.session==session&&before.x==x)return;
        Request next=new Request(session,x,charging,highCapacity);
        discardCancellation();
        requested=next;
        if(!watcherStarted){watcherStarted=true;worker.execute(this::startWatcher);}
        worker.execute(()->publish(next,false));
    }
    synchronized void clear(){
        events.removeCallbacks(restoreTask);
        processExitWall=0;
        if(requested!=null)discardCancellation();
        requested=null;
        scheduleCancellation(null);
    }
    private synchronized void scheduleCancellation(Request request){
        if(requested!=request||!noticeMayExist||cancellation!=null||worker.isShutdown())return;
        cancellation=new Cancellation();
        worker.execute(cancellation);
    }
    private void discardCancellation(){
        if(cancellation!=null&&cancellation.retry!=null)cancellation.retry.cancel(false);
        cancellation=null;
    }
    private synchronized boolean beginPublish(Request request){
        if(requested!=request||!interactive)return false;
        noticeMayExist=true;
        return true;
    }
    void close(){
        synchronized(this){
            if(worker.isShutdown())return;
            discardCancellation();
            clear();
            worker.execute(()->{if(watcher!=null)watcher.close();});
            worker.shutdown();
        }
        try{if(!worker.awaitTermination(1800,TimeUnit.MILLISECONDS))worker.shutdownNow();}
        catch(InterruptedException interrupted){worker.shutdownNow();Thread.currentThread().interrupt();}
    }

    private void startWatcher(){
        try{watcher=new DischargeTaskWatcher(context,Integer.parseInt(Owner.read(ownerFile)),this::processExited);}
        catch(Exception|LinkageError unavailable){}
    }

    private synchronized void processExited(long wall){
        if(requested==null||worker.isShutdown())return;
        processExitWall=wall;
        if(interactive){
            events.removeCallbacks(restoreTask);
            events.postDelayed(restoreTask,350);
        }
    }

    private synchronized void restoreAfterTaskRemoval(){
        if(!interactive||processExitWall==0||requested==null||worker.isShutdown())return;
        worker.execute(()->{
            long wall;
            synchronized(this){
                if(!interactive||processExitWall==0||requested==null)return;
                wall=processExitWall;processExitWall=0;
            }
            if(watcher!=null&&watcher.wasTaskRemoved(wall)){
                Request latest=requested;
                if(latest!=null)publish(latest,true);
            }
        });
    }

    private void publish(Request request,boolean restore){
        if(requested!=request||!interactive)return;
        try{
            if(request.charging){publishCharge(request,restore);return;}
            long x,wall;
            double rm,voltage,temp,percentChange;
            String fields="SELECT s.x,s.rm,s.voltage,s.temp,s.wall,"
                +"s.pct-(SELECT pct_start FROM sessions WHERE id=s.session)";
            try(Cursor row=db.rawQuery(fields+ELIGIBLE+" ORDER BY s.x DESC LIMIT 1",
                    new String[]{Long.toString(request.session),Long.toString(request.x)})){
                if(!row.moveToFirst()){
                    if(!restore&&interactive)scheduleCancellation(request);
                    return;
                }
                x=row.getLong(0);rm=row.getDouble(1);voltage=row.getDouble(2);
                temp=row.isNull(3)?Double.NaN:row.getDouble(3);wall=row.getLong(4);
                percentChange=row.isNull(5)?Double.NaN:row.getDouble(5);
            }
            if(!restore&&shownSession==request.session&&shownX==x)return;
            double watts=recentPower(request.session,x);
            double wh=RemainingEnergy.wh(rm,voltage,request.highCapacity);
            long minutes=watts>0&&Core.finite(wh)?Math.round(wh/watts*60):-1;
            if(!beginPublish(request))return;
            if(!deliver("--ez","visible","true","--ez","onlyIfMissing",Boolean.toString(restore),"--el","wall",Long.toString(wall),
                "--es","rm",Double.toString(rm),"--es","wh",Double.toString(wh),
                "--es","voltage",Double.toString(voltage/1000),"--es","temp",Double.toString(temp),
                "--es","watts",Double.toString(watts),"--el","minutes",Long.toString(minutes),
                "--es","percentChange",Double.toString(percentChange),"--el","elapsed",Long.toString(x)))return;
            shownSession=request.session;shownX=x;
        }catch(Exception failure){
            android.util.Log.w("AColBattery","Record notification update failed",failure);
        }
    }

    private void publishCharge(Request request,boolean restore)throws Exception{
        long wall;
        double rm,current,watts,voltage,temp;
        try(Cursor row=db.rawQuery("SELECT wall,rm,current,power,voltage,temp FROM samples"
                +" WHERE session=? AND x=? AND online=1 AND (quality&"+INVALID_QUALITY+")=0",
                new String[]{Long.toString(request.session),Long.toString(request.x)})){
            if(!row.moveToFirst()){
                if(!restore&&interactive)scheduleCancellation(request);
                return;
            }
            wall=row.getLong(0);rm=reading(row,1);current=reading(row,2);
            watts=reading(row,3);voltage=reading(row,4);temp=reading(row,5);
        }
        if(!beginPublish(request))return;
        if(!deliver("--ez","visible","true","--ez","charging","true","--ez","onlyIfMissing",Boolean.toString(restore),
                "--el","wall",Long.toString(wall),"--el","elapsed",Long.toString(request.x),
                "--es","rm",Double.toString(rm),"--es","current",Double.toString(current),
                "--es","watts",Double.toString(watts),"--es","voltage",Double.toString(voltage/1000),"--es","temp",Double.toString(temp)))return;
        shownSession=request.session;shownX=request.x;
    }

    private static double reading(Cursor row,int column){return row.isNull(column)?Double.NaN:row.getDouble(column);}
    private double recentPower(long session,long end){
        long from=Math.max(0,end-RECENT_MS);
        double wattMillis=0;long millis=0;
        try(Cursor rows=db.rawQuery("SELECT s.x,s.interval_ms,s.power"+ELIGIBLE+" AND s.x>?",
                new String[]{Long.toString(session),Long.toString(end),Long.toString(from)})){
            while(rows.moveToNext()){
                long duration=Math.min(rows.getLong(1),rows.getLong(0)-from);
                wattMillis+=rows.getDouble(2)*duration;
                millis+=duration;
            }
        }
        return millis>0?wattMillis/millis:Double.NaN;
    }
    private boolean deliver(String... extras)throws Exception{
        boolean withdrawing="false".equals(extras[2]);
        int user=Integer.parseInt(Owner.read(ownerFile));
        if(user<0)throw new IOException("Invalid notification user");
        List<String> command=new ArrayList<>(Arrays.asList("/system/bin/content","call",
            "--uri","content://"+DischargeNotice.AUTHORITY,"--user",Integer.toString(user),"--method",DischargeNotice.ACTION));
        for(int i=0;i<extras.length;i+=3){
            char type=extras[i].charAt(3);
            command.add("--extra");command.add(extras[i+1]+":"+(type=='z'?'b':type)+":"+extras[i+2]);
        }
        try{
            String result=run(command,1500);
            if(result.contains("posted=true"))return true;
            if(result.contains("posted=false"))return false;
            throw new IOException(result);
        }
        catch(IOException unavailable){
            List<String> fallback=new ArrayList<>(Arrays.asList("/system/bin/cmd","activity","broadcast"));
            if(!withdrawing)fallback.add("--async");
            fallback.addAll(Arrays.asList("--user",Integer.toString(user),"--receiver-replace-pending",
                "-a",DischargeNotice.ACTION,"-n",Owner.PACKAGE+"/"+DischargeNotice.RECEIVER));
            fallback.addAll(Arrays.asList(extras));
            try{
                String result=run(fallback,800);
                return !withdrawing||result.contains("Broadcast completed: result="+Activity.RESULT_OK);
            }
            catch(IOException failure){failure.addSuppressed(unavailable);throw failure;}
        }
    }
    private static String run(List<String> command,int timeout)throws Exception{
        java.lang.Process process=new ProcessBuilder(command).redirectErrorStream(true).start();
        ProcessResponse result=ProcessResponse.read(process,OperationTimeout.deadline(timeout),2048);
        if(result.code!=0||result.text.contains("Error:"))throw new IOException(result.text);
        return result.text;
    }
}
