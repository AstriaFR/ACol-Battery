package cn.astriafr.acobattery.common;

public final class PowerDisplayStateTest {
    static void check(boolean value){if(!value)throw new AssertionError();}
    public static void main(String[] args){
        PowerDisplayState display=new PowerDisplayState(false);
        check(!display.performanceDue(0)&&!display.batteryDue(0));
        check(display.deadline(true)==Long.MAX_VALUE);
        display.enabled=true;
        check(display.performanceDue(1000)&&display.batteryDue(1000));
        display.performanceSampled(1000,700);
        display.batterySampled(1000,10000);
        check(display.deadline(true)==1700);
        check(!display.performanceDue(1699)&&display.performanceDue(1700));
        check(!display.batteryDue(10999)&&display.batteryDue(11000));
        check(display.deadline(false)==Long.MAX_VALUE);
        display.refresh();
        check(display.performanceDue(12000)&&display.batteryDue(12000));
        check(PowerDisplayState.fps(0).equals("0"));
        check(PowerDisplayState.fps(239.7).equals("240"));
        check(PowerDisplayState.fps(999.5).equals("—"));
        check(PowerDisplayState.fps(Double.NaN).equals("—"));
        check(PowerDisplayState.frequency(2400.2).equals("2400"));
        check(PowerDisplayState.frequency(10000).equals("—"));
        check(PowerDisplayState.power(1.42).equals("1.42"));
        check(PowerDisplayState.power(-12.345).equals("12.3"));
        check(PowerDisplayState.power(9.999).equals("10.0"));
        check(PowerDisplayState.power(100.23).equals("100"));
        check(PowerDisplayState.power(-0.0).equals("0.00"));
        check(PowerDisplayState.power(Double.NaN).equals("—"));
        check(PowerDisplayState.temperature(-6.6).equals("6.6"));
        check(PowerDisplayState.temperature(36.8).equals("36.8"));
        check(PowerDisplayState.sign(-1,true).equals("−"));
        check(PowerDisplayState.sign(1,true).equals("+"));
        check(PowerDisplayState.sign(-0.0,true).isEmpty());
        display.enabled=false;check(display.deadline(true)==Long.MAX_VALUE);
        System.out.println("Performance monitor: independent deadlines and fixed-width value formatting passed");
    }
}
