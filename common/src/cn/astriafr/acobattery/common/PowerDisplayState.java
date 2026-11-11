package cn.astriafr.acobattery.common;

import java.util.Locale;

/** Display deadlines for performance and battery updates. */
public final class PowerDisplayState {
    public boolean enabled;
    private long performanceNext, batteryNext;
    public PowerDisplayState(boolean enabled){this.enabled=enabled;}
    public long deadline(boolean interactive){return enabled&&interactive?Math.min(performanceNext,batteryNext):Long.MAX_VALUE;}
    public boolean performanceDue(long now){return enabled&&now>=performanceNext;}
    public boolean batteryDue(long now){return enabled&&now>=batteryNext;}
    public void refresh(){performanceNext=0;batteryNext=0;}
    public void performanceSampled(long now,long intervalMs){performanceNext=now+intervalMs;}
    public void batterySampled(long now,long intervalMs){batteryNext=now+intervalMs;}

    public static String fps(double value){
        if(!Double.isFinite(value)||value<0)return "—";
        long rounded=Math.round(value);
        return rounded<=999?Long.toString(rounded):"—";
    }
    public static String frequency(double mhz){return number(mhz,0);}
    public static String power(double watts){return number(Math.abs(watts),2);}
    public static String temperature(double celsius){return number(Math.abs(celsius),1);}
    public static String sign(double value,boolean showPlus){
        if(!Double.isFinite(value))return "";
        if(value<0)return "−";
        return showPlus&&value>0?"+":"";
    }
    private static String number(double value,int decimals){
        if(!Double.isFinite(value)||value<0||value>=10000)return "—";
        for(int digits=decimals;digits>=0;digits--){
            String formatted=String.format(Locale.US,"%."+digits+"f",value);
            if(formatted.length()<=4)return formatted;
        }
        return "—";
    }
}
