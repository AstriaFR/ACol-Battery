package cn.astriafr.acobattery.common;

public final class PowerDisplayStateTest {
    static void check(boolean value){if(!value)throw new AssertionError();}
    public static void main(String[] args){
        PowerDisplayState display=new PowerDisplayState(false);
        check(!display.due(0,true));check(display.deadline(true)==Long.MAX_VALUE);
        display.enabled=true;check(display.due(0,true));display.sampled(1000,10);
        check(!display.due(10999,true));check(display.due(11000,true));
        check(!display.due(12000,false));check(display.deadline(false)==Long.MAX_VALUE);
        display.refresh();check(display.due(12000,true));display.sampled(12000,3);
        check(display.deadline(true)==15000);
        check(PowerDisplayState.text(1.42).equals("1.42W"));
        check(PowerDisplayState.text(-12.345).equals("12.35W"));
        check(PowerDisplayState.text(-0.0).equals("0.00W"));
        check(PowerDisplayState.text(Double.NaN).equals("—W"));
        display.enabled=false;check(display.deadline(true)==Long.MAX_VALUE);
        System.out.println("Power display: independent cadence, screen-off pause, disabled state and two-decimal formatting passed");
    }
}
