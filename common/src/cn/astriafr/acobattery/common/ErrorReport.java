package cn.astriafr.acobattery.common;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Condense the detail view of current and archived reports. */
public final class ErrorReport {
    private static final String APP_PACKAGE="cn.astriafr.acobattery.";
    private static final Pattern EXCEPTION=Pattern.compile(
        "^(?:(异常|原因异常|附加异常|Caused by|Suppressed)[：:]\\s*)?((?:[\\w$]+\\.)+[A-Z][\\w$]*)(?:[：:]\\s*(.*))?$");
    private static final Pattern SHARED_FRAMES=Pattern.compile("^(?:… \\d+ 个调用帧与上级相同|\\.\\.\\. \\d+ more)$");
    private static final Pattern FORWARDED_FAILURE=Pattern.compile(
        "^异常："+Pattern.quote(APP_PACKAGE)+"(?:RootSession\\$CommandFailure|common\\.StartupFailure\\$Failure)：(?:系统未能完成当前操作，请重试初始化。|初始化命令未能完成，请重试。)$");
    private static final Pattern NESTED_FAILURE=Pattern.compile("\\n诊断输出\\n技术详情\\n(异常：[^\\n]+)");
    private static final Pattern PROBE_STATE=Pattern.compile(
        "^(?:androidSdk|nativeCapabilities|capabilities|cutoffAvailable|cellCount|screenListener|screenFallback|screenStateKnown|screenStateAvailable|screenStateSource|reading\\.(?:onlineKnown|online|rm|pct|fv|driverFull|enabled|source))[：:].*$");
    private static final Pattern EMPTY_FIELD=Pattern.compile("^[a-zA-Z.]+[：:]$");

    private ErrorReport(){}

    public static String concise(String report){
        String compact=StartupFeedback.compactReport(report);
        String[] rows=compact.split("\n");
        boolean startupCheck=("\n"+compact+"\n").contains("\n初始化降级报告\n");
        boolean forwarded=hasNestedCause(compact);
        Set<String> blocks=new LinkedHashSet<>();
        String section="";
        for(int i=0;i<rows.length;i++){
            String line=rows[i].trim();
            int firstFrame=i+1;
            while(i+1<rows.length){
                String next=rows[i+1].trim();
                if(!next.startsWith("at ")&&!SHARED_FRAMES.matcher(next).matches())break;
                i++;
            }
            if(forwarded&&FORWARDED_FAILURE.matcher(line).matches())continue;
            if(line.equals("技术详情")||line.equals("诊断输出")||line.equals("--- 技术详情 ---")||line.equals("--- 诊断输出 ---")
                ||line.equals("ACol-Battery 初始化错误报告")||line.equals("初始化降级报告"))continue;
            if(startupCheck){
                if(line.equals("【接口检查】")||line.equals("【后台连接】")){section=line;continue;}
                if(PROBE_STATE.matcher(line).matches()||line.startsWith("日志说明：")||line.startsWith("应用可继续使用；")
                    ||EMPTY_FIELD.matcher(line).matches())continue;
            }
            String block=shortException(line)+locations(rows,firstFrame,i+1);
            if(blocks.contains(block))continue;
            if(!section.isEmpty()){blocks.add(section);section="";}
            blocks.add(block);
        }
        String summary=String.join("\n",blocks).trim();
        return summary.isEmpty()?compact:summary;
    }

    private static boolean hasNestedCause(String report){
        Matcher nested=NESTED_FAILURE.matcher(report);
        while(nested.find())if(!FORWARDED_FAILURE.matcher(nested.group(1)).matches())return true;
        return false;
    }

    private static String locations(String[] rows,int start,int end){
        String first="",app="";
        for(int i=start;i<end;i++){
            String line=rows[i].trim();
            if(!line.startsWith("at "))continue;
            String frame=line.substring(3);
            if(frame.startsWith(APP_PACKAGE+"common.FallbackDiagnostics.record("))continue;
            if(first.isEmpty())first=frame;
            if(frame.startsWith(APP_PACKAGE)){app=frame;break;}
        }
        if(first.isEmpty())return "";
        String location="\n位置："+shortFrame(first);
        return app.isEmpty()||app.equals(first)?location:location+"\n应用调用："+shortFrame(app);
    }

    private static String shortFrame(String frame){
        if(!frame.startsWith(APP_PACKAGE))return frame;
        int arguments=frame.indexOf('(');
        if(arguments<0)return frame;
        int method=frame.lastIndexOf('.',arguments);
        int owner=frame.lastIndexOf('.',method-1);
        return frame.substring(owner+1);
    }

    private static String shortException(String line){
        Matcher match=EXCEPTION.matcher(line);
        if(!match.matches())return line;
        String label=match.group(1),type=match.group(2),message=match.group(3);
        if(label==null)label="异常";
        else if(label.equals("Caused by"))label="原因异常";
        else if(label.equals("Suppressed"))label="附加异常";
        return label+"："+type.substring(type.lastIndexOf('.')+1)+(message==null||message.isEmpty()?"":" · "+message);
    }
}
