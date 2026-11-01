package cn.astriafr.acobattery.daemon;

/** Recovery decisions must not merge separate boots, power states or modes. */
public final class RecoveryTest {
    static void check(boolean value){if(!value)throw new AssertionError();}
    static boolean resume(String saved,String boot,String kind,boolean wasFast,boolean online,boolean fast,long end,long now){
        return Core.resumeSession(saved,boot,kind,wasFast,online,fast,1000,end,now);
    }
    static void close(double value,double expected){check(Math.abs(value-expected)<1e-9);}
    public static void main(String[] args){
        Core.Sampling longSleep=new Core.Sampling();longSleep.restoreExpected(120*60000L);
        check(longSleep.expected==120*60000L&&longSleep.maxGap()==180*60000L);
        close(new Core.Depletion().consume(5000,4900,100*60000L,longSleep.maxGap()),100);
        check(!Core.finite(new Core.Depletion().consume(5000,4900,181*60000L,longSleep.maxGap())));
        longSleep.restoreExpected(Long.MAX_VALUE);check(longSleep.expected==120*60000L);
        longSleep.restoreExpected(-1);check(longSleep.expected==1000);
        // APK updater and system_server recovery preserve kernel boot and monotonic clock.
        check(resume("boot-a","boot-a","discharge",false,false,false,2000,2300));
        check(resume("boot-a","boot-a","discharge",false,false,false,2000,3600000));
        check(resume("boot-a","boot-a","charge",false,true,false,2000,2300));
        check(resume("boot-a","boot-a","discharge",true,false,true,2000,2300));
        check(resume("boot-a","boot-a","discharge",true,true,true,2000,2300));
        check(!resume("boot-a","boot-b","discharge",false,false,false,2000,2300));
        check(!resume("boot-a","boot-a","discharge",false,true,false,2000,2300));
        check(!resume("boot-a","boot-a","charge",false,false,false,2000,2300));
        check(!resume("boot-a","boot-a","discharge",true,false,false,2000,2300));
        check(!resume("boot-a","boot-a","discharge",false,false,true,2000,2300));
        check(!resume("boot-a","boot-a","discharge",false,false,false,2000,1999));
        check(!resume("boot-a","boot-a","discharge",false,false,false,999,2300));
        check(!resume("","","discharge",false,false,false,2000,2300));
        check(!resume(null,null,"discharge",false,false,false,2000,2300));
        check(resume("boot-a","boot-a","discharge",false,false,false,2000,2000));
        check(Core.resumableStop("service_updated"));check(Core.resumableStop("framework_restarted"));
        for(String reason:new String[]{"recording_stopped","app_uninstalled","plugged","unplugged","full_tail_confirmed","fast_stopped"})
            check(!Core.resumableStop(reason));
        // Keep RM rebound credit so a restart cannot count the same decline twice.
        Core.Depletion before=new Core.Depletion();close(before.consume(5000,5010,1000),0);
        Core.Depletion after=new Core.Depletion();after.restore(before.checkpoint());
        close(after.consume(5010,5005,1000),0);close(after.consume(5005,4998,1000),2);
        after.restore(Double.NaN);close(after.consume(5000,4999,1000),1);
        after.restore(20);close(after.consume(5000,4999,1000),1);
        System.out.println("Session recovery, split boundaries and RM credit tests passed");
    }
}
