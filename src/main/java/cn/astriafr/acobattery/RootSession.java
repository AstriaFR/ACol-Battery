package cn.astriafr.acobattery;

import org.json.JSONObject;
import cn.astriafr.acobattery.common.FriendlyError;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** One foreground su session; its lifetime never controls the detached daemon. */
final class RootSession implements Closeable {
    static final class CommandFailure extends IOException {
        final String diagnostics;
        CommandFailure(String output){super(FriendlyError.fromOutput(output,"初始化命令未能完成，请重试。"));diagnostics=output;}
    }
    private Process process;
    private BufferedWriter input;
    private final BlockingQueue<String> output=new LinkedBlockingQueue<>(512);
    private volatile boolean closed;
    private static final String EOF="__ACO_ROOT_EOF__";
    static String quote(String text){return "'"+text.replace("'","'\"'\"'")+"'";}
    RootSession()throws IOException{
        String executable="su";
        for(String path:new String[]{"/system/bin/su","/system/xbin/su","/sbin/su"})if(new File(path).canExecute()){executable=path;break;}
        process=new ProcessBuilder(executable).redirectErrorStream(true).start();
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
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(timeoutSeconds);StringBuilder result=new StringBuilder();
        while(!closed){
            long remaining=until-System.nanoTime();if(remaining<=0)throw new IOException("等待 root 授权或初始化超时，请授权后重试。");
            String line=output.poll(remaining,TimeUnit.NANOSECONDS);
            if(line==null||EOF.equals(line))throw new IOException("root 授权被拒绝或连接已断开。");
            if(line.startsWith(marker)){
                if(!"0".equals(line.substring(marker.length())))throw new CommandFailure(result.toString().trim());
                return result.toString().trim();
            }
            if(result.length()+line.length()>131072)throw new IOException("初始化返回过长。");result.append(line).append('\n');
        }
        throw new IOException("root 连接已关闭。");
    }
    void broker(Consumer<JSONObject> callback)throws Exception{
        write("exec env CLASSPATH=/data/adb/aco_battery_app/runtime/bin/daemon.dex.jar /system/bin/app_process /system/bin cn.astriafr.acobattery.daemon.AppBridge\n");
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        for(;;){
            long left=until-System.nanoTime();if(left<=0)throw new IOException("前台连接未能就绪。");String line=output.poll(left,TimeUnit.NANOSECONDS);
            if(line==null||EOF.equals(line))throw new IOException("前台 root 连接已断开。");
            try{if("ready".equals(new JSONObject(line).optString("event")))break;}catch(Exception ignored){}
        }
        Thread reader=new Thread(()->{
            while(!closed){
                try{
                    String line=output.take();
                    if(EOF.equals(line)){callback.accept(new JSONObject().put("event","disconnected"));return;}
                    try{callback.accept(new JSONObject(line));}catch(Exception ignored){}
                }catch(InterruptedException e){return;}catch(Exception ignored){}
            }
        },"aco-root-channel");reader.setDaemon(true);reader.start();
    }
    void request(JSONObject request)throws IOException{write(request.toString()+"\n");}
    @Override public void close(){closed=true;try{input.close();}catch(Exception ignored){}if(process!=null)process.destroy();output.offer(EOF);}
}
