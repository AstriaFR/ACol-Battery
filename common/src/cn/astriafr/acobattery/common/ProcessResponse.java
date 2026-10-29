package cn.astriafr.acobattery.common;

import java.io.*;
import java.net.SocketTimeoutException;
import java.util.concurrent.*;

/** Read command output without an unbounded pipe read or process wait. */
public final class ProcessResponse {
    public final int code;public final String text;
    private ProcessResponse(int code,String text){this.code=code;this.text=text;}
    public static ProcessResponse read(Process process,long deadline,int limit)throws Exception{
        FutureTask<byte[]> output=new FutureTask<>(()->{
            try(InputStream in=process.getInputStream();ByteArrayOutputStream bytes=new ByteArrayOutputStream()){
                byte[] buffer=new byte[4096];int n;
                while((n=in.read(buffer))!=-1){if(bytes.size()+n>limit)throw new IOException("返回过长");bytes.write(buffer,0,n);}
                return bytes.toByteArray();
            }
        });
        Thread reader=new Thread(output,"aco-command-output");reader.setDaemon(true);reader.start();
        try{
            if(!process.waitFor(OperationTimeout.remaining(deadline),TimeUnit.MILLISECONDS))throw new SocketTimeoutException("操作超时，请刷新状态。");
            byte[] bytes=output.get(OperationTimeout.remaining(deadline),TimeUnit.MILLISECONDS);
            return new ProcessResponse(process.exitValue(),new String(bytes,java.nio.charset.StandardCharsets.UTF_8));
        }catch(ExecutionException failure){Throwable cause=failure.getCause();if(cause instanceof Exception)throw (Exception)cause;throw failure;}
        finally{
            // SIGTERM permits the voltage script's existing transaction rollback trap.
            if(process.isAlive())process.destroy();output.cancel(true);
        }
    }
}
