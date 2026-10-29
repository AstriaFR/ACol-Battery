package cn.astriafr.acobattery.daemon;

import android.content.Context;
import android.os.Process;
import android.util.Base64;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.*;

/** Foreground-only usage history reads. Never holds the recorder/database mutex. */
final class DailyUsage {
    final Context context;
    final AtomicBoolean busy=new AtomicBoolean();
    final LinkedHashMap<String,String> labels=new LinkedHashMap<>(32,.75f,true);
    final LinkedHashMap<String,JSONObject> recent=new LinkedHashMap<>(8,.75f,true);
    DailyUsage(Context context){this.context=context;}

    JSONObject read(long from,long requestedTo,String token)throws Exception {
        if(!busy.compareAndSet(false,true))throw new IOException("亮屏统计正在读取，请稍后重试");
        int priority=Process.THREAD_PRIORITY_DEFAULT;
        try {
            priority=Process.getThreadPriority(Process.myTid());
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            long now=System.currentTimeMillis();TimeZone zone=TimeZone.getDefault();
            Calendar first=Calendar.getInstance(zone);first.setTimeInMillis(ScreenUsage.midnight(now,zone));first.add(Calendar.DAY_OF_MONTH,-9);
            if(from<first.getTimeInMillis()||from>now||requestedTo<from)throw new IOException("仅能补读最近十天的使用情况");
            long day=ScreenUsage.midnight(from,zone),next=ScreenUsage.nextDay(day,zone),to=Math.min(now,Math.min(requestedTo,next));
            if(to-from>27*3600000L)throw new IOException("使用情况读取范围无效");
            int user=(Integer)Class.forName("android.app.ActivityManager").getMethod("getCurrentUser").invoke(null);
            if(user!=0)throw new IOException("当前仅能读取主用户的使用情况");
            String key=zone.getID()+":"+from+":"+to+":"+token;
            if(recent.containsKey(key))return recent.get(key);
            Core.Timeline timeline=new Core.Timeline();boolean restored=restore(timeline,token,from,zone);
            long[] on=new long[24],covered=new long[24];List<Map<String,Long>> apps=new ArrayList<>();
            for(int i=0;i<24;i++)apps.add(new LinkedHashMap<>());
            // A resumed activity does not prove that the screen is on. Recover
            // screen/keyguard/app state before counting a midnight or cold start.
            // A valid continuous checkpoint avoids rereading that history.
            long queryFrom=restored&&timeline.screenKnown()?from:Math.max(0,from-86400000L);
            List<Core.Event> events=Main.queryUsage(context,queryFrom,to);long cursor=from;
            for(Core.Event event:events){
                if(event.time<=from){timeline.apply(event);continue;}
                if(event.time>to)break;
                accumulate(timeline,cursor,event.time,zone,on,covered,apps);timeline.apply(event);cursor=event.time;
            }
            accumulate(timeline,cursor,to,zone,on,covered,apps);
            JSONArray hours=new JSONArray();JSONObject names=new JSONObject();
            for(int i=0;i<24;i++){
                JSONArray rows=new JSONArray();
                for(Map.Entry<String,Long> app:apps.get(i).entrySet()){
                    rows.put(new JSONArray().put(app.getKey()).put(app.getValue()));
                    if(!names.has(app.getKey()))names.put(app.getKey(),label(app.getKey()));
                }
                hours.put(new JSONObject().put("onMs",on[i]).put("coveredMs",covered[i]).put("apps",rows));
            }
            JSONObject result=new JSONObject().put("ok",true).put("dayStart",day).put("dayEnd",next).put("zone",zone.getID())
                .put("from",from).put("through",to).put("hours",hours).put("labels",names)
                .put("state",checkpoint(timeline,to,zone)).put("seedAccepted",restored);
            // Unknown leading time must remain repairable if events arrive later.
            long known=0;for(long ms:covered)known+=ms;
            if(known==to-from){recent.put(key,result);while(recent.size()>8)recent.remove(recent.keySet().iterator().next());}
            return result;
        } finally {try{Process.setThreadPriority(priority);}finally{busy.set(false);}}
    }
    static void accumulate(Core.Timeline timeline,long start,long end,TimeZone zone,long[] on,long[] covered,List<Map<String,Long>> apps){
        if(!timeline.screenKnown())return;
        long cursor=start;Calendar clock=Calendar.getInstance(zone);
        while(cursor<end){
            clock.setTimeInMillis(cursor);int hour=clock.get(Calendar.HOUR_OF_DAY);
            long remaining=3600000L-clock.get(Calendar.MINUTE)*60000L-clock.get(Calendar.SECOND)*1000L-clock.get(Calendar.MILLISECOND);
            long stop=Math.min(end,cursor+remaining),ms=stop-cursor;covered[hour]+=ms;
            if(timeline.screenOn()){
                on[hour]+=ms;String pkg=timeline.currentOwner();Map<String,Long> rows=apps.get(hour);rows.put(pkg,rows.getOrDefault(pkg,0L)+ms);
            }
            cursor=stop;
        }
    }
    String label(String pkg){
        if(pkg.equals(Core.OTHER))return "其他亮屏使用";
        if(labels.containsKey(pkg))return labels.get(pkg);String name=pkg;
        try{name=context.getPackageManager().getApplicationLabel(context.getPackageManager().getApplicationInfo(pkg,0)).toString();}catch(Exception ignored){}
        labels.put(pkg,name);while(labels.size()>256)labels.remove(labels.keySet().iterator().next());return name;
    }
    static String checkpoint(Core.Timeline timeline,long through,TimeZone zone)throws Exception {
        Core.Timeline.State state=timeline.snapshot();JSONArray resumed=new JSONArray();
        for(Map.Entry<String,String> app:state.resumed.entrySet())resumed.put(new JSONArray().put(app.getKey()).put(app.getValue()));
        JSONObject json=new JSONObject().put("v",1).put("at",through).put("zone",zone.getID()).put("screen",state.screen).put("locked",state.locked).put("known",state.known).put("apps",resumed);
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(GZIPOutputStream zip=new GZIPOutputStream(bytes)){zip.write(json.toString().getBytes(StandardCharsets.UTF_8));}
        String token=Base64.encodeToString(bytes.toByteArray(),Base64.URL_SAFE|Base64.NO_WRAP|Base64.NO_PADDING);
        return token.length()<=4096?token:"0";
    }
    static boolean restore(Core.Timeline timeline,String token,long from,TimeZone zone){
        if(token==null||token.equals("0")||token.length()>4096)return false;
        try {
            byte[] packed=Base64.decode(token,Base64.URL_SAFE|Base64.NO_WRAP|Base64.NO_PADDING);ByteArrayOutputStream bytes=new ByteArrayOutputStream();
            try(InputStream in=new GZIPInputStream(new ByteArrayInputStream(packed))){byte[] buffer=new byte[1024];int n;while((n=in.read(buffer))!=-1){if(bytes.size()+n>65536)return false;bytes.write(buffer,0,n);}}
            JSONObject json=new JSONObject(new String(bytes.toByteArray(),StandardCharsets.UTF_8));
            if(json.optInt("v")!=1||json.optLong("at")!=from||!json.optString("zone").equals(zone.getID()))return false;
            JSONArray resumed=json.getJSONArray("apps");if(resumed.length()>256)return false;
            Core.Timeline.State state=new Core.Timeline.State();state.screen=json.getBoolean("screen");state.locked=json.getBoolean("locked");state.known=json.getBoolean("known");
            for(int i=0;i<resumed.length();i++){JSONArray app=resumed.getJSONArray(i);state.resumed.put(app.getString(0),app.getString(1));}
            timeline.restore(state);return true;
        }catch(Exception ignored){return false;}
    }
}
