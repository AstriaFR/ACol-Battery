package cn.astriafr.acobattery.common;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.json.JSONObject;

public final class StartupDiagnosticsTest {
    static void check(boolean value,String text){if(!value)throw new AssertionError(text);}

    static JSONObject healthy()throws Exception{
        return new JSONObject().put("capabilities",31).put("screenListener",true).put("screenFallback",false)
            .put("screenStateKnown",true).put("cutoffAvailable",true).put("cellCount",2)
            .put("reading",new JSONObject().put("rm",4500).put("pct",80).put("cellCount",2)
                .put("voltage",4000).put("current",-500).put("temp",32).put("voltageSource","bcc").put("currentSource","bcc"));
    }
    static StartupFeedback.Notice find(List<StartupFeedback.Notice> list,String title){for(StartupFeedback.Notice n:list)if(n.title.equals(title))return n;throw new AssertionError(title);}
    static void failureLifecycle()throws Exception{
        File directory=Files.createTempDirectory("startup-failure-").toFile();
        File database=new File(directory,"records.db");byte[] original={1,3,5,7};Files.write(database.toPath(),original);
        try{
            String first=StartupFailure.begin(directory);check(StartupFailure.read(directory,first)==null,"Fresh launch inherited a failure");
            IOException cause=new IOException("SQLiteCantOpenDatabaseException: Is a directory");
            StartupFailure.record(directory,first,cause);StartupFailure.Failure failure=StartupFailure.read(directory,first);
            check(failure!=null&&failure.getMessage().contains("无法打开记录数据库"),"Concrete database cause lost");
            check(failure.diagnostics.contains("Is a directory")&&failure.diagnostics.contains("StartupDiagnosticsTest.java"),"Technical detail or source location missing");
            check(StartupFailure.details(failure).contains("Is a directory"),"Forwarding dropped daemon diagnostics");
            String second=StartupFailure.begin(directory);check(!first.equals(second),"Launch token reused");
            StartupFailure.record(directory,first,new IOException("stale"));check(StartupFailure.read(directory,second)==null,"Stale writer changed current launch");
            StartupFailure.record(directory,second,new UnsatisfiedLinkError("dlopen failed: test.so not found"));
            check(StartupFailure.read(directory,first)==null,"Old launch consumed current failure");
            StartupFailure.clear(directory,first);check(StartupFailure.read(directory,second)!=null,"Old successful launch cleared new failure");
            try{StartupFailure.check(directory,second);throw new AssertionError("Failure was not raised immediately");}
            catch(StartupFailure.Failure e){check(e.diagnostics.contains("UnsatisfiedLinkError"),"Native component failure not preserved");}
            StartupFailure.clear(directory,second);check(StartupFailure.read(directory,second)==null,"Success did not clear the failure");
            Files.write(new File(directory,StartupFailure.REPORT).toPath(),"incomplete json".getBytes("UTF-8"));check(StartupFailure.read(directory,second)==null,"Malformed file escaped the reader");
            check(Arrays.equals(original,Files.readAllBytes(database.toPath())),"Startup status modified the database");
            check(directory.listFiles(f->f.getName().endsWith(".tmp")).length==0,"Atomic report temporary file leaked");
        }finally{for(File file:directory.listFiles())Files.delete(file.toPath());Files.delete(directory.toPath());}
    }
    static void feedback()throws Exception{
        check(StartupFeedback.notices(null,healthy()).isEmpty(),"Healthy startup must not prompt");
        check(StartupFeedback.notices(null,new JSONObject()).isEmpty(),"Missing optional checks must not invent failures");
        JSONObject capacityFallback=healthy();capacityFallback.getJSONObject("reading").put("rmSource","android");
        List<StartupFeedback.Notice> capacityNotices=StartupFeedback.notices(null,capacityFallback);
        check(capacityNotices.size()==1&&find(capacityNotices,"剩余容量使用系统数据").text.contains("CHARGE_COUNTER"),"Capacity fallback must appear in the startup notice");
        check(StartupFeedback.notices(capacityFallback,healthy()).isEmpty(),"Recovered RM must override probe capacity fallback");
        JSONObject diagnosticProbe=healthy().put("nativeDiagnostics","timerfd_create: errno=1 (Operation not permitted)").put("fallbackDiagnostics",new org.json.JSONArray().put("读取 battery_rm\nNoSuchFileException"));
        capacityFallback.put("fallbackDiagnostics",new JSONObject().put("native","wake_lock: errno=13 (Permission denied)").put("entries",new org.json.JSONArray().put("屏幕监听注册\nSecurityException")));
        String fallbackReport=StartupFeedback.downgradeReport(diagnosticProbe,capacityFallback);
        check(fallbackReport.contains("CHARGE_COUNTER")&&fallbackReport.contains("NoSuchFileException")&&fallbackReport.contains("SecurityException")&&fallbackReport.contains("timerfd_create")&&fallbackReport.contains("wake_lock"),"Report must show both probe and backend fallback diagnostics");
        check(!fallbackReport.contains("\n\n")&&fallbackReport.contains("应用可继续使用"),"Fallback report must be compact and permit continued use");
        FallbackDiagnostics.record("重复读取",new java.io.IOException("first"));FallbackDiagnostics.record("重复读取",new java.io.IOException("again"));
        check(FallbackDiagnostics.snapshot().length()==1,"Repeated sampling errors must be deduplicated");
        JSONObject probe=healthy().put("nativeCapabilities",0).put("screenListener",false).put("cutoffAvailable",false);
        check(StartupFeedback.notices(probe,healthy()).isEmpty(),"Real startup status must override recovered probe failures");
        JSONObject status=healthy().put("capabilities",0).put("screenListener",false).put("screenFallback",true)
            .put("cutoffAvailable",false).put("recordingEnabled",false).put("recordingProblem","未读到剩余容量");
        List<StartupFeedback.Notice> notices=StartupFeedback.notices(null,status);
        check(notices.size()==6,"All degraded checks must appear once");
        check(find(notices,"记录已暂停").text.contains("设置"),"Missing readings need manual retry advice");
        check(find(notices,"休眠采样受限").text.contains("不能保证深度休眠"),"Sleep limit must be clear");
        check(find(notices,"截止电压调整不可用").text.contains("跳过"),"Skipped features must be explicit");
        status.put("screenStateKnown",false);check(find(StartupFeedback.notices(null,status),"屏幕监听受限").text.contains("暂时无法确认"),"Unknown state must not promise detection");
        status=healthy();status.getJSONObject("reading").put("voltageSource","android").put("currentSource","android");
        notices=StartupFeedback.notices(null,status);check(notices.size()==1&&notices.get(0).text.contains("电压、电流"),"Merge Android fallbacks");
        status=healthy();status.getJSONObject("reading").put("temp",JSONObject.NULL).put("voltage",JSONObject.NULL);
        check(find(StartupFeedback.notices(null,status),"部分电池数据缺失").text.contains("电压、温度"),"Do not invent zeroes for missing data");
        status=healthy().put("recordingEnabled",false);check(StartupFeedback.notices(null,status).isEmpty(),"Manual stop is not a startup downgrade");
        String report="概要：数据库不可用\n错误代码：旧编号\n--- 诊断输出 ---\nCLASSPATH=private\nat stack.trace\n";
        String text=StartupFeedback.savedSummary(report);check(!text.contains("CLASSPATH")&&!text.contains("stack.trace"),"Legacy report leaked diagnostics");
        String full=StartupFeedback.compactReport(report);check(full.contains("stack.trace")&&!full.contains("旧编号")&&!full.contains("\n\n"),"Full legacy report must remain visible without numbers or blank lines");
        StartupFeedback.Failure failure=StartupFeedback.failure("java.net.SocketTimeoutException: timeout");
        check(failure.reason.contains("超时")&&!failure.reason.contains("Exception"),"Explain timeout in plain language");
        check(StartupFeedback.failure(FriendlyError.message(new java.util.concurrent.TimeoutException())).reason.contains("超时"),"Handshake timeout must retain its cause");
        report=failure.text()+"\n--- 技术详情 ---\nat stack.trace";
        check(StartupFeedback.savedSummary(report).equals(failure.text()),"Saved action must survive reload");
        failure=StartupFeedback.failure("等待 root 授权或初始化超时，请重试。");check(failure.reason.contains("超时"),"Root timeout must stay distinct");
        failure=StartupFeedback.failure("历史 root 目录的签名与当前安装不一致。");check(failure.action.contains("备份"),"Signature mismatch advice must protect records");
        failure=StartupFeedback.failure("系统拒绝访问所需文件，请确认 root 授权与访问权限。");check(failure.reason.contains("拒绝访问"),"File access errors must not be misreported as failed root authorization");
        StackTraceElement shared=new StackTraceElement("Backend","run","Backend.java",12);
        Throwable cause=new java.io.IOException("root read failed"),extra=new java.io.IOException("rollback failed"),parent=new Exception("startup failed",cause);
        parent.setStackTrace(new StackTraceElement[]{shared});cause.setStackTrace(new StackTraceElement[]{new StackTraceElement("Gauge","read","Gauge.java",7),shared});extra.setStackTrace(new StackTraceElement[]{shared});parent.addSuppressed(extra);
        String details=StartupFeedback.technicalDetails(parent,e->"driver output\n\nread failed");
        check(details.contains("原因异常")&&details.contains("附加异常")&&details.contains("Gauge.read(Gauge.java:7)"),"Keep causes, suppressed errors and exact locations");
        check(details.contains("个调用帧与上级相同")&&details.indexOf("driver output")==details.lastIndexOf("driver output"),"Do not repeat shared frames or identical diagnostics");
        check(!details.contains("\n\n"),"No empty lines in technical details");
        Throwable a=new Exception("A"),b=new Exception("B");a.initCause(b);b.initCause(a);
        check(StartupFeedback.technicalDetails(a,e->"").contains("循环引用"),"Cyclic cause chains must terminate");
        check(StartupFeedback.databaseProblem("SQLiteDatabaseCorruptException")&&StartupFeedback.databaseProblem("SQLiteCantOpenDatabaseException"),"Database recovery button missing for supported failures");
        check(!StartupFeedback.databaseProblem("UnsatisfiedLinkError")&&!StartupFeedback.databaseProblem("未能取得 root 权限"),"Unrelated errors must not offer database reset");
    }
    public static void main(String[] args)throws Exception{
        failureLifecycle();feedback();
        System.out.println("Startup diagnostics: launch isolation, preserved causes, downgrade notices and reports passed");
    }
}
