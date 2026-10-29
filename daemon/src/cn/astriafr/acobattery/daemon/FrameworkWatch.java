package cn.astriafr.acobattery.daemon;

import android.os.IBinder;
import android.os.DeadObjectException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** A surviving root process must not reuse framework proxies after a soft reboot. */
final class FrameworkWatch implements AutoCloseable {
    interface Lookup { IBinder get(String name)throws Exception; }
    private final List<IBinder> services=new ArrayList<>();
    private final Runnable wake;
    private final IBinder.DeathRecipient death=this::lost;
    private volatile boolean failed,closed;

    FrameworkWatch(Runnable wake){this.wake=wake;}
    void start()throws Exception {
        start(name->(IBinder)Class.forName("android.os.ServiceManager")
            .getMethod("checkService",String.class).invoke(null,name));
    }
    void start(Lookup lookup)throws Exception {
        try {
            for(String name:new String[]{"activity","usagestats","display","power","package"}) {
                boolean optional=name.equals("display")||name.equals("power");
                IBinder binder;
                try{binder=lookup.get(name);}catch(Exception unavailable){if(optional)continue;throw unavailable;}
                if(binder==null||!binder.isBinderAlive()){if(optional)continue;throw new IOException("系统服务尚未就绪："+name);}
                try{binder.linkToDeath(death,0);services.add(binder);}catch(Exception unavailable){if(!optional)throw unavailable;}
            }
            if(!healthy())throw new IOException("系统服务正在重启，稍后自动重试。");
        }catch(Exception e){close();throw e;}
    }
    synchronized void lost(){
        if(!closed&&!failed){failed=true;wake.run();}
    }
    synchronized boolean healthy(){
        if(failed||closed)return false;
        for(IBinder binder:services)if(!binder.isBinderAlive()){lost();return false;}
        return !services.isEmpty();
    }
    static Throwable cause(Throwable error){
        for(int i=0;i<16&&error.getCause()!=null&&error.getCause()!=error;i++)error=error.getCause();
        return error;
    }
    void observe(Throwable error){
        for(int i=0;i<16&&error!=null;i++,error=error.getCause())
            if(error instanceof DeadObjectException){lost();return;}
    }
    @Override public synchronized void close(){
        closed=true;
        for(IBinder binder:services)try{binder.unlinkToDeath(death,0);}catch(RuntimeException ignored){}
        services.clear();
    }
}
