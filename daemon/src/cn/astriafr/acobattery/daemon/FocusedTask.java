package cn.astriafr.acobattery.daemon;

import android.content.ComponentName;
import android.view.Display;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

/** Resolves the visible leaf task with focus on the monitor's display. */
final class FocusedTask {
    final int id;
    final ComponentName activity;
    final int windowingMode;

    private FocusedTask(int id,ComponentName activity,int windowingMode){
        this.id=id;this.activity=activity;this.windowingMode=windowingMode;
    }

    static final class Lookup {
        private final Method service,runningTasks,windowingMode;
        private final Field id,focused,visible,activity;

        Lookup()throws ReflectiveOperationException{
            service=Class.forName("android.app.ActivityTaskManager").getMethod("getService");
            runningTasks=Class.forName("android.app.IActivityTaskManager")
                .getMethod("getTasks",int.class,boolean.class,boolean.class,int.class);
            Class<?> task=Class.forName("android.app.TaskInfo");
            id=task.getField("taskId");focused=task.getField("isFocused");
            visible=task.getField("isVisible");activity=task.getField("topActivity");
            windowingMode=task.getMethod("getWindowingMode");
        }

        FocusedTask read()throws ReflectiveOperationException{
            List<?> tasks=(List<?>)runningTasks.invoke(service.invoke(null),16,false,false,Display.DEFAULT_DISPLAY);
            for(Object task:tasks){
                ComponentName component=(ComponentName)activity.get(task);
                if(component!=null&&focused.getBoolean(task)&&visible.getBoolean(task))
                    return new FocusedTask(id.getInt(task),component,(int)windowingMode.invoke(task));
            }
            return null;
        }
    }
}
