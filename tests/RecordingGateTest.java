package cn.astriafr.acobattery.common;

import java.io.*;
import java.nio.file.*;
import java.util.*;

public final class RecordingGateTest {
    static void check(boolean value){if(!value)throw new AssertionError();}
    static void rejected(File data,double rm,double pct)throws Exception{
        try{RecordingGate.enable(data,rm,pct);throw new AssertionError("Unavailable readings enabled recording");}
        catch(IOException expected){check(expected.getMessage().contains("记录保持关闭"));}
        check(new File(data,RecordingGate.DISABLED).isFile());
    }
    public static void main(String[] args)throws Exception{
        Path root=Files.createTempDirectory("acol-recording-gate-");File data=root.toFile();
        try{
            Files.write(root.resolve("records.db"),new byte[]{1,2,3});
            check(!RecordingGate.problem(Double.NaN,Double.NaN).isEmpty());
            RecordingGate.pause(data,RecordingGate.problem(Double.NaN,50));
            check(new File(data,RecordingGate.DISABLED).isFile());
            check(new String(Files.readAllBytes(root.resolve(RecordingGate.PROBLEM)),"UTF-8").contains("剩余容量"));
            // New readings alone must never reopen the persisted recording switch.
            check(RecordingGate.problem(5000,80).isEmpty());check(new File(data,RecordingGate.DISABLED).isFile());
            rejected(data,Double.NaN,80);rejected(data,5000,Double.NaN);rejected(data,-1,50);rejected(data,5000,101);
            RecordingGate.enable(data,5000,80);
            check(!new File(data,RecordingGate.DISABLED).exists()&&!new File(data,RecordingGate.PROBLEM).exists());
            check(RecordingGate.problem(0,0).isEmpty());check(RecordingGate.problem(5600,100).isEmpty());
            check(Arrays.equals(Files.readAllBytes(root.resolve("records.db")),new byte[]{1,2,3}));
            check(!root.resolve("samples").toFile().exists());
            System.out.println("Recording gate: persistent pause, explicit retry, invalid values, successful enable and unchanged history passed");
        }finally{try(java.util.stream.Stream<Path> files=Files.walk(root)){for(Path p:(Iterable<Path>)files.sorted(Comparator.reverseOrder())::iterator)Files.delete(p);}}
    }
}
