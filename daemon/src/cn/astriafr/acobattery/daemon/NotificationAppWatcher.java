package cn.astriafr.acobattery.daemon;

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.UserHandle;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/** Observes the owner's app leaving the foreground or exiting without polling. */
final class NotificationAppWatcher implements AutoCloseable {
    private final Context context;
    private final ActivityManager activity;
    private final Method removeListener;
    private final Object listener;

    NotificationAppWatcher(Context context,int user,Runnable onBackground)throws Exception{
        this.context=user==0?context:(Context)Context.class.getMethod("createContextAsUser",UserHandle.class,int.class)
            .invoke(context,UserHandle.getUserHandleForUid(user*100000),0);
        int uid=appInfo().uid;
        int cutpoint=ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND;
        activity=this.context.getSystemService(ActivityManager.class);
        Class<?> type=Class.forName("android.app.ActivityManager$OnUidImportanceListener");
        listener=Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(proxy,method,args)->{
            switch(method.getName()){
                case "hashCode":return System.identityHashCode(proxy);
                case "equals":return proxy==args[0];
                case "onUidImportance":
                    if((int)args[0]==uid&&(int)args[1]>cutpoint)onBackground.run();
                    return null;
                default:return null;
            }
        });
        removeListener=ActivityManager.class.getMethod("removeOnUidImportanceListener",type);
        try{
            ActivityManager.class.getMethod("addOnUidImportanceListener",type,int.class,int[].class)
                .invoke(activity,listener,cutpoint,new int[]{uid});
        }catch(NoSuchMethodException olderAndroid){
            ActivityManager.class.getMethod("addOnUidImportanceListener",type,int.class).invoke(activity,listener,cutpoint);
        }
    }

    private ApplicationInfo appInfo()throws Exception{return context.getPackageManager().getApplicationInfo(Owner.PACKAGE,0);}

    boolean canRestore(){
        try{return (appInfo().flags&ApplicationInfo.FLAG_STOPPED)==0;}
        catch(Exception unavailable){return false;}
    }

    @Override public void close(){
        try{removeListener.invoke(activity,listener);}catch(Exception ignored){}
    }
}
