package cn.astriafr.acobattery.common;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.LinkedHashMap;
import org.json.JSONArray;

/** Bounded first-occurrence diagnostics; repeated sampling failures do not grow logs. */
public final class FallbackDiagnostics {
    private static final LinkedHashMap<String,String> entries=new LinkedHashMap<>();
    private static java.util.function.BiConsumer<String,String> sink;
    private FallbackDiagnostics(){}
    public static synchronized void record(String operation,Throwable error){
        String key=operation+"\n"+error.getClass().getName();if(entries.containsKey(key))return;
        StringWriter trace=new StringWriter();error.printStackTrace(new PrintWriter(trace));
        String detail=operation+"\n"+trace.toString().trim();
        if(detail.length()>2048)detail=detail.substring(0,2048)+"\n（此项堆栈已截断）";
        if(entries.size()>=32)entries.remove(entries.keySet().iterator().next());
        entries.put(key,detail);
        if(sink!=null)try{sink.accept(operation,detail);}catch(Exception ignored){}
    }
    public static synchronized void record(String operation,String message){
        if(!entries.containsKey(operation+"\n"+IllegalStateException.class.getName()))record(operation,new IllegalStateException(message));
    }
    public static synchronized JSONArray snapshot(){return new JSONArray(entries.values());}
    public static synchronized void setSink(java.util.function.BiConsumer<String,String> output){
        sink=output;if(sink!=null)for(String detail:entries.values())try{sink.accept(detail.split("\n",2)[0],detail);}catch(Exception ignored){}
    }
}
