package cn.astriafr.acobattery.daemon;

/** Current display/accounting rules; minute RM accounting is covered separately. */
public final class CoreTest {
    static void close(double actual,double expected){if(!Core.finite(actual)||Math.abs(actual-expected)>1e-9)throw new AssertionError(actual+" != "+expected);}
    static void missing(double actual){if(Core.finite(actual))throw new AssertionError("Expected missing data: "+actual);}
    static Core.Reading reading(int cells){
        Core.Reading r=new Core.Reading();r.cellCount=cells;r.current=1000;r.vmin=r.vmax=4000;return r;
    }
    public static void main(String[] args){
        close(Core.remainingMah(5040123),5040.123);close(Core.remainingMah(0),0);
        for(long value:new long[]{Long.MIN_VALUE,Integer.MIN_VALUE,-1,Long.MAX_VALUE})missing(Core.remainingMah(value));
        Core.Reading one=reading(1),two=reading(2);
        close(one.compensatedCurrent(),-1000);close(one.power(),-4);
        close(two.compensatedCurrent(),-2000);close(two.power(),-8);
        two.rm=Double.NaN;two.pct=Double.NaN;close(two.power(),-8);
        two.current=-1000;close(two.power(),8);
        two.vmin=Double.NaN;missing(two.power());
        one.current=Double.NaN;missing(one.power());missing(reading(0).power());
        close(Core.dischargeEnergyMwh(3900,4100,100),400);
        close(Core.averagePower(400,3600000),.4);close(Core.averagePower(400,60000),24);
        close(Core.dischargeEnergyMwh(3900,4100,0),0);
        missing(Core.dischargeEnergyMwh(Double.NaN,4100,100));
        missing(Core.dischargeEnergyMwh(3900,4100,-1));missing(Core.averagePower(400,0));
        Core.Sampling sampling=new Core.Sampling();sampling.expected=sampling.fastMs=1000;
        close(sampling.maxGap(),5000);sampling.fastMs=0;close(sampling.maxGap(),1500);
        System.out.println("Core: remaining capacity, cell compensation, instantaneous power and RM energy units passed");
    }
}
