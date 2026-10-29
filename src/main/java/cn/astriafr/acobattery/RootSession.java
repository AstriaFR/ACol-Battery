package cn.astriafr.acobattery;

import org.json.JSONObject;
import cn.astriafr.acobattery.common.FriendlyError;
import cn.astriafr.acobattery.common.OperationTimeout;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** One foreground su session; its lifetime never controls the detached daemon. */
final class RootSession implements Closeable {
    static final class CommandFailure extends IOException {
        final String diagnostics;
        CommandFailure(String output){super(FriendlyError.fromOutput(output,"初始化命令未能完成，请重试。"));diagnostics=output;}
        CommandFailure(String message,String diagnostics){super(message);this.diagnostics=diagnostics;}
    }
    private Process process;
    private BufferedWriter input;
    private final BlockingQueue<String> output=new LinkedBlockingQueue<>(512);
    private volatile boolean closed;
    private final ConcurrentHashMap<String,CompletableFuture<JSONObject>> nativeResults=new ConcurrentHashMap<>();
    private static final String EOF="__ACO_ROOT_EOF__";
    static String quote(String text){return "'"+text.replace("'","'\"'\"'")+"'";}
    static Process openRootShell()throws IOException{
        IOException failure=null;
        // Let the root manager's PATH select its own su first. Fall back only if
        // the executable cannot be started, never after a denied authorization.
        for(String executable:new String[]{"su","/system/bin/su","/system/xbin/su","/sbin/su"}){
            try{return new ProcessBuilder(executable).redirectErrorStream(true).start();}
            catch(IOException unavailable){failure=unavailable;}
        }
        throw new IOException("无法启动 root 授权入口，请在 root 管理器中允许本应用使用 su。",failure);
    }
    RootSession()throws IOException{this(openRootShell());}
    RootSession(Process process)throws IOException{
        this.process=process;
        input=new BufferedWriter(new OutputStreamWriter(process.getOutputStream(),StandardCharsets.UTF_8));
        Thread reader=new Thread(()->{
            try(BufferedReader in=new BufferedReader(new InputStreamReader(process.getInputStream(),StandardCharsets.UTF_8))){String line;while((line=in.readLine())!=null&&!closed)output.put(line);}
            catch(Exception ignored){}
            finally{output.offer(EOF);}
        },"aco-root-output");reader.setDaemon(true);reader.start();
    }
    private synchronized void write(String text)throws IOException{if(closed)throw new IOException("root 连接已关闭。");input.write(text);input.flush();}
    String command(String command,int timeoutSeconds)throws Exception{
        String marker="__ACO_END_"+java.util.UUID.randomUUID().toString().replace("-","")+"_";
        write("( set -e; "+command+"; ); aco_rc=$?; printf '\\n"+marker+"%s\\n' \"$aco_rc\"\n");
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(Math.max(1,Math.min(OperationTimeout.MAX_SECONDS,timeoutSeconds)));StringBuilder result=new StringBuilder();
        while(!closed){
            long remaining=until-System.nanoTime();if(remaining<=0)throw new IOException("等待 root 授权或初始化超时，请授权后重试。");
            String line=output.poll(remaining,TimeUnit.NANOSECONDS);
            if(line==null)throw new IOException("等待 root 授权或初始化超时，请重试。");
            if(EOF.equals(line))throw new IOException("root 授权被拒绝或连接已断开。");
            if(line.startsWith(marker)){
                if(!"0".equals(line.substring(marker.length())))throw new CommandFailure(result.toString().trim());
                return result.toString().trim();
            }
            if(result.length()+line.length()>131072)throw new IOException("初始化返回过长。");result.append(line).append('\n');
        }
        throw new IOException("root 连接已关闭。");
    }
    void broker(Consumer<JSONObject> callback,long until)throws Exception{
        write("exec env CLASSPATH=/data/adb/aco_battery_app/runtime/bin/daemon.dex.jar /system/bin/app_process /system/bin cn.astriafr.acobattery.daemon.AppBridge\n");
        for(;;){
            long left=until-System.nanoTime();if(left<=0)throw new IOException("前台连接未能就绪。");String line=output.poll(left,TimeUnit.NANOSECONDS);
            if(line==null)throw new IOException("前台连接未能就绪。");
            if(EOF.equals(line))throw new IOException("前台 root 连接已断开。");
            try{if("ready".equals(new JSONObject(line).optString("event")))break;}catch(Exception ignored){}
        }
        Thread reader=new Thread(()->{
            while(!closed){
                try{
                    String line=output.take();
                    if(EOF.equals(line)){failNativeResults();callback.accept(new JSONObject().put("event","disconnected"));return;}
                    try{
                        JSONObject message=new JSONObject(line);CompletableFuture<JSONObject> result=nativeResults.get(message.optString("id"));
                        if(result!=null)result.complete(message);else callback.accept(message);
                    }catch(Exception ignored){}
                }catch(InterruptedException e){return;}catch(Exception ignored){}
            }
        },"aco-root-channel");reader.setDaemon(true);reader.start();
    }
    void request(JSONObject request)throws IOException{write(request.toString()+"\n");}
    JSONObject api(String arguments)throws Exception{
        String id="native-"+java.util.UUID.randomUUID();CompletableFuture<JSONObject> future=new CompletableFuture<>();nativeResults.put(id,future);
        try{
            request(new JSONObject().put("id",id).put("command","/data/adb/aco_battery_app/runtime/bin/curve-client "+arguments));
            JSONObject result=future.get(OperationTimeout.MAX_MS,TimeUnit.MILLISECONDS);
            if(result.optInt("errno",1)!=0)throw new IOException(result.optString("stderr","后台操作未完成。"));
            JSONObject data=new JSONObject(result.optString("stdout"));if(!data.optBoolean("ok",false))throw new IOException(data.optString("error","后台操作未完成。"));return data;
        }catch(ExecutionException e){throw new IOException("前台连接已关闭。",e.getCause());}finally{nativeResults.remove(id);}
    }
    void failNativeResults(){for(CompletableFuture<JSONObject> result:nativeResults.values())result.completeExceptionally(new IOException("root 连接已关闭。"));}
    JSONObject connect(Consumer<JSONObject> callback,Consumer<String> phase,boolean commit)throws Exception{
        String id="native-startup";CompletableFuture<JSONObject> ready=new CompletableFuture<>();
        long until=OperationTimeout.deadline();broker(message->{
            if(!id.equals(message.optString("id"))){callback.accept(message);return;}
            String event=message.optString("event");
            if(event.equals("startup_phase"))phase.accept(message.optString("message"));
            else if(event.equals("startup_ready"))ready.complete(message);
            else ready.completeExceptionally(new CommandFailure(message.optString("message",message.optString("stderr","前台连接未能就绪。")),message.optString("diagnostics")));
        },until);
        request(new JSONObject().put("id",id).put("action","initialize").put("commit",commit));
        // Broker creation and backend handshake share one ten-second deadline.
        JSONObject reply;
        try{reply=ready.get(OperationTimeout.remaining(until),TimeUnit.MILLISECONDS);}catch(ExecutionException failure){Throwable cause=failure.getCause();if(cause instanceof Exception)throw (Exception)cause;throw failure;}
        JSONObject status=reply.optJSONObject("status");
        if(status==null||!status.optBoolean("ok")||!status.optBoolean("frameworkHealthy",true))throw new IOException("后台连接尚未就绪，请重试初始化。");
        return status;
    }
    @Override public void close(){closed=true;failNativeResults();try{input.close();}catch(Exception ignored){}if(process!=null)process.destroy();output.offer(EOF);}
}
