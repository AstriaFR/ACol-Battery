package cn.astriafr.acobattery.daemon;

import java.io.File;
import java.util.*;
import java.util.function.Supplier;

/** External inputs only. Unknown is never evidence of unplugging. */
final class PowerInputs {
    interface Reader { String read(String path)throws Exception; }
    static final class Result {
        final boolean online,known,systemFallback;
        final String source,diagnostics;
        Result(boolean online,boolean fallback){this(online,true,fallback?"android":"sysfs","");}
        Result(boolean online,boolean known,String source,String diagnostics){
            this.online=online;this.known=known;this.source=source;this.diagnostics=diagnostics;
            systemFallback=known&&!source.equals("sysfs");
        }
        static Result unknown(String details){return new Result(false,false,"unavailable",details);}
    }
    private final File directory;private final Reader read;
    private List<File> supplies=Collections.emptyList();private long nextScan;
    PowerInputs(File directory,Reader read){this.directory=directory;this.read=read;}
    synchronized void invalidate(){nextScan=0;}
    private String value(File node,StringBuilder details){
        try{String value=read.read(node.getPath());return value==null?"":value.trim();}
        catch(Exception error){if(details!=null)details.append(node).append(": ").append(error.getClass().getSimpleName()).append(": ").append(error.getMessage()).append('\n');return "";}
    }
    private void discover(){
        Set<File> selected=new LinkedHashSet<>();
        for(String name:new String[]{"usb","wireless","wls","ac","dc","dock"}){
            File supply=new File(directory,name);if(supply.isDirectory())selected.add(supply);
        }
        File[] entries=directory.listFiles();if(entries!=null)for(File supply:entries){
            String type=value(new File(supply,"type"),null);
            if(type.equals("USB")||type.startsWith("USB_")||type.equals("Mains")||type.equals("UPS")||type.equals("Wireless")||type.equals("Dock"))selected.add(supply);
        }
        selected.removeIf(supply->"Device".equalsIgnoreCase(value(new File(supply,"scope"),null)));
        supplies=new ArrayList<>(selected);nextScan=System.nanoTime()+60000000000L;
    }
    static Boolean parseOnline(String value){
        if("0".equals(value))return false;
        return "1".equals(value)||"2".equals(value)?Boolean.TRUE:null;
    }
    synchronized Result online(Supplier<Boolean> fallback){
        return resolve(()->{Boolean value=fallback==null?null:fallback.get();return value==null?Result.unknown("系统回退未返回有效连接状态"):new Result(value,true);});
    }
    synchronized Result resolve(Supplier<Result> fallback){
        if(nextScan==0||System.nanoTime()>=nextScan)discover();
        boolean known=false,invalid=false;StringBuilder details=new StringBuilder();
        if(supplies.isEmpty())details.append("未发现可读取的外部供电节点：").append(directory).append('\n');
        for(File supply:supplies){
            File node=new File(supply,"online");String raw=value(node,details);Boolean on=parseOnline(raw);
            details.append(node).append(" = ").append(raw.isEmpty()?"<空或不可读>":raw.substring(0,Math.min(80,raw.length()))).append('\n');
            if(Boolean.TRUE.equals(on))return new Result(true,false);
            if(on==null)invalid=true;else known=true;
        }
        if(known&&!invalid)return new Result(false,false);
        nextScan=Math.min(nextScan,System.nanoTime()+5000000000L);
        Result result;
        try{result=fallback==null?null:fallback.get();}
        catch(Exception failure){details.append("系统回退异常：").append(failure).append('\n');result=null;}
        if(result!=null){details.append(result.diagnostics);if(result.known)return new Result(result.online,true,result.source,limit(details));}
        return Result.unknown(limit(details));
    }
    private static String limit(StringBuilder text){return text.substring(0,Math.min(4096,text.length()));}
}
