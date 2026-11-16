package cn.astriafr.acobattery;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.PowerManager;
import android.service.notification.StatusBarNotification;
import android.widget.RemoteViews;
import cn.astriafr.acobattery.common.DischargeNotice;
import java.util.Locale;

/** Receives an explicit snapshot from the independent recorder. */
public final class DischargeNotificationReceiver extends BroadcastReceiver {
    private static final String CHANNEL="discharge_record";
    private static final int ID=0x41434f44;
    private static final String SEPARATOR="  ";

    static boolean canPost(Context context){
        NotificationManager manager=context.getSystemService(NotificationManager.class);
        if(manager==null||!manager.areNotificationsEnabled())return false;
        NotificationChannel channel=manager.getNotificationChannel(CHANNEL);
        return channel==null||channel.getImportance()!=NotificationManager.IMPORTANCE_NONE;
    }

    @Override public void onReceive(Context context,Intent intent){
        if(DischargeNotice.ACTION.equals(intent.getAction())){
            boolean delivered=deliver(context,intent.getExtras());
            if(isOrderedBroadcast())setResultCode(delivered?Activity.RESULT_OK:Activity.RESULT_CANCELED);
        }
    }

    static synchronized boolean deliver(Context context,Bundle snapshot){
        if(snapshot==null)return false;
        NotificationManager manager=context.getSystemService(NotificationManager.class);
        if(manager==null)return false;
        try{
            if(!snapshot.getBoolean("visible",false)){manager.cancel(ID);return true;}
            PowerManager power=context.getSystemService(PowerManager.class);
            if(power==null||!power.isInteractive()||!canPost(context))return false;
            if(snapshot.getBoolean("onlyIfMissing")){
                for(StatusBarNotification active:manager.getActiveNotifications())
                    if(active.getId()==ID&&active.getTag()==null)return false;
            }
            post(context,manager,snapshot);
            return true;
        }catch(RuntimeException failure){
            android.util.Log.w("AColBattery","Record notification unavailable",failure);
            return false;
        }
    }

    private static void post(Context context,NotificationManager manager,Bundle snapshot){
        if(manager.getNotificationChannel(CHANNEL)==null)
            manager.createNotificationChannel(new NotificationChannel(CHANNEL,"放电记录",NotificationManager.IMPORTANCE_LOW));
        boolean charging=snapshot.getBoolean("charging");
        String capacity=metric(number(snapshot,"rm"),"%.0f",charging?"mAh":" mAh");
        String voltage=metric(number(snapshot,"voltage"),"%.2f",charging?"V":" V");
        String temperature=metric(number(snapshot,"temp"),"%.1f",charging?"℃":" °C");
        double power=number(snapshot,"watts");
        String watts=metric(power,charging&&Math.abs(power)>=10?"%.1f":"%.2f","W");
        long elapsed=snapshot.getLong("elapsed",-1);
        String text,title;
        RemoteViews readings;
        if(charging){
            double current=number(snapshot,"current");
            String amps=Math.abs(current)>=1000?metric(current/1000,"%.2f","A"):metric(current,"%.0f","mA");
            title=elapsed<0?"—":String.format(Locale.ROOT,"%dm%02ds",elapsed/60_000,elapsed/1000%60);
            text=String.join(SEPARATOR,capacity,amps,watts,voltage,temperature);
            readings=new RemoteViews(context.getPackageName(),R.layout.charge_notification);
            readings.setTextViewText(R.id.charge_notice_values,text);
        }else{
            double remainingWh=number(snapshot,"wh");
            String first="剩余 "+capacity;
            String second=metric(remainingWh,remainingWh<2?"%.2f":"%.1f"," Wh");
            String third=duration(snapshot.getLong("minutes",-1));
            watts="最近平均 "+watts;
            title=elapsed<0?null:percentageChange(number(snapshot,"percentChange"))+" "+duration(elapsed/60_000);
            text=String.join(SEPARATOR,first,second,third)+"\n"+String.join(SEPARATOR,watts,voltage,temperature);
            readings=new RemoteViews(context.getPackageName(),R.layout.discharge_notification);
            readings.setTextViewText(R.id.record_notice_top_start,first);
            readings.setTextViewText(R.id.record_notice_top_center,second);
            readings.setTextViewText(R.id.record_notice_top_end,third);
            readings.setTextViewText(R.id.record_notice_bottom_start,watts);
            readings.setTextViewText(R.id.record_notice_bottom_center,voltage);
            readings.setTextViewText(R.id.record_notice_bottom_end,temperature);
        }
        Intent open=new Intent(context,MainActivity.class).setAction(Intent.ACTION_MAIN)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent content=PendingIntent.getActivity(context,0,open,PendingIntent.FLAG_IMMUTABLE);
        Notification notification=new Notification.Builder(context,CHANNEL)
            .setSmallIcon(R.drawable.tile_power).setColor(Color.rgb(7,130,103))
            .setContentTitle(title==null?"放电记录":title)
            .setSubText(title)
            .setContentText(text)
            .setCustomContentView(readings).setCustomBigContentView(readings)
            .setStyle(new Notification.DecoratedCustomViewStyle())
            .setWhen(snapshot.getLong("wall",0)).setShowWhen(false)
            .setOngoing(true).setOnlyAlertOnce(true).setVisibility(Notification.VISIBILITY_PRIVATE)
            .setContentIntent(content).build();
        manager.notify(ID,notification);
    }

    private static double number(Bundle snapshot,String key){
        try{return Double.parseDouble(snapshot.getString(key));}
        catch(NumberFormatException|NullPointerException invalid){return Double.NaN;}
    }
    private static String metric(double value,String pattern,String unit){
        return Double.isFinite(value)?String.format(Locale.ROOT,pattern,value)+unit:"—"+unit;
    }
    private static String percentageChange(double change){
        if(!Double.isFinite(change))return "—";
        return (change>0?"+":"-")+Math.round(Math.abs(change))+"%";
    }
    private static String duration(long minutes){
        if(minutes<0)return "—";
        return minutes/60+"时"+minutes%60+"分";
    }
}
