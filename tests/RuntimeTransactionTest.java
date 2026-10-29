package cn.astriafr.acobattery.common;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Real filesystem tests with injected recorder control and permission failures. */
public final class RuntimeTransactionTest {
    static void check(boolean value,String text){if(!value)throw new AssertionError(text);}
    static void write(File file,String value)throws IOException{Files.createDirectories(file.getParentFile().toPath());Files.write(file.toPath(),value.getBytes("UTF-8"));}
    static String read(File file)throws IOException{return new String(Files.readAllBytes(file.toPath()),"UTF-8");}
    static final class Crash extends Error{}
    static final class Fixture implements AutoCloseable {
        final File base,runtime,stage,boot;int stops,starts;boolean failStart,crashStart;
        final RuntimeTransaction.Control control=new RuntimeTransaction.Control(){
            public void stop(){stops++;}
            public void start()throws Exception{
                starts++;
                if(crashStart){crashStart=false;throw new Crash();}
                if(failStart){failStart=false;throw new IOException("new recorder rejected");}
            }
        };
        Fixture(boolean previous)throws Exception{
            base=Files.createTempDirectory("acol-runtime-transaction-").toFile();runtime=new File(base,"runtime");stage=new File(runtime,"update-stage");boot=new File(base,"service.d/entry.sh");
            for(String name:RuntimePayload.FILES){if(previous)write(new File(runtime,name),"old:"+name);write(new File(stage,name),"new:"+name);}
            if(previous){write(boot,"old boot");write(new File(base,"runtime.version"),"old-hash");write(new File(base,"initialized"),"old-init");}
            for(String name:new String[]{"records.db","records.db-wal","records.db-shm","owner.cert","owner.user","settings.keep"})write(new File(base,name),"protected:"+name);
        }
        RuntimeTransaction transaction(){return new RuntimeTransaction(base,boot,(file,execute)->{});}
        void old()throws Exception{
            for(String name:RuntimePayload.FILES)check(read(new File(runtime,name)).equals("old:"+name),"Old payload lost: "+name);
            check(read(boot).equals("old boot"),"Boot entry lost");check(read(new File(base,"runtime.version")).equals("old-hash"),"Old version lost");
            protectedData();
        }
        void protectedData()throws Exception{for(String name:new String[]{"records.db","records.db-wal","records.db-shm","owner.cert","owner.user","settings.keep"})check(read(new File(base,name)).equals("protected:"+name),"Protected data changed: "+name);}
        public void close()throws Exception{try(java.util.stream.Stream<Path> files=Files.walk(base.toPath())){for(Path path:(Iterable<Path>)files.sorted(Comparator.reverseOrder())::iterator)Files.delete(path);}}
    }
    public static void main(String[] args)throws Exception{
        try(Fixture f=new Fixture(true)){
            RuntimeTransaction t=f.transaction();t.apply("new-hash","owner-a",f.control);
            check(read(new File(f.base,"runtime.version")).equals("old-hash"),"Version committed before bridge handshake");
            check(new File(f.base,"update.transaction").isFile(),"Missing recovery marker");
            try{t.commit("wrong-owner");throw new AssertionError();}catch(IOException expected){}
            t.commit("owner-a");check(read(new File(f.base,"runtime.version")).equals("new-hash"),"Commit failed");
            check(!new File(f.base,"update.transaction").exists()&&!f.stage.exists(),"Committed recovery state left active");f.protectedData();
        }
        try(Fixture f=new Fixture(true)){
            f.failStart=true;try{f.transaction().apply("new","owner-a",f.control);throw new AssertionError();}catch(IOException expected){}
            f.old();check(f.starts==2&&f.stops==2,"Old recorder was not restarted");check(!new File(f.base,"update.transaction").exists(),"Rollback remained pending");
        }
        try(Fixture f=new Fixture(true)){
            f.crashStart=true;try{f.transaction().apply("new","owner-a",f.control);throw new AssertionError();}catch(Crash expected){}
            int stopped=f.stops;
            try{f.transaction().recover("other",token->true,f.control);throw new AssertionError();}catch(IOException expected){}
            check(f.stops==stopped,"An active updater was interrupted");
            f.transaction().recover("other",token->false,f.control);f.old();
        }
        try(Fixture f=new Fixture(true)){
            boolean[] injected={false};
            RuntimeTransaction t=new RuntimeTransaction(f.base,f.boot,(file,execute)->{
                if(file.getPath().equals(new File(f.runtime,"bin/libcurvewait.so").getPath())&&!injected[0]){injected[0]=true;throw new IOException("permission denied");}
            });
            try{t.apply("new","owner-a",f.control);throw new AssertionError();}catch(IOException expected){}
            f.old();
        }
        try(Fixture f=new Fixture(true)){
            f.crashStart=true;try{f.transaction().apply("new","owner-a",f.control);}catch(Crash expected){}
            f.failStart=true;try{f.transaction().recover("",token->false,f.control);throw new AssertionError();}catch(IOException expected){}
            check(new File(f.base,"update.transaction").isFile(),"Failed recovery discarded its snapshot");
            f.transaction().recover("",token->false,f.control);f.old();
        }
        try(Fixture f=new Fixture(false)){
            f.failStart=true;try{f.transaction().apply("new","owner-a",f.control);throw new AssertionError();}catch(IOException expected){}
            check(!f.boot.exists()&&!new File(f.base,"runtime.version").exists(),"First-install failure left installed markers");
            check(!new File(f.runtime,"bin/daemon.dex.jar").exists(),"Failed first-install runtime survived");f.protectedData();
        }
        try(Fixture f=new Fixture(true)){
            Files.delete(new File(f.stage,"cutoff.sh").toPath());
            try{f.transaction().apply("new","owner-a",f.control);throw new AssertionError();}catch(IOException expected){}
            check(f.stops==0,"Invalid payload stopped the old recorder");f.old();
        }
        try(Fixture f=new Fixture(true)){
            f.crashStart=true;try{f.transaction().apply("new","owner-a",f.control);}catch(Crash expected){}
            f.transaction().discard();check(!new File(f.base,"runtime.rollback").exists(),"Runtime backup was not removed during cleanup");f.protectedData();
        }
        try(Fixture f=new Fixture(true);RandomAccessFile lock=new RandomAccessFile(new File(f.base,"update.lock"),"rw");java.nio.channels.FileLock held=lock.getChannel().lock()){
            try{f.transaction().apply("new","owner-a",f.control);throw new AssertionError();}catch(IOException expected){}
            check(f.stops==0,"Concurrent updater bypassed the lock");f.old();
        }
        try(Fixture f=new Fixture(true)){
            boolean[] failed={false};RuntimeTransaction t=new RuntimeTransaction(f.base,f.boot,(file,execute)->{
                if(file.equals(f.boot)&&!failed[0]){failed[0]=true;throw new IOException("boot permission denied");}
            });
            try{t.apply("new","owner-a",f.control);throw new AssertionError();}catch(IOException expected){}
            check(f.stops==0,"Preparation failure stopped the recorder");f.old();
        }
        try(Fixture f=new Fixture(true)){
            RuntimeTransaction t=new RuntimeTransaction(f.base,f.boot,(file,execute)->{if(file.getName().equals("libcurvewait.so"))throw new Crash();});
            try{t.apply("new","owner-a",f.control);throw new AssertionError();}catch(Crash expected){}
            check(new File(f.base,"update.pending").isFile()&&!new File(f.base,"update.installed").exists(),"Partial installation could be started by the supervisor");
            f.transaction().recover("",token->false,f.control);f.old();
        }
        System.out.println("Runtime transactions: commit, failed start, interrupted update, live owner, permissions, retry, fresh install and protected data passed");
    }
}
