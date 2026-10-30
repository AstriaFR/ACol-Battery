package cn.astriafr.acobattery.daemon;

import java.io.File;
import java.util.*;
import java.util.function.Function;
import java.util.function.Supplier;

/** Read external power supplies only; battery/parallel and device-scope outputs are not inputs. */
final class PowerInputs {
    static final class Result {
        final boolean online,systemFallback;
        Result(boolean online,boolean systemFallback){this.online=online;this.systemFallback=systemFallback;}
    }
    private final File directory;
    private final Function<String,String> read;
    private List<File> nodes=Collections.emptyList();
    private long nextScan;
    PowerInputs(File directory,Function<String,String> read){this.directory=directory;this.read=read;}
    synchronized void invalidate(){nextScan=0;}
    private void discover(){
        Set<File> selected=new LinkedHashSet<>();
        // Preserve existing ROMs whose standard input does not expose a type file.
        for(String name:new String[]{"usb","wireless","wls","ac","dc","dock"}){
            File supply=new File(directory,name);
            if(!"Device".equals(read.apply(new File(supply,"scope").getPath())))selected.add(new File(supply,"online"));
        }
        File[] supplies=directory.listFiles();
        if(supplies!=null)for(File supply:supplies){
            if("Device".equals(read.apply(new File(supply,"scope").getPath())))continue;
            String type=read.apply(new File(supply,"type").getPath());
            if(type.equals("USB")||type.startsWith("USB_")||type.equals("Mains")||type.equals("UPS")||type.equals("Wireless")||type.equals("Dock"))selected.add(new File(supply,"online"));
        }
        nodes=new ArrayList<>(selected);nextScan=System.nanoTime()+60000000000L;
    }
    synchronized Result online(Supplier<Boolean> systemFallback){
        if(nextScan==0||System.nanoTime()>=nextScan)discover();
        boolean known=false,invalid=false;
        for(File node:nodes){
            if(!node.isFile())continue;
            String value=read.apply(node.getPath());
            if("1".equals(value))return new Result(true,false);
            if("0".equals(value))known=true;else invalid=true;
        }
        // Unreadable inputs are unknown, rather than evidence of unplugging.
        if(invalid||!known){
            Boolean value=systemFallback==null?null:systemFallback.get();
            if(value!=null)return new Result(value,true);
            throw new IllegalStateException("充电连接状态读取失败，停止本次采样以免错误划分记录");
        }
        return new Result(false,false);
    }
}
