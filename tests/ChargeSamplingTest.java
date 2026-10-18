package cn.astriafr.acobattery.daemon;

public final class ChargeSamplingTest {
    static void check(boolean value){if(!value)throw new AssertionError();}
    static Core.Reading tail(long elapsed){
        Core.Reading r=new Core.Reading();r.elapsed=elapsed;r.rm=5000;r.pct=100;
        r.online=r.full=r.enabled=true;r.current=-20;r.fv=4450;r.vmin=r.vmax=4400;r.temp=30;return r;
    }
    public static void main(String[] args){
        Core.Sampling sampling=new Core.Sampling();check(sampling.interval(1)==2000);
        check(sampling.interval(0)==120000);sampling.interactive=false;check(sampling.interval(0)==900000);
        for(int seconds=1;seconds<=10;seconds++){
            sampling.chargeMs=seconds*1000L;check(sampling.interval(1)==seconds*1000L);
            sampling.sampled(1,500);check(sampling.next==500+seconds*1000L);
            Core.FullGate gate=new Core.FullGate();boolean confirmed=false;
            long confirmedAt=(300000+seconds*1000L-1)/(seconds*1000L)*(seconds*1000L);
            for(long elapsed=0;elapsed<=confirmedAt;elapsed+=seconds*1000L){
                boolean full=gate.update(tail(elapsed),seconds*1000L);
                if(elapsed<300000)check(!full);else{check(full);confirmed=true;}
            }
            check(confirmed);
        }
        Core.FullGate gate=new Core.FullGate();
        for(long t=0;t<=290000;t+=10000)check(!gate.update(tail(t),10000));
        check(!gate.update(tail(310000),10000)); // A real missing sample resets confirmation.
        Core.FullGate changing=new Core.FullGate();
        for(long t=0;t<=100000;t+=2000)check(!changing.update(tail(t),2000));
        for(long t=110000;t<300000;t+=10000)check(!changing.update(tail(t),10000));
        check(changing.update(tail(300000),10000)); // Editing the interval need not reset the tail.
        System.out.println("Charging intervals 1-10 s, defaults, tail confirmation and cadence edits passed");
    }
}
