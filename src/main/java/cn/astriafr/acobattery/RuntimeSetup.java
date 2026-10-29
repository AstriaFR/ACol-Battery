package cn.astriafr.acobattery;

import cn.astriafr.acobattery.common.RuntimePayload;
import java.io.File;

/** Commands use Android's tools, not a particular root manager's utilities. */
final class RuntimeSetup {
    static final String BASE="/data/adb/aco_battery_app",RUNTIME=BASE+"/runtime",STAGE=RUNTIME+"/update-stage";
    static final String BOOT="/data/adb/service.d/aco_battery_app.sh";
    private RuntimeSetup(){}
    static String quote(String value){return "'"+value.replace("'","'\"'\"'")+"'";}
    static String stage(File source){
        StringBuilder command=new StringBuilder("umask 077; /system/bin/mkdir -p "+STAGE+"/bin; ");
        for(String name:RuntimePayload.FILES)command.append("/system/bin/cp ").append(quote(new File(source,name).getAbsolutePath())).append(' ').append(quote(STAGE+"/"+name)).append("; ");
        return command.append(permissions(STAGE)).toString();
    }
    static String permissions(String directory){
        return "/system/bin/chmod 700 "+directory+" "+directory+"/bin "+directory+"/bin/aco-launcher "+directory+"/boot.sh "+directory+"/service.sh "+directory+"/cutoff.sh "+directory+"/transfer.sh; /system/bin/chmod 600 "+directory+"/bin/daemon.dex.jar "+directory+"/bin/libcurvewait.so";
    }
    static String install(String version){
        return "CLASSPATH="+STAGE+"/bin/daemon.dex.jar /system/bin/app_process /system/bin cn.astriafr.acobattery.daemon.Admin apply-update "+quote(version)+" \"$$\"";
    }
    static String recover(){
        return "if [ -f "+BASE+"/update.transaction ]; then aco_recovery="+STAGE+"/bin/daemon.dex.jar; [ -f \"$aco_recovery\" ] || aco_recovery="+RUNTIME+"/bin/daemon.dex.jar; CLASSPATH=\"$aco_recovery\" /system/bin/app_process /system/bin cn.astriafr.acobattery.daemon.Admin recover-update; fi";
    }
    static String cleanStage(){
        StringBuilder command=new StringBuilder("/system/bin/rm -f");
        for(String name:RuntimePayload.FILES)command.append(' ').append(quote(STAGE+"/"+name));
        return command.append("; /system/bin/rmdir "+STAGE+"/bin "+STAGE+" 2>/dev/null || true").toString();
    }
    static String installBoot(){
        // KernelSU, Magisk and APatch share this executable late-start entry point.
        return "/system/bin/mkdir -p /data/adb/service.d; /system/bin/cp "+RUNTIME+"/boot.sh "+RUNTIME+"/boot.entry.next; /system/bin/chmod 700 "+RUNTIME+"/boot.entry.next; /system/bin/mv -f "+RUNTIME+"/boot.entry.next "+BOOT;
    }
}
