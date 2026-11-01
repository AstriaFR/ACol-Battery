package cn.astriafr.acobattery.common;
import org.json.*;
public final class BatteryHistorySnapshotTest {
 static void check(boolean v){if(!v)throw new AssertionError();}
 static boolean green(JSONObject snapshot,String key)throws Exception{JSONArray a=snapshot.getJSONArray("nearActual");for(int i=0;i<a.length();i++)if(key.equals(a.getString(i)))return true;return false;}
 public static void main(String[] args)throws Exception{
  JSONObject source=new JSONObject().put("cycles",480).put("qmax",6200).put("fcc",6100).put("rawFcc",5800).put("soh",99).put("rawSoh",94.5).put("cutoffMv",3150).put("designCapacity",6500).put("serial","private");
  JSONObject saved=BatteryHistorySnapshot.capture(source);check(saved.getInt("lockedCapacity")==400);check(green(saved,"qmax")&&green(saved,"rawFcc")&&green(saved,"rawSoh")&&!green(saved,"fcc"));check(!saved.has("serial")&&!saved.has("coefficients"));
  source.put("qmax",1).put("cutoffMv",2900);check(saved.getInt("qmax")==6200&&saved.getInt("cutoffMv")==3150&&green(saved,"rawFcc"));
  source.put("qmax",6200);JSONObject low=BatteryHistorySnapshot.capture(source);check(low.getBoolean("noCompensation")&&low.getInt("lockedCapacity")==100);check(green(low,"fcc")&&!green(low,"rawFcc")&&!green(low,"rawSoh"));
  JSONObject missing=BatteryHistorySnapshot.capture(new JSONObject());check(missing.isNull("qmax")&&missing.isNull("lockedCapacity")&&missing.getJSONArray("nearActual").length()==0);
  System.out.println("Battery history: frozen values, capture-time trust flags, low-cutoff policy, null fields and data-only snapshot passed");
 }
}
