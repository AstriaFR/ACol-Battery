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
    static boolean positive(JSONObject info,String key){double v=info.optDouble(key,Double.NaN);return Double.isFinite(v)&&v>0;}
    public static JSONObject capture(JSONObject info)throws JSONException {
        JSONObject out=new JSONObject();
        for(String key:new String[]{"cycles","qmax","fcc","rawFcc","soh","rawSoh","cutoffMv","designCapacity"})out.put(key,info.has(key)?info.get(key):JSONObject.NULL);
        double cutoff=info.optDouble("cutoffMv",Double.NaN),qmax=info.optDouble("qmax",Double.NaN);
        boolean known=Double.isFinite(cutoff)&&cutoff>0,noCompensation=known&&cutoff<3000;
        double reference=info.optDouble(noCompensation?"fcc":"rawFcc",Double.NaN);
        boolean bounded=known&&!noCompensation&&positive(info,"rawFccAbove");
        if(bounded)out.put("rawFccAbove",info.get("rawFccAbove"));
        out.put("lockedCapacity",!bounded&&Double.isFinite(qmax)&&qmax>0&&Double.isFinite(reference)&&reference>0?Math.max(0,qmax-reference):JSONObject.NULL);
        JSONArray green=new JSONArray();
        if(positive(info,"qmax"))green.put("qmax");
        String capacityKey=noCompensation?"fcc":"rawFcc";
        if(known&&positive(info,capacityKey))green.put(capacityKey);
        return out.put("nearActual",green).put("noCompensation",noCompensation).put("snapshotVersion",1);
    }
}
