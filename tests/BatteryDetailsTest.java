package cn.astriafr.acobattery.daemon;

import java.util.*;

public final class BatteryDetailsTest {
    static void equal(Object actual,Object expected){if(!Objects.equals(actual,expected))throw new AssertionError(actual+" != "+expected);}
    public static void main(String[] args){
        equal(BatteryDetails.termVoltage("GAUGE_TERM_VOLTAGE: effective=DEBUG_FORCE_CLIENT type=Max v=3150\nOTHER: v=3000"),3150);
        equal(BatteryDetails.termVoltage("GAUGE_TERM_VOLTAGE: effective=DEEP_COUNT_VOTER type=Max v=3060\r\n"),3060);
        equal(BatteryDetails.termVoltage("GAUGE_SHUTDOWN_VOLTAGE: effective=DEBUG_FORCE_CLIENT type=Max v=3000"),null);
        equal(BatteryDetails.termVoltage("GAUGE_TERM_VOLTAGE: voter=TEST v=3150"),null);
        equal(BatteryDetails.termVoltage("GAUGE_TERM_VOLTAGE: effective=TEST type=Max v=-1"),null);
        equal(BatteryDetails.termVoltage("GAUGE_TERM_VOLTAGE: effective=TEST type=Max v=999999999999"),null);
        equal(BatteryDetails.termVoltage(""),null);equal(BatteryDetails.termVoltage(null),null);
        List<int[]> table=Arrays.asList(new int[]{3150,1000,7},new int[]{3000,800,3},new int[]{3060,900,5},new int[]{3350,1200,10});
        equal(BatteryDetails.coefficient(table,3150)[0],3150);
        equal(BatteryDetails.coefficient(table,3100)[0],3060);
        equal(BatteryDetails.coefficient(table,3500)[0],3350);
        equal(BatteryDetails.coefficient(table,2500),null);
        equal(BatteryDetails.coefficient(table,null),null);
        // The same driver-reported values must be reversed using the gauge term voltage.
        Double raw=BatteryDetails.rawSoh(99,3150,table);
        equal(BatteryDetails.rawFcc(6304,raw,3150,table,6500),5384);
        if(Math.abs(raw-99/1.07)>0.001)throw new AssertionError("Incorrect SOH coefficient");
        equal(BatteryDetails.rawSoh(99,2999,table),null);
        equal(BatteryDetails.rawFcc(6304,raw,2999,table,6500),null);
        System.out.println("BatteryDetails: term status parsing and driver floor selection passed");
    }
}
