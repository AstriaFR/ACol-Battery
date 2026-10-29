package cn.astriafr.acobattery.daemon;

public final class RecordFilterTest {
    public static void main(String[] args){
        if(args.length>0&&args[0].equals("--sql")){
            for(boolean all:new boolean[]{false,true})for(int type=0;type<4;type++)for(boolean month:new boolean[]{false,true}){
                long from=month?1000:0,to=month?2000:0;
                System.out.println(all+"\t"+type+"\t"+from+"\t"+to+"\t"+RecordFilter.where(all,30,type,from,to));
            }
            return;
        }
        if(!RecordFilter.where(true,30,0,0,0).isEmpty())throw new AssertionError();
        try{RecordFilter.where(false,30,4,0,0);throw new AssertionError();}catch(IllegalArgumentException expected){}
        try{RecordFilter.where(false,30,-1,0,0);throw new AssertionError();}catch(IllegalArgumentException expected){}
        System.out.println("RecordFilter: unfiltered and invalid-type checks passed");
    }
}
