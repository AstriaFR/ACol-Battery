package cn.astriafr.acobattery;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.BitmapFactory;
import android.graphics.drawable.GradientDrawable;
import android.graphics.fonts.Font;
import android.graphics.fonts.FontFamily;
import android.graphics.fonts.FontStyle;
import android.net.Uri;
import android.os.Build;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import cn.astriafr.acobattery.common.FriendlyError;
import cn.astriafr.acobattery.common.RuntimePayload;
import cn.astriafr.acobattery.common.RecordingGate;
import cn.astriafr.acobattery.common.StartupFeedback;
import cn.astriafr.acobattery.common.ErrorLogStore;
import org.json.*;
import java.io.*;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/** Initialization, root deployment, recovery and startup reports. */
final class StartupController {
    static final String BASE=RuntimeSetup.BASE,RUNTIME=RuntimeSetup.RUNTIME;
    static final int SAVE_DATABASE_REBUILD=6103;
    static final class InitFailure extends Exception {
        final String diagnostics;
        InitFailure(String message){this(message,"");}
        InitFailure(String message,String diagnostics){super(message);this.diagnostics=diagnostics==null?"":diagnostics;}
    }
    final MainActivity app;
    volatile RootSession root;
    volatile boolean initialized,rootReady;
    volatile String connectionStatus="正在连接后台…";
    final Object channelLock=new Object();
    final ConcurrentLinkedQueue<JSONObject> waitingRequests=new ConcurrentLinkedQueue<>();
    boolean channelLost;
    FrameLayout errorPage;String currentPhase="初始化";
    TextView stage,detail,errorText,errorTitle;
    Button retry,errorView,errorCopy,rebuild,errorRebuild;
    boolean canRebuild,rebuilding,showingFallbackReport;
    LinearLayout errorActions,initializationContent;ProgressBar progress;
    String errorReport="",downgradeReport="";JSONObject probeReport;
    List<StartupFeedback.Notice> pendingNotices=java.util.Collections.emptyList();
    AlertDialog startupNotice;View reportHome;
    StartupController(MainActivity app){this.app=app;}
    RootSession openRoot()throws IOException{
        RootSession session=new RootSession();
        synchronized(channelLock){
            if(app.destroyed){session.close();throw new IOException("页面已关闭。");}
            root=session;channelLost=false;
        }
        return session;
    }
    void disconnect(){
        RootSession previous;
        synchronized(channelLock){previous=root;root=null;rootReady=false;channelLost=true;waitingRequests.clear();}
        if(previous!=null)previous.close();
    }
    void close(){
        initialized=false;disconnect();pendingNotices=java.util.Collections.emptyList();
        if(startupNotice!=null){startupNotice.dismiss();startupNotice=null;}
    }
    JSONObject connectRoot(java.util.function.Consumer<String> phase,boolean commit)throws Exception{
        final RootSession session=root;
        if(session==null)throw new IOException("root 连接已关闭。");
        synchronized(channelLock){channelLost=false;}
        JSONObject status=session.connect(message->{
            synchronized(channelLock){
                if(root!=session||app.destroyed)return;
                if("disconnected".equals(message.optString("event"))){rootReady=false;channelLost=true;connectionPhase("root 连接已断开，请重新打开 App。");}
            }
            app.runOnUiThread(()->{if(root==session&&!app.destroyed)app.browser.deliver(message);});
        },text->{if(root==session&&!app.destroyed)phase.accept(text);},commit);
        connected(session);return status;
    }
    void post(String text){
        if(!initialized||app.destroyed||text==null||text.length()>8192)return;
        try{JSONObject request=new JSONObject(text);if(!request.optString("id").matches("[a-f0-9-]{12,48}"))return;
            synchronized(channelLock){
                if(!rootReady){
                    if(channelLost)throw new IOException("root 连接已断开。");
                    if(waitingRequests.size()>=32)throw new IOException("正在连接后台，请稍候。");
                    waitingRequests.add(request);return;
                }
                root.request(request);
            }
        }catch(Exception error){try{JSONObject request=new JSONObject(text);app.browser.deliver(new JSONObject().put("id",request.optString("id")).put("event","error").put("message","root 连接已断开，请重新打开 App。"));}catch(Exception ignored){}}
    }
    void showInitialization(){
        if(app.destroyed)return;
        initialized=false;errorPage=null;errorText=null;errorTitle=null;errorCopy=null;errorRebuild=null;canRebuild=false;showingFallbackReport=false;
        LinearLayout layout=new LinearLayout(app);initializationContent=layout;layout.setOrientation(LinearLayout.VERTICAL);layout.setGravity(Gravity.CENTER_VERTICAL);layout.setPadding(app.dp(28),app.dp(32),app.dp(28),app.dp(32));layout.setBackgroundColor(Color.rgb(246,248,251));
        LinearLayout title=new LinearLayout(app);title.setGravity(Gravity.CENTER_VERTICAL);
        ImageView icon=new ImageView(app);try(InputStream in=app.getAssets().open("webroot/assets/acol-icon.png")){icon.setImageBitmap(BitmapFactory.decodeStream(in));}catch(IOException ignored){}
        title.addView(icon,new LinearLayout.LayoutParams(app.dp(58),app.dp(58)));
        LinearLayout names=new LinearLayout(app);names.setOrientation(LinearLayout.VERTICAL);names.setPadding(app.dp(14),0,0,0);
        TextView name=new TextView(app);name.setText("ACol 电池");name.setTextSize(32);
        try{
            Font latin=new Font.Builder(app.getAssets(),"webroot/assets/acol-brand.ttf").setWeight(700).build();
            Font chinese=new Font.Builder(app.getAssets(),"webroot/assets/acol-cjk.otf").setWeight(700).build();
            name.setTypeface(new Typeface.CustomFallbackBuilder(new FontFamily.Builder(latin).build())
                .addCustomFallback(new FontFamily.Builder(chinese).build())
                .setStyle(new FontStyle(700,FontStyle.FONT_SLANT_UPRIGHT)).build());
        }catch(Exception ignored){name.setTypeface(null,Typeface.BOLD);}
        name.setTextColor(Color.rgb(24,38,56));names.addView(name);
        TextView version=new TextView(app);try{version.setText(app.getPackageManager().getPackageInfo(app.getPackageName(),0).versionName+" · AstriaFR");}catch(Exception ignored){version.setText("2.3 · AstriaFR");}version.setTextColor(Color.rgb(96,113,133));version.setTextSize(13);names.addView(version);title.addView(names);layout.addView(title);
        stage=new TextView(app);stage.setTextSize(20);stage.setMinLines(1);stage.setIncludeFontPadding(false);stage.setTypeface(null,Typeface.BOLD);stage.setTextColor(Color.rgb(7,130,103));stage.setPadding(0,app.dp(32),0,app.dp(12));layout.addView(stage);
        detail=new TextView(app);detail.setTextSize(14);detail.setIncludeFontPadding(false);detail.setMinLines(2);detail.setMinHeight(app.dp(48));detail.setGravity(Gravity.TOP);detail.setTextColor(Color.rgb(96,113,133));detail.setLineSpacing(app.dp(5),1);layout.addView(detail);
        progress=new ProgressBar(app,null,android.R.attr.progressBarStyleHorizontal);progress.setIndeterminate(true);LinearLayout.LayoutParams line=new LinearLayout.LayoutParams(-1,app.dp(4));line.topMargin=app.dp(24);layout.addView(progress,line);
        retry=new Button(app);retry.setText("重试初始化");retry.setVisibility(View.GONE);retry.setOnClickListener(v->initialize());LinearLayout.LayoutParams button=new LinearLayout.LayoutParams(-1,app.dp(48));button.topMargin=app.dp(22);layout.addView(retry,button);
        errorActions=new LinearLayout(app);errorActions.setOrientation(LinearLayout.VERTICAL);errorActions.setVisibility(View.GONE);
        errorView=new Button(app);errorView.setText("查看报告");errorView.setOnClickListener(v->openErrorPage());
        errorActions.addView(errorView,new LinearLayout.LayoutParams(-1,app.dp(44)));
        rebuild=new Button(app);rebuild.setText("重建数据库");rebuild.setTextColor(Color.rgb(190,65,65));rebuild.setVisibility(View.GONE);rebuild.setOnClickListener(v->askRebuild());
        errorActions.addView(rebuild,new LinearLayout.LayoutParams(-1,app.dp(44)));
        LinearLayout.LayoutParams actionLine=new LinearLayout.LayoutParams(-1,-2);actionLine.topMargin=app.dp(12);layout.addView(errorActions,actionLine);
        ScrollView scroll=new ScrollView(app);scroll.setFillViewport(true);scroll.setBackgroundColor(Color.rgb(246,248,251));
        scroll.addView(layout,new ScrollView.LayoutParams(-1,-2));app.mount(scroll);
    }
    void phase(String name,String text){currentPhase=name;if(app.destroyed)return;app.runOnUiThread(()->{if(!app.destroyed&&stage!=null&&detail!=null){stage.setText(name);detail.setText(text);}});}
    void connectionPhase(String text){
        connectionStatus=text;
        app.runOnUiThread(()->{if(!app.destroyed&&app.browser.web!=null)app.browser.web.evaluateJavascript("window.dispatchEvent(new CustomEvent('aco-connection',{detail:"+JSONObject.quote(text)+"}))",null);});
    }
    void connected(RootSession session)throws Exception{
        synchronized(channelLock){
            if(app.destroyed||root!=session||channelLost)throw new IOException("前台连接已关闭。");rootReady=true;
            JSONObject request;while((request=waitingRequests.poll())!=null)root.request(request);
        }
        connectionPhase("");
    }
    void copyFailure(){
        String report=errorPage!=null&&errorPage.getVisibility()==View.VISIBLE&&errorText!=null?errorText.getText().toString():errorReport;if(report.isEmpty())return;
        ClipboardManager clipboard=(ClipboardManager)app.getSystemService(Context.CLIPBOARD_SERVICE);
        if(clipboard==null)return;
        clipboard.setPrimaryClip(ClipData.newPlainText("ACol 错误报告",report));
        Toast.makeText(app,"错误信息已复制",Toast.LENGTH_SHORT).show();
    }
    void openErrorPage(){
        openReport(errorReport,false);
    }
    void openReport(String report,boolean fallback){
        if(app.destroyed||report.isEmpty()||app.contentFrame==null)return;showingFallbackReport=fallback;
        if(errorPage==null){
            LinearLayout page=new LinearLayout(app);page.setOrientation(LinearLayout.VERTICAL);page.setPadding(app.dp(20),app.dp(16),app.dp(20),app.dp(16));page.setBackgroundColor(Color.rgb(246,248,251));
            LinearLayout header=new LinearLayout(app);header.setGravity(Gravity.CENTER_VERTICAL);
            Button back=app.pageBackButton(this::closeErrorPage);LinearLayout.LayoutParams backLine=new LinearLayout.LayoutParams(app.dp(62),app.dp(40));backLine.rightMargin=app.dp(10);header.addView(back,backLine);
            errorTitle=new TextView(app);errorTitle.setTextSize(19);errorTitle.setTypeface(null,Typeface.BOLD);errorTitle.setTextColor(Color.rgb(24,38,56));
            header.addView(errorTitle,new LinearLayout.LayoutParams(0,-2,1));
            errorCopy=new Button(app);errorCopy.setText("复制报告");errorCopy.setTextSize(12);errorCopy.setMinWidth(0);errorCopy.setMinimumWidth(0);errorCopy.setOnClickListener(v->copyFailure());
            LinearLayout.LayoutParams copyTop=new LinearLayout.LayoutParams(-2,app.dp(40));copyTop.rightMargin=app.dp(6);header.addView(errorCopy,copyTop);
            reportHome=app.homeIconButton(app::goHomeFromNative);header.addView(reportHome,new LinearLayout.LayoutParams(app.dp(36),app.dp(40)));
            page.addView(header,new LinearLayout.LayoutParams(-1,-2));
            ScrollView scroll=new ScrollView(app);scroll.setFillViewport(false);
            errorText=new TextView(app);errorText.setTextSize(12);errorText.setTypeface(Typeface.MONOSPACE);errorText.setTextColor(Color.rgb(55,70,90));errorText.setLineSpacing(0,1.08f);errorText.setPadding(app.dp(12),app.dp(12),app.dp(12),app.dp(12));errorText.setTextIsSelectable(true);
            GradientDrawable box=new GradientDrawable();box.setColor(Color.rgb(238,242,247));box.setCornerRadius(app.dp(10));box.setStroke(app.dp(1),Color.rgb(214,222,232));errorText.setBackground(box);
            scroll.addView(errorText,new ScrollView.LayoutParams(-1,-2));
            LinearLayout.LayoutParams scrollLine=new LinearLayout.LayoutParams(-1,0,1);scrollLine.topMargin=app.dp(12);page.addView(scroll,scrollLine);
            errorRebuild=new Button(app);errorRebuild.setText("重建数据库");errorRebuild.setTextColor(Color.rgb(190,65,65));errorRebuild.setOnClickListener(v->askRebuild());
            LinearLayout.LayoutParams rebuildLine=new LinearLayout.LayoutParams(-2,app.dp(48));rebuildLine.gravity=Gravity.END;rebuildLine.topMargin=app.dp(8);page.addView(errorRebuild,rebuildLine);
            errorPage=new FrameLayout(app);errorPage.setBackgroundColor(Color.rgb(246,248,251));errorPage.addView(page,new FrameLayout.LayoutParams(-1,-1));
        }
        errorTitle.setText(fallback?"初始化降级报告":"初始化问题");errorText.setText(report);
        reportHome.setVisibility(rootReady&&app.browser.web!=null?View.VISIBLE:View.GONE);
        errorRebuild.setVisibility(!fallback&&canRebuild&&!rebuilding?View.VISIBLE:View.GONE);
        boolean newErrorParent=errorPage.getParent()!=app.contentFrame;
        if(newErrorParent){if(errorPage.getParent() instanceof android.view.ViewGroup)((android.view.ViewGroup)errorPage.getParent()).removeView(errorPage);app.contentFrame.addView(errorPage,new FrameLayout.LayoutParams(-1,-1));}
        boolean entering=newErrorParent||PageMotion.closing(errorPage)||errorPage.getVisibility()!=View.VISIBLE||!errorPage.isAttachedToWindow();
        errorPage.bringToFront();if(entering)PageMotion.enter(errorPage);
    }
    void closeErrorPage(){PageMotion.hide(errorPage);showingFallbackReport=false;}
    String prepareDowngradeReport(JSONObject probe,JSONObject status,List<StartupFeedback.Notice> notices){
        if(notices.isEmpty())return "";
        String report="时间："+new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z",Locale.US).format(new Date())+"\n版本："+appVersion()+"\n设备："+Build.MANUFACTURER+" "+Build.MODEL+" / Android "+Build.VERSION.RELEASE+"\n"+StartupFeedback.downgradeReport(probe,status);
        archiveError("fallback","初始化降级",report);
        return report;
    }
    static String summary(Throwable error){
        return error instanceof InitFailure&&error.getMessage()!=null?error.getMessage():FriendlyError.message(error);
    }
    String appVersion(){
        try{android.content.pm.PackageInfo info=app.getPackageManager().getPackageInfo(app.getPackageName(),0);return info.versionName+" ("+info.versionCode+")";}
        catch(Exception e){return "未知";}
    }
    String failureReport(String failedAt,Throwable error){
        StringBuilder out=new StringBuilder();
        out.append("ACol-Battery 初始化错误报告\n");
        out.append("时间：").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z",Locale.US).format(new Date())).append('\n');
        out.append("版本：").append(appVersion()).append('\n');
        out.append(StartupFeedback.failure(summary(error)).text()).append('\n');
        out.append("阶段：").append(failedAt).append('\n');
        out.append("设备：").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append(" / Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        out.append("ABI：").append(String.join(", ",Build.SUPPORTED_ABIS)).append('\n');
        out.append(StartupFeedback.technicalDetails(error,StartupController::diagnostics)).append('\n');
        return StartupFeedback.compactReport(out.toString());
    }
    static String diagnostics(Throwable error){
        if(error instanceof InitFailure)return ((InitFailure)error).diagnostics;
        if(error instanceof RootSession.CommandFailure)return ((RootSession.CommandFailure)error).diagnostics;
        return "";
    }
    void writeFailure(String text){
        archiveError("failure","初始化或连接失败",text);
    }
    void archiveError(String kind,String title,String report){try{new ErrorLogStore(new File(app.getFilesDir(),"error-logs")).append(kind,title,report);}catch(Exception ignored){}}
    void discardOldReports(){
        for(String name:new String[]{"initialization-error.txt","initialization-fallback.txt"})
            try{java.nio.file.Files.deleteIfExists(new File(app.getFilesDir(),name).toPath());}catch(IOException ignored){}
    }
    void showFailureUI(String report){
        if(app.destroyed)return;
        errorReport=StartupFeedback.compactReport(report);
        canRebuild=StartupFeedback.databaseProblem(errorReport);rebuilding=false;
        stage.setText("初始化未完成");
        detail.setText(StartupFeedback.savedSummary(errorReport));
        progress.setVisibility(View.GONE);
        retry.setVisibility(View.VISIBLE);
        if(errorActions!=null){
            errorView.setText("查看报告");
            rebuild.setVisibility(canRebuild?View.VISIBLE:View.GONE);
            errorActions.setVisibility(View.VISIBLE);
        }
        if(errorPage!=null)errorPage.setVisibility(View.GONE);
    }
    void fail(String failedAt,Throwable error){
        if(app.destroyed)return;
        String report=failureReport(failedAt,error);
        writeFailure(report);
        app.runOnUiThread(()->showFailureUI(report));
    }
    void askRebuild(){
        if(!canRebuild||rebuilding||app.destroyed)return;
        LinearLayout panel=new LinearLayout(app);panel.setOrientation(LinearLayout.VERTICAL);panel.setPadding(app.dp(22),app.dp(20),app.dp(22),app.dp(12));
        TextView title=new TextView(app);title.setText("重建数据库？");title.setTextSize(21);title.setTypeface(null,Typeface.BOLD);panel.addView(title);
        TextView text=new TextView(app);text.setText("数据库内的记录和设置会重置，当前记录也会结束。\n请选择外部位置保存原数据库，保存并校验成功后才会重建。");text.setTextSize(15);text.setLineSpacing(app.dp(3),1.05f);text.setPadding(0,app.dp(14),0,app.dp(18));panel.addView(text);
        LinearLayout buttons=new LinearLayout(app);buttons.setGravity(Gravity.CENTER_VERTICAL);panel.addView(buttons,new LinearLayout.LayoutParams(-1,app.dp(48)));
        Button cancel=new Button(app);cancel.setText("取消");cancel.setTextColor(Color.rgb(7,130,103));cancel.setBackgroundColor(Color.TRANSPARENT);buttons.addView(cancel,new LinearLayout.LayoutParams(-2,-1));
        buttons.addView(new View(app),new LinearLayout.LayoutParams(0,1,1));
        Button confirm=new Button(app);confirm.setText("确认重建");confirm.setTextColor(Color.rgb(190,65,65));confirm.setBackgroundColor(Color.TRANSPARENT);buttons.addView(confirm,new LinearLayout.LayoutParams(-2,-1));
        AlertDialog dialog=new AlertDialog.Builder(app).setView(panel).create();cancel.setOnClickListener(v->dialog.dismiss());confirm.setOnClickListener(v->{dialog.dismiss();chooseDatabaseBackup();});dialog.show();
    }
    void chooseDatabaseBackup(){
        try{String name="ACol电池-数据库备份-"+new SimpleDateFormat("yyyyMMdd-HHmmss",Locale.US).format(new Date())+".zip";
            Intent picker=new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/zip").putExtra(Intent.EXTRA_TITLE,name);app.startActivityForResult(picker,SAVE_DATABASE_REBUILD);
        }catch(Exception error){Toast.makeText(app,"无法打开系统文件选择器，数据库未更改。",Toast.LENGTH_LONG).show();}
    }
    void rebuildDatabase(Uri destination){
        if(!canRebuild||rebuilding||app.destroyed)return;
        canRebuild=false;rebuilding=true;closeErrorPage();retry.setVisibility(View.GONE);errorActions.setVisibility(View.GONE);progress.setVisibility(View.VISIBLE);
        phase("重建数据库","正在保存并校验原数据库。");
        app.worker.execute(()->{
            String token=java.util.UUID.randomUUID().toString();boolean committed=false;
            try{
                disconnect();openRoot();
                if(!root.command("id -u",10).equals("0"))throw new IOException("未获得 root 权限。");
                JSONObject result=new JSONObject(root.command(admin("prepare-database-export "+token),10));
                if(!result.optBoolean("ok"))throw new IOException("无法准备数据库备份。");
                long size=result.getLong("size");String expected=result.getString("sha256");
                if(size<=0||!expected.matches("[0-9a-f]{64}"))throw new IOException("数据库备份信息无效。");
                MessageDigest written=MessageDigest.getInstance("SHA-256");long total=0;
                try(OutputStream out=app.getContentResolver().openOutputStream(destination,"wt")){
                    if(out==null)throw new IOException("无法打开所选文件。");
                    for(long block=0;total<size;block++){
                        String encoded=root.command("dd if="+RootSession.quote(BASE+"/rebuild-export-"+token+".zip")+" bs=49152 skip="+block+" count=1 2>/dev/null | base64",10);
                        byte[] bytes=Base64.decode(encoded,Base64.DEFAULT);if(bytes.length==0||total+bytes.length>size)throw new IOException("数据库备份读取不完整。");
                        out.write(bytes);written.update(bytes);total+=bytes.length;
                    }out.flush();
                }
                if(!hexDigest(written.digest()).equals(expected))throw new IOException("数据库备份校验失败。");
                MessageDigest saved=MessageDigest.getInstance("SHA-256");long savedSize=0;
                try(InputStream in=app.getContentResolver().openInputStream(destination)){
                    if(in==null)throw new IOException("无法重新读取所选文件以校验备份。");byte[] buf=new byte[65536];int n;while((n=in.read(buf))!=-1){saved.update(buf,0,n);savedSize+=n;if(savedSize>size)throw new IOException("保存的数据库备份大小不正确。");}
                }
                if(savedSize!=size||!hexDigest(saved.digest()).equals(expected))throw new IOException("保存的数据库备份校验失败。");
                app.runOnUiThread(()->{if(!app.destroyed)phase("重建数据库","备份已保存，正在重建数据库。");});
                JSONObject reset=new JSONObject(root.command(admin("rebuild-database-external "+token),10));
                if(!reset.optBoolean("ok"))throw new IOException("数据库重建未完成，请重试。");committed=true;
                RootSession authorized=root;
                app.runOnUiThread(()->{if(!app.destroyed){rebuilding=false;Toast.makeText(app,"数据库备份已保存，正在重新初始化",Toast.LENGTH_LONG).show();initialize(authorized);}});
            }catch(Exception failure){
                if(!committed&&root!=null)try{root.command(admin("cancel-database-export "+token),10);}catch(Exception ignored){}
                disconnect();fail("重建数据库",failure);
            }
        });
    }
    static String hexDigest(byte[] bytes){StringBuilder out=new StringBuilder();for(byte b:bytes)out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString();}
    static String digest(byte[] bytes)throws Exception{StringBuilder out=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes))out.append(String.format("%02x",b&255));return out.toString();}
    String assetText(String name)throws IOException{try(InputStream in=app.getAssets().open(name)){ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[4096];int n;while((n=in.read(b))>0)out.write(b,0,n);return out.toString("UTF-8").trim();}}
    File payload()throws Exception{
        File file=new File(app.getCacheDir(),"aco-payload.zip");try(InputStream in=app.getAssets().open("payload.zip");OutputStream out=new FileOutputStream(file)){byte[] b=new byte[8192];int n;while((n=in.read(b))>0)out.write(b,0,n);}return file;
    }
    String admin(String action){
        return "aco_admin="+RUNTIME+"/bin/daemon.dex.jar; if [ -f "+BASE+"/update.transaction ] && [ -f "+RuntimeSetup.STAGE+"/bin/daemon.dex.jar ]; then aco_admin="+RuntimeSetup.STAGE+"/bin/daemon.dex.jar; fi; CLASSPATH=\"$aco_admin\" /system/bin/app_process /system/bin cn.astriafr.acobattery.daemon.Admin "+action;
    }
    void initialize(){initialize(null);}
    void initialize(RootSession authorized){
        if(app.destroyed){if(authorized!=null)authorized.close();return;}
        if(app.getSharedPreferences("maintenance",Context.MODE_PRIVATE).getBoolean("clearPending",false)){if(authorized!=null)authorized.close();app.clearAllData();return;}
        synchronized(channelLock){rootReady=false;waitingRequests.clear();}
        canRebuild=false;if(rebuild!=null)rebuild.setVisibility(View.GONE);
        probeReport=null;downgradeReport="";pendingNotices=java.util.Collections.emptyList();
        if(startupNotice!=null){startupNotice.dismiss();startupNotice=null;}
        retry.setVisibility(View.GONE);progress.setVisibility(View.VISIBLE);
        if(errorActions!=null)errorActions.setVisibility(View.GONE);
        if(errorText!=null)errorText.setText("");
        closeErrorPage();
        app.worker.execute(()->{
            try{
                discardOldReports();
                if(root!=authorized)disconnect();
                phase("识别系统","已识别 Android "+Build.VERSION.RELEASE+"（API "+Build.VERSION.SDK_INT+"），自动选择兼容接口。");
                if(Build.VERSION.SDK_INT<35)throw new InitFailure("需要 Android 15 或更新系统。");
                if(!java.util.Arrays.asList(Build.SUPPORTED_64_BIT_ABIS).contains("arm64-v8a"))throw new InitFailure("当前设备不支持 ARM64。");
                if(authorized==null)phase("等待 root 授权","已识别 Android "+Build.VERSION.RELEASE+"。请在 root 管理器中允许 ACol电池获取 root 权限。");
                else phase("root 权限已就绪","正在复用当前连接，准备运行环境。");
                if(authorized==null){openRoot();String uid=root.command("id -u",10);if(!uid.equals("0"))throw new InitFailure("未获得 root 权限。请在 root 管理器中允许 ACol电池使用 su，然后重试。");}
                else synchronized(channelLock){if(app.destroyed){authorized.close();return;}root=authorized;channelLost=false;}
                if(app.destroyed)return;
                try{root.command("[ -x /system/bin/app_process ] && [ -x /system/bin/cp ] && [ -x /system/bin/chmod ] && [ -x /system/bin/mv ] || { echo '系统缺少部署独立后台所需的运行工具。'; false; }",10);}
                catch(RootSession.CommandFailure failure){throw new InitFailure("系统缺少 app_process、cp、chmod 或 mv，无法部署独立后台。",failure.diagnostics);}
                phase("准备独立运行环境","程序与记录保存在独立的 root 目录。");
                File archive=payload();String version=assetText("payload.sha256");
                String cert=digest(app.getPackageManager().getPackageInfo(app.getPackageName(),PackageManager.GET_SIGNING_CERTIFICATES).signingInfo.getApkContentsSigners()[0].toByteArray());
                int user=android.os.Process.myUid()/100000;
                try{root.command("umask 077; mkdir -p "+BASE+"; chmod 700 "+BASE+"; if [ -f "+BASE+"/owner.cert ] && [ \"$(cat "+BASE+"/owner.cert)\" != "+RootSession.quote(cert)+" ]; then echo '历史目录签名不一致，已停止初始化。'; false; else printf '%s' "+RootSession.quote(cert)+" > "+BASE+"/owner.cert; printf '%s' "+user+" > "+BASE+"/owner.user; fi",10);}
                catch(RootSession.CommandFailure failure){
                    if(failure.diagnostics.contains("历史目录签名不一致"))throw new InitFailure("历史 root 目录的签名与当前安装不一致，已停止初始化。请安装同一签名的版本；如需重建目录，请先备份记录。",failure.diagnostics);
                    throw failure;
                }
                root.command(RuntimeSetup.recover()+"; /system/bin/rm -f "+BASE+"/cleanup.request",10);
                String current=root.command("cat "+BASE+"/runtime.version 2>/dev/null || true",10);
                boolean updating=!version.equals(current);
                if(updating){
                    // Run the new updater so even old recorders are suspended without
                    // executing their old finish-and-auto-delete shutdown behavior.
                    File extracted=java.nio.file.Files.createTempDirectory(app.getCacheDir().toPath(),"aco-runtime-").toFile();
                    try{
                        RuntimePayload.extract(archive,extracted,version);
                        root.command(RuntimeSetup.stage(extracted),10);
                        checkProbe(RuntimeSetup.STAGE);
                        phase("更新独立后台","新环境已检查，正在切换运行文件；失败时恢复原环境。");
                        root.command(RuntimeSetup.install(version),10);
                    }finally{RuntimePayload.discard(extracted);}
                }
                if(app.destroyed)return;
                if(!updating){checkProbe(RUNTIME);}
                phase("连接记录服务","确认后台运行状态并建立前台连接。");
                if(!updating)root.command("if [ ! -f "+BASE+"/update.transaction ]; then /system/bin/rm -f "+BASE+"/update.pending; fi; "+RuntimeSetup.installBoot(),10);
                JSONObject startupStatus=connectRoot(text->{connectionPhase(text);phase("连接记录服务",text);},updating);
                java.util.List<StartupFeedback.Notice> notices=StartupFeedback.notices(probeReport,startupStatus);
                String report=prepareDowngradeReport(probeReport,startupStatus,notices);
                app.getSharedPreferences("startup",Context.MODE_PRIVATE).edit().putBoolean("initialized",true).putInt("androidSdk",Build.VERSION.SDK_INT).commit();
                archive.delete();
                phase("初始化完成","正在打开电池记录。");
                app.runOnUiThread(()->{if(!app.destroyed){pendingNotices=notices;downgradeReport=report;app.browser.showWeb();}});
            }catch(Exception e){
                disconnect();
                if(app.clearingData||app.transferringData)return;
                fail(currentPhase,e);
            }
        });
    }
    void checkProbe(String directory)throws Exception{
        phase("检查电池接口","检查底层读数、单双电芯与采样能力。");
        String probe=root.command("CLASSPATH="+directory+"/bin/daemon.dex.jar /system/bin/app_process /system/bin cn.astriafr.acobattery.daemon.Main --probe "+directory,10);
        JSONObject report=null;for(String line:probe.split("\\n"))try{JSONObject candidate=new JSONObject(line);if(candidate.has("reading"))report=candidate;}catch(JSONException ignored){}
        if(report==null)throw new InitFailure("未能取得电池接口检查结果。",probe.trim().isEmpty()?"探针没有输出。":"探针输出：\n"+probe.trim());
        probeReport=report;
        JSONObject reading=report.getJSONObject("reading");
        String problem=RecordingGate.problem(reading.optDouble("rm",Double.NaN),reading.optDouble("pct",Double.NaN));
        if(!problem.isEmpty())root.command("umask 077; touch "+BASE+"/"+RecordingGate.DISABLED+"; printf '%s' "+RootSession.quote(problem)+" > "+BASE+"/"+RecordingGate.PROBLEM,10);
        int cells=reading.optInt("cellCount",0);
        phase("电池接口检查完成",cells==2?"已识别双电芯。":cells==1?"已识别单电芯。":"正在继续准备后台。");
    }
    void connectExisting(){
        if(app.destroyed)return;
        rootReady=false;
        connectionPhase("等待 root 授权…");
        app.worker.execute(()->{
            try{
                openRoot();if(!root.command("id -u",10).equals("0"))throw new IOException("未获得 root 权限。");
                if(app.destroyed)return;
                connectionPhase("正在连接后台…");root.command(RuntimeSetup.recover(),10);
                String version=assetText("payload.sha256");
                String state=root.command("if [ -f "+BASE+"/initialized ] && [ -f "+RUNTIME+"/bin/daemon.dex.jar ] && [ ! -e "+BASE+"/update.pending ] && [ ! -e "+BASE+"/cleanup.request ] && [ \"$(cat "+BASE+"/runtime.version 2>/dev/null)\" = "+RootSession.quote(version)+" ]; then echo READY; else echo SETUP; fi",10);
                if(!state.equals("READY")){
                    RootSession authorized=root;
                    app.runOnUiThread(()->{if(app.destroyed)return;app.browser.destroy();showInitialization();initialize(authorized);});return;
                }
                // Reuse a healthy recorder, or restart it without re-probing.
                JSONObject startupStatus=connectRoot(this::connectionPhase,false);
                java.util.List<StartupFeedback.Notice> notices=StartupFeedback.notices(null,startupStatus);
                String report=prepareDowngradeReport(null,startupStatus,notices);
                app.runOnUiThread(()->{if(!app.destroyed){pendingNotices=notices;downgradeReport=report;showStartupNotice();}});
            }catch(Exception e){
                disconnect();
                if(app.clearingData||app.transferringData)return;
                String report=failureReport("连接已有后台",e);
                writeFailure(report);
                app.runOnUiThread(()->{
                    if(app.destroyed)return;
                    app.browser.destroy();
                    showInitialization();
                    showFailureUI(report);
                });
            }
        });
    }
    void showStartupNotice(){
        if(app.destroyed||app.isFinishing()||!rootReady||!app.browser.pageLoaded||pendingNotices.isEmpty()||startupNotice!=null)return;
        boolean dark=app.browser.activeDarkTheme==null?app.systemDark():app.browser.activeDarkTheme;
        int background=dark?Color.rgb(22,27,25):Color.WHITE,foreground=dark?Color.rgb(230,237,232):Color.rgb(24,38,32);
        int secondary=dark?Color.rgb(170,185,176):Color.rgb(96,113,103),green=dark?Color.rgb(112,211,173):Color.rgb(7,130,103);
        LinearLayout panel=new LinearLayout(app);panel.setOrientation(LinearLayout.VERTICAL);panel.setPadding(app.dp(22),app.dp(22),app.dp(22),app.dp(14));
        GradientDrawable box=new GradientDrawable();box.setColor(background);box.setCornerRadius(app.dp(22));panel.setBackground(box);
        TextView title=new TextView(app);title.setText("初始化完成，部分功能受限");title.setTextSize(21);title.setTypeface(null,Typeface.BOLD);title.setTextColor(foreground);panel.addView(title);
        TextView intro=new TextView(app);intro.setText("应用已可使用。以下项目已降级或跳过：");intro.setTextSize(14);intro.setTextColor(secondary);intro.setPadding(0,app.dp(10),0,app.dp(6));panel.addView(intro);
        ScrollView scroll=new ScrollView(app){@Override protected void onMeasure(int width,int height){int max=(int)(app.getResources().getDisplayMetrics().heightPixels*0.52f);super.onMeasure(width,View.MeasureSpec.makeMeasureSpec(max,View.MeasureSpec.AT_MOST));}};
        LinearLayout items=new LinearLayout(app);items.setOrientation(LinearLayout.VERTICAL);
        for(StartupFeedback.Notice notice:pendingNotices){
            TextView name=new TextView(app);name.setText(notice.title);name.setTextSize(16);name.setTypeface(null,Typeface.BOLD);name.setTextColor(foreground);name.setPadding(0,app.dp(15),0,app.dp(5));items.addView(name);
            TextView text=new TextView(app);text.setText(notice.text);text.setTextSize(14);text.setTextColor(secondary);text.setLineSpacing(app.dp(3),1.05f);items.addView(text);
        }
        scroll.addView(items,new ScrollView.LayoutParams(-1,-2));panel.addView(scroll,new LinearLayout.LayoutParams(-1,-2));
        LinearLayout actions=new LinearLayout(app);actions.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams actionLine=new LinearLayout.LayoutParams(-1,app.dp(48));actionLine.topMargin=app.dp(12);panel.addView(actions,actionLine);
        Button report=new Button(app);report.setText("错误报告");report.setTextSize(15);report.setTextColor(secondary);report.setBackgroundColor(Color.TRANSPARENT);actions.addView(report,new LinearLayout.LayoutParams(-2,-1));
        actions.addView(new View(app),new LinearLayout.LayoutParams(0,1,1));
        Button acknowledge=new Button(app);acknowledge.setText("继续使用");acknowledge.setTextSize(16);acknowledge.setTextColor(green);acknowledge.setBackgroundColor(Color.TRANSPARENT);actions.addView(acknowledge,new LinearLayout.LayoutParams(-2,-1));
        AlertDialog dialog=new AlertDialog.Builder(app).setView(panel).create();startupNotice=dialog;
        dialog.setCanceledOnTouchOutside(false);report.setOnClickListener(v->{dialog.dismiss();openReport(downgradeReport,true);});acknowledge.setOnClickListener(v->dialog.dismiss());dialog.setOnDismissListener(v->{if(startupNotice==dialog)startupNotice=null;});
        dialog.show();
        if(dialog.getWindow()!=null){dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);dialog.getWindow().setLayout(Math.min(app.getResources().getDisplayMetrics().widthPixels-app.dp(32),app.dp(600)),-2);}
        pendingNotices=java.util.Collections.emptyList();
    }
}
