package cn.astriafr.acobattery.daemon;

import android.annotation.TargetApi;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Handler;

/** Keep API 36 references out of the class loaded on Android 15. */
final class ScreenEvents {
    static String register(DisplayManager manager,DisplayManager.DisplayListener listener,Handler handler){
        if(Build.VERSION.SDK_INT>=36){
            try{Api36.register(manager,listener,handler);return "display_state";}
            catch(RuntimeException|LinkageError e){System.err.println("Display state subscription unavailable; using legacy listener: "+e.getClass().getSimpleName());}
        }
        manager.registerDisplayListener(listener,handler);
        return "display_legacy";
    }
    @TargetApi(36) private static final class Api36 {
        static void register(DisplayManager manager,DisplayManager.DisplayListener listener,Handler handler){
            manager.registerDisplayListener(command->handler.post(command),DisplayManager.EVENT_TYPE_DISPLAY_STATE,listener);
        }
    }
}
