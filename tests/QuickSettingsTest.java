package cn.astriafr.acobattery.common;
import org.json.JSONObject;
public final class QuickSettingsTest {
 static void check(boolean value){if(!value)throw new AssertionError();}
 public static void main(String[] args)throws Exception{
  JSONObject state=new JSONObject().put("ok",true).put("fastEnabled",false).put("recordingEnabled",true).put("powerDisplayEnabled",true);
  check(!QuickTileState.read(state,true).enabled&&QuickTileState.read(state,true).available);
  check(QuickTileState.read(state,false).enabled);
  state.put("recordingEnabled",false);check(!QuickTileState.read(state,true).available);check(QuickTileState.read(state,false).available);
  state.put("fastEnabled",true);check(QuickTileState.read(state,true).available&&QuickTileState.read(state,true).enabled);
  state.put("powerDisplayError","unavailable");check(QuickTileState.read(state,false).enabled&&QuickTileState.read(state,false).subtitle.contains("异常"));
  for(JSONObject bad:new JSONObject[]{null,new JSONObject(),new JSONObject().put("ok",true).put("fastEnabled","true")})try{QuickTileState.read(bad,true);throw new AssertionError("Unknown state accepted");}catch(java.io.IOException expected){}
  check(QuickTileState.command(true,true).equals("set_fast 1"));check(QuickTileState.command(true,false).equals("set_fast 0"));
  check(QuickTileState.command(false,true).equals("set_power_display 1"));check(QuickTileState.command(false,false).equals("set_power_display 0"));
  System.out.println("Quick settings: confirmed flags, recording guard, independent display, unknown state and explicit commands passed");
 }
}
