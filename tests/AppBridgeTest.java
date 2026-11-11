package cn.astriafr.acobattery.daemon;
import java.io.IOException;
import org.json.JSONObject;

/** Real command routing, including the settings reset's foreground request. */
public final class AppBridgeTest {
    static void check(boolean value){if(!value)throw new AssertionError();}
    static void rejected(String command){
        try{AppBridge.parse(command);throw new AssertionError("Unsafe command accepted");}
        catch(IllegalArgumentException expected){}
    }
    public static void main(String[] args)throws Exception{
        JSONObject startup=new JSONObject().put("ok",true).put("frameworkHealthy",true);
        check(Admin.ready(startup)); // Existing installed backends use the older handshake.
        check(!Admin.ready(startup.put("recorderReady",false)));
        check(Admin.ready(startup.put("recorderReady",true)));
        Admin.requireShutdownReply(new JSONObject().put("ok",true));
        try{
            Admin.requireShutdownReply(new JSONObject().put("ok",false).put("error","stop failed"));
            throw new AssertionError("Rejected shutdown accepted");
        }catch(IOException expected){check("stop failed".equals(expected.getMessage()));}
        String client=AppBridge.ROOT+"bin/curve-client ";
        String[] defaults=AppBridge.parse(client+"default_settings");
        check(defaults.length==2&&defaults[0].equals("api")&&defaults[1].equals("default_settings"));
        check(AppBridge.parse(client+"battery_history_record")[1].equals("battery_history_record"));
        check(AppBridge.parse(client+"battery_history_trend 0 0")[1].equals("battery_history_trend"));
        check(AppBridge.parse(client+"export_points 1 0 0")[1].equals("export_points"));
        check(AppBridge.parse(client+"detail_preview 1 0 1000 1200")[1].equals("detail_preview"));
        check(AppBridge.parse(client+"detail_sample 1 0 1000 500")[1].equals("detail_sample"));
        check(AppBridge.parse(client+"interval_apps 1 0")[1].equals("interval_apps"));
        check(AppBridge.parse(client+"settle_usage 1")[1].equals("settle_usage"));
        rejected(client+"battery_history_record; id");
        check(AppBridge.parse(client+"set_settings 5 30 560 1 300 450 50 60 2 120 10 1 2 2 10001 0").length==18);
        check(AppBridge.parse(client+"set_settings 5 30 560 1 300 450 50 60 2 120 0.7 1 2 0.5 10001 0").length==18);
        check(AppBridge.parse(client+"set_settings 5 30 560 1 300 450 50 60 2 120 0.7 1 2 0.5 10001 0 0").length==19);
        rejected(client+"set_settings 5 30 560 1 300 450 50 60 2 120 0.75 1 2 0.5 10001 0");
        rejected(client+"set_settings 5 30 560 1 300 450 50 60 2 120 10 1 2 0.55 10001 0");
        rejected(client+"set_recording 0.5");
        check(AppBridge.parse(client+"set_recording 0")[1].equals("set_recording"));
        rejected(client+"default_settings; id");rejected(client+"set_recording 1;id");
        rejected(client+"unknown_action");rejected("/system/bin/id");
        System.out.println("Bridge routing: reset defaults, settings writes and rejected commands passed");
    }
}
