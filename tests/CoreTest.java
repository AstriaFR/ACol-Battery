package cn.astriafr.acobattery.daemon;

/** Current display/accounting rules; minute RM accounting is covered separately. */
public final class CoreTest {
    static void close(double actual,double expected){if(!Core.finite(actual)||Math.abs(actual-expected)>1e-9)throw new AssertionError(actual+" != "+expected);}
    static void missing(double actual){if(Core.finite(actual))throw new AssertionError("Expected missing data: "+actual);}
    static Core.Reading reading(int cells){
        Core.Reading r=new Core.Reading();r.cellCount=cells;r.current=1000;r.vmin=r.vmax=4000;return r;
    }
    static void dischargeStart(){
        Core.DischargeStart start=new Core.DischargeStart();
        Core.Depletion depletion=new Core.Depletion();
        double[] rm={5000,5000,5080,5090,5080,5085,5075};
        double base=rm[0],loss=0,firstDrop=Double.NaN;
        for(int i=1;i<rm.length;i++){
            if(start.advance(rm[i-1],rm[i],120000,180000))base=rm[i];
            else{
                double used=depletion.consume(rm[i-1],rm[i],120000,180000);
                if(i==4)firstDrop=used;
                loss+=used;
            }
        }
        close(base,5090);close(firstDrop,10);close(loss,base-rm[rm.length-1]);
        start=new Core.DischargeStart();
        if(start.advance(5000,5090,240000,180000)||start.advance(5090,5100,120000,180000))
            throw new AssertionError("The startup baseline crossed a sampling gap");
    }
    static void chargingIntegral(){
        Core.ChargingIntegral one=new Core.ChargingIntegral(1),two=new Core.ChargingIntegral(2);
        for(Core.ChargingIntegral integral:new Core.ChargingIntegral[]{one,two}){
            integral.sample(0,3600,0);
            integral.sample(1000,1800,0);
            integral.sample(2000,Double.NaN,0);
            integral.sample(3000,1800,0);
            integral.sample(300000,1800,Core.REBOOT_GAP);
            integral.sample(301000,-900,0);
            integral.sample(302000,900,Core.UNCLASSIFIED_CELLS);
            integral.sample(303000,1800,0);
        }
        close(one.value(),.875);close(two.value(),1.75);
        Core.ChargingIntegral interrupted=new Core.ChargingIntegral(1);
        interrupted.sample(0,1800,0);interrupted.sample(50000,1800,1);missing(interrupted.value());
    }
    public static void main(String[] args){
        dischargeStart();chargingIntegral();
        close(Core.remainingMah(5040123),5040.123);close(Core.remainingMah(0),0);
        for(long value:new long[]{Long.MIN_VALUE,Integer.MIN_VALUE,-1,Long.MAX_VALUE})missing(Core.remainingMah(value));
        close(Core.levelPercentage(42,50),84);close(Core.levelPercentage(0,100),0);
        missing(Core.levelPercentage(101,100));missing(Core.levelPercentage(-1,100));missing(Core.levelPercentage(0,0));
        Core.Reading unknown=new Core.Reading();unknown.cellCount=0;
        Core.applySystem(unknown,8200,1000000,30);
        close(unknown.displayVoltage(),8200);close(unknown.chargingCurrent(),1000);
        missing(unknown.voltage());missing(unknown.power());missing(unknown.cell1);missing(unknown.cell2);
        Core.Reading known=new Core.Reading();known.cellCount=2;
        Core.applySystem(known,8200,1000000,30);
        close(known.displayVoltage(),4100);close(known.power(),8.2);
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
        Core.Cadence cadence=new Core.Cadence(2000);cadence.configure(500,0);close(cadence.next,0);
        cadence.sampled(0);close(cadence.next,500);close(cadence.due(499)?1:0,0);close(cadence.due(500)?1:0,1);
        cadence.sampled(500);cadence.configure(1100,600);close(cadence.next,1600);close(cadence.interval,1100);
        System.out.println("Core: remaining capacity, cell compensation, instantaneous power and RM energy units passed");
    }
}
