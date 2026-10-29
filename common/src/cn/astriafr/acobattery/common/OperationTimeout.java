package cn.astriafr.acobattery.common;

import java.net.SocketTimeoutException;
import java.util.concurrent.TimeUnit;

/** A single operation's deadline; sampling cadence and retry intervals are separate. */
public final class OperationTimeout {
    public static final int MAX_MS=10000,MAX_SECONDS=10;
    private OperationTimeout(){}
    public static long deadline(){return System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(MAX_MS);}
    public static int remaining(long deadline)throws SocketTimeoutException{
        long left=deadline-System.nanoTime();if(left<=0)throw new SocketTimeoutException("操作超时，请重试。");
        return (int)Math.min(MAX_MS,Math.max(1,TimeUnit.NANOSECONDS.toMillis(left)));
    }
}
