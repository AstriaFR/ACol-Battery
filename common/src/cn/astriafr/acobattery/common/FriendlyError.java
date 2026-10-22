package cn.astriafr.acobattery.common;

import java.util.Locale;

/** Keep stack traces in diagnostics; expose causes as short Chinese sentences. */
public final class FriendlyError {
    private FriendlyError(){}
    public static String message(Throwable error){
        StringBuilder all=new StringBuilder();Throwable current=error;
        for(int i=0;i<8&&current!=null;i++,current=current.getCause())all.append(current.getClass().getName()).append(": ").append(current.getMessage()).append('\n');
        return fromOutput(all.toString(),"系统未能完成当前操作，请重试初始化。");
    }
    public static String fromOutput(String output,String fallback){
        if(output==null||output.trim().isEmpty())return fallback;
        String lower=output.toLowerCase(Locale.ROOT);
        if(lower.contains("无法启动 root 授权入口"))return "无法启动 root 授权入口，请在 root 管理器中允许本应用使用 su。";
        if(lower.contains("sqlitecorrupt")||lower.contains("database disk image is malformed"))return "记录数据库已损坏，请先备份记录，再重新初始化数据库。";
        if(lower.contains("no such column")||lower.contains("no such table"))return "记录数据库结构与当前版本不匹配，需要重新初始化数据库。";
        if(lower.contains("sqlitecantopen")||lower.contains("unable to open database"))return "无法打开记录数据库，请确认 root 目录可以访问。";
        if(lower.contains("sqlitediskio")||lower.contains("disk i/o error"))return "读写记录数据库时发生存储错误，请检查可用空间和目录权限。";
        if(lower.contains("database is locked")||lower.contains("sqlitedatabaselocked"))return "记录数据库正被另一个后台进程使用，请稍后重试。";
        if(lower.contains("enospc")||lower.contains("no space left"))return "手机存储空间不足，无法写入后台程序或记录。";
        if(lower.contains("permission denied")||lower.contains("eacces")||lower.contains("operation not permitted"))return "系统拒绝访问所需文件，请确认 root 授权与访问权限。";
        if(lower.contains("unsatisfiedlinkerror")||lower.contains("dlopen failed"))return "无法加载底层采样组件，设备架构或系统库可能不兼容。";
        if(lower.contains("noclassdeffound")||lower.contains("classnotfound")||lower.contains("could not find class"))return "后台程序文件不完整，请重新初始化以部署程序。";
        if(lower.contains("nosuchmethod")||lower.contains("nosuchfield")||lower.contains("no enum constant"))return "当前系统缺少所需接口，这项功能暂无法初始化。";
        if(lower.contains("syntax error")||lower.contains("unexpected token")||lower.contains("unknown option"))return "初始化脚本与当前命令环境不兼容，请更新 App 后重试。";
        if(lower.contains("sockettimeout")||lower.contains("etimedout"))return "记录服务响应超时，请稍后重试。";
        if(lower.contains("econnrefused")||lower.contains("connect failed"))return "记录服务尚未就绪，请重新初始化。";
        if(lower.contains("broken pipe")||lower.contains("epipe")||lower.contains("socket closed"))return "与记录服务的连接已中断，请重新打开 App。";
        if(lower.contains("jsonexception"))return "后台返回的数据格式不正确，请重新初始化。";
        if(lower.contains("no such file")||lower.contains("enoent")||lower.contains("not found"))return "所需运行文件未找到，请重新初始化以部署后台程序。";
        for(String line:output.split("\\n")){
            String text=line.trim();int separator=text.indexOf(": ");
            if(separator>=0&&text.substring(0,separator).matches("[a-zA-Z0-9_.$]+(?:Exception|Error|Failure)"))text=text.substring(separator+2).trim();
            if(text.length()>0&&text.length()<200&&text.matches("^[\\p{IsHan}].*")&&!text.contains("{")&&!text.contains("}")&&!text.contains("CLASSPATH")&&!text.startsWith("异常堆栈"))return text;
        }
        return fallback;
    }
}
