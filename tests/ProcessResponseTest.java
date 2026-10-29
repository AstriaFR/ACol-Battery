package cn.astriafr.acobattery.common;

import java.io.*;
import java.util.concurrent.TimeUnit;

public final class ProcessResponseTest {
    static final class Child extends Process {
        final byte[] output;final boolean exit;boolean destroyed;long wait;
        Child(String output,boolean exit)throws Exception{this.output=output.getBytes("UTF-8");this.exit=exit;}
        public InputStream getInputStream(){return new ByteArrayInputStream(output);}
        public InputStream getErrorStream(){return new ByteArrayInputStream(new byte[0]);}
        public OutputStream getOutputStream(){return new ByteArrayOutputStream();}
        public int waitFor(){throw new AssertionError("Unbounded wait");}
        public boolean waitFor(long timeout,TimeUnit unit){wait=unit.toMillis(timeout);return exit;}
        public int exitValue(){if(!exit&&!destroyed)throw new IllegalThreadStateException();return 0;}
        public boolean isAlive(){return !exit&&!destroyed;}
        public void destroy(){destroyed=true;}
    }
    static void deadlines()throws Exception{
        if(OperationTimeout.MAX_MS!=10000||OperationTimeout.MAX_SECONDS!=10)throw new AssertionError();
        int left=OperationTimeout.remaining(OperationTimeout.deadline());if(left<=0||left>10000)throw new AssertionError();
        if(OperationTimeout.remaining(System.nanoTime()+30_000_000_000L)!=10000)throw new AssertionError();
        try{OperationTimeout.remaining(System.nanoTime()-1);throw new AssertionError();}catch(java.net.SocketTimeoutException expected){}
    }
    public static void main(String[] args)throws Exception{
        deadlines();
        Child good=new Child("ok",true);ProcessResponse reply=ProcessResponse.read(good,OperationTimeout.deadline(),64);
        if(!reply.text.equals("ok")||reply.code!=0||good.wait<=0||good.wait>10000)throw new AssertionError();
        Child stuck=new Child("",false);
        try{ProcessResponse.read(stuck,OperationTimeout.deadline(),64);throw new AssertionError();}catch(java.net.SocketTimeoutException expected){}
        if(!stuck.destroyed||stuck.wait>10000)throw new AssertionError();
        try{ProcessResponse.read(new Child("large",true),OperationTimeout.deadline(),1);throw new AssertionError();}catch(IOException expected){}
        System.out.println("Commands: deadlines, bounded output, timed waits and timeout cancellation passed");
    }
}
