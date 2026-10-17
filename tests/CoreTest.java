package cn.astriafr.acobattery.daemon;

public final class CoreTest {
    static void close(double actual,double expected){if(Math.abs(actual-expected)>1e-9)throw new AssertionError(actual+" != "+expected);}
    static void missing(double actual){if(Core.finite(actual))throw new AssertionError("Expected missing data: "+actual);}
    static Core.Reading reading(int cells,long time){
        Core.Reading r=new Core.Reading();r.cellCount=cells;r.elapsed=time;r.current=1000;r.vmin=r.vmax=4000;return r;
    }
    public static void main(String[] args){
        Core.Reading a=reading(1,0),b=reading(1,1000);
        Core.Integral one=Core.integrateFast(a,b,5000);
        close(one.mah,1000.0/3600);close(one.energyMwh,4000.0/3600);
        a.cellCount=b.cellCount=2;
        Core.Integral two=Core.integrateFast(a,b,5000);
        close(two.mah,2000.0/3600);close(two.energyMwh,8000.0/3600);
        // Invalid, quantized or correcting RM must not reject independent current/power.
        a.rm=Double.NaN;b.rm=5000;b.pct=Double.NaN;
        close(Core.integrateFast(a,b,5000).energyMwh,two.energyMwh);
        a.rm=5000;b.rm=5100;
        close(Core.integrateFast(a,b,5000).mah,two.mah);
        b.elapsed=1800;
        close(Core.integrateFast(a,b,5000).mah,1);close(Core.integrateFast(a,b,5000).energyMwh,4);
        b.vmin=b.vmax=Double.NaN;
        close(Core.integrateFast(a,b,5000).mah,1);missing(Core.integrateFast(a,b,5000).energyMwh);
        b.current=Double.NaN;missing(Core.integrateFast(a,b,5000).mah);
        b=reading(1,1000);missing(Core.integrateFast(a,b,5000).mah); // cell profile changed
        b=reading(2,6000);missing(Core.integrateFast(a,b,5000).energyMwh); // do not extrapolate a gap
        b=reading(2,1000);b.current=-100;missing(Core.integrateFast(a,b,5000).mah);
        Core.Sampling sampling=new Core.Sampling();sampling.expected=sampling.fastMs=1000;
        close(sampling.maxGap(),5000);sampling.fastMs=0;close(sampling.maxGap(),1500);
        // Different endpoint loads use trapezoids, not only the last reading.
        a=reading(1,0);b=reading(1,1000);b.current=2000;b.vmin=b.vmax=4200;
        Core.Integral varying=Core.integrateFast(a,b,5000);
        close(varying.mah,1500.0/3600);close(varying.energyMwh,6200.0/3600);
        System.out.println("Core integration tests passed");
    }
}
