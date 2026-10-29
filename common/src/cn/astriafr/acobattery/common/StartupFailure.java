package cn.astriafr.acobattery.common;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.UUID;
import org.json.JSONObject;

/** A current launch's fatal cause, available even before the daemon socket exists. */
public final class StartupFailure {
    public static final String ATTEMPT="startup.attempt",REPORT="startup.failure";
    private StartupFailure(){}
    public static final class Failure extends IOException {
        public final String diagnostics;
        Failure(String message,String diagnostics){super(message);this.diagnostics=diagnostics;}
    }
    static void write(File file,String text)throws IOException{
        File temporary=File.createTempFile("startup-",".tmp",file.getParentFile());
        try{
            Files.write(temporary.toPath(),text.getBytes(StandardCharsets.UTF_8));
            Files.move(temporary.toPath(),file.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        }finally{Files.deleteIfExists(temporary.toPath());}
    }
    public static String attempt(File directory){
        try{return new String(Files.readAllBytes(new File(directory,ATTEMPT).toPath()),StandardCharsets.UTF_8).trim();}
        catch(IOException ignored){return "";}
    }
    public static String begin(File directory)throws IOException{
        Files.deleteIfExists(new File(directory,REPORT).toPath());
        String token=UUID.randomUUID().toString();write(new File(directory,ATTEMPT),token);return token;
    }
    public static void record(File directory,String token,Throwable error)throws Exception{
        if(token.isEmpty()||!token.equals(attempt(directory)))return;
        JSONObject report=new JSONObject().put("attempt",token).put("message",FriendlyError.message(error))
            .put("diagnostics",details(error));
        write(new File(directory,REPORT),report.toString());
    }
    public static Failure read(File directory,String token){
        if(token.isEmpty())return null;
        File file=new File(directory,REPORT);
        try{
            if(!file.isFile()||file.length()>262144)return null;
            JSONObject report=new JSONObject(new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8));
            if(!token.equals(attempt(directory))||!token.equals(report.optString("attempt")))return null;
            return new Failure(report.optString("message","记录服务启动失败。"),report.optString("diagnostics"));
        }catch(Exception unavailable){return null;}
    }
    public static void check(File directory,String token)throws Failure{Failure failure=read(directory,token);if(failure!=null)throw failure;}
    public static void clear(File directory,String token)throws IOException{
        if(!token.isEmpty()&&token.equals(attempt(directory)))Files.deleteIfExists(new File(directory,REPORT).toPath());
    }
    public static String details(Throwable error){
        return StartupFeedback.technicalDetails(error,e->e instanceof Failure?((Failure)e).diagnostics:"");
    }
    public static void print(Throwable error,PrintStream output){output.println(details(error));output.flush();}
}
