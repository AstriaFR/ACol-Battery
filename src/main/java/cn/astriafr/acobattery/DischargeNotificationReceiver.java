package cn.astriafr.acobattery;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.PowerManager;
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
        if(!DischargeNotice.ACTION.equals(intent.getAction()))return;
        NotificationManager manager=context.getSystemService(NotificationManager.class);
        if(manager==null)return;
        try{
            if(!intent.getBooleanExtra("visible",false)){manager.cancel(ID);return;}
            PowerManager power=context.getSystemService(PowerManager.class);
            if(power==null||!power.isInteractive()||!canPost(context))return;
            post(context,manager,intent);
        }catch(RuntimeException failure){
            android.util.Log.w("AColBattery","Discharge notification unavailable",failure);
        }
    }

    private static void post(Context context,NotificationManager manager,Intent snapshot){
        if(manager.getNotificationChannel(CHANNEL)==null)
            manager.createNotificationChannel(new NotificationChannel(CHANNEL,"放电记录",NotificationManager.IMPORTANCE_LOW));
        double remainingWh=number(snapshot,"wh");
        String capacity="剩余 "+metric(number(snapshot,"rm"),"%.0f"," mAh");
        String energy=metric(remainingWh,remainingWh<2?"%.2f":"%.1f"," Wh");
        String voltage=metric(number(snapshot,"voltage"),"%.2f"," V");
        String temperature=metric(number(snapshot,"temp"),"%.1f"," °C");
        String watts="最近平均 "+metric(number(snapshot,"watts"),"%.2f","W");
        String time=duration(snapshot.getLongExtra("minutes",-1));
        long elapsed=snapshot.getLongExtra("elapsed",-1);
        String title=elapsed<0?null:percentageChange(number(snapshot,"percentChange"))+" "+duration(elapsed/60_000);
        String remaining=capacity+SEPARATOR+energy+SEPARATOR+time;
        String measurements=watts+SEPARATOR+voltage+SEPARATOR+temperature;
        RemoteViews readings=new RemoteViews(context.getPackageName(),R.layout.discharge_notification);
        readings.setTextViewText(R.id.discharge_capacity,capacity);
        readings.setTextViewText(R.id.discharge_energy,energy);
        readings.setTextViewText(R.id.discharge_duration,time);
        readings.setTextViewText(R.id.discharge_voltage,voltage);
        readings.setTextViewText(R.id.discharge_temperature,temperature);
        readings.setTextViewText(R.id.discharge_power,watts);
        Intent open=new Intent(context,MainActivity.class).setAction(Intent.ACTION_MAIN)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent content=PendingIntent.getActivity(context,0,open,PendingIntent.FLAG_IMMUTABLE);
        Notification notification=new Notification.Builder(context,CHANNEL)
            .setSmallIcon(R.drawable.tile_power).setColor(Color.rgb(7,130,103))
            .setContentTitle(title==null?"放电记录":title)
            .setSubText(title)
            .setContentText(remaining+"\n"+measurements)
            .setCustomContentView(readings).setCustomBigContentView(readings)
            .setStyle(new Notification.DecoratedCustomViewStyle())
            .setWhen(snapshot.getLongExtra("wall",0)).setShowWhen(false)
            .setOngoing(true).setOnlyAlertOnce(true).setVisibility(Notification.VISIBILITY_PRIVATE)
            .setContentIntent(content).build();
        manager.notify(ID,notification);
    }

    private static double number(Intent snapshot,String key){
        try{return Double.parseDouble(snapshot.getStringExtra(key));}
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
