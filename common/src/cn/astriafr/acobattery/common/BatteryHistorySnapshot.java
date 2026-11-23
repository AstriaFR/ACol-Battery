package cn.astriafr.acobattery.common;

import org.json.*;

/** Freeze measured/calculated values and their trust markings when a snapshot is taken. */
public final class BatteryHistorySnapshot {
    private BatteryHistorySnapshot(){}
    public static Double trustedFcc(JSONObject snapshot){
        JSONArray marked=snapshot.optJSONArray("nearActual");if(marked==null)return null;
        for(String key:new String[]{"rawFcc","fcc"})for(int i=0;i<marked.length();i++)if(key.equals(marked.optString(i))&&positive(snapshot,key))return snapshot.optDouble(key);
        return null;
    }
    public static Double fccLowerBound(JSONObject snapshot){
        return !snapshot.optBoolean("noCompensation")&&positive(snapshot,"rawFccAbove")
            ?snapshot.optDouble("rawFccAbove"):null;
    }
    public static JSONObject withTailRm(JSONObject snapshot,double rm)throws JSONException{
        if(!Double.isFinite(rm)||rm<0)return snapshot;
        return (snapshot==null?new JSONObject():snapshot).put("tailRm",rm);
    }
    static boolean positive(JSONObject info,String key){double v=info.optDouble(key,Double.NaN);return Double.isFinite(v)&&v>0;}
    static boolean uncompensated(JSONObject info,double cutoff,boolean known){
        if(!known)return false;
        if(info.has("noCompensation")&&!info.isNull("noCompensation"))return info.optBoolean("noCompensation");
        JSONArray table=info.optJSONArray("coefficients");
        if(table==null||table.length()==0)return false;
        JSONObject selected=null;double selectedCutoff=0;
        for(int i=0;i<table.length();i++){
            JSONObject row=table.optJSONObject(i);if(row==null)continue;
            double rowCutoff=row.optDouble("cutoffMv",Double.NaN),fccOffset=row.optDouble("fccOffset",Double.NaN),sohOffset=row.optDouble("sohOffset",Double.NaN);
            if(!Double.isFinite(rowCutoff)||!Double.isFinite(fccOffset)||!Double.isFinite(sohOffset)||rowCutoff<=0||rowCutoff>cutoff)continue;
            if(selected==null||rowCutoff>selectedCutoff){selected=row;selectedCutoff=rowCutoff;}
        }
        return selected==null||(selected.optDouble("fccOffset",0)==0&&selected.optDouble("sohOffset",0)==0);
    }
    public static JSONObject capture(JSONObject info)throws JSONException {
        JSONObject out=new JSONObject();
        for(String key:new String[]{"cycles","qmax","fcc","rawFcc","soh","rawSoh","cutoffMv","designCapacity"})out.put(key,info.has(key)?info.get(key):JSONObject.NULL);
        double cutoff=info.optDouble("cutoffMv",Double.NaN),qmax=info.optDouble("qmax",Double.NaN);
        boolean known=Double.isFinite(cutoff)&&cutoff>0,noCompensation=uncompensated(info,cutoff,known);
        double reference=info.optDouble(noCompensation?"fcc":"rawFcc",Double.NaN);
        boolean bounded=known&&!noCompensation&&positive(info,"rawFccAbove");
        if(bounded)out.put("rawFccAbove",info.get("rawFccAbove"));
        out.put("lockedCapacity",!bounded&&Double.isFinite(qmax)&&qmax>0&&Double.isFinite(reference)&&reference>0?Math.max(0,qmax-reference):JSONObject.NULL);
        JSONArray green=new JSONArray();
        if(positive(info,"qmax"))green.put("qmax");
        String capacityKey=noCompensation?"fcc":"rawFcc";
        if(bounded||known&&positive(info,capacityKey))green.put(capacityKey);
        return out.put("nearActual",green).put("noCompensation",noCompensation).put("snapshotVersion",1);
    }
}
