package cn.astriafr.acobattery.common;

import java.io.IOException;
import org.json.JSONObject;

/** Tile labels and commands use confirmed backend flags, never a cached UI switch. */
public final class QuickTileState {
    public final boolean enabled,available;public final String subtitle;
    private QuickTileState(boolean enabled,boolean available,String subtitle){this.enabled=enabled;this.available=available;this.subtitle=subtitle;}
    public static QuickTileState read(JSONObject status,boolean game)throws IOException {
        if(status==null||!status.optBoolean("ok"))throw new IOException("后台未连接，请先打开应用。");
        String key=game?"fastEnabled":"powerDisplayEnabled";
        if(!(status.opt(key) instanceof Boolean))throw new IOException("后台状态不可用，请先打开应用完成更新。");
        boolean enabled=(Boolean)status.opt(key);
        if(game&&!enabled&&status.has("recordingEnabled")&&!status.optBoolean("recordingEnabled"))return new QuickTileState(false,false,"请先开启记录");
        String error=game?"":status.optString("powerDisplayError","");
        return new QuickTileState(enabled,true,enabled&&!error.isEmpty()?"显示异常":"");
    }
    public static String command(boolean game,boolean enabled){return(game?"set_fast ":"set_power_display ")+(enabled?1:0);}
}
