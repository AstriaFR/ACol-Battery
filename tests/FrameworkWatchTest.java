package cn.astriafr.acobattery.daemon;

import android.os.IBinder;
import java.lang.reflect.Proxy;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public final class FrameworkWatchTest {
    static final class Service {
        boolean alive=true;
        boolean denyLink;
        final List<IBinder.DeathRecipient> callbacks=new ArrayList<>();
        final IBinder binder=(IBinder)Proxy.newProxyInstance(IBinder.class.getClassLoader(),new Class<?>[]{IBinder.class},(proxy,method,args)->{
            switch(method.getName()){
                case "isBinderAlive":return alive;
                case "linkToDeath":if(denyLink)throw new SecurityException("listener denied");callbacks.add((IBinder.DeathRecipient)args[0]);return null;
                case "unlinkToDeath":return callbacks.remove(args[0]);
                default:throw new UnsupportedOperationException(method.getName());
            }
        });
        void die(){alive=false;for(IBinder.DeathRecipient callback:new ArrayList<>(callbacks))callback.binderDied();}
    }
    static void check(boolean value){if(!value)throw new AssertionError();}
    public static void main(String[] args)throws Exception {
        AtomicInteger wakes=new AtomicInteger();List<Service> services=new ArrayList<>();
        FrameworkWatch watch=new FrameworkWatch(wakes::incrementAndGet);
        watch.start(name->{Service service=new Service();services.add(service);return service.binder;});
        check(watch.healthy());services.get(0).die();check(!watch.healthy());check(wakes.get()==1);
        services.get(1).die();check(wakes.get()==1);watch.close();
        for(Service service:services)check(service.callbacks.isEmpty());
        FrameworkWatch restarted=new FrameworkWatch(wakes::incrementAndGet);
        restarted.start(name->new Service().binder);check(restarted.healthy());restarted.close();
        FrameworkWatch unavailable=new FrameworkWatch(wakes::incrementAndGet);Service first=new Service();
        try{unavailable.start(name->name.equals("activity")?first.binder:null);throw new AssertionError();}
        catch(java.io.IOException expected){check(first.callbacks.isEmpty());}
        FrameworkWatch stale=new FrameworkWatch(wakes::incrementAndGet);Service staleService=new Service();
        stale.start(name->staleService.binder);staleService.alive=false;check(!stale.healthy());stale.close();
        RuntimeException cause=new RuntimeException("actual cause");
        check(FrameworkWatch.cause(new InvocationTargetException(cause))==cause);
        FrameworkWatch noDisplay=new FrameworkWatch(wakes::incrementAndGet);
        noDisplay.start(name->name.equals("display")||name.equals("power")?null:new Service().binder);
        check(noDisplay.healthy());noDisplay.close();
        FrameworkWatch deniedDisplay=new FrameworkWatch(wakes::incrementAndGet);
        deniedDisplay.start(name->{if(name.equals("display")||name.equals("power"))throw new SecurityException("optional service denied");return new Service().binder;});
        check(deniedDisplay.healthy());deniedDisplay.close();
        FrameworkWatch noUsage=new FrameworkWatch(wakes::incrementAndGet);Service required=new Service();
        noUsage.start(name->name.equals("usagestats")?null:required.binder);
        check(noUsage.healthy()&&!noUsage.usageError().isEmpty());
        required.die();check(!noUsage.healthy());noUsage.close();
        FrameworkWatch deniedUsage=new FrameworkWatch(wakes::incrementAndGet);
        deniedUsage.start(name->{if(name.equals("usagestats"))throw new SecurityException("lookup denied");return new Service().binder;});
        check(deniedUsage.healthy()&&deniedUsage.usageError().contains("lookup denied"));deniedUsage.close();
        FrameworkWatch unlinkedUsage=new FrameworkWatch(wakes::incrementAndGet);Service refused=new Service();refused.denyLink=true;
        unlinkedUsage.start(name->name.equals("usagestats")?refused.binder:new Service().binder);
        check(unlinkedUsage.healthy()&&unlinkedUsage.usageError().contains("listener denied"));unlinkedUsage.close();
        System.out.println("Framework death/recovery tests passed");
    }
}
