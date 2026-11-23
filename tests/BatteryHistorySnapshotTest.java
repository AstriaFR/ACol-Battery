package cn.astriafr.acobattery.common;
import org.json.*;
public final class BatteryHistorySnapshotTest {
 static void check(boolean v){if(!v)throw new AssertionError();}
 static boolean green(JSONObject snapshot,String key)throws Exception{JSONArray a=snapshot.getJSONArray("nearActual");for(int i=0;i<a.length();i++)if(key.equals(a.getString(i)))return true;return false;}
 public static void main(String[] args)throws Exception{
  JSONArray table=new JSONArray().put(new JSONObject().put("cutoffMv",3000).put("fccOffset",800).put("sohOffset",3)).put(new JSONObject().put("cutoffMv",3150).put("fccOffset",1000).put("sohOffset",7));
  JSONObject source=new JSONObject().put("cycles",480).put("qmax",6200).put("fcc",6100).put("rawFcc",5800).put("soh",99).put("rawSoh",94.5).put("cutoffMv",3150).put("designCapacity",6500).put("coefficients",table).put("serial","private");
  JSONObject saved=BatteryHistorySnapshot.capture(source);check(!saved.getBoolean("noCompensation")&&saved.getInt("lockedCapacity")==400);check(green(saved,"qmax")&&green(saved,"rawFcc")&&!green(saved,"rawSoh")&&!green(saved,"fcc"));check(!saved.has("serial")&&!saved.has("coefficients"));
  check(BatteryHistorySnapshot.trustedFcc(saved)==5800);
  source.put("qmax",1).put("cutoffMv",2900);check(saved.getInt("qmax")==6200&&saved.getInt("cutoffMv")==3150&&green(saved,"rawFcc"));
  source.put("qmax",6200);JSONObject low=BatteryHistorySnapshot.capture(source);check(low.getBoolean("noCompensation")&&low.getInt("lockedCapacity")==100);check(green(low,"fcc")&&!green(low,"rawFcc")&&!green(low,"rawSoh"));
  check(BatteryHistorySnapshot.trustedFcc(low)==6100);check(BatteryHistorySnapshot.trustedFcc(new JSONObject().put("fcc",6000))==null);
  // Sub-3.0 V tiers compensate when matched; >= 3.0 V stays uncompensated below a higher minimum tier; missing table trusts neither FCC.
  JSONObject subThreeSource=new JSONObject().put("qmax",6200).put("fcc",6100).put("rawFcc",5800).put("soh",99).put("rawSoh",94.5).put("cutoffMv",2900).put("designCapacity",6500).put("coefficients",new JSONArray().put(new JSONObject().put("cutoffMv",2850).put("fccOffset",400).put("sohOffset",2)));
  JSONObject subThreeCompensated=BatteryHistorySnapshot.capture(subThreeSource);
  check(!subThreeCompensated.getBoolean("noCompensation")&&green(subThreeCompensated,"rawFcc")&&!green(subThreeCompensated,"fcc")&&subThreeCompensated.getInt("lockedCapacity")==400);
  JSONObject aboveThreeSource=new JSONObject().put("qmax",6200).put("fcc",6100).put("rawFcc",JSONObject.NULL).put("soh",100).put("rawSoh",JSONObject.NULL).put("cutoffMv",3000).put("designCapacity",6500).put("coefficients",new JSONArray().put(new JSONObject().put("cutoffMv",3100).put("fccOffset",600).put("sohOffset",4)));
  JSONObject aboveThreeUncompensated=BatteryHistorySnapshot.capture(aboveThreeSource);
  check(aboveThreeUncompensated.getBoolean("noCompensation")&&green(aboveThreeUncompensated,"fcc")&&!green(aboveThreeUncompensated,"rawFcc")&&aboveThreeUncompensated.getInt("lockedCapacity")==100);
  JSONObject unknownTable=BatteryHistorySnapshot.capture(new JSONObject().put("qmax",6200).put("fcc",6100).put("cutoffMv",3150).put("coefficients",new JSONArray()));
  check(!unknownTable.getBoolean("noCompensation")&&!green(unknownTable,"fcc")&&!green(unknownTable,"rawFcc")&&green(unknownTable,"qmax")&&unknownTable.isNull("lockedCapacity"));
  source.put("qmax",6000);check(BatteryHistorySnapshot.capture(source).getInt("lockedCapacity")==0);
  source.put("cutoffMv",3150).put("rawFcc",JSONObject.NULL).put("rawFccAbove",5700);
  JSONObject bounded=BatteryHistorySnapshot.capture(source);check(bounded.isNull("lockedCapacity")&&bounded.getInt("rawFccAbove")==5700);
  check(green(bounded,"rawFcc")&&BatteryHistorySnapshot.fccLowerBound(bounded)==5700);
  bounded.put("nearActual",new JSONArray().put("qmax"));
  check(BatteryHistorySnapshot.fccLowerBound(bounded)==5700);
  check(BatteryHistorySnapshot.fccLowerBound(low)==null);
  JSONObject missing=BatteryHistorySnapshot.capture(new JSONObject());check(missing.isNull("qmax")&&missing.isNull("lockedCapacity")&&missing.getJSONArray("nearActual").length()==0);
  source.put("tailRm",6040);
  JSONObject manual=BatteryHistorySnapshot.capture(source);
  check(!manual.has("tailRm"));
  check(BatteryHistorySnapshot.withTailRm(manual,6040).getDouble("tailRm")==6040);
  check(BatteryHistorySnapshot.withTailRm(null,0).getDouble("tailRm")==0);
  check(BatteryHistorySnapshot.withTailRm(null,Double.NaN)==null);
  System.out.println("Battery history: frozen values, capture-time trust flags, low-cutoff policy, null fields and data-only snapshot passed");
 }
}
