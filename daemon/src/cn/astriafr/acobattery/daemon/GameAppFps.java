package cn.astriafr.acobattery.daemon;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Accumulates valid performance intervals wholly attributable to one foreground app. */
final class GameAppFps {
    private static final class Total {
        double weighted;
        long ms;
    }

    private final Map<String,Total> totals=new LinkedHashMap<>();
    long sampleX=-1;
    private int spanIndex;
    boolean measured;

    static GameAppFps restore(long sampleX,String text)throws JSONException {
        GameAppFps result=new GameAppFps();
        JSONObject saved=new JSONObject(text);
        for(Iterator<String> keys=saved.keys();keys.hasNext();){
            String pkg=keys.next();
            JSONArray entry=saved.optJSONArray(pkg);
            if(entry==null)continue;
            double weighted=entry.optDouble(0,Double.NaN);
            long ms=entry.optLong(1,0);
            if(!Core.finite(weighted)||weighted<0||ms<=0)continue;
            Total total=new Total();
            total.weighted=weighted;total.ms=ms;
            result.totals.put(pkg,total);
        }
        result.sampleX=sampleX;
        return result;
    }

    void sample(long x,String data,long wallStart,List<ScreenUsage.AppSpan> spans){
        if(x<=sampleX)return;
        sampleX=x;
        JSONArray row;
        try{row=new JSONArray(data);}catch(JSONException invalid){return;}
        double fps=row.optDouble(1,Double.NaN);
        long duration=row.optLong(7,0);
        if(!Core.finite(fps)||fps<0||duration<=0||duration>x||row.optInt(6,0)!=0)return;
        long end=wallStart+x,start=end-duration;
        while(spanIndex<spans.size()&&spans.get(spanIndex).end<=start)spanIndex++;
        if(spanIndex>=spans.size())return;
        ScreenUsage.AppSpan span=spans.get(spanIndex);
        if(span.start>start||span.end<end||Core.OFF.equals(span.pkg)
            ||Core.OTHER.equals(span.pkg)||Core.UNATTRIBUTED.equals(span.pkg))return;
        double weighted=fps*duration;
        if(!Core.finite(weighted))return;
        Total total=totals.computeIfAbsent(span.pkg,pkg->new Total());
        if(!Core.finite(total.weighted+weighted))return;
        total.weighted+=weighted;total.ms+=duration;
        measured=true;
    }

    Double average(String pkg){
        Total total=totals.get(pkg);
        return total==null?null:total.weighted/total.ms;
    }

    String encode()throws JSONException {
        JSONObject saved=new JSONObject();
        for(Map.Entry<String,Total> entry:totals.entrySet()){
            Total total=entry.getValue();
            saved.put(entry.getKey(),new JSONArray().put(total.weighted).put(total.ms));
        }
        return saved.toString();
    }
}
