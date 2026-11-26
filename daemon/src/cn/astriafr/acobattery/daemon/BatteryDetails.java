package cn.astriafr.acobattery.daemon;

import java.nio.ByteBuffer;
import java.util.*;
import java.util.regex.*;

/** Reverse the OPLUS driver's integer compensation and handle the confirmed lowest-tier estimate. */
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
        if(coefficients!=null&&cutoff!=null&&cutoff>0)
            for(int[] row:coefficients)if(row!=null&&row.length>=3&&row[0]>0&&row[0]<=cutoff&&(selected==null||row[0]>selected[0]))selected=row;
        return selected;
    }
    static Integer lowestCutoff(List<int[]> coefficients){
        Integer lowest=null;
        for(int[] row:coefficients){
            if(row!=null&&row.length>=3&&row[0]>0&&(lowest==null||row[0]<lowest))lowest=row[0];
        }
        return lowest;
    }
    static boolean confirmCompensationFloor(boolean confirmed,Integer cutoff,Integer lowest,Integer fcc,double tailRm){
        if(fcc==null||fcc<=0||!Double.isFinite(tailRm)||tailRm<=0)return confirmed;
        double difference=fcc-tailRm;
        if(difference<200)return false;
        return confirmed||difference>200&&cutoff!=null&&cutoff>0&&lowest!=null&&cutoff<lowest;
    }
    static boolean hasCompensation(int[] row){
        return row!=null&&(row[1]!=0||row[2]!=0);
    }
    public static boolean noCompensation(Integer cutoff,List<int[]> coefficients){
        return cutoff!=null&&cutoff>0&&coefficients!=null&&!coefficients.isEmpty()&&!hasCompensation(coefficient(coefficients,cutoff));
    }
    private static long compensatedSoh(int rawSoh,int offset){
        return Math.min(100,rawSoh+(long)rawSoh*offset/100);
    }
    private static int maxRawSoh(Integer soh,int offset){
        if(soh!=null&&soh>0&&soh<=100)
            for(int raw=100;raw>0;raw--)if(compensatedSoh(raw,offset)==soh)return raw;
        return 0;
    }
    private static boolean ambiguousRawSoh(int soh,int offset,int maxRawSoh){
        return maxRawSoh>1&&compensatedSoh(maxRawSoh-1,offset)==soh;
    }
    public static Double rawSoh(Integer soh,Integer cutoff,List<int[]> coefficients){
        return rawSoh(soh,cutoff,coefficients,false);
    }
    static Double rawSoh(Integer soh,Integer cutoff,List<int[]> coefficients,boolean estimateAtFloor){
        if(soh==null||soh<=0||cutoff==null||cutoff<=0)return null;
        int[] row=coefficient(coefficients,cutoff);
        if(!hasCompensation(row))return null;
        int raw=maxRawSoh(soh,row[2]);
        if(raw>0)return ambiguousRawSoh(soh,row[2],raw)?null:(double)raw;
        if(!estimateAtFloor||soh>=100||row[2]<=0)return null;
        // Below the lowest tier the gauge can retain a different offset. Estimate
        // within the lowest tier's response when it skips the reported integer.
        for(int upper=2;upper<=100;upper++){
            long above=compensatedSoh(upper,row[2]);
            if(above<=soh)continue;
            long below=compensatedSoh(upper-1,row[2]);
            return below<soh?upper-1+(soh-below)/(double)(above-below):null;
        }
        return null;
    }
    public static Integer rawFcc(Integer fcc,Double rawSoh,Integer cutoff,List<int[]> coefficients,Integer design){
        if(fcc==null||fcc<=0||cutoff==null||cutoff<=0)return null;
        int[] row=coefficient(coefficients,cutoff);
        if(!hasCompensation(row))return null;
        if(row[1]==0)return fcc;
        if((design!=null&&design>0&&fcc>=design)||rawSoh==null||rawSoh<=0)return null;
        int wholeSoh=rawSoh.intValue();
        long offset=(long)row[1]*wholeSoh/100;
        long nextOffset=(long)row[1]*(wholeSoh+1)/100;
        long value=Math.round(fcc-offset-(nextOffset-offset)*(rawSoh-wholeSoh));
        return value>0&&value<=Integer.MAX_VALUE?(int)value:null;
    }
    public static Integer rawFccAbove(Integer fcc,Integer soh,Integer cutoff,List<int[]> coefficients,Integer design){
        if(fcc==null||fcc<=0||cutoff==null||cutoff<=0)return null;
        int[] row=coefficient(coefficients,cutoff);
        if(row==null||row[1]<=0)return null;
        int maxRaw=maxRawSoh(soh,row[2]);
        boolean fccCapped=design!=null&&design>0&&fcc>=design;
        if(!fccCapped&&(soh==null||!ambiguousRawSoh(soh,row[2],maxRaw)))return null;
        // The largest possible SOH gives the largest offset. Print a strict lower bound.
        long bound=(long)fcc-(long)row[1]*(maxRaw==0?100:maxRaw)/100-1;
        return bound>0?(int)bound:null;
    }
    public static Integer normalizeQmax(Integer qmax,Integer fcc){
        return normalizeQmax(qmax,fcc,null);
    }
    public static Integer normalizeQmax(Integer qmax,Integer fcc,Integer design){
        if(qmax==null||qmax<=0)return null;
        // FCC can fall well below the physical capacity. Prefer design capacity;
        // without it, use a conservative floor rather than shrinking valid Qmax.
        long reference=design!=null&&design>0?design:Math.max(20000,fcc!=null&&fcc>0?fcc:0);
        while(qmax>=reference*2)qmax/=10;
        return qmax>0?qmax:null;
    }
}
