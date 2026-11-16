package cn.astriafr.acobattery.daemon;

import android.content.AttributionSource;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.SystemClock;
import cn.astriafr.acobattery.common.DischargeNotice;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Uses the same external-provider route as the content command from this root process. */
final class NotificationProviderClient implements AutoCloseable {
    private static final long RETRY_MS=60000;
    private final ExecutorService worker=Executors.newSingleThreadExecutor(task->{
        Thread thread=new Thread(task,"aco-notice-binder");thread.setDaemon(true);return thread;
    });
    private Api api;
    private Future<Boolean> pending;
    private long retryAt;

    // A null result permits command fallback. Keep only one in-process call in
    // flight; the receiver orders late results and fallback delivery by sequence.
    Boolean deliver(int user,Bundle extras)throws InterruptedException{
        if(pending!=null){
            if(!pending.isDone())return null;
            try{pending.get();}catch(ExecutionException failure){delayRetry();}
            pending=null;
        }
        if(SystemClock.elapsedRealtime()<retryAt)return null;
        pending=worker.submit(()->{
            if(api==null)api=new Api();
            return api.deliver(user,extras);
        });
        try{return pending.get(1500,TimeUnit.MILLISECONDS);}
        catch(TimeoutException slow){delayRetry();return null;}
        catch(ExecutionException unavailable){delayRetry();return null;}
        finally{if(pending.isDone())pending=null;}
    }

    private void delayRetry(){retryAt=SystemClock.elapsedRealtime()+RETRY_MS;}
    @Override public void close(){worker.shutdownNow();}

    private static final class Api {
        final Method manager,acquire,release,call;
        final Field provider;
        final AttributionSource caller=new AttributionSource.Builder(android.os.Process.myUid()).setPackageName("root").build();

        Api()throws ReflectiveOperationException{
            manager=Class.forName("android.app.ActivityManager").getMethod("getService");
            Class<?> service=Class.forName("android.app.IActivityManager");
            acquire=service.getMethod("getContentProviderExternal",String.class,int.class,IBinder.class,String.class);
            release=service.getMethod("removeContentProviderExternalAsUser",String.class,IBinder.class,int.class);
            provider=acquire.getReturnType().getField("provider");
            call=provider.getType().getMethod("call",AttributionSource.class,String.class,String.class,String.class,Bundle.class);
        }

        boolean deliver(int user,Bundle extras)throws Exception{
            Object service=manager.invoke(null);
            IBinder token=new Binder();
            String authority=DischargeNotice.AUTHORITY;
            Object holder=acquire.invoke(service,authority,user,token,"aco-record-notice");
            if(holder==null)throw new IOException("Notification provider unavailable");
            try{
                Object target=provider.get(holder);
                if(target==null)throw new IOException("Notification provider unavailable");
                Bundle result=(Bundle)call.invoke(target,caller,authority,DischargeNotice.ACTION,null,extras);
                if(result==null||!result.containsKey("posted"))throw new IOException("Invalid notification provider response");
                return result.getBoolean("posted");
            }finally{
                // An external reference would otherwise keep the app process alive.
                try{release.invoke(service,authority,token,user);}catch(ReflectiveOperationException|RuntimeException ignored){}
            }
        }
    }
}
