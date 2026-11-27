package cn.astriafr.acobattery.daemon;

import java.util.*;

public final class BatteryDetailsTest {
    static void equal(Object actual,Object expected){if(!Objects.equals(actual,expected))throw new AssertionError(actual+" != "+expected);}
    static void chargingProtocols(){
        String[] reorderedHeader={"","charge_type","batt_qmax","wired_online"};
        equal(ChargingLog.fields(reorderedHeader,new String[]{"","15","6000","1"}).get("charge_type"),"15");
        equal(ChargingLog.fields(reorderedHeader,new String[]{"","15"}).get("charge_type"),null);
        String[] names={"未知","SDP","DCP","CDP","ACA","USB Type-C","USB PD","USB PD DRP","USB PD PPS",
            "USB PD SDP","Apple BrickID","QC 2.0","QC 3.0","VOOC","SUPERVOOC","UFCS"};
        Map<Integer,OplusVotes.Node> fastVotes=new HashMap<>();
        fastVotes.put(8,OplusVotes.Node.PPS_CURR);
        fastVotes.put(13,OplusVotes.Node.VOOC_CURR);
        fastVotes.put(14,OplusVotes.Node.VOOC_CURR);
        fastVotes.put(15,OplusVotes.Node.UFCS_CURR);
        equal(BatteryService.chargingProtocolName(0),names[0]);
        for(double voltage:new double[]{Double.NaN,4000,5000,5400,6000,9000})
            equal(BatteryService.inputCurrentVote(0,voltage),null);
        for(int type=1;type<names.length;type++){
            equal(BatteryService.chargingProtocolName(type),names[type]);
            OplusVotes.Node expected=fastVotes.getOrDefault(type,OplusVotes.Node.WIRED_ICL);
            equal(BatteryService.inputCurrentVote(type,5401),expected);
            equal(BatteryService.inputCurrentVote(type,0),expected);
            equal(BatteryService.inputCurrentVote(type,Double.NaN),expected);
            equal(BatteryService.inputCurrentVote(type,5400),OplusVotes.Node.WIRED_ICL);
        }
        equal(BatteryService.chargingProtocolName(-1),null);
        equal(BatteryService.chargingProtocolName(16),null);
    }
    public static void main(String[] args){
        chargingProtocols();
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
        equal(BatteryDetails.rawFcc(6304,raw,3150,table,6500),5374);
        equal(raw,93.0);
        equal(BatteryDetails.noCompensation(2999,table),true);
        equal(BatteryDetails.rawSoh(99,2999,table),null);
        equal(BatteryDetails.rawFcc(6304,raw,2999,table,6500),null);
        // Models with compensation tiers below 3.00 V must still reverse using their actual table.
        List<int[]> subThreeVoltTable=Arrays.asList(new int[]{2850,400,2},new int[]{3000,800,3});
        equal(BatteryDetails.noCompensation(2900,subThreeVoltTable),false);
        Double subRaw=BatteryDetails.rawSoh(99,2900,subThreeVoltTable);
        equal(subRaw,98.0);
        equal(BatteryDetails.rawFcc(6100,subRaw,2900,subThreeVoltTable,6500),5708);
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
