package cn.astriafr.acobattery.daemon;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.UserHandle;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.function.LongConsumer;

/** Observes removal of the owner's app task without polling. */
final class DischargeTaskWatcher implements AutoCloseable {
    private final Context context;
    private final ActivityManager activity;
    private final Method removeListener;
    private final Object listener;

    DischargeTaskWatcher(Context context,int user,LongConsumer onExit)throws Exception{
        this.context=user==0?context:(Context)Context.class.getMethod("createContextAsUser",UserHandle.class,int.class)
            .invoke(context,UserHandle.getUserHandleForUid(user*100000),0);
        int uid=appInfo().uid;
        activity=this.context.getSystemService(ActivityManager.class);
        Class<?> type=Class.forName("android.app.ActivityManager$OnUidImportanceListener");
        listener=Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(proxy,method,args)->{
            switch(method.getName()){
                case "hashCode":return System.identityHashCode(proxy);
                case "equals":return proxy==args[0];
                case "onUidImportance":
                    if((int)args[0]==uid&&(int)args[1]==ActivityManager.RunningAppProcessInfo.IMPORTANCE_GONE)
                        onExit.accept(System.currentTimeMillis());
                    return null;
                default:return null;
            }
        });
        removeListener=ActivityManager.class.getMethod("removeOnUidImportanceListener",type);
        int cutpoint=ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND;
        try{
            ActivityManager.class.getMethod("addOnUidImportanceListener",type,int.class,int[].class)
                .invoke(activity,listener,cutpoint,new int[]{uid});
        }catch(NoSuchMethodException olderAndroid){
            ActivityManager.class.getMethod("addOnUidImportanceListener",type,int.class).invoke(activity,listener,cutpoint);
        }
    }

    private ApplicationInfo appInfo()throws Exception{return context.getPackageManager().getApplicationInfo(Owner.PACKAGE,0);}

    boolean wasTaskRemoved(long observedAt){
        try{
            if((appInfo().flags&ApplicationInfo.FLAG_STOPPED)!=0)return false;
            for(ApplicationExitInfo exit:activity.getHistoricalProcessExitReasons(Owner.PACKAGE,0,6)){
                if(!Owner.PACKAGE.equals(exit.getProcessName()))continue;
                if(Math.abs(exit.getTimestamp()-observedAt)>3000||exit.getReason()!=ApplicationExitInfo.REASON_USER_REQUESTED)return false;
                try{
                    return ApplicationExitInfo.class.getMethod("getSubReason").invoke(exit)
                        .equals(ApplicationExitInfo.class.getField("SUBREASON_REMOVE_TASK").get(null));
                }catch(ReflectiveOperationException unavailable){
                    String description=exit.getDescription();
                    return description!=null&&description.startsWith("[REMOVE TASK]");
                }
            }
        }catch(Exception unavailable){}
        return false;
    }

    @Override public void close(){
        try{removeListener.invoke(activity,listener);}catch(Exception ignored){}
    }
}
