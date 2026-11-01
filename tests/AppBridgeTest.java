package cn.astriafr.acobattery.daemon;

/** Real command routing, including the settings reset's foreground request. */
public final class AppBridgeTest {
    static void check(boolean value){if(!value)throw new AssertionError();}
    static void rejected(String command){
        try{AppBridge.parse(command);throw new AssertionError("Unsafe command accepted");}
        catch(IllegalArgumentException expected){}
    }
    public static void main(String[] args){
        String client=AppBridge.ROOT+"bin/curve-client ";
        String[] defaults=AppBridge.parse(client+"default_settings");
        check(defaults.length==2&&defaults[0].equals("api")&&defaults[1].equals("default_settings"));
        check(AppBridge.parse(client+"set_settings 5 30 560 1 300 450 50 60 2 120 10 1 2 2 10001 0").length==18);
        check(AppBridge.parse(client+"set_recording 0")[1].equals("set_recording"));
        rejected(client+"default_settings; id");rejected(client+"set_recording 1;id");
        rejected(client+"unknown_action");rejected("/system/bin/id");
        System.out.println("Bridge routing: reset defaults, settings writes and rejected commands passed");
    }
}
