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
        // The same driver-reported values must be reversed using the gauge term voltage and device table.
        Double raw=BatteryDetails.rawSoh(99,3150,table);
        equal(BatteryDetails.noCompensation(3150,table),false);
        equal(BatteryDetails.rawFcc(6304,raw,3150,table,6500),5384);
        if(Math.abs(raw-99/1.07)>0.001)throw new AssertionError("Incorrect SOH coefficient");
        equal(BatteryDetails.noCompensation(2999,table),true);
        equal(BatteryDetails.rawSoh(99,2999,table),null);
        equal(BatteryDetails.rawFcc(6304,raw,2999,table,6500),null);
        // Models with compensation tiers below 3.00 V must still reverse using their actual table.
        List<int[]> subThreeVoltTable=Arrays.asList(new int[]{2850,400,2},new int[]{3000,800,3});
        equal(BatteryDetails.noCompensation(2900,subThreeVoltTable),false);
        Double subRaw=BatteryDetails.rawSoh(99,2900,subThreeVoltTable);
        if(subRaw==null||Math.abs(subRaw-99/1.02)>0.001)throw new AssertionError("Sub-3V SOH coefficient ignored");
        equal(BatteryDetails.rawFcc(6100,subRaw,2900,subThreeVoltTable,6500),5712);
        equal(BatteryDetails.rawFccAbove(6500,99,2900,subThreeVoltTable,6500),6107);
        equal(BatteryDetails.noCompensation(2800,subThreeVoltTable),true);
        // Models whose lowest compensation tier is above 3.00 V are uncompensated below that tier.
        List<int[]> higherFloorTable=Arrays.asList(new int[]{3100,600,4},new int[]{3250,900,6});
        equal(BatteryDetails.noCompensation(3000,higherFloorTable),true);
        equal(BatteryDetails.rawSoh(99,3000,higherFloorTable),null);
        equal(BatteryDetails.rawFcc(6100,99.0,3000,higherFloorTable,6500),null);
        // Zero-offset tiers (including <0 0 0> DTBO overrides) apply no compensation; missing tables stay unknown.
        equal(BatteryDetails.noCompensation(3000,Collections.singletonList(new int[]{0,0,0})),true);
        equal(BatteryDetails.noCompensation(2850,Arrays.asList(new int[]{2800,0,0},new int[]{3000,500,3})),true);
        equal(BatteryDetails.noCompensation(3150,Collections.emptyList()),false);
        equal(BatteryDetails.rawSoh(98,3150,Collections.emptyList()),null);
        equal(BatteryDetails.rawFcc(6100,98.0,3150,Collections.emptyList(),6500),null);
        // Capacity-only compensation with 0% SOH offset can still reverse at 100% SOH.
        List<int[]> fccOnlyTable=Collections.singletonList(new int[]{2900,300,0});
        equal(BatteryDetails.rawSoh(100,2950,fccOnlyTable),100.0);
        equal(BatteryDetails.rawFcc(6200,100.0,2950,fccOnlyTable,6500),5900);
        // Saturation gives a bound even when the compensated SOH is itself capped.
        equal(BatteryDetails.rawFccAbove(6500,99,3000,table,6500),5723);
        equal(BatteryDetails.rawFccAbove(6500,100,3000,table,6500),5699);
        equal(BatteryDetails.rawFccAbove(6499,99,3000,table,6500),null);
        System.out.println("BatteryDetails: term status parsing and driver floor selection passed");
    }
}
