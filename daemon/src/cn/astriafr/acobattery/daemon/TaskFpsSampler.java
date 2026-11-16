package cn.astriafr.acobattery.daemon;

import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.SystemClock;
import java.lang.reflect.Method;

/** One listener for the focused task. Registration and reads run on the FPS worker. */
final class TaskFpsSampler {
    private static final String CALLBACK="android.window.ITaskFpsCallback";
    private static final long RETRY_MS=5000;
    private Api api;
    private Subscription subscription;
    private long retryAt;
    private boolean failureLogged;

    double read(long maxReportAgeMs){
        if(SystemClock.elapsedRealtime()<retryAt)return Double.NaN;
        try{
            if(api==null)api=new Api();
            FocusedTask focused=api.focus.read();
            int taskId=focused==null?-1:focused.id;
            if(subscription!=null&&(taskId!=subscription.taskId||!subscription.manager.asBinder().isBinderAlive()))
                unregister();
            if(taskId<0)return Double.NaN;
            if(subscription==null){
                Subscription next=new Subscription(api,taskId);
                api.register.invoke(next.manager,taskId,next.callback);
                subscription=next;
            }
            failureLogged=false;
            Report report=subscription.latest;
            return report!=null&&SystemClock.elapsedRealtime()-report.receivedAt<=maxReportAgeMs
                ?report.fps:Double.NaN;
        }catch(ReflectiveOperationException|RuntimeException|LinkageError unavailable){
            failed(unavailable);
            return Double.NaN;
        }
    }

    void stop(){
        try{unregister();}
        catch(ReflectiveOperationException|RuntimeException|LinkageError unavailable){failed(unavailable);}
    }

    private void unregister()throws ReflectiveOperationException{
        if(subscription==null)return;
        if(subscription.manager.asBinder().isBinderAlive())api.unregister.invoke(subscription.manager,subscription.callback);
        subscription=null;
    }

    private void failed(Throwable failure){
        retryAt=SystemClock.elapsedRealtime()+RETRY_MS;
        if(!failureLogged){
            failureLogged=true;
            System.err.println("Task FPS callback unavailable: "+FrameworkWatch.cause(failure));
        }
    }

    private static final class Api {
        final FocusedTask.Lookup focus=new FocusedTask.Lookup();
        final Method windowBinder,windowInterface,callbackInterface,register,unregister;

        Api()throws ReflectiveOperationException{
            windowBinder=Class.forName("android.os.ServiceManager").getMethod("checkService",String.class);
            windowInterface=Class.forName("android.view.IWindowManager$Stub").getMethod("asInterface",IBinder.class);
            callbackInterface=Class.forName(CALLBACK+"$Stub").getMethod("asInterface",IBinder.class);
            Class<?> callback=Class.forName(CALLBACK);
            Class<?> manager=Class.forName("android.view.IWindowManager");
            register=manager.getMethod("registerTaskFpsCallback",int.class,callback);
            unregister=manager.getMethod("unregisterTaskFpsCallback",callback);
        }

    }

    private static final class Subscription extends Binder {
        final int taskId;
        final IInterface manager;
        final Object callback;
        volatile Report latest;

        Subscription(Api api,int taskId)throws ReflectiveOperationException{
            this.taskId=taskId;
            manager=(IInterface)api.windowInterface.invoke(null,api.windowBinder.invoke(null,"window"));
            if(manager==null)throw new IllegalStateException("WindowManager unavailable");
            // Let the framework's generated proxy marshal the hidden callback interface.
            attachInterface(null,CALLBACK);
            callback=api.callbackInterface.invoke(null,this);
        }

        @Override protected boolean onTransact(int code,Parcel data,Parcel reply,int flags)throws RemoteException{
            if(code!=FIRST_CALL_TRANSACTION)return super.onTransact(code,data,reply,flags);
            data.enforceInterface(CALLBACK);
            float fps=data.readFloat();
            data.enforceNoDataAvail();
            if(Float.isFinite(fps)&&fps>=0)latest=new Report(fps,SystemClock.elapsedRealtime());
            return true;
        }
    }

    private static final class Report {
        final float fps;
        final long receivedAt;
        Report(float fps,long receivedAt){this.fps=fps;this.receivedAt=receivedAt;}
    }
}
