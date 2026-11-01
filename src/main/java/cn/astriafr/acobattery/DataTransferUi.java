package cn.astriafr.acobattery;

import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.graphics.Color;
import android.view.View;
import android.widget.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.*;
import org.json.*;
import cn.astriafr.acobattery.common.*;

/** File-picker grants remain in the Activity; detached root work never handles arbitrary URIs. */
final class DataTransferUi {
    static final int PICK_RESTORE=6101,SAVE_BACKUP=6102;
    final MainActivity app;final File marker;RootSession shell;
    String token,mode;Button primary,secondary;AlertDialog preview,confirmation;
    volatile int generation;boolean active,polling,saveLaunched,reported;
    JSONObject last;final android.os.Handler handler;
    DataTransferUi(MainActivity app){this.app=app;marker=new File(app.getNoBackupFilesDir(),"acol-transfer.pending");handler=new android.os.Handler(app.getMainLooper());}
    File input(){return new File(app.getCacheDir(),"acol-input-"+token+".zip");}
    File output(){return new File(app.getCacheDir(),"acol-backup-"+token+".zip");}
    boolean resume(){
        if(!marker.isFile())return false;
        try{JSONObject data=new JSONObject(new String(Files.readAllBytes(marker.toPath()),StandardCharsets.UTF_8));token=data.getString("token");mode=data.getString("mode");if(!token.matches("[0-9a-f-]{36}")||!Arrays.asList("backup","restore").contains(mode))throw new IOException("数据任务标记异常。");active=true;app.transferringData=true;page("正在完成数据操作","正在读取后台任务状态。");try(FileOutputStream ready=new FileOutputStream(new File(app.getNoBackupFilesDir(),"acol-transfer.ready"))){ready.write(token.getBytes(StandardCharsets.UTF_8));ready.getFD().sync();}poll();}
        catch(Exception error){app.showInitialization();app.fail("读取数据任务状态",error);}return true;
    }
    void backup(){if(!app.initialized||!app.rootReady||active||app.clearingData)return;token=UUID.randomUUID().toString();mode="backup";begin();}
    void restore(){
        if(!app.initialized||!app.rootReady||active||app.clearingData)return;
        try{Intent picker=new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");app.startActivityForResult(picker,PICK_RESTORE);}
        catch(Exception error){Toast.makeText(app,"无法打开系统文件选择器。",Toast.LENGTH_LONG).show();}
    }
    void result(int request,int code,Intent data){
        Uri uri=data==null?null:data.getData();
        if(request==PICK_RESTORE){if(code!=android.app.Activity.RESULT_OK||uri==null)return;token=UUID.randomUUID().toString();mode="restore";validate(uri);}
        else if(request==SAVE_BACKUP){if(code!=android.app.Activity.RESULT_OK||uri==null){finish("已取消保存备份");return;}save(uri);}
    }
    void copy(InputStream in,OutputStream out,int expectedGeneration)throws Exception{
        if(in==null||out==null)throw new IOException("无法打开数据包文件。");byte[] bytes=new byte[65536];long total=0;int n;
        while((n=in.read(bytes))!=-1){if(expectedGeneration!=generation||app.destroyed)throw new IOException("操作已取消。");total+=n;if(total>DataArchive.MAX_BYTES)throw new IOException("数据包过大。");out.write(bytes,0,n);}
    }
    void validate(Uri uri){
        active=true;int task=++generation;File file=input();preview=new AlertDialog.Builder(app).setTitle("检查数据包").setMessage("正在读取并校验，现有数据不会更改。").setNegativeButton("取消",(d,w)->cancelPreview()).create();preview.setOnCancelListener(d->cancelPreview());preview.show();
        app.worker.execute(()->{
            try{
                try(InputStream in=app.getContentResolver().openInputStream(uri);FileOutputStream out=new FileOutputStream(file)){copy(in,out,task);out.getFD().sync();}
                JSONObject meta=DataArchive.inspect(file,null,()->{if(task!=generation||app.destroyed)throw new IOException("操作已取消。");});
                app.runOnUiThread(()->{if(task!=generation||app.destroyed)return;preview.dismiss();preview=null;confirm(meta);});
            }catch(Exception error){file.delete();app.runOnUiThread(()->{if(task!=generation||app.destroyed)return;if(preview!=null)preview.dismiss();preview=null;active=false;new AlertDialog.Builder(app).setTitle("无法恢复").setMessage("数据包未通过校验，现有数据未更改。\n"+error.getMessage()).setPositiveButton("知道了",null).show();});}
        });
    }
    void cancelPreview(){generation++;active=false;if(preview!=null){preview.dismiss();preview=null;}}
    void confirm(JSONObject meta){
        String date=new SimpleDateFormat("yyyy/MM/dd HH:mm:ss",Locale.US).format(new Date(meta.optLong("created")));
        confirmation=new AlertDialog.Builder(app).setTitle("恢复备份并覆盖现有数据？").setMessage("备份时间："+date+"\n将覆盖现有记录、设置、使用时间缓存和错误日志。未包含在备份中的数据也会被删除。\n应用会短暂重启，恢复完成后重新初始化。").setNegativeButton("取消",(d,w)->{active=false;input().delete();}).setPositiveButton("确认覆盖",(d,w)->begin()).create();
        confirmation.setOnCancelListener(d->{active=false;input().delete();});confirmation.setOnDismissListener(d->confirmation=null);confirmation.show();confirmation.getButton(-2).setTextColor(Color.rgb(7,130,103));confirmation.getButton(-1).setTextColor(Color.rgb(190,65,65));
    }
    void page(String title,String message){
        app.showInitialization();app.stage.setText(title);app.detail.setText(message);app.retry.setVisibility(View.GONE);app.errorActions.setVisibility(View.GONE);
        LinearLayout layout=(LinearLayout)app.contentFrame.getChildAt(0);primary=new Button(app);primary.setTextColor(Color.rgb(7,130,103));secondary=new Button(app);secondary.setBackgroundColor(Color.TRANSPARENT);
        layout.addView(primary,new LinearLayout.LayoutParams(-1,app.dp(48)));layout.addView(secondary,new LinearLayout.LayoutParams(-1,app.dp(44)));primary.setVisibility(View.GONE);secondary.setVisibility(View.GONE);
    }
    void begin(){
        try{
            File next=new File(marker.getPath()+".next");try(FileOutputStream out=new FileOutputStream(next)){out.write(new JSONObject().put("token",token).put("mode",mode).toString().getBytes(StandardCharsets.UTF_8));out.getFD().sync();}
            Files.move(next.toPath(),marker.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING,java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        }catch(Exception error){active=false;Toast.makeText(app,"无法保存任务状态，未开始操作。",Toast.LENGTH_LONG).show();return;}
        active=true;app.transferringData=true;generation++;if(app.errorLogs!=null)app.errorLogs.close();app.closeErrorPage();if(app.root!=null)app.root.close();app.root=null;app.rootReady=false;app.waitingRequests.clear();
        if(app.web!=null){app.web.removeJavascriptInterface("AcoNative");app.web.destroy();app.web=null;}
        page(mode.equals("backup")?"准备备份":"准备恢复","应用会短暂重启，以保证记录与使用时间数据完整。");
        app.worker.execute(()->{try{connect();shell.command(app.admin("transfer-start "+mode+" "+token),10);app.runOnUiThread(this::poll);}catch(Exception error){app.runOnUiThread(()->problem(error));}});
    }
    void connect()throws Exception{if(shell!=null)return;shell=new RootSession();if(!"0".equals(shell.command("id -u",10)))throw new IOException("未获得 root 权限。");}
    void poll(){
        if(!active||app.destroyed||polling)return;polling=true;int task=generation;
        app.worker.execute(()->{
            try{connect();JSONObject data=new JSONObject(shell.command(app.admin("transfer-status "+token),10));app.runOnUiThread(()->{if(task!=generation||app.destroyed)return;polling=false;update(data);});}
            catch(Exception error){if(shell!=null)shell.close();shell=null;app.runOnUiThread(()->{if(task!=generation||app.destroyed)return;polling=false;problem(error);});}
        });
    }
    void update(JSONObject data){
        last=data;app.stage.setText(mode.equals("backup")?"备份数据":"恢复数据");app.detail.setText(data.optString("message","正在处理…"));String phase=data.optString("phase");
        if(Arrays.asList("done","failed","recovery_required").contains(phase)&&data.optBoolean("running")){handler.postDelayed(this::poll,1000);return;}
        if(phase.equals("done")){
            if(mode.equals("restore")){finish("恢复完成");return;}
            app.progress.setVisibility(View.GONE);primary.setText("保存数据包");primary.setVisibility(View.VISIBLE);primary.setOnClickListener(v->chooseSave());secondary.setText("取消保存");secondary.setVisibility(View.VISIBLE);secondary.setOnClickListener(v->finish("已取消保存备份"));
            if(!saveLaunched){saveLaunched=true;chooseSave();}return;
        }
        if(phase.equals("failed")||phase.equals("recovery_required")){
            app.progress.setVisibility(View.GONE);String report=data.optString("error",data.optString("message"));if(data.optBoolean("safe")&&!reported){reported=true;app.archiveError("transfer",mode.equals("backup")?"备份未完成":data.optBoolean("restored")?"恢复后续处理未完成":"恢复未完成",report);}
            primary.setText(data.optBoolean("safe")?"返回应用":"继续恢复处理");primary.setVisibility(View.VISIBLE);primary.setOnClickListener(v->{if(data.optBoolean("safe"))finish("");else recover();});secondary.setText("错误报告");secondary.setVisibility(View.VISIBLE);secondary.setOnClickListener(v->app.openReport(report,true));return;
        }
        primary.setVisibility(View.GONE);secondary.setVisibility(View.GONE);app.progress.setVisibility(View.VISIBLE);
        if(data.optBoolean("orphaned")){recover();return;}handler.postDelayed(this::poll,1000);
    }
    void recover(){
        app.progress.setVisibility(View.VISIBLE);primary.setVisibility(View.GONE);secondary.setVisibility(View.GONE);
        app.worker.execute(()->{try{connect();shell.command(app.admin("transfer-recover "+token),10);app.runOnUiThread(()->handler.postDelayed(this::poll,1000));}catch(Exception error){app.runOnUiThread(()->problem(error));}});
    }
    void problem(Exception error){
        if(app.destroyed)return;app.progress.setVisibility(View.GONE);app.detail.setText("暂时无法取得任务状态，现有数据不会在此处重新覆盖。\n"+error.getMessage());primary.setText("重试");primary.setVisibility(View.VISIBLE);primary.setOnClickListener(v->{app.progress.setVisibility(View.VISIBLE);poll();});secondary.setText("退出应用");secondary.setVisibility(View.VISIBLE);secondary.setOnClickListener(v->app.finish());
    }
    void chooseSave(){
        if(app.destroyed)return;
        String name="ACol电池-数据备份-"+new SimpleDateFormat("yyyyMMdd-HHmmss",Locale.US).format(new Date())+".acoldata";
        try{Intent picker=new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/zip").putExtra(Intent.EXTRA_TITLE,name);app.startActivityForResult(picker,SAVE_BACKUP);}
        catch(Exception error){problem(error);}
    }
    void save(Uri uri){
        int task=generation;primary.setVisibility(View.GONE);secondary.setVisibility(View.GONE);app.progress.setVisibility(View.VISIBLE);app.detail.setText("正在保存数据包…");
        app.worker.execute(()->{
            try{try(InputStream in=new FileInputStream(output());OutputStream out=app.getContentResolver().openOutputStream(uri,"wt")){copy(in,out,task);out.flush();}app.runOnUiThread(()->{if(task==generation&&!app.destroyed)finish("备份已保存");});}
            catch(Exception error){app.runOnUiThread(()->{if(task!=generation||app.destroyed)return;app.progress.setVisibility(View.GONE);app.detail.setText("保存未完成，可重新选择位置。\n"+error.getMessage());primary.setText("重新保存");primary.setVisibility(View.VISIBLE);primary.setOnClickListener(v->chooseSave());secondary.setText("取消保存");secondary.setVisibility(View.VISIBLE);secondary.setOnClickListener(v->finish("已取消保存备份"));});}
        });
    }
    void finish(String message){
        if(!active)return;generation++;handler.removeCallbacksAndMessages(null);app.progress.setVisibility(View.VISIBLE);primary.setVisibility(View.GONE);secondary.setVisibility(View.GONE);app.detail.setText("正在重新连接应用…");
        app.worker.execute(()->{
            try{connect();shell.command(app.admin("transfer-clean "+token),10);}catch(Exception ignored){}
            if(shell!=null)shell.close();shell=null;input().delete();output().delete();new File(app.getNoBackupFilesDir(),"acol-transfer.ready").delete();boolean removed=!marker.exists()||marker.delete();
            app.runOnUiThread(()->{
                if(app.destroyed)return;if(!removed){problem(new IOException("任务已完成，但无法移除任务标记。"));return;}
                active=false;app.transferringData=false;((BatteryApplication)app.getApplication()).resumeMetricsCleanup();app.errorReport="";app.showInitialization();if(!message.isEmpty())Toast.makeText(app,message,Toast.LENGTH_LONG).show();app.initialize();
            });
        });
    }
    boolean back(){
        if(preview!=null){cancelPreview();return true;}if(confirmation!=null){confirmation.cancel();return true;}return app.transferringData;
    }
    void close(){generation++;active=false;handler.removeCallbacksAndMessages(null);if(preview!=null)preview.dismiss();if(confirmation!=null)confirmation.dismiss();if(shell!=null)shell.close();}
}
