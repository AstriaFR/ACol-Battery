package cn.astriafr.acobattery;

import cn.astriafr.acobattery.common.RuntimePayload;
import android.content.Context;
import android.content.pm.PackageManager;
import java.io.File;

/** Commands use Android's tools, not a particular root manager's utilities. */
final class RuntimeSetup {
    static final String BASE="/data/adb/aco_battery_app",RUNTIME=BASE+"/runtime",STAGE=RUNTIME+"/update-stage";
    private RuntimeSetup(){}
    static String quote(String value){return "'"+value.replace("'","'\"'\"'")+"'";}
    static String ownerCheck(Context context,boolean claim)throws Exception{
        String certificate=StartupController.digest(context.getPackageManager()
            .getPackageInfo(context.getPackageName(),PackageManager.GET_SIGNING_CERTIFICATES)
            .signingInfo.getApkContentsSigners()[0].toByteArray());
        String user=Integer.toString(android.os.Process.myUid()/100000);
        String userFile=BASE+"/owner.user",certFile=BASE+"/owner.cert";
        String check=checkOwnerFile(userFile,user,"历史记录属于另一 Android 用户，无法连接后台。")
            +checkOwnerFile(certFile,certificate,"历史目录签名不一致，已停止初始化。");
        if(!claim)return check+"true";
        // Claim the user first and never overwrite another concurrent claim.
        return "umask 077; mkdir -p "+BASE+"; chmod 700 "+BASE+"; "+check
            +claimOwnerFile(userFile,user)+checkOwnerFile(userFile,user,"历史记录属于另一 Android 用户，无法连接后台。")
            +claimOwnerFile(certFile,certificate)+check+"true";
    }
    private static String checkOwnerFile(String file,String expected,String message){
        return "if [ -e "+file+" ] && [ \"$(cat "+file+")\" != "+quote(expected)+" ]; then echo "+quote(message)+"; exit 1; fi; ";
    }
    private static String claimOwnerFile(String file,String value){
        return "if [ ! -e "+file+" ]; then (set -C; printf '%s' "+quote(value)+" > "+file+") 2>/dev/null || [ -s "+file+" ]; fi; ";
    }
    static String hasOwner(){return "[ -s "+BASE+"/owner.user ] && [ -s "+BASE+"/owner.cert ]";}
    static String stage(File source){
        StringBuilder command=new StringBuilder(
            "(aco_compare() { if [ -x /system/bin/cmp ]; then /system/bin/cmp -s \"$1\" \"$2\"; "
            +"else /system/bin/toybox cmp -s \"$1\" \"$2\"; fi; }; "
            +"umask 077 && [ ! -e "+BASE+"/update.transaction ] && /system/bin/mkdir -p "+STAGE+"/bin");
        for(String name:RuntimePayload.FILES){
            String from=quote(new File(source,name).getAbsolutePath()),to=quote(STAGE+"/"+name);
            command.append(" && /system/bin/cp ").append(from).append(' ').append(to)
                .append(" && aco_compare ").append(from).append(' ').append(to);
        }
        return command.append(" && ").append(permissions(STAGE))
            .append(") || { echo '无法完整复制并校验后台安装文件。'; false; }").toString();
    }
    static String permissions(String directory){
        return "/system/bin/chmod 700 "+directory+" "+directory+"/bin "+directory+"/bin/aco-launcher "+directory+"/boot.sh "+directory+"/service.sh "+directory+"/transfer.sh && /system/bin/chmod 600 "+directory+"/bin/daemon.dex.jar "+directory+"/bin/libcurvewait.so";
    }
    static String install(String version,String contentsDigest){
        return "CLASSPATH="+STAGE+"/bin/daemon.dex.jar /system/bin/app_process /system/bin cn.astriafr.acobattery.daemon.Admin apply-update "+quote(version)+" \"$$\" "+quote(contentsDigest);
    }
    static String recover(){return recover(null);}
    static String recover(File verifiedJar){
        String journal=BASE+"/update.transaction",jar="/bin/daemon.dex.jar";
        String candidates=verifiedJar==null?STAGE+jar+" "+RUNTIME+jar+" "+BASE+"/runtime.rollback"+jar:quote(verifiedJar.getAbsolutePath());
        return "(if [ ! -f "+journal+" ]; then exit 0; fi; "
            +"for aco_recovery in "+candidates+"; do "
            +"[ -s \"$aco_recovery\" ] || continue; "
            +"CLASSPATH=\"$aco_recovery\" /system/bin/app_process /system/bin cn.astriafr.acobattery.daemon.Admin recover-update && exit 0; "
            +"[ -f "+journal+" ] || exit 0; done; exit 1)";
    }
    static String cleanStage(){
        StringBuilder command=new StringBuilder("/system/bin/rm -f");
        for(String name:RuntimePayload.FILES)command.append(' ').append(quote(STAGE+"/"+name));
        return command.append("; /system/bin/rmdir "+STAGE+"/bin "+STAGE+" 2>/dev/null || true").toString();
    }
}
