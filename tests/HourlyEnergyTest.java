package cn.astriafr.acobattery.daemon;

import java.util.*;

public final class HourlyEnergyTest {
    static void close(double actual,double expected){if(Math.abs(actual-expected)>1e-9)throw new AssertionError(actual+" != "+expected);}
    public static void main(String[] args){
        TimeZone zone=TimeZone.getTimeZone("Asia/Shanghai");Calendar c=Calendar.getInstance(zone);
        c.clear();c.set(2026,Calendar.OCTOBER,29);long day=c.getTimeInMillis(),hour=3600000;
        List<ScreenUsage.AppSpan> spans=Arrays.asList(
            new ScreenUsage.AppSpan(day,day+hour/2,"app.a"),
            new ScreenUsage.AppSpan(day+hour/2,day+hour,Core.OFF),
            new ScreenUsage.AppSpan(day+hour,day+2*hour,"app.b"));
        HourlyEnergy data=new HourlyEnergy(spans,day,day+24*hour,zone);
        data.add(day-1000,day+2*hour,2);
        close(data.wh[0],2);close(data.wh[1],2);
        close(data.apps.get(0).get("app.a"),1);close(data.apps.get(0).get(Core.OFF),1);close(data.apps.get(1).get("app.b"),2);
        // Small intervals and high powers are valid for accounting, regardless
        // of the power curve's display-only 10 s / 15 W filters.
        data.add(day+2*hour,day+2*hour+5000,18);
        close(data.wh[2],.025);close(data.apps.get(2).get(Core.OTHER),.025);
        data.add(day+3*hour,day+4*hour,0);close(data.covered[3],hour);
        data.add(day+4*hour,day+5*hour,Double.NaN);data.add(day+4*hour,day+5*hour,-2);
        close(data.covered[4],0);
        // Only the requested day's share of a midnight-crossing interval counts.
        data.add(day+24*hour-30000,day+24*hour+30000,12);close(data.wh[23],.1);
        double total=0,assigned=0;
        for(int h=0;h<24;h++){total+=data.wh[h];for(double wh:data.apps.get(h).values())assigned+=wh;}
        close(total,4.125);close(assigned,total);
        HourlyEnergy game=new HourlyEnergy(Collections.emptyList(),day,day+hour,zone);
        HourlyEnergy.RmMinutes minute=new HourlyEnergy.RmMinutes(game,day);
        minute.sample(0,5000,4000,0);minute.sample(1000,4990,4000,0);
        close(game.wh[0],0); // Wait for the RM minute settlement, not the power curve.
        minute.sample(60000,4980,4000,0);close(game.wh[0],.08);
        minute.sample(90000,4975,4000,0);minute.finish();close(game.wh[0],.1);
        minute.sample(100000,4970,4000,8);close(game.wh[0],.1); // Recovered gap remains unmeasured.
        // After a one-hour clock correction, usage must still match the
        // elapsed-time energy interval in the same record.
        ScreenUsage.Builder actual=new ScreenUsage.Builder();
        actual.accept(day+hour,day+hour+60000,true,"clock.app");
        ScreenUsage.Builder aligned=actual.onRecordClock(day+hour,day+hour+60000,day,day+60000);
        HourlyEnergy corrected=new HourlyEnergy(aligned.apps,day,day+hour,zone);corrected.add(day,day+60000,6);
        close(corrected.wh[0],.1);close(corrected.apps.get(0).get("clock.app"),.1);
        if(aligned.spans.get(0).start!=day||aligned.spans.get(0).end!=day+60000)throw new AssertionError("Screen clock not aligned");
        System.out.println("Hourly energy: hour/day splitting, app/off/unknown allocation and accounting validity passed");
    }
}
