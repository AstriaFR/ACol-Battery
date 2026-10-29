package cn.astriafr.acobattery;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** Real bridge reader/writer handshake with an in-memory process, without su. */
public final class RootSessionStartupTest {
    static void check(boolean value,String text){if(!value)throw new AssertionError(text);}
    static final class Shell extends Process {
        final PipedInputStream output=new PipedInputStream(),commands=new PipedInputStream();
        final PipedOutputStream send,toCommands;final List<String> received=Collections.synchronizedList(new ArrayList<>());
        final boolean fail;volatile boolean exited;
        Shell(boolean fail)throws IOException{
            this.fail=fail;send=new PipedOutputStream(output);toCommands=new PipedOutputStream(commands);
            Thread server=new Thread(()->{
                try(BufferedReader in=new BufferedReader(new InputStreamReader(commands,"UTF-8"));PrintWriter out=new PrintWriter(new OutputStreamWriter(send,"UTF-8"),true)){
                    String line;while((line=in.readLine())!=null){
                        received.add(line);
                        if(line.startsWith("exec env "))out.println("{\"event\":\"ready\"}");
                        else{
                            JSONObject request=new JSONObject(line);String id=request.getString("id");
                            if(request.optString("action").equals("initialize")){
                                out.println(new JSONObject().put("id",id).put("event","startup_phase").put("message","正在连接后台…"));
                                out.println(fail?new JSONObject().put("id",id).put("event","result").put("message","后台无法启动。").put("diagnostics","full technical cause")
                                    :new JSONObject().put("id",id).put("event","startup_ready").put("status",new JSONObject().put("ok",true).put("frameworkHealthy",true)));
                            }else out.println(new JSONObject().put("id",id).put("event","result"));
                        }
                    }
                }catch(Exception ignored){}finally{exited=true;}
            });server.setDaemon(true);server.start();
        }
        public OutputStream getOutputStream(){return toCommands;}
        public InputStream getInputStream(){return output;}
        public InputStream getErrorStream(){return new ByteArrayInputStream(new byte[0]);}
        public int waitFor()throws InterruptedException{while(!exited)Thread.sleep(1);return 0;}
        public int exitValue(){if(!exited)throw new IllegalThreadStateException();return 0;}
        public void destroy(){try{toCommands.close();}catch(IOException ignored){}try{output.close();}catch(IOException ignored){}exited=true;}
    }
    public static void main(String[] args)throws Exception{
        Shell shell=new Shell(false);List<String> phases=new ArrayList<>();CountDownLatch delivered=new CountDownLatch(1);
        try(RootSession root=new RootSession(shell)){
            JSONObject status=root.connect(message->{if(message.optString("id").equals("regular"))delivered.countDown();},phases::add,true);
            check(status.optBoolean("frameworkHealthy"),"Final status not returned for startup notices");
            check(phases.size()==1,"Missing startup feedback");
            check(shell.received.get(0).startsWith("exec env "),"A second helper process was started");
            JSONObject request=new JSONObject(shell.received.get(1));check(request.getBoolean("commit"),"Update was not committed through the handshake");
            root.request(new JSONObject().put("id","regular"));check(delivered.await(2,TimeUnit.SECONDS),"Frontend messages were not delivered after startup");
        }
        try(RootSession root=new RootSession(new Shell(true))){
            try{root.connect(message->{},text->{},false);throw new AssertionError("Failure accepted as ready");}
            catch(RootSession.CommandFailure failure){check(failure.getMessage().equals("后台无法启动。"),"Human cause lost");check(failure.diagnostics.equals("full technical cause"),"Technical report lost");}
        }
        System.out.println("Root startup: one bridge, health handshake, phase feedback, request routing and full failure diagnostics passed");
    }
}
