package cn.astriafr.acobattery.common;

import java.io.*;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;

/** Missing startup readings pause recording until the user explicitly retries. */
public final class RecordingGate {
    public static final String DISABLED="recording.disabled",PROBLEM="recording.readings-blocked";
    private RecordingGate(){}
    public static String problem(double rm,double pct){
        boolean missingRm=!Double.isFinite(rm)||rm<0,missingPct=!Double.isFinite(pct)||pct<0||pct>100;
        return missingRm&&missingPct?"未读到剩余容量和系统电量":missingRm?"未读到剩余容量":missingPct?"未读到系统电量":"";
    }
    public static void pause(File data,String reason)throws IOException{
        File flag=new File(data,DISABLED);if(!flag.exists()&&!flag.createNewFile())throw new IOException("停止状态保存失败。");
        Files.write(new File(data,PROBLEM).toPath(),reason.getBytes(StandardCharsets.UTF_8));
    }
    public static void enable(File data,double rm,double pct)throws IOException{
        String reason=problem(rm,pct);
        if(!reason.isEmpty()){pause(data,reason);throw new IOException(reason+"，记录保持关闭，请稍后再开启。");}
        Files.deleteIfExists(new File(data,PROBLEM).toPath());
        Files.deleteIfExists(new File(data,DISABLED).toPath());
    }
}
