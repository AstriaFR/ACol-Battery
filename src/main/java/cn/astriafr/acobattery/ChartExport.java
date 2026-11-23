package cn.astriafr.acobattery;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Bounded CSV staging; the system document picker grants the destination without root. */
final class ChartExport {
    static final int SAVE=6104;
    static final long MAX_BYTES=128L*1024*1024;
    private static final String PENDING_PICKER="chartExport.pendingPicker";
    private static final String TOKEN_FORMAT="[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}";
    private static final ExecutorService WRITER=Executors.newSingleThreadExecutor();
    private static final Set<File> WRITING=ConcurrentHashMap.newKeySet();
    final MainActivity app;
    private File file;private OutputStream output;private String token,name;private long size;
    private boolean picking,pickerLaunched,pickerSaved,saving,closed;
    ChartExport(MainActivity app,Bundle state){
        this.app=app;
        String restored=state==null?null:state.getString(PENDING_PICKER);
        if(restored!=null&&restored.matches(TOKEN_FORMAT)){
            token=restored;file=new File(directory(),token+".csv");picking=pickerLaunched=pickerSaved=true;
        }
    }
    private File directory(){return new File(app.getCacheDir(),"chart-exports");}
    synchronized void saveState(Bundle state){
        if(picking&&pickerLaunched){state.putString(PENDING_PICKER,token);pickerSaved=true;}
    }
    synchronized String begin(String filename){
        if(closed||file!=null||app.destroyed||filename==null||!filename.matches("[^/\\\\\r\n]{1,100}\\.csv"))return "";
        try{
            File directory=directory();if(!directory.isDirectory()&&!directory.mkdirs())return "";
            // Another Activity may still own a restored picker or an in-flight write.
            long cutoff=System.currentTimeMillis()-TimeUnit.DAYS.toMillis(7);
            File[] stale=directory.listFiles();
            if(stale!=null)for(File old:stale)if(old.getName().matches(TOKEN_FORMAT+"\\.csv")&&old.lastModified()<cutoff&&!WRITING.contains(old))old.delete();
            token=UUID.randomUUID().toString();name=filename;file=new File(directory,token+".csv");output=new FileOutputStream(file);size=0;return token;
        }catch(IOException error){clean();return "";}
    }
    synchronized boolean append(String id,String text){
        if(closed||!matches(id)||output==null||text==null||text.length()>65536)return false;
        byte[] bytes=text.getBytes(StandardCharsets.UTF_8);if(size+bytes.length>MAX_BYTES)return false;
        try{output.write(bytes);size+=bytes.length;return true;}catch(IOException error){return false;}
    }
    synchronized boolean finish(String id){
        if(closed||!matches(id)||output==null)return false;
        try{output.close();output=null;picking=true;}catch(IOException error){clean();return false;}
        app.runOnUiThread(()->{
            synchronized(ChartExport.this){
                if(closed||!matches(id)||!picking||app.destroyed){if(matches(id))clean();return;}
                try{
                    pickerLaunched=true;
                    app.startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("text/csv").putExtra(Intent.EXTRA_TITLE,name),SAVE);
                }
                catch(Exception error){clean();message("无法打开文件选择器，未导出。");}
            }
        });return true;
    }
    synchronized void cancel(String id){if(matches(id)&&!picking&&!saving)clean();}
    private boolean matches(String id){return token!=null&&token.equals(id);}
    void result(int result,Intent data){
        final File source;final Uri destination;final String id;
        synchronized(this){
            if(!picking||file==null||closed)return;picking=pickerLaunched=pickerSaved=false;
            if(result!=Activity.RESULT_OK||data==null||data.getData()==null){clean();message("已取消导出");return;}
            saving=true;source=file;destination=data.getData();id=token;WRITING.add(source);
        }
        Context context=app.getApplicationContext();
        WRITER.execute(()->{
            String feedback="导出完成";
            try(InputStream in=new FileInputStream(source);OutputStream out=context.getContentResolver().openOutputStream(destination,"wt")){
                if(out==null)throw new IOException("无法写入所选文件");byte[] buffer=new byte[65536];int n;
                while((n=in.read(buffer))!=-1)out.write(buffer,0,n);
                out.flush();
            }catch(Exception error){feedback="导出失败："+error.getMessage();}
            finally{
                synchronized(ChartExport.this){if(matches(id))clean();}
                WRITING.remove(source);
            }
            String message=feedback;
            context.getMainExecutor().execute(()->Toast.makeText(context,message,Toast.LENGTH_LONG).show());
        });
    }
    private void message(String text){app.runOnUiThread(()->{if(!app.destroyed)Toast.makeText(app,text,Toast.LENGTH_LONG).show();});}
    private void clean(){try{if(output!=null)output.close();}catch(IOException ignored){}output=null;if(file!=null)file.delete();file=null;token=null;size=0;picking=pickerLaunched=pickerSaved=saving=false;}
    synchronized void close(){
        closed=true;
        if(!saving&&!(picking&&pickerSaved&&!app.isFinishing()))clean();
    }
}
