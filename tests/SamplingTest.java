package cn.astriafr.acobattery.daemon;

import java.util.concurrent.atomic.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;

public final class SamplingTest {
    static void check(boolean value){if(!value)throw new AssertionError();}
    static Core.Reading tail(long elapsed){
        Core.Reading r=new Core.Reading();r.elapsed=elapsed;r.rm=5000;r.pct=100;
        r.online=r.full=r.enabled=true;r.current=-20;r.fv=4450;r.vmin=r.vmax=4400;r.temp=30;return r;
    }
    static void chargeTail(){
        Core.Sampling sampling=new Core.Sampling();
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
    }
    static void screenFallback(){
        Core.Sampling sampling=new Core.Sampling();sampling.screenOnMs=180000;sampling.screenOffMs=900000;
        AtomicReference<Boolean> power=new AtomicReference<>(false),display=new AtomicReference<>(true);
        AtomicBoolean denied=new AtomicBoolean();AtomicInteger displayReads=new AtomicInteger(),failures=new AtomicInteger();
        ScreenState state=new ScreenState(sampling,()->{if(denied.get())throw new SecurityException();return power.get();},()->{displayReads.incrementAndGet();return display.get();},error->failures.incrementAndGet());
        check(!state.refresh(100,true));check(state.known&&!sampling.interactive);check(displayReads.get()==0);check(!sampling.takeScreenSample());
        check(sampling.interval(0)==900000);check(!state.refresh(5100,false));check(state.changedAt==100);
        denied.set(true);check(state.refresh(10100,false));check(sampling.interactive&&sampling.takeScreenSample());
        check(sampling.next==10100&&sampling.interval(0)==180000&&state.source.equals("display"));check(failures.get()==1);
        check(state.accept(false,15100,false,"broadcast"));check(!sampling.interactive);sampling.takeScreenSample();
        long offAt=state.changedAt;
        check(!state.accept(false,20100,false,"broadcast"));check(state.changedAt==offAt);
        display.set(null);check(!state.refresh(25100,false));check(!state.available&&state.known&&!sampling.interactive);
        check(state.changedAt==offAt&&!sampling.takeScreenSample());
        denied.set(false);power.set(false);check(!state.refresh(30100,false));check(state.available&&state.changedAt==offAt);
        // Frequent checks do not restart the game mode's continuous screen-off period.
        sampling.fastMs=10000;
        for(long time=35100;time<offAt+600000;time+=5000){check(!state.refresh(time,false));check(state.changedAt==offAt);check(sampling.interval(0)==10000);}
        check(state.accept(true,offAt+600000,false,"broadcast"));check(sampling.interactive&&sampling.next==offAt+600000);
        long charge=sampling.chargeMs;check(sampling.interval(1)==charge);
        Core.Sampling unknownSampling=new Core.Sampling();ScreenState unknown=new ScreenState(unknownSampling,()->null,()->null,error->{});
        check(!unknown.refresh(0,true)&&!unknown.known);check(unknown.accept(true,5000,false,"broadcast"));check(unknownSampling.takeScreenSample()&&unknownSampling.next==5000);
        check(ScreenState.POLL_MS==5000);
    }
    static void powerInputFallback()throws Exception{
        Path root=Files.createTempDirectory("power-input-test-");
        try{
            Path usb=Files.createDirectory(root.resolve("usb")),node=usb.resolve("online");
            Files.write(node,"0".getBytes(StandardCharsets.UTF_8));
            PowerInputs inputs=new PowerInputs(root.toFile(),path->{try{return new String(Files.readAllBytes(Paths.get(path)),StandardCharsets.UTF_8).trim();}catch(Exception ignored){return "";}});
            AtomicInteger calls=new AtomicInteger();
            PowerInputs.Result normal=inputs.online(()->{calls.incrementAndGet();return true;});
            check(!normal.online&&!normal.systemFallback&&calls.get()==0);
            Files.write(node,"1".getBytes(StandardCharsets.UTF_8));normal=inputs.online(()->{calls.incrementAndGet();return false;});
            check(normal.online&&!normal.systemFallback&&calls.get()==0);
            Files.write(node,"unknown".getBytes(StandardCharsets.UTF_8));
            PowerInputs.Result fallback=inputs.online(()->{calls.incrementAndGet();return true;});check(fallback.online&&fallback.systemFallback);
            fallback=inputs.online(()->false);check(!fallback.online&&fallback.systemFallback);
            try{inputs.online(()->null);throw new AssertionError("Unknown connection accepted");}catch(IllegalStateException expected){}
            Files.delete(node);fallback=inputs.online(()->true);check(fallback.online&&fallback.systemFallback);
            Files.write(node,"0".getBytes(StandardCharsets.UTF_8));normal=inputs.online(()->true);check(!normal.online&&!normal.systemFallback);
        }finally{try(java.util.stream.Stream<Path> files=Files.walk(root)){for(Path file:(Iterable<Path>)files.sorted(java.util.Comparator.reverseOrder())::iterator)Files.delete(file);}}
    }
    public static void main(String[] args)throws Exception{
        chargeTail();screenFallback();powerInputFallback();
        System.out.println("Sampling: charge intervals, tail confirmation, screen and power input fallback passed");
    }
}
