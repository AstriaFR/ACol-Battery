package cn.astriafr.acobattery.daemon;

import java.nio.ByteBuffer;
import java.util.*;
import java.util.regex.*;

/** OPLUS compensation reversal, matching PlusPlusBattery's integer arithmetic. */
public final class BatteryDetails {
    private static final Pattern EFFECTIVE_VOLTAGE=Pattern.compile("\\bv=(-?\\d+)\\s*$");
    public static Integer termVoltage(String status){
        if(status==null)return null;
        for(String line:status.split("\\r?\\n")){
            line=line.trim();
            if(!line.startsWith("GAUGE_TERM_VOLTAGE:")||!line.contains("effective="))continue;
            Matcher match=EFFECTIVE_VOLTAGE.matcher(line);
            if(match.find())try{int value=Integer.parseInt(match.group(1));return value>0?value:null;}catch(NumberFormatException ignored){}
        }
        return null;
    }
    public static List<int[]> coefficients(byte[] bytes){
        List<int[]> result=new ArrayList<>();ByteBuffer buffer=ByteBuffer.wrap(bytes);
        while(buffer.remaining()>=12)result.add(new int[]{buffer.getInt(),buffer.getInt(),buffer.getInt()});
        return result;
    }
    static int[] coefficient(List<int[]> coefficients,Integer cutoff){
        int[] selected=null;
        if(cutoff!=null)for(int[] row:coefficients)if(row[0]<=cutoff&&(selected==null||row[0]>selected[0]))selected=row;
        return selected;
    }
    public static Double rawSoh(Integer soh,Integer cutoff,List<int[]> coefficients){
        if(soh==null||soh<=0||cutoff==null||cutoff<=0)return null;
        if(cutoff<3000)return null; // Keep the existing UI policy: do not reverse compensation below 3 V.
        if(soh==100)return null;
        int[] row=coefficient(coefficients,cutoff);float factor=row==null?1f:1f+row[2]/100f;
        float value=soh/factor;return Float.isFinite(value)&&value>=.0001f?(double)value:null;
    }
    public static Integer rawFcc(Integer fcc,Double rawSoh,Integer cutoff,List<int[]> coefficients,Integer design){
        if(fcc==null||fcc<=0||cutoff==null||cutoff<=0)return null;
        if(cutoff<3000)return null;
        if(fcc.equals(design)||rawSoh==null||rawSoh<=0)return null;
        int[] row=coefficient(coefficients,cutoff);
        long value=row==null?fcc:(long)fcc-(long)row[1]*rawSoh.intValue()/100;
        return value>0&&value<=Integer.MAX_VALUE?(int)value:null;
    }
    public static Integer normalizeQmax(Integer qmax,Integer fcc){
        return normalizeQmax(qmax,fcc,null);
    }
    public static Integer normalizeQmax(Integer qmax,Integer fcc,Integer design){
        if(qmax==null||qmax<0)return null;
        // FCC can fall well below the physical capacity. Prefer design capacity;
        // without it, use a conservative floor rather than shrinking valid Qmax.
        long reference=design!=null&&design>0?design:Math.max(20000,fcc!=null&&fcc>0?fcc:0);
        while(qmax>=reference*2)qmax/=10;return qmax;
    }
}
