package cn.astriafr.acobattery.common;

import org.json.*;

/** Freeze measured/calculated values and their trust markings at tail completion. */
public final class BatteryHistorySnapshot {
    private BatteryHistorySnapshot(){}
    static boolean positive(JSONObject info,String key){double v=info.optDouble(key,Double.NaN);return Double.isFinite(v)&&v>0;}
    public static JSONObject capture(JSONObject info)throws JSONException {
        JSONObject out=new JSONObject();
        for(String key:new String[]{"cycles","qmax","fcc","rawFcc","soh","rawSoh","cutoffMv","designCapacity"})out.put(key,info.has(key)?info.get(key):JSONObject.NULL);
        double cutoff=info.optDouble("cutoffMv",Double.NaN),qmax=info.optDouble("qmax",Double.NaN);
        boolean known=Double.isFinite(cutoff)&&cutoff>0,noCompensation=known&&cutoff<3000;
        double reference=info.optDouble(noCompensation?"fcc":"rawFcc",Double.NaN);
        out.put("lockedCapacity",Double.isFinite(qmax)&&Double.isFinite(reference)?qmax-reference:JSONObject.NULL);
        JSONArray green=new JSONArray();
        if(positive(info,"qmax"))green.put("qmax");
        if(known)for(String key:noCompensation?new String[]{"fcc"}:new String[]{"rawFcc","rawSoh"})if(positive(info,key))green.put(key);
        return out.put("nearActual",green).put("noCompensation",noCompensation).put("snapshotVersion",1);
    }
}
