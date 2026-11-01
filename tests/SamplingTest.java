package cn.astriafr.acobattery.daemon;

import java.util.concurrent.atomic.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;

public final class SamplingTest {
    static void check(boolean value){if(!value)throw new AssertionError();}
    static Core.Reading tail(long elapsed){
        Core.Reading r=new Core.Reading();r.elapsed=elapsed;r.rm=5000;r.pct=100;
        r.online=true;Core.applyTail(r,4450,1,1);r.current=-20;r.vmin=r.vmax=4400;r.temp=30;return r;
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
        for(double[] missing:new double[][]{{Double.NaN,1,1},{4450,Double.NaN,1},{4450,1,Double.NaN},{4450,2,1},{4450,1,-1}}){
            Core.FullGate limited=new Core.FullGate();Core.Reading r=tail(0);
            Core.applyTail(r,missing[0],missing[1],missing[2]);check(r.valid());
            for(long t=0;t<=600000;t+=10000){r.elapsed=t;check(!limited.update(r,10000));}
            // Valid live readings recover detection without inventing a completed tail.
            for(long t=610000;t<=910000;t+=10000){r=tail(t);check(limited.update(r,10000)==(t==910000));}
        }
        Core.Reading notFull=tail(0);Core.applyTail(notFull,4450,0,0);
        check(!new Core.FullGate().update(notFull));
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
    static Core.Reading electrical(long elapsed){
        Core.Reading r=new Core.Reading();r.elapsed=elapsed;r.online=true;
        r.cellCount=2;r.vmin=4390;r.vmax=4430;r.current=-24;return r;
    }
    static void electricalTail(){
        for(int seconds=1;seconds<=10;seconds++){
            Core.FullGate gate=new Core.FullGate();long step=seconds*1000L,finish=(300000+step-1)/step*step;
            for(long t=0;t<=finish;t+=step)check(gate.update(electrical(t),step)==(t>=300000));
        }
        Core.Reading r=electrical(0);check(!r.valid()&&Core.FullGate.electricalCandidate(r));
        r.vmax=4410;check(!Core.FullGate.electricalCandidate(r)); // Mean exactly 4.4 V is excluded.
        r.vmax=4430;
        for(double current:new double[]{-25,25,Double.NaN,Double.POSITIVE_INFINITY}){r.current=current;check(!Core.FullGate.electricalCandidate(r));}
        for(double current:new double[]{-24.999,24.999,0}){r.current=current;check(Core.FullGate.electricalCandidate(r));}
        r.cellCount=0;check(!Core.FullGate.electricalCandidate(r));r.cellCount=1;r.vmin=4401;check(Core.FullGate.electricalCandidate(r));
        r.current=50;check(!Core.FullGate.electricalCandidate(r));r.current=-50;check(!Core.FullGate.electricalCandidate(r));
        r.current=49.999;check(Core.FullGate.electricalCandidate(r));r.current=-49.999;check(Core.FullGate.electricalCandidate(r));
        r.online=false;check(!Core.FullGate.electricalCandidate(r));r.online=true;r.onlineKnown=false;check(!Core.FullGate.electricalCandidate(r));
        Core.FullGate gate=new Core.FullGate();
        for(long t=0;t<=290000;t+=10000)check(!gate.update(electrical(t),10000));
        check(!gate.update(electrical(310000),10000)&&gate.progress(310000)==0); // A missing sample restarts the five minutes.
        for(long t=320000;t<=610000;t+=10000)check(gate.update(electrical(t),10000)==(t==610000));
        r=electrical(620000);r.online=false;check(!gate.update(r,10000)&&gate.progress(620000)==0);
        gate=new Core.FullGate();
        for(long t=0;t<=150000;t+=10000)check(!gate.update(tail(t),10000));
        for(long t=160000;t<=760000;t+=10000)check(gate.update(electrical(t),10000)==(t==760000)); // Vendor log seen: the independent path now needs ten minutes.
        gate=new Core.FullGate();
        for(long t=0;t<=300000;t+=10000){r=electrical(t);Core.applyTail(r,4450,1,1);r.rm=5000+t/1000;r.pct=Double.NaN;r.temp=Double.NaN;check(gate.update(r,10000)==(t==300000));}
        gate.reset();check(gate.progress(300000)==0&&!gate.update(electrical(300000),10000));
        check(!gate.update(electrical(290000),10000)&&gate.progress(290000)==0); // Monotonic clock rewind also restarts.
        for(int seconds=1;seconds<=10;seconds++){
            gate=new Core.FullGate();long step=seconds*1000L,finish=(600000+step-1)/step*step;
            for(long t=0;t<=finish;t+=step){
                r=electrical(t);if(t==0)Core.applyTail(r,0,0,1); // A valid idle value still proves the vendor log was read.
                check(gate.update(r,step)==(t>=600000));
            }
            check(gate.vendorLogSeen&&gate.electricalDuration()==600000);
            gate.interrupt();check(gate.vendorLogSeen&&gate.electricalDuration()==600000&&gate.progress(finish)==0);
            gate.reset();check(!gate.vendorLogSeen&&gate.electricalDuration()==300000);
        }
        gate=new Core.FullGate();
        for(long t=0;t<=600000;t+=10000){
            r=electrical(t);if(t==240000)Core.applyTail(r,4490,0,1);
            check(gate.update(r,10000)==(t==600000));
            if(t==300000)check(gate.progress(t)==.5); // A successful late read extends the same continuous window.
        }
        for(int cells:new int[]{1,2}){
            gate=new Core.FullGate();r=tail(0);r.cellCount=cells;r.current=cells==2?25:50;
            for(long t=0;t<=600000;t+=10000){r.elapsed=t;check(!gate.update(r,10000));} // Both rules exclude exactly 50 mA after compensation.
        }
    }
    static void optionalChargingLog(){
        Core.Reading r=new Core.Reading();r.online=false;
        check(ChargingLog.apply(r,null,"/vendor/",path->{throw new AssertionError("Read while unplugged");})==null);
        r.online=true;r.onlineKnown=false;
        check(ChargingLog.apply(r,null,"/vendor/",path->{throw new AssertionError("Read while power is unknown");})==null);
        r.onlineKnown=true;AtomicInteger headerReads=new AtomicInteger();
        ChargingLog.Reader reader=path->{
            if(path.endsWith("battery_log_head")){headerReads.incrementAndGet();return ",fv_mv,batt_full,mmi_chg";}
            if(path.endsWith("battery_log_content"))return ",4490,1,1";
            throw new AssertionError("Unexpected tail interface: "+path);
        };
        String[] header=ChargingLog.apply(r,null,"/vendor/",reader);
        check(r.full&&r.enabled&&r.vendorLogRead&&r.fv==4490&&headerReads.get()==1);
        ChargingLog.apply(r,header,"/vendor/",reader);check(headerReads.get()==1);
        ChargingLog.apply(r,new String[]{"stale"},"/vendor/",reader);check(r.full&&headerReads.get()==2);
        r=electrical(0);ChargingLog.apply(r,null,"/vendor/",path->{throw new java.io.IOException("missing vendor interface");});
        check(!r.full&&!r.enabled&&!r.vendorLogRead&&!Core.finite(r.fv)&&r.error.isEmpty());
        Core.FullGate gate=new Core.FullGate();for(long t=0;t<=300000;t+=10000){r.elapsed=t;check(gate.update(r,10000)==(t==300000));}
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
            check(!inputs.online(()->null).known);
            Files.write(node,"2".getBytes(StandardCharsets.UTF_8));normal=inputs.online(()->{throw new AssertionError("PPS incorrectly fell back");});check(normal.known&&normal.online&&!normal.systemFallback);
            Files.write(node,"3".getBytes(StandardCharsets.UTF_8));check(!inputs.online(()->null).known);
            PowerInputs.Result unknown=inputs.resolve(()->{throw new SecurityException("denied");});check(!unknown.known&&unknown.diagnostics.contains("denied")&&unknown.diagnostics.contains("online = 3"));
            Path wireless=Files.createDirectory(root.resolve("wireless"));Files.write(wireless.resolve("scope"),"Device".getBytes(StandardCharsets.UTF_8));Files.write(wireless.resolve("online"),"1".getBytes(StandardCharsets.UTF_8));inputs.invalidate();
            Files.write(node,"0".getBytes(StandardCharsets.UTF_8));check(!inputs.online(()->true).online);
            Files.delete(node);fallback=inputs.online(()->true);check(fallback.online&&fallback.systemFallback);
            Files.write(node,"0".getBytes(StandardCharsets.UTF_8));normal=inputs.online(()->true);check(!normal.online&&!normal.systemFallback);
        }finally{try(java.util.stream.Stream<Path> files=Files.walk(root)){for(Path file:(Iterable<Path>)files.sorted(java.util.Comparator.reverseOrder())::iterator)Files.delete(file);}}
    }
    static void systemPowerFallback()throws Exception {
        String off="AC powered: false\nUSB powered: false\nWireless powered: false\n";
        check(Boolean.FALSE.equals(SystemPower.dump(off)));
        check(Boolean.TRUE.equals(SystemPower.dump(off+"Dock powered: true\n")));
        check(Boolean.TRUE.equals(SystemPower.dump(off.replace("USB powered: false","USB powered: true"))));
        for(String bad:new String[]{"", "USB powered: false",off+"USB powered: true",off+"Dock powered: unknown",off+"DUMP TIMEOUT"})check(SystemPower.dump(bad)==null);
        for(String raw:new String[]{"0","1","2","4","8","15"})check(SystemPower.plugged(raw)!=null);
        for(String raw:new String[]{"-1","16","999", "bad"})check(SystemPower.plugged(raw)==null);
        SystemPower reader=new SystemPower();AtomicInteger dumps=new AtomicInteger();
        PowerInputs.Result value=reader.read(0,()->null,()->{dumps.incrementAndGet();return off;});check(value.known&&!value.online&&value.source.equals("battery_service")&&value.diagnostics.contains("plugged"));
        reader.read(500,()->{throw new AssertionError("Cache missed");},()->null);check(dumps.get()==1);
        value=reader.read(1000,()->{throw new SecurityException("no app");},()->"partial");check(!value.known&&value.diagnostics.contains("no app")&&value.diagnostics.contains("partial"));
        reader.invalidate();value=reader.read(1001,()->"2",()->{throw new AssertionError("Broadcast was valid");});check(value.known&&value.online&&value.source.equals("android"));
        reader.invalidate();value=reader.read(1002,()->null,()->{throw new java.io.IOException("timeout");});check(!value.known&&value.diagnostics.contains("timeout"));
    }
    public static void main(String[] args)throws Exception{
        chargeTail();electricalTail();optionalChargingLog();screenFallback();powerInputFallback();systemPowerFallback();
        System.out.println("Sampling: independent tail rules, exact boundaries, optional charging-only logs, screen and power input fallback passed");
    }
}
