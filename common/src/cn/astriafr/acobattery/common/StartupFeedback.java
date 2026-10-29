package cn.astriafr.acobattery.common;

import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.function.Function;

/** Readable startup failures, compact complete reports and optional-capability notices. */
public final class StartupFeedback {
    private StartupFeedback(){}
    public static final class Failure {
        public final String reason,action;
        Failure(String reason,String action){this.reason=reason;this.action=action;}
        public String text(){return "原因："+reason+"\n建议："+action;}
    }
    public static Failure failure(String raw){
        String reason=FriendlyError.fromOutput(raw,"");
        String action="重试；仍然失败时，复制错误报告以便排查。";
        if(raw!=null){
            if(raw.contains("需要 Android 15"))return new Failure("当前 Android 版本不受支持。","请使用 Android 15 或更新系统。");
            if(raw.contains("不支持 ARM64"))return new Failure("当前设备架构不受支持。","请在 ARM64 设备上使用本应用。");
            if(raw.contains("未获得 root")||raw.contains("无法启动 root 授权")||raw.contains("等待 root 授权")||raw.contains("root 授权被拒绝"))return new Failure(raw.contains("超时")?"等待 root 授权超时。":"未能取得 root 权限。","在 root 管理器中允许 ACol 电池访问，然后重试。");
            if(raw.contains("签名")&&raw.contains("不一致"))return new Failure("已有数据目录与当前应用的签名不一致。","安装相同签名的安装包；重建目录前请先备份记录。");
        }
        if(reason.isEmpty()||reason.equals("系统未能完成当前操作，请重试初始化。")||reason.equals("初始化命令未能完成，请重试。")){
            reason="初始化未能完成，请查看错误报告。";
        }
        // Keep advice separate instead of repeating it inside a long exception message.
        int split=reason.indexOf("，请");if(split>=0){action=reason.substring(split+1);reason=reason.substring(0,split)+"。";}
        else{split=reason.indexOf("请");if(split>0){action=reason.substring(split);reason=reason.substring(0,split).trim();}}
        return new Failure(reason,action);
    }
    /** Compact old saved files too; the retired number is never displayed or copied. */
    public static String compactReport(String report){
        StringBuilder out=new StringBuilder();
        for(String line:report.replace("\r\n","\n").replace('\r','\n').split("\n")){
            String text=line.trim();if(text.isEmpty()||text.matches("错误(?:代码|编号)[：:].*"))continue;
            if(out.length()>0)out.append('\n');out.append(line.replaceAll("\\s+$",""));
        }
        return out.toString();
    }
    public static String savedSummary(String report){
        String reason="",action="";
        for(String line:report.split("\\n")){
            if(line.contains("技术详情")||line.contains("诊断输出"))break;
            if(line.startsWith("概要："))reason=line.substring(3).trim();
            if(line.startsWith("原因："))reason=line.substring(3).trim();
            if(line.startsWith("建议："))action=line.substring(3).trim();
        }
        return action.isEmpty()?failure(reason).text():new Failure(reason,action).text();
    }
    public static boolean databaseProblem(String report){
        String text=report.toLowerCase(java.util.Locale.ROOT);
        return text.contains("sqlitedatabasecorrupt")||text.contains("sqlitecantopen")||text.contains("database disk image is malformed")
            ||text.contains("no such column")||text.contains("no such table")||text.contains("记录数据库已损坏")
            ||text.contains("数据库重建未完成")||text.contains("阶段：重建数据库");
    }
    /** Preserve causes, suppressed failures and diagnostics without repeating shared frames. */
    public static String technicalDetails(Throwable error,Function<Throwable,String> diagnostics){
        StringBuilder out=new StringBuilder("技术详情\n");
        Set<Throwable> seen=Collections.newSetFromMap(new IdentityHashMap<Throwable,Boolean>());
        Set<String> output=new LinkedHashSet<>();
        appendError(out,error,null,"异常",seen,output,diagnostics);
        if(!output.isEmpty()){
            out.append("诊断输出\n");for(String text:output)out.append(text).append('\n');
        }
        return compactReport(out.toString());
    }
    static void appendError(StringBuilder out,Throwable error,StackTraceElement[] enclosing,String label,
            Set<Throwable> seen,Set<String> output,Function<Throwable,String> diagnostics){
        if(error==null)return;
        if(!seen.add(error)){out.append(label).append("：已在上文列出（循环引用）\n");return;}
        out.append(label).append("：").append(error.getClass().getName());
        String message=error.getMessage();if(message!=null&&!message.trim().isEmpty())out.append("：").append(compactReport(message));out.append('\n');
        StackTraceElement[] frames=error.getStackTrace();int unique=frames.length;
        if(enclosing!=null){int parent=enclosing.length-1;while(unique>0&&parent>=0&&frames[unique-1].equals(enclosing[parent])){unique--;parent--;}}
        for(int i=0;i<unique;i++)out.append("  at ").append(frames[i]).append('\n');
        if(unique<frames.length)out.append("  … ").append(frames.length-unique).append(" 个调用帧与上级相同\n");
        String text=diagnostics.apply(error);if(text!=null){text=compactReport(text);if(!text.isEmpty())output.add(text);}
        for(Throwable extra:error.getSuppressed())appendError(out,extra,frames,"附加异常",seen,output,diagnostics);
        appendError(out,error.getCause(),frames,"原因异常",seen,output,diagnostics);
    }
    public static final class Notice {
        public final String title,text;
        Notice(String title,String text){this.title=title;this.text=text;}
    }
    static JSONObject merge(JSONObject probe,JSONObject status){
        JSONObject result=new JSONObject();
        for(JSONObject source:new JSONObject[]{probe,status})if(source!=null){
            java.util.Iterator<String> keys=source.keys();while(keys.hasNext()){String key=keys.next();try{result.put(key,source.get(key));}catch(Exception ignored){}}
        }
        return result;
    }
    public static String downgradeReport(JSONObject probe,JSONObject status){
        StringBuilder out=new StringBuilder("初始化降级报告\n应用可继续使用；关闭报告不会停止后台或取消已启用的回退。\n");
        for(Notice notice:notices(probe,status))out.append(notice.title).append("：").append(notice.text).append('\n');
        appendDiagnostics(out,"接口检查",probe);appendDiagnostics(out,"后台连接",status);
        out.append("日志说明：相同操作的同类错误仅保留首次，最多保存最近 32 项；单项过长时标明截断。\n");
        return compactReport(out.toString());
    }
    static void appendDiagnostics(StringBuilder out,String stage,JSONObject data){
        if(data==null)return;out.append("【").append(stage).append("】\n");
        for(String key:new String[]{"androidSdk","nativeCapabilities","capabilities","cutoffAvailable","cellCount","recordingProblem","screenListener","screenFallback","screenStateKnown","screenStateSource","screenError","usageError","error","diagnosticsError"})
            if(data.has(key))out.append(key).append("：").append(data.opt(key)).append('\n');
        JSONObject reading=data.optJSONObject("reading");
        if(reading!=null)for(String key:new String[]{"rmSource","rm","pct","voltageSource","currentSource","source","error"})
            if(reading.has(key))out.append("reading.").append(key).append("：").append(reading.opt(key)).append('\n');
        if(!data.optString("nativeDiagnostics").isEmpty())out.append(data.optString("nativeDiagnostics")).append('\n');
        Object logs=data.opt("fallbackDiagnostics");
        org.json.JSONArray entries=logs instanceof org.json.JSONArray?(org.json.JSONArray)logs:logs instanceof JSONObject?((JSONObject)logs).optJSONArray("entries"):null;
        if(logs instanceof JSONObject&&!((JSONObject)logs).optString("native").isEmpty())out.append(((JSONObject)logs).optString("native")).append('\n');
        if(entries!=null)for(int i=0;i<entries.length();i++)out.append(entries.optString(i)).append('\n');
    }
    public static List<Notice> notices(JSONObject probe,JSONObject status){
        JSONObject data=merge(probe,status),reading=data.optJSONObject("reading");List<Notice> out=new ArrayList<>();
        String problem=data.optString("recordingProblem","");
        if(problem.isEmpty()&&reading!=null)problem=RecordingGate.problem(reading.optDouble("rm",Double.NaN),reading.optDouble("pct",Double.NaN));
        if(!problem.isEmpty())out.add(new Notice("记录已暂停",problem+"。请在设置中开启记录总开关，届时会重新检查。"));
        int caps=data.has("capabilities")?data.optInt("capabilities"):data.optInt("nativeCapabilities",-1);
        if(caps>=0){
            if((caps&1)==0)out.add(new Notice("休眠采样受限","已改用普通定时等待，不能保证深度休眠期间准时采样。"));
            if((caps&2)==0)out.add(new Notice("电源监听受限","已改用采样时检查，插拔充电器的识别可能延迟。"));
            if((caps&4)==0)out.add(new Notice("唤醒锁不可用","将继续尝试采样，休眠期间的读取可能中断。"));
        }
        if(data.optBoolean("screenFallback")||(data.has("screenListener")&&!data.optBoolean("screenListener")))
            out.add(new Notice("屏幕监听受限",data.has("screenStateKnown")&&!data.optBoolean("screenStateKnown")?"暂时无法确认亮息屏状态，将保留已知状态并继续尝试读取。":"已改用广播和状态检测，亮息屏切换的识别可能延迟。"));
        else if(!data.optString("screenError").isEmpty())out.add(new Notice("屏幕通知受限","亮息屏切换的识别可能延迟。"));
        if(data.has("cutoffAvailable")&&!data.optBoolean("cutoffAvailable"))out.add(new Notice("截止电压调整不可用","未找到所需接口，已跳过此功能，其他功能可继续使用。"));
        int cells=data.has("cellCount")?data.optInt("cellCount"):reading==null?-1:reading.optInt("cellCount",-1);
        if(cells==0)out.add(new Notice("电芯数量待确认","将继续自动识别，部分电流和功率数据暂不可用。"));
        if(reading!=null){
            if("android".equals(reading.optString("rmSource")))out.add(new Notice("剩余容量使用系统数据","未读到 battery_rm，已改用 Android 剩余容量（CHARGE_COUNTER），用于容量变化、耗电统计和剩余能量估算。"));
            boolean voltage="android".equals(reading.optString("voltageSource")),current="android".equals(reading.optString("currentSource"));
            if(voltage||current)out.add(new Notice("部分读数使用系统数据",(voltage&&current?"电压、电流":voltage?"电压":"电流")+"未取得优先底层读数，已使用 Android 系统数据。"));
            String missing="";for(String[] item:new String[][]{{"voltage","电压"},{"current","电流"},{"temp","温度"}})
                if(reading.has(item[0])&&(!Double.isFinite(reading.optDouble(item[0],Double.NaN))))missing+=(missing.isEmpty()?"":"、")+item[1];
            if(!missing.isEmpty())out.add(new Notice("部分电池数据缺失",missing+"暂时无法读取，对应数据会保留缺口。"));
        }
        if(!data.optString("usageError").isEmpty())out.add(new Notice("应用统计暂不可用","当前无法读取应用使用情况，统计可能不完整，后台会继续重试。"));
        return out;
    }
}
