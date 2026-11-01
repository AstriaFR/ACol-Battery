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
            Core.applyTail(r,missing[0],missing[1],missing[2]);check(!r.tailAvailable&&r.valid());
            for(long t=0;t<=600000;t+=10000){r.elapsed=t;check(!limited.update(r,10000));}
            // Valid live readings recover detection without inventing a completed tail.
            for(long t=610000;t<=910000;t+=10000){r=tail(t);check(limited.update(r,10000)==(t==910000));}
        }
        Core.Reading notFull=tail(0);Core.applyTail(notFull,4450,0,0);
        check(notFull.tailAvailable&&!new Core.FullGate().update(notFull));
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
    static void tailFallback(){
        AtomicInteger androidReads=new AtomicInteger();
        TailInputs.Result status=TailInputs.resolve("/power/",path->"Full",()->{androidReads.incrementAndGet();return 2;});
        check(status.known()&&status.status==5&&status.source.equals("sysfs_status")&&androidReads.get()==0);
        status=TailInputs.resolve("/power/",path->"Charging",()->{throw new AssertionError("A valid non-Full root state must stop fallback");});
        check(status.status==2);
        status=TailInputs.resolve("/power/",path->path.contains("battery/")?"Unknown":"Full",()->{throw new AssertionError("BMS state was available");});
        check(status.status==5&&status.source.equals("sysfs_status"));
        status=TailInputs.resolve("/power/",path->{throw new java.io.IOException("unreadable");},()->{androidReads.incrementAndGet();return 5;});
        check(status.status==5&&status.source.equals("android_status")&&androidReads.get()==1&&status.diagnostics.contains("unreadable"));
        check(!TailInputs.resolve("/power/",path->"Unknown",()->1).known());
        check(!TailInputs.resolve("/power/",path->"bad",()->{throw new SecurityException("denied");}).known());
        for(String value:new String[]{"Full","Charging","Discharging","Not charging","Not_charging"})check(TailInputs.validStatus(TailInputs.parseStatus(value)));
        for(String value:new String[]{"Unknown","100","Trickle","",null})check(!TailInputs.validStatus(TailInputs.parseStatus(value)));
        check(TailInputs.targetVoltage("/power/",path->"4450000",1,new StringBuilder())==4450);
        check(TailInputs.targetVoltage("/power/",path->"8900000",2,new StringBuilder())==4450);
        check(!Core.finite(TailInputs.targetVoltage("/power/",path->"8900000",1,new StringBuilder())));
        check(!Core.finite(TailInputs.targetVoltage("/power/",path->"4450000",0,new StringBuilder())));
        for(String source:new String[]{"sysfs_status","android_status"}){
            Core.FullGate gate=new Core.FullGate();
            for(long t=0;t<=300000;t+=10000){
                Core.Reading r=tail(t);r.pct=99;Core.applyTail(r,4450,Double.NaN,Double.NaN);
                Core.applyTailFallback(r,4450,5,source);
                check(r.tailAvailable&&!r.chargeEnabledKnown&&gate.update(r,10000)==(t==300000));
            }
            Core.Reading unavailable=tail(310000);Core.applyTail(unavailable,Double.NaN,Double.NaN,Double.NaN);
            Core.applyTailFallback(unavailable,Double.NaN,5,source);check(!unavailable.tailAvailable&&!gate.update(unavailable,10000));
            check(gate.progress(310000)==0);
            Core.Reading paused=tail(0);Core.applyTail(paused,4450,Double.NaN,0);Core.applyTailFallback(paused,4450,5,source);
            check(paused.chargeEnabledKnown&&!new Core.FullGate().update(paused));
            Core.Reading charging=tail(0);Core.applyTailFallback(charging,4450,2,source);check(!new Core.FullGate().update(charging));
            Core.Reading tooMuchCurrent=tail(0);tooMuchCurrent.current=-1000;Core.applyTailFallback(tooMuchCurrent,4450,5,source);
            check(!new Core.FullGate().update(tooMuchCurrent));
        }
        Core.Reading original=tail(0);check(original.tailAvailable&&original.tailSource.equals("oplus_log"));
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
        chargeTail();tailFallback();screenFallback();powerInputFallback();systemPowerFallback();
        System.out.println("Sampling: charge intervals, ordered tail fallbacks, sustained confirmation, screen and power input fallback passed");
    }
}
