package cn.astriafr.acobattery.daemon;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import cn.astriafr.acobattery.common.DischargeNotice;
import cn.astriafr.acobattery.common.OperationTimeout;
import cn.astriafr.acobattery.common.ProcessResponse;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Publishes committed screen-on discharge samples without polling the hardware. */
final class DischargeNotification {
    static final String SETTING="dischargeNotificationEnabled", RUNTIME_KEY="discharge_notification";
    private static final long MIN_INTERVAL_MS=30_000, RECENT_MS=30*60_000;
    private static final int INVALID_QUALITY=1|2|4|8|Core.REBOOT_GAP|Core.DISCHARGE_REBASE;
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
    private final File ownerFile;
    private final ExecutorService worker=Executors.newSingleThreadExecutor(work->{
        Thread thread=new Thread(work,"aco-discharge-notice");thread.setDaemon(true);return thread;
    });
    private volatile boolean interactive;
    private volatile Request requested;
    private long shownSession=-1,shownX=-1;

    private static final class Request {
        final long session,x;
        final boolean highCapacity;
        Request(long session,long x,boolean highCapacity){this.session=session;this.x=x;this.highCapacity=highCapacity;}
    }

    DischargeNotification(SQLiteDatabase db,File data){
        this.db=db;ownerFile=new File(data,"owner.user");
    }
    void screen(boolean interactive){this.interactive=interactive;}
    synchronized void sample(long session,long x,boolean highCapacity){
        Request before=requested;
        if(!interactive||worker.isShutdown()||before!=null&&before.session==session&&before.x==x)return;
        Request next=new Request(session,x,highCapacity);
        requested=next;
        worker.execute(()->publish(next));
    }
    synchronized void clear(){
        if(requested==null||worker.isShutdown())return;
        requested=null;
        worker.execute(()->publish(null));
    }
    void close(){
        clear();
        worker.shutdown();
        try{if(!worker.awaitTermination(1800,TimeUnit.MILLISECONDS))worker.shutdownNow();}
        catch(InterruptedException interrupted){worker.shutdownNow();Thread.currentThread().interrupt();}
    }

    private void publish(Request request){
        if(requested!=request||request!=null&&!interactive)return;
        try{
            if(request==null){cancel();return;}
            long x,wall;
            double rm,voltage,temp,percentChange;
            String fields="SELECT s.x,s.rm,s.voltage,s.temp,s.wall,"
                +"s.pct-(SELECT pct_start FROM sessions WHERE id=s.session)";
            try(Cursor row=db.rawQuery(fields+ELIGIBLE+" ORDER BY s.x DESC LIMIT 1",
                    new String[]{Long.toString(request.session),Long.toString(request.x)})){
                if(!row.moveToFirst()){
                    if(requested==request&&interactive)cancel();
                    return;
                }
                x=row.getLong(0);rm=row.getDouble(1);voltage=row.getDouble(2);
                temp=row.isNull(3)?Double.NaN:row.getDouble(3);wall=row.getLong(4);
                percentChange=row.isNull(5)?Double.NaN:row.getDouble(5);
            }
            if(shownSession==request.session&&shownX==x)return;
            double watts=recentPower(request.session,x);
            double wh=RemainingEnergy.wh(rm,voltage,request.highCapacity);
            long minutes=watts>0&&Core.finite(wh)?Math.round(wh/watts*60):-1;
            if(requested!=request||!interactive)return;
            broadcast("--ez","visible","true","--el","wall",Long.toString(wall),
                "--es","rm",Double.toString(rm),"--es","wh",Double.toString(wh),
                "--es","voltage",Double.toString(voltage/1000),"--es","temp",Double.toString(temp),
                "--es","watts",Double.toString(watts),"--el","minutes",Long.toString(minutes),
                "--es","percentChange",Double.toString(percentChange),"--el","elapsed",Long.toString(x));
            shownSession=request.session;shownX=x;
        }catch(Exception failure){
            android.util.Log.w("AColBattery","Discharge notification update failed",failure);
        }
    }
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
    private void cancel()throws Exception{
        if(shownSession<0)return;
        broadcast("--ez","visible","false");
        shownSession=shownX=-1;
    }
    private void broadcast(String... extras)throws Exception{
        int user=Integer.parseInt(Owner.read(ownerFile));
        if(user<0)throw new IOException("Invalid notification user");
        List<String> command=new ArrayList<>(Arrays.asList("/system/bin/cmd","activity","broadcast",
            "--async","--user",Integer.toString(user),"--receiver-replace-pending",
            "-a",DischargeNotice.ACTION,"-n",Owner.PACKAGE+"/"+DischargeNotice.RECEIVER));
        command.addAll(Arrays.asList(extras));
        java.lang.Process process=new ProcessBuilder(command).redirectErrorStream(true).start();
        ProcessResponse result=ProcessResponse.read(process,OperationTimeout.deadline(800),2048);
        if(result.code!=0)throw new IOException(result.text);
    }
}
