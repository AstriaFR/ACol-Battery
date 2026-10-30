package cn.astriafr.acobattery;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.BitmapFactory;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.fonts.Font;
import android.graphics.fonts.FontFamily;
import android.graphics.fonts.FontStyle;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.KeyEvent;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;
import android.webkit.*;
import android.widget.*;
import cn.astriafr.acobattery.common.FriendlyError;
import cn.astriafr.acobattery.common.RuntimePayload;
import cn.astriafr.acobattery.common.RecordingGate;
import cn.astriafr.acobattery.common.StartupFeedback;
import cn.astriafr.acobattery.common.ErrorLogStore;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.List;
import java.util.concurrent.*;

public final class MainActivity extends Activity {
    // Stable package, signing identity, root paths and WebView origin preserve local data during renaming.
    static final String BASE="/data/adb/aco_battery_app",RUNTIME=BASE+"/runtime";
    static final int SAVE_DATABASE_REBUILD=6103;
    static final class InitFailure extends Exception {
        final String diagnostics;
        InitFailure(String message){this(message,"");}
        InitFailure(String message,String diagnostics){super(message);this.diagnostics=diagnostics==null?"":diagnostics;}
    }
    final ExecutorService worker=Executors.newSingleThreadExecutor();
    final java.util.concurrent.ConcurrentHashMap<String,String> iconCache=new java.util.concurrent.ConcurrentHashMap<>();
    final Object iconLock=new Object();
    RootSession root;
    WebView web;
    FrameLayout contentFrame,errorPage;boolean immersive,backPending;String currentPhase="初始化";
    TextView stage,detail,errorText,errorTitle;
    Button retry,errorView,errorCopy,rebuild,errorRebuild;
    boolean canRebuild,rebuilding;
    LinearLayout errorActions;
    ProgressBar progress;
    String errorReport="",downgradeReport="";boolean showingFallbackReport;
    JSONObject probeReport;
    boolean pageLoaded;
    java.util.List<StartupFeedback.Notice> pendingNotices=java.util.Collections.emptyList();
    AlertDialog startupNotice;
    AlertDialog clearDataDialog;
    ErrorLogPages errorLogs;
    View reportHome;
    boolean clearingData;
    boolean transferringData;
    DataTransferUi dataTransfer;
    Boolean activeDarkTheme;
    long themeBarGeneration;
    volatile boolean destroyed,initialized,rootReady;
    volatile String connectionStatus="正在连接后台…";
    final Object channelLock=new Object();
    final OnBackInvokedCallback backCallback=this::back;
    OnBackInvokedDispatcher backDispatcher;
    final java.util.concurrent.ConcurrentLinkedQueue<JSONObject> waitingRequests=new java.util.concurrent.ConcurrentLinkedQueue<>();
    @Override protected void attachBaseContext(Context base){
        // Override font scaling only; keep density, locale and orientation live.
        Configuration fixedFont=new Configuration();fixedFont.fontScale=1.0f;
        super.attachBaseContext(base.createConfigurationContext(fixedFont));
    }
    @Override public void onConfigurationChanged(Configuration configuration){
        super.onConfigurationChanged(configuration);
        if(web!=null){
            web.getSettings().setTextZoom(100);
            web.evaluateJavascript("window.dispatchEvent(new CustomEvent('aco-system-theme',{detail:"+systemDark()+"}))",null);
        }
    }
    boolean systemDark(){return (getResources().getConfiguration().uiMode&Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES;}
    int dp(float value){return Math.round(getResources().getDisplayMetrics().density*value);}
    @Override public void onCreate(Bundle state){
        super.onCreate(state);getWindow().setDecorFitsSystemWindows(false);getWindow().setStatusBarColor(Color.WHITE);getWindow().setNavigationBarColor(Color.WHITE);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE|View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        dataTransfer=new DataTransferUi(this);if(dataTransfer.resume())return;
        if(getSharedPreferences("maintenance",MODE_PRIVATE).getBoolean("clearPending",false)){clearAllData();return;}
        worker.execute(this::importOldErrorReports);
        if(getSharedPreferences("startup",MODE_PRIVATE).getBoolean("initialized",false)&&getSharedPreferences("startup",MODE_PRIVATE).getInt("androidSdk",0)==Build.VERSION.SDK_INT){showWeb();connectExisting();}
        else{showInitialization();initialize();}
    }
    void registerBack(){
        if(destroyed||!getWindow().getDecorView().isAttachedToWindow())return;
        OnBackInvokedDispatcher dispatcher=getWindow().getDecorView().findOnBackInvokedDispatcher();
        if(dispatcher==null)return;
        if(backDispatcher!=null&&backDispatcher!=dispatcher)backDispatcher.unregisterOnBackInvokedCallback(backCallback);
        backDispatcher=dispatcher;
        dispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_OVERLAY,backCallback);
    }
    @Override public void onAttachedToWindow(){super.onAttachedToWindow();registerBack();}
    @Override protected void onResume(){super.onResume();getWindow().getDecorView().post(this::registerBack);}
    @Override public void onWindowFocusChanged(boolean focused){super.onWindowFocusChanged(focused);if(focused)registerBack();}
    @Override public void onBackPressed(){back();}
    @Override public boolean dispatchKeyEvent(KeyEvent event){
        if(event.getKeyCode()==KeyEvent.KEYCODE_BACK){
            if(event.getAction()==KeyEvent.ACTION_UP&&!event.isCanceled())back();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }
    @Override protected void onActivityResult(int request,int result,android.content.Intent intent){
        super.onActivityResult(request,result,intent);if(dataTransfer!=null&&(request==DataTransferUi.PICK_RESTORE||request==DataTransferUi.SAVE_BACKUP))dataTransfer.result(request,result,intent);
        else if(request==SAVE_DATABASE_REBUILD&&result==RESULT_OK&&intent!=null&&intent.getData()!=null)rebuildDatabase(intent.getData());
    }
    void showInitialization(){
        initialized=false;errorPage=null;errorText=null;errorTitle=null;errorCopy=null;errorRebuild=null;canRebuild=false;showingFallbackReport=false;
        LinearLayout layout=new LinearLayout(this);layout.setOrientation(LinearLayout.VERTICAL);layout.setGravity(Gravity.CENTER_VERTICAL);layout.setPadding(dp(28),dp(32),dp(28),dp(32));layout.setBackgroundColor(Color.rgb(246,248,251));
        LinearLayout title=new LinearLayout(this);title.setGravity(Gravity.CENTER_VERTICAL);
        ImageView icon=new ImageView(this);try(InputStream in=getAssets().open("webroot/assets/acol-icon.png")){icon.setImageBitmap(BitmapFactory.decodeStream(in));}catch(IOException ignored){}
        title.addView(icon,new LinearLayout.LayoutParams(dp(58),dp(58)));
        LinearLayout names=new LinearLayout(this);names.setOrientation(LinearLayout.VERTICAL);names.setPadding(dp(14),0,0,0);
        TextView name=new TextView(this);name.setText("ACol 电池");name.setTextSize(32);
        try{
            Font latin=new Font.Builder(getAssets(),"webroot/assets/acol-brand.ttf").setWeight(700).build();
            Font chinese=new Font.Builder(getAssets(),"webroot/assets/acol-cjk.otf").setWeight(700).build();
            name.setTypeface(new Typeface.CustomFallbackBuilder(new FontFamily.Builder(latin).build())
                .addCustomFallback(new FontFamily.Builder(chinese).build())
                .setStyle(new FontStyle(700,FontStyle.FONT_SLANT_UPRIGHT)).build());
        }catch(Exception ignored){name.setTypeface(null,Typeface.BOLD);}
        name.setTextColor(Color.rgb(24,38,56));names.addView(name);
        TextView version=new TextView(this);try{version.setText(getPackageManager().getPackageInfo(getPackageName(),0).versionName+" · AstriaFR");}catch(Exception ignored){version.setText("2.0 · AstriaFR");}version.setTextColor(Color.rgb(96,113,133));version.setTextSize(13);names.addView(version);title.addView(names);layout.addView(title);
        stage=new TextView(this);stage.setTextSize(20);stage.setMinLines(1);stage.setIncludeFontPadding(false);stage.setTypeface(null,Typeface.BOLD);stage.setTextColor(Color.rgb(7,130,103));stage.setPadding(0,dp(32),0,dp(12));layout.addView(stage);
        detail=new TextView(this);detail.setTextSize(14);detail.setIncludeFontPadding(false);detail.setMinLines(2);detail.setMinHeight(dp(48));detail.setGravity(Gravity.TOP);detail.setTextColor(Color.rgb(96,113,133));detail.setLineSpacing(dp(5),1);layout.addView(detail);
        progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);progress.setIndeterminate(true);LinearLayout.LayoutParams line=new LinearLayout.LayoutParams(-1,dp(4));line.topMargin=dp(24);layout.addView(progress,line);
        retry=new Button(this);retry.setText("重试初始化");retry.setVisibility(View.GONE);retry.setOnClickListener(v->initialize());LinearLayout.LayoutParams button=new LinearLayout.LayoutParams(-1,dp(48));button.topMargin=dp(22);layout.addView(retry,button);
        errorActions=new LinearLayout(this);errorActions.setOrientation(LinearLayout.VERTICAL);errorActions.setVisibility(View.GONE);
        errorView=new Button(this);errorView.setText("查看报告");errorView.setOnClickListener(v->openErrorPage());
        errorActions.addView(errorView,new LinearLayout.LayoutParams(-1,dp(44)));
        rebuild=new Button(this);rebuild.setText("重建数据库");rebuild.setTextColor(Color.rgb(190,65,65));rebuild.setVisibility(View.GONE);rebuild.setOnClickListener(v->askRebuild());
        errorActions.addView(rebuild,new LinearLayout.LayoutParams(-1,dp(44)));
        LinearLayout.LayoutParams actionLine=new LinearLayout.LayoutParams(-1,-2);actionLine.topMargin=dp(12);layout.addView(errorActions,actionLine);
        mount(layout);
        try{
            File previous=new File(getFilesDir(),"initialization-error.txt");
            if(previous.isFile()&&previous.length()<=524288){errorReport=StartupFeedback.compactReport(new String(java.nio.file.Files.readAllBytes(previous.toPath()),StandardCharsets.UTF_8));if(!errorReport.isEmpty()){errorView.setText("查看上次错误");errorActions.setVisibility(View.VISIBLE);}}
        }catch(IOException ignored){}
    }
    void phase(String name,String text){currentPhase=name;if(destroyed)return;runOnUiThread(()->{stage.setText(name);detail.setText(text);});}
    void connectionPhase(String text){
        connectionStatus=text;
        runOnUiThread(()->{if(!destroyed&&web!=null)web.evaluateJavascript("window.dispatchEvent(new CustomEvent('aco-connection',{detail:"+JSONObject.quote(text)+"}))",null);});
    }
    void connected()throws Exception{
        synchronized(channelLock){
            if(destroyed)throw new IOException("页面已关闭。");rootReady=true;
            JSONObject request;while((request=waitingRequests.poll())!=null)root.request(request);
        }
        connectionPhase("");
    }
    void copyFailure(){
        String report=errorPage!=null&&errorPage.getVisibility()==View.VISIBLE&&errorText!=null?errorText.getText().toString():errorReport;if(report.isEmpty())return;
        ClipboardManager clipboard=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
        if(clipboard==null)return;
        clipboard.setPrimaryClip(ClipData.newPlainText("ACol 错误报告",report));
        Toast.makeText(this,"错误信息已复制",Toast.LENGTH_SHORT).show();
    }
    void openErrorPage(){
        openReport(errorReport,false);
    }
    void openReport(String report,boolean fallback){
        if(report.isEmpty()||contentFrame==null)return;showingFallbackReport=fallback;
        if(errorPage==null){
            LinearLayout page=new LinearLayout(this);page.setOrientation(LinearLayout.VERTICAL);page.setPadding(dp(20),dp(16),dp(20),dp(16));page.setBackgroundColor(Color.rgb(246,248,251));
            LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);
            Button back=pageBackButton(this::closeErrorPage);LinearLayout.LayoutParams backLine=new LinearLayout.LayoutParams(dp(62),dp(40));backLine.rightMargin=dp(10);header.addView(back,backLine);
            errorTitle=new TextView(this);errorTitle.setTextSize(19);errorTitle.setTypeface(null,Typeface.BOLD);errorTitle.setTextColor(Color.rgb(24,38,56));
            header.addView(errorTitle,new LinearLayout.LayoutParams(0,-2,1));
            errorCopy=new Button(this);errorCopy.setText("复制报告");errorCopy.setTextSize(12);errorCopy.setMinWidth(0);errorCopy.setMinimumWidth(0);errorCopy.setOnClickListener(v->copyFailure());
            LinearLayout.LayoutParams copyTop=new LinearLayout.LayoutParams(-2,dp(40));copyTop.rightMargin=dp(6);header.addView(errorCopy,copyTop);
            reportHome=homeIconButton(this::goHomeFromNative);header.addView(reportHome,new LinearLayout.LayoutParams(dp(36),dp(40)));
            page.addView(header,new LinearLayout.LayoutParams(-1,-2));
            ScrollView scroll=new ScrollView(this);scroll.setFillViewport(false);
            errorText=new TextView(this);errorText.setTextSize(12);errorText.setTypeface(Typeface.MONOSPACE);errorText.setTextColor(Color.rgb(55,70,90));errorText.setLineSpacing(0,1.08f);errorText.setPadding(dp(12),dp(12),dp(12),dp(12));errorText.setTextIsSelectable(true);
            GradientDrawable box=new GradientDrawable();box.setColor(Color.rgb(238,242,247));box.setCornerRadius(dp(10));box.setStroke(dp(1),Color.rgb(214,222,232));errorText.setBackground(box);
            scroll.addView(errorText,new ScrollView.LayoutParams(-1,-2));
            LinearLayout.LayoutParams scrollLine=new LinearLayout.LayoutParams(-1,0,1);scrollLine.topMargin=dp(12);page.addView(scroll,scrollLine);
            errorRebuild=new Button(this);errorRebuild.setText("重建数据库");errorRebuild.setTextColor(Color.rgb(190,65,65));errorRebuild.setOnClickListener(v->askRebuild());
            LinearLayout.LayoutParams rebuildLine=new LinearLayout.LayoutParams(-2,dp(48));rebuildLine.gravity=Gravity.END;rebuildLine.topMargin=dp(8);page.addView(errorRebuild,rebuildLine);
            errorPage=new FrameLayout(this);errorPage.setBackgroundColor(Color.rgb(246,248,251));errorPage.addView(page,new FrameLayout.LayoutParams(-1,-1));
        }
        errorTitle.setText(fallback?"初始化降级报告":"初始化问题");errorText.setText(report);
        reportHome.setVisibility(rootReady&&web!=null?View.VISIBLE:View.GONE);
        errorRebuild.setVisibility(!fallback&&canRebuild&&!rebuilding?View.VISIBLE:View.GONE);
        if(errorPage.getParent()!=contentFrame){if(errorPage.getParent() instanceof android.view.ViewGroup)((android.view.ViewGroup)errorPage.getParent()).removeView(errorPage);contentFrame.addView(errorPage,new FrameLayout.LayoutParams(-1,-1));}
        errorPage.setVisibility(View.VISIBLE);errorPage.bringToFront();
    }
    Button pageBackButton(Runnable action){
        boolean dark=activeDarkTheme==null?systemDark():activeDarkTheme;
        Button button=new Button(this);button.setText("返回");button.setAllCaps(false);button.setTextSize(15);button.setTextColor(dark?Color.rgb(206,232,218):Color.rgb(25,126,103));button.setMinWidth(0);button.setMinimumWidth(0);button.setMinHeight(0);button.setMinimumHeight(0);button.setPadding(dp(8),0,dp(8),0);
        GradientDrawable background=new GradientDrawable();background.setColor(dark?Color.rgb(11,17,13):Color.WHITE);background.setCornerRadius(dp(10));background.setStroke(dp(1),dark?Color.rgb(35,54,42):Color.rgb(232,236,241));button.setBackground(background);button.setOnClickListener(v->action.run());return button;
    }
    void closeErrorPage(){if(errorPage!=null)errorPage.setVisibility(View.GONE);showingFallbackReport=false;}
    String prepareDowngradeReport(JSONObject probe,JSONObject status,List<StartupFeedback.Notice> notices){
        if(notices.isEmpty())return "";
        String report="时间："+new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z",Locale.US).format(new Date())+"\n版本："+appVersion()+"\n设备："+Build.MANUFACTURER+" "+Build.MODEL+" / Android "+Build.VERSION.RELEASE+"\n"+StartupFeedback.downgradeReport(probe,status);
        try(PrintWriter out=new PrintWriter(new File(getFilesDir(),"initialization-fallback.txt"),"UTF-8")){out.print(report);}catch(Exception ignored){}
        archiveError("fallback","初始化降级",report);
        return report;
    }
    static String summary(Throwable error){
        return error instanceof InitFailure&&error.getMessage()!=null?error.getMessage():FriendlyError.message(error);
    }
    String appVersion(){
        try{android.content.pm.PackageInfo info=getPackageManager().getPackageInfo(getPackageName(),0);return info.versionName+" ("+info.versionCode+")";}
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
        out.append(StartupFeedback.technicalDetails(error,MainActivity::diagnostics)).append('\n');
        return StartupFeedback.compactReport(out.toString());
    }
    static String diagnostics(Throwable error){
        if(error instanceof InitFailure)return ((InitFailure)error).diagnostics;
        if(error instanceof RootSession.CommandFailure)return ((RootSession.CommandFailure)error).diagnostics;
        return "";
    }
    void writeFailure(String text){
        try(PrintWriter out=new PrintWriter(new File(getFilesDir(),"initialization-error.txt"),"UTF-8")){out.print(text);}
        catch(Exception ignored){}
        archiveError("failure","初始化或连接失败",text);
    }
    void archiveError(String kind,String title,String report){try{new ErrorLogStore(new File(getFilesDir(),"error-logs")).append(kind,title,report);}catch(Exception ignored){}}
    void importOldErrorReports(){
        if(getSharedPreferences("maintenance",MODE_PRIVATE).getBoolean("logsImported",false))return;
        try{
            ErrorLogStore store=new ErrorLogStore(new File(getFilesDir(),"error-logs"));
            if(store.list(0,1).getJSONArray("items").length()>0){getSharedPreferences("maintenance",MODE_PRIVATE).edit().putBoolean("logsImported",true).commit();return;}
            for(String name:new String[]{"initialization-error.txt","initialization-fallback.txt"}){
                File file=new File(getFilesDir(),name);if(file.isFile()&&file.length()<=2097152)store.append("legacy",name.contains("fallback")?"已有初始化降级报告":"已有初始化错误报告",new String(java.nio.file.Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8));
            }
            getSharedPreferences("maintenance",MODE_PRIVATE).edit().putBoolean("logsImported",true).commit();
        }catch(Exception ignored){}
    }
    View homeIconButton(Runnable action){
        ImageButton button=new ImageButton(this);button.setContentDescription("返回主页");button.setBackgroundColor(Color.TRANSPARENT);button.setPadding(dp(7),dp(7),dp(7),dp(7));
        button.setImageDrawable(new android.graphics.drawable.Drawable(){
            final android.graphics.Paint paint=new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            public void draw(Canvas canvas){android.graphics.Rect bounds=getBounds();canvas.save();canvas.translate(bounds.left,bounds.top);canvas.scale(bounds.width()/24f,bounds.height()/24f);paint.setColor(Color.rgb(7,130,103));paint.setStyle(android.graphics.Paint.Style.STROKE);paint.setStrokeWidth(1.8f);paint.setStrokeCap(android.graphics.Paint.Cap.ROUND);paint.setStrokeJoin(android.graphics.Paint.Join.ROUND);android.graphics.Path path=new android.graphics.Path();path.moveTo(3,10);path.lineTo(12,3);path.lineTo(21,10);path.moveTo(5,9);path.lineTo(5,20);path.lineTo(10,20);path.lineTo(10,13);path.lineTo(14,13);path.lineTo(14,20);path.lineTo(19,20);path.lineTo(19,9);canvas.drawPath(path,paint);canvas.restore();}
            public void setAlpha(int alpha){paint.setAlpha(alpha);}public void setColorFilter(android.graphics.ColorFilter filter){paint.setColorFilter(filter);}public int getOpacity(){return android.graphics.PixelFormat.TRANSLUCENT;}
        });button.setOnClickListener(v->action.run());return button;
    }
    void goHomeFromNative(){closeErrorPage();if(errorLogs!=null)errorLogs.close();if(web!=null)web.evaluateJavascript("window.__acoGoHome&&window.__acoGoHome()",null);}
    void openErrorLogs(){if(!initialized||clearingData)return;if(errorLogs==null)errorLogs=new ErrorLogPages(this);errorLogs.open();}
    void askClearAllData(int step){
        if(!initialized||clearingData||clearDataDialog!=null)return;
        boolean dark=activeDarkTheme==null?systemDark():activeDarkTheme;int textColor=dark?Color.rgb(230,237,232):Color.rgb(24,38,56),muted=dark?Color.rgb(170,185,176):Color.rgb(96,113,103);
        LinearLayout panel=new LinearLayout(this);panel.setOrientation(LinearLayout.VERTICAL);panel.setPadding(dp(22),dp(22),dp(22),dp(12));
        GradientDrawable box=new GradientDrawable();box.setColor(dark?Color.rgb(22,27,25):Color.WHITE);box.setCornerRadius(dp(22));panel.setBackground(box);
        TextView title=new TextView(this);title.setText(step==1?"清除所有数据？":"确认清除所有数据？");title.setTextSize(21);title.setTypeface(null,Typeface.BOLD);title.setTextColor(textColor);panel.addView(title);
        TextView text=new TextView(this);text.setText(step==1?"将停止当前记录，并删除全部充放电记录、设置、使用时间缓存和错误日志。\n完成后应用会关闭，重新打开时重新初始化。":"这些数据将永久删除，无法恢复。\n确认后立即开始清除。");text.setTextSize(15);text.setTextColor(muted);text.setLineSpacing(dp(3),1.05f);text.setPadding(0,dp(14),0,dp(16));panel.addView(text);
        LinearLayout actions=new LinearLayout(this);actions.setGravity(Gravity.CENTER_VERTICAL);panel.addView(actions,new LinearLayout.LayoutParams(-1,dp(48)));
        Button cancel=new Button(this);cancel.setText("取消");cancel.setTextSize(16);cancel.setTextColor(dark?Color.rgb(112,211,173):Color.rgb(7,130,103));cancel.setBackgroundColor(Color.TRANSPARENT);
        Button confirm=new Button(this);confirm.setText(step==1?"继续（3）":"确认清除");confirm.setTextSize(16);confirm.setTextColor(Color.rgb(190,65,65));confirm.setBackgroundColor(Color.TRANSPARENT);
        actions.addView(step==1?confirm:cancel,new LinearLayout.LayoutParams(-2,-1));actions.addView(new View(this),new LinearLayout.LayoutParams(0,1,1));actions.addView(step==1?cancel:confirm,new LinearLayout.LayoutParams(-2,-1));
        AlertDialog dialog=new AlertDialog.Builder(this).setView(panel).create();clearDataDialog=dialog;dialog.setCanceledOnTouchOutside(false);
        long readyAt=android.os.SystemClock.elapsedRealtime()+(step==1?3000:0);android.os.Handler handler=new android.os.Handler(getMainLooper());
        Runnable countdown=new Runnable(){public void run(){
            if(clearDataDialog!=dialog)return;long remaining=readyAt-android.os.SystemClock.elapsedRealtime();
            if(remaining>0){confirm.setEnabled(false);confirm.setAlpha(.45f);confirm.setText("继续（"+((remaining+999)/1000)+"）");handler.postDelayed(this,Math.min(1000,remaining));}
            else{confirm.setEnabled(true);confirm.setAlpha(1);confirm.setText("继续");}
        }};
        cancel.setOnClickListener(v->dialog.dismiss());confirm.setOnClickListener(v->{if(clearDataDialog!=dialog||android.os.SystemClock.elapsedRealtime()<readyAt)return;clearDataDialog=null;dialog.dismiss();if(step==1)askClearAllData(2);else clearAllData();});
        dialog.setOnDismissListener(v->{handler.removeCallbacks(countdown);if(clearDataDialog==dialog)clearDataDialog=null;});
        dialog.show();if(dialog.getWindow()!=null){dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);dialog.getWindow().setLayout(Math.min(getResources().getDisplayMetrics().widthPixels-dp(32),dp(520)),-2);}
        if(step==1)countdown.run();
    }
    void clearAllData(){
        if(clearingData||transferringData||destroyed)return;
        if(!getSharedPreferences("maintenance",MODE_PRIVATE).edit().putBoolean("clearPending",true).commit()){Toast.makeText(this,"无法保存清除状态，未开始清除。",Toast.LENGTH_LONG).show();return;}
        clearingData=true;if(errorLogs!=null)errorLogs.close();closeErrorPage();
        if(root!=null)root.close();root=null;rootReady=false;waitingRequests.clear();
        if(web!=null){web.removeJavascriptInterface("AcoNative");web.destroy();web=null;}
        showInitialization();retry.setVisibility(View.GONE);if(errorActions!=null)errorActions.setVisibility(View.GONE);phase("清除所有数据","正在停止独立后台并清除记录、设置和日志。");
        worker.execute(()->{
            try{
                root=new RootSession();if(!root.command("id -u",10).equals("0"))throw new IOException("未获得 root 权限，尚未清除数据。");
                JSONObject result=new JSONObject(root.command(admin("clear-all-data"),10));if(!result.optBoolean("ok"))throw new IOException("后台数据清除未完成。");
                root.close();root=null;
                runOnUiThread(()->{
                    if(destroyed)return;
                    try{
                        android.app.ActivityManager manager=(android.app.ActivityManager)getSystemService(ACTIVITY_SERVICE);
                        if(manager==null||!manager.clearApplicationUserData())throw new IOException("系统未接受清除应用数据请求，重新打开后会继续清除。");
                    }catch(Exception failure){clearingData=false;fail("清除应用数据",failure);}
                });
            }catch(Exception failure){if(root!=null)root.close();root=null;clearingData=false;fail("清除所有数据",failure);}
        });
    }
    void showFailureUI(String report){
        if(destroyed)return;
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
        String report=failureReport(failedAt,error);
        writeFailure(report);
        runOnUiThread(()->showFailureUI(report));
    }
    void askRebuild(){
        if(!canRebuild||rebuilding||destroyed)return;
        LinearLayout panel=new LinearLayout(this);panel.setOrientation(LinearLayout.VERTICAL);panel.setPadding(dp(22),dp(20),dp(22),dp(12));
        TextView title=new TextView(this);title.setText("重建数据库？");title.setTextSize(21);title.setTypeface(null,Typeface.BOLD);panel.addView(title);
        TextView text=new TextView(this);text.setText("数据库内的记录和设置会重置，当前记录也会结束。\n请选择外部位置保存原数据库，保存并校验成功后才会重建。");text.setTextSize(15);text.setLineSpacing(dp(3),1.05f);text.setPadding(0,dp(14),0,dp(18));panel.addView(text);
        LinearLayout buttons=new LinearLayout(this);buttons.setGravity(Gravity.CENTER_VERTICAL);panel.addView(buttons,new LinearLayout.LayoutParams(-1,dp(48)));
        Button cancel=new Button(this);cancel.setText("取消");cancel.setTextColor(Color.rgb(7,130,103));cancel.setBackgroundColor(Color.TRANSPARENT);buttons.addView(cancel,new LinearLayout.LayoutParams(-2,-1));
        buttons.addView(new View(this),new LinearLayout.LayoutParams(0,1,1));
        Button confirm=new Button(this);confirm.setText("确认重建");confirm.setTextColor(Color.rgb(190,65,65));confirm.setBackgroundColor(Color.TRANSPARENT);buttons.addView(confirm,new LinearLayout.LayoutParams(-2,-1));
        AlertDialog dialog=new AlertDialog.Builder(this).setView(panel).create();cancel.setOnClickListener(v->dialog.dismiss());confirm.setOnClickListener(v->{dialog.dismiss();chooseDatabaseBackup();});dialog.show();
    }
    void chooseDatabaseBackup(){
        try{String name="ACol电池-数据库备份-"+new SimpleDateFormat("yyyyMMdd-HHmmss",Locale.US).format(new Date())+".zip";
            Intent picker=new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/zip").putExtra(Intent.EXTRA_TITLE,name);startActivityForResult(picker,SAVE_DATABASE_REBUILD);
        }catch(Exception error){Toast.makeText(this,"无法打开系统文件选择器，数据库未更改。",Toast.LENGTH_LONG).show();}
    }
    void rebuildDatabase(Uri destination){
        if(!canRebuild||rebuilding||destroyed)return;
        canRebuild=false;rebuilding=true;closeErrorPage();retry.setVisibility(View.GONE);errorActions.setVisibility(View.GONE);progress.setVisibility(View.VISIBLE);
        phase("重建数据库","正在保存并校验原数据库。");
        worker.execute(()->{
            String token=java.util.UUID.randomUUID().toString();boolean committed=false;
            try{
                if(root!=null)root.close();rootReady=false;root=new RootSession();
                if(!root.command("id -u",10).equals("0"))throw new IOException("未获得 root 权限。");
                JSONObject result=new JSONObject(root.command(admin("prepare-database-export "+token),10));
                if(!result.optBoolean("ok"))throw new IOException("无法准备数据库备份。");
                long size=result.getLong("size");String expected=result.getString("sha256");
                if(size<=0||!expected.matches("[0-9a-f]{64}"))throw new IOException("数据库备份信息无效。");
                MessageDigest written=MessageDigest.getInstance("SHA-256");long total=0;
                try(OutputStream out=getContentResolver().openOutputStream(destination,"wt")){
                    if(out==null)throw new IOException("无法打开所选文件。");
                    for(long block=0;total<size;block++){
                        String encoded=root.command("dd if="+RootSession.quote(BASE+"/rebuild-export-"+token+".zip")+" bs=49152 skip="+block+" count=1 2>/dev/null | base64",10);
                        byte[] bytes=Base64.decode(encoded,Base64.DEFAULT);if(bytes.length==0||total+bytes.length>size)throw new IOException("数据库备份读取不完整。");
                        out.write(bytes);written.update(bytes);total+=bytes.length;
                    }out.flush();
                }
                if(!hexDigest(written.digest()).equals(expected))throw new IOException("数据库备份校验失败。");
                MessageDigest saved=MessageDigest.getInstance("SHA-256");long savedSize=0;
                try(InputStream in=getContentResolver().openInputStream(destination)){
                    if(in==null)throw new IOException("无法重新读取所选文件以校验备份。");byte[] buf=new byte[65536];int n;while((n=in.read(buf))!=-1){saved.update(buf,0,n);savedSize+=n;if(savedSize>size)throw new IOException("保存的数据库备份大小不正确。");}
                }
                if(savedSize!=size||!hexDigest(saved.digest()).equals(expected))throw new IOException("保存的数据库备份校验失败。");
                runOnUiThread(()->{if(!destroyed)phase("重建数据库","备份已保存，正在重建数据库。");});
                JSONObject reset=new JSONObject(root.command(admin("rebuild-database-external "+token),10));
                if(!reset.optBoolean("ok"))throw new IOException("数据库重建未完成，请重试。");committed=true;
                RootSession authorized=root;
                runOnUiThread(()->{if(!destroyed){rebuilding=false;Toast.makeText(this,"数据库备份已保存，正在重新初始化",Toast.LENGTH_LONG).show();initialize(authorized);}});
            }catch(Exception failure){
                if(!committed&&root!=null)try{root.command(admin("cancel-database-export "+token),10);}catch(Exception ignored){}
                if(root!=null)root.close();root=null;fail("重建数据库",failure);
            }
        });
    }
    static String hexDigest(byte[] bytes){StringBuilder out=new StringBuilder();for(byte b:bytes)out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString();}
    static String digest(byte[] bytes)throws Exception{StringBuilder out=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes))out.append(String.format("%02x",b&255));return out.toString();}
    String assetText(String name)throws IOException{try(InputStream in=getAssets().open(name)){ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[4096];int n;while((n=in.read(b))>0)out.write(b,0,n);return out.toString("UTF-8").trim();}}
    File payload()throws Exception{
        File file=new File(getCacheDir(),"aco-payload.zip");try(InputStream in=getAssets().open("payload.zip");OutputStream out=new FileOutputStream(file)){byte[] b=new byte[8192];int n;while((n=in.read(b))>0)out.write(b,0,n);}return file;
    }
    String admin(String action){
        return "aco_admin="+RUNTIME+"/bin/daemon.dex.jar; if [ -f "+BASE+"/update.transaction ] && [ -f "+RuntimeSetup.STAGE+"/bin/daemon.dex.jar ]; then aco_admin="+RuntimeSetup.STAGE+"/bin/daemon.dex.jar; fi; CLASSPATH=\"$aco_admin\" /system/bin/app_process /system/bin cn.astriafr.acobattery.daemon.Admin "+action;
    }
    void initialize(){initialize(null);}
    void initialize(RootSession authorized){
        if(getSharedPreferences("maintenance",MODE_PRIVATE).getBoolean("clearPending",false)){if(authorized!=null)authorized.close();clearAllData();return;}
        rootReady=false;waitingRequests.clear();
        canRebuild=false;if(rebuild!=null)rebuild.setVisibility(View.GONE);
        probeReport=null;downgradeReport="";pendingNotices=java.util.Collections.emptyList();
        if(startupNotice!=null){startupNotice.dismiss();startupNotice=null;}
        retry.setVisibility(View.GONE);progress.setVisibility(View.VISIBLE);
        if(errorActions!=null){errorActions.setVisibility(errorReport.isEmpty()?View.GONE:View.VISIBLE);errorView.setText("查看上次错误");}
        if(errorText!=null)errorText.setText("");
        closeErrorPage();
        worker.execute(()->{
            try{
                if(root!=null&&root!=authorized)root.close();
                phase("识别系统","已识别 Android "+Build.VERSION.RELEASE+"（API "+Build.VERSION.SDK_INT+"），自动选择兼容接口。");
                if(Build.VERSION.SDK_INT<35)throw new InitFailure("需要 Android 15 或更新系统。");
                if(!java.util.Arrays.asList(Build.SUPPORTED_64_BIT_ABIS).contains("arm64-v8a"))throw new InitFailure("当前设备不支持 ARM64。");
                if(authorized==null)phase("等待 root 授权","已识别 Android "+Build.VERSION.RELEASE+"。请在 root 管理器中允许 ACol电池获取 root 权限。");
                else phase("root 权限已就绪","正在复用当前连接，准备运行环境。");
                if(authorized==null){root=new RootSession();String uid=root.command("id -u",10);if(!uid.equals("0"))throw new InitFailure("未获得 root 权限。请在 root 管理器中允许 ACol电池使用 su，然后重试。");}
                else root=authorized;
                if(destroyed)return;
                try{root.command("[ -x /system/bin/app_process ] && [ -x /system/bin/cp ] && [ -x /system/bin/chmod ] && [ -x /system/bin/mv ] || { echo '系统缺少部署独立后台所需的运行工具。'; false; }",10);}
                catch(RootSession.CommandFailure failure){throw new InitFailure("系统缺少 app_process、cp、chmod 或 mv，无法部署独立后台。",failure.diagnostics);}
                phase("准备独立运行环境","程序与记录保存在独立的 root 目录。");
                File archive=payload();String version=assetText("payload.sha256");
                String cert=digest(getPackageManager().getPackageInfo(getPackageName(),PackageManager.GET_SIGNING_CERTIFICATES).signingInfo.getApkContentsSigners()[0].toByteArray());
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
                    File extracted=java.nio.file.Files.createTempDirectory(getCacheDir().toPath(),"aco-runtime-").toFile();
                    try{
                        RuntimePayload.extract(archive,extracted,version);
                        root.command(RuntimeSetup.stage(extracted),10);
                        checkProbe(RuntimeSetup.STAGE);
                        phase("更新独立后台","新环境已检查，正在切换运行文件；失败时恢复原环境。");
                        root.command(RuntimeSetup.install(version),10);
                    }finally{RuntimePayload.discard(extracted);}
                }
                if(destroyed)return;
                if(!updating){checkProbe(RUNTIME);}
                phase("连接记录服务","确认后台运行状态并建立前台连接。");
                if(!updating)root.command("if [ ! -f "+BASE+"/update.transaction ]; then /system/bin/rm -f "+BASE+"/update.pending; fi; "+RuntimeSetup.installBoot(),10);
                JSONObject startupStatus=root.connect(this::deliver,text->{connectionPhase(text);phase("连接记录服务",text);},updating);connected();
                java.util.List<StartupFeedback.Notice> notices=StartupFeedback.notices(probeReport,startupStatus);
                String report=prepareDowngradeReport(probeReport,startupStatus,notices);
                getSharedPreferences("startup",MODE_PRIVATE).edit().putBoolean("initialized",true).putInt("androidSdk",Build.VERSION.SDK_INT).commit();
                archive.delete();
                phase("初始化完成","正在打开电池记录。");
                runOnUiThread(()->{if(!destroyed){pendingNotices=notices;downgradeReport=report;showWeb();}});
            }catch(Exception e){
                if(root!=null)root.close();
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
        rootReady=false;
        connectionPhase("等待 root 授权…");
        worker.execute(()->{
            try{
                root=new RootSession();if(!root.command("id -u",10).equals("0"))throw new IOException("未获得 root 权限。");
                if(destroyed)return;
                connectionPhase("正在连接后台…");root.command(RuntimeSetup.recover(),10);
                String version=assetText("payload.sha256");
                String state=root.command("if [ -f "+BASE+"/initialized ] && [ -f "+RUNTIME+"/bin/daemon.dex.jar ] && [ ! -e "+BASE+"/update.pending ] && [ ! -e "+BASE+"/cleanup.request ] && [ \"$(cat "+BASE+"/runtime.version 2>/dev/null)\" = "+RootSession.quote(version)+" ]; then echo READY; else echo SETUP; fi",10);
                if(!state.equals("READY")){
                    RootSession authorized=root;
                    runOnUiThread(()->{if(destroyed)return;if(web!=null){web.destroy();web=null;}showInitialization();initialize(authorized);});return;
                }
                // Reuse a healthy recorder, or restart it without re-probing.
                JSONObject startupStatus=root.connect(this::deliver,this::connectionPhase,false);connected();
                java.util.List<StartupFeedback.Notice> notices=StartupFeedback.notices(null,startupStatus);
                String report=prepareDowngradeReport(null,startupStatus,notices);
                runOnUiThread(()->{if(!destroyed){pendingNotices=notices;downgradeReport=report;showStartupNotice();}});
            }catch(Exception e){
                if(root!=null)root.close();rootReady=false;waitingRequests.clear();
                String report=failureReport("连接已有后台",e);
                writeFailure(report);
                runOnUiThread(()->{
                    if(destroyed)return;
                    if(web!=null){web.destroy();web=null;}
                    showInitialization();
                    showFailureUI(report);
                });
            }
        });
    }
    @SuppressLint({"SetJavaScriptEnabled","AddJavascriptInterface"}) void showWeb(){
        pageLoaded=false;
        initialized=true;web=new WebView(this);web.setOverScrollMode(View.OVER_SCROLL_NEVER);web.setVerticalScrollBarEnabled(false);web.setHorizontalScrollBarEnabled(false);web.setBackgroundColor(Color.rgb(246,248,251));WebSettings settings=web.getSettings();settings.setTextZoom(100);settings.setJavaScriptEnabled(true);settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);settings.setAllowContentAccess(false);settings.setSupportMultipleWindows(false);settings.setMediaPlaybackRequiresUserGesture(true);web.addJavascriptInterface(new Bridge(),"AcoNative");
        web.setWebViewClient(new WebViewClient(){
            @Override public void onPageFinished(WebView view,String url){if(view==web){pageLoaded=true;showStartupNotice();}}
            @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest request){
                Uri url=request.getUrl();
                if(request.isForMainFrame()&&request.hasGesture()&&"https://github.com/AstriaFR/ACol-Battery".equals(url.toString())){
                    try{Intent intent=new Intent(Intent.ACTION_VIEW,url);intent.addCategory(Intent.CATEGORY_BROWSABLE);startActivity(intent);}
                    catch(android.content.ActivityNotFoundException unavailable){Toast.makeText(MainActivity.this,"无法打开浏览器",Toast.LENGTH_SHORT).show();}
                    return true;
                }
                return !trusted(url);
            }
            @Override public WebResourceResponse shouldInterceptRequest(WebView view,WebResourceRequest request){
                Uri uri=request.getUrl();if(!trusted(uri))return new WebResourceResponse("text/plain","UTF-8",403,"Blocked",null,new ByteArrayInputStream(new byte[0]));
                String path=uri.getPath().substring(1);
                try{String ext=path.substring(path.lastIndexOf('.')+1);String mime=ext.equals("js")?"application/javascript":ext.equals("css")?"text/css":ext.equals("png")?"image/png":ext.equals("ttf")?"font/ttf":ext.equals("otf")?"font/otf":"text/html";
                    return new WebResourceResponse(mime,(ext.equals("png")||ext.equals("ttf")||ext.equals("otf"))?null:"UTF-8",getAssets().open(path));
                }catch(Exception e){return new WebResourceResponse("text/plain","UTF-8",404,"Not found",null,new ByteArrayInputStream(new byte[0]));}
            }
        });
        web.setWebChromeClient(new WebChromeClient(){
            @Override public boolean onConsoleMessage(ConsoleMessage message){
                if(message.messageLevel()==ConsoleMessage.MessageLevel.ERROR)android.util.Log.e("AColWebView",message.message()+" ("+message.sourceId()+":"+message.lineNumber()+")");
                return true;
            }
        });
        web.getSettings().setCacheMode(WebSettings.LOAD_NO_CACHE);
        mount(web);web.loadUrl("https://aco.local/webroot/index.html");
    }
    void mount(View child){
        contentFrame=new FrameLayout(this);contentFrame.setBackgroundColor(Color.rgb(246,248,251));
        contentFrame.addView(child,new FrameLayout.LayoutParams(-1,-1));
        contentFrame.setPadding(0,0,0,0);
        contentFrame.setOnApplyWindowInsetsListener((view,insets)->{
            android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());
            view.setPadding(bars.left,bars.top,bars.right,bars.bottom);return new WindowInsets.Builder(insets).setInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout(),android.graphics.Insets.NONE).build();
        });
        setContentView(contentFrame);contentFrame.requestApplyInsets();contentFrame.post(this::registerBack);
    }
    void showStartupNotice(){
        if(destroyed||isFinishing()||!rootReady||!pageLoaded||pendingNotices.isEmpty()||startupNotice!=null)return;
        boolean dark=activeDarkTheme==null?systemDark():activeDarkTheme;
        int background=dark?Color.rgb(22,27,25):Color.WHITE,foreground=dark?Color.rgb(230,237,232):Color.rgb(24,38,32);
        int secondary=dark?Color.rgb(170,185,176):Color.rgb(96,113,103),green=dark?Color.rgb(112,211,173):Color.rgb(7,130,103);
        LinearLayout panel=new LinearLayout(this);panel.setOrientation(LinearLayout.VERTICAL);panel.setPadding(dp(22),dp(22),dp(22),dp(14));
        GradientDrawable box=new GradientDrawable();box.setColor(background);box.setCornerRadius(dp(22));panel.setBackground(box);
        TextView title=new TextView(this);title.setText("初始化完成，部分功能受限");title.setTextSize(21);title.setTypeface(null,Typeface.BOLD);title.setTextColor(foreground);panel.addView(title);
        TextView intro=new TextView(this);intro.setText("应用已可使用。以下项目已降级或跳过：");intro.setTextSize(14);intro.setTextColor(secondary);intro.setPadding(0,dp(10),0,dp(6));panel.addView(intro);
        ScrollView scroll=new ScrollView(this){@Override protected void onMeasure(int width,int height){int max=(int)(getResources().getDisplayMetrics().heightPixels*0.52f);super.onMeasure(width,View.MeasureSpec.makeMeasureSpec(max,View.MeasureSpec.AT_MOST));}};
        LinearLayout items=new LinearLayout(this);items.setOrientation(LinearLayout.VERTICAL);
        for(StartupFeedback.Notice notice:pendingNotices){
            TextView name=new TextView(this);name.setText(notice.title);name.setTextSize(16);name.setTypeface(null,Typeface.BOLD);name.setTextColor(foreground);name.setPadding(0,dp(15),0,dp(5));items.addView(name);
            TextView text=new TextView(this);text.setText(notice.text);text.setTextSize(14);text.setTextColor(secondary);text.setLineSpacing(dp(3),1.05f);items.addView(text);
        }
        scroll.addView(items,new ScrollView.LayoutParams(-1,-2));panel.addView(scroll,new LinearLayout.LayoutParams(-1,-2));
        LinearLayout actions=new LinearLayout(this);actions.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams actionLine=new LinearLayout.LayoutParams(-1,dp(48));actionLine.topMargin=dp(12);panel.addView(actions,actionLine);
        Button report=new Button(this);report.setText("错误报告");report.setTextSize(15);report.setTextColor(secondary);report.setBackgroundColor(Color.TRANSPARENT);actions.addView(report,new LinearLayout.LayoutParams(-2,-1));
        actions.addView(new View(this),new LinearLayout.LayoutParams(0,1,1));
        Button acknowledge=new Button(this);acknowledge.setText("继续使用");acknowledge.setTextSize(16);acknowledge.setTextColor(green);acknowledge.setBackgroundColor(Color.TRANSPARENT);actions.addView(acknowledge,new LinearLayout.LayoutParams(-2,-1));
        AlertDialog dialog=new AlertDialog.Builder(this).setView(panel).create();startupNotice=dialog;
        dialog.setCanceledOnTouchOutside(false);report.setOnClickListener(v->{dialog.dismiss();openReport(downgradeReport,true);});acknowledge.setOnClickListener(v->dialog.dismiss());dialog.setOnDismissListener(v->{if(startupNotice==dialog)startupNotice=null;});
        dialog.show();
        if(dialog.getWindow()!=null){dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);dialog.getWindow().setLayout(Math.min(getResources().getDisplayMetrics().widthPixels-dp(32),dp(600)),-2);}
        pendingNotices=java.util.Collections.emptyList();
    }
    void applyThemeBars(boolean dark){
        runOnUiThread(()->{
            if(destroyed||isFinishing())return;
            final long generation=++themeBarGeneration;
            if(activeDarkTheme!=null&&activeDarkTheme==dark)return;
            final WebView target=web;
            if(target!=null&&target.isAttachedToWindow()&&target.getVisibility()==View.VISIBLE){
                try{
                    // Change the system bars only when the matching WebView state
                    // is ready for its next draw, rather than at DOM mutation time.
                    target.postVisualStateCallback(generation,new WebView.VisualStateCallback(){
                        @Override public void onComplete(long requestId){
                            if(generation==themeBarGeneration&&web==target&&!destroyed&&!isFinishing())commitThemeBars(dark);
                        }
                    });
                    return;
                }catch(RuntimeException unavailable){android.util.Log.w("AColWebView","Theme visual-state callback unavailable",unavailable);}
            }
            commitThemeBars(dark);
        });
    }
    void commitThemeBars(boolean dark){
        activeDarkTheme=dark;
        int surface=dark?Color.BLACK:Color.rgb(246,248,251);
        getWindow().setStatusBarColor(surface);getWindow().setNavigationBarColor(surface);
        WindowInsetsController controller=getWindow().getInsetsController();
        if(controller!=null){int appearance=WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS|WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;controller.setSystemBarsAppearance(dark?0:appearance,appearance);}
        if(contentFrame!=null)contentFrame.setBackgroundColor(surface);
        if(web!=null)web.setBackgroundColor(surface);
    }
    static boolean trusted(Uri uri){String path=uri.getPath();return "https".equals(uri.getScheme())&&"aco.local".equals(uri.getHost())&&path!=null&&path.startsWith("/webroot/")&&!path.contains("..")&&!path.contains("\\");}
    String appIcon(String pkg){
        String cached=iconCache.get(pkg);if(cached!=null)return cached;
        synchronized(iconLock){
            cached=iconCache.get(pkg);if(cached!=null)return cached;
            File dir=new File(getFilesDir(),"app-icons");
            if(!dir.isDirectory()&&!dir.mkdirs())return null;
            long updated=0;
            try{updated=getPackageManager().getPackageInfo(pkg,0).lastUpdateTime;}catch(Exception ignored){}
            String name;
            try{name=digest(pkg.getBytes(StandardCharsets.UTF_8))+"-"+updated;}catch(Exception e){return null;}
            File file=new File(dir,name+".webp");
            String icon=null;
            try{
                if(file.isFile()&&file.length()>0){
                    icon="data:image/webp;base64,"+Base64.encodeToString(java.nio.file.Files.readAllBytes(file.toPath()),Base64.NO_WRAP);
                }else{
                    Drawable drawable=getPackageManager().getApplicationIcon(pkg);
                    int size=128;
                    Bitmap bitmap=Bitmap.createBitmap(size,size,Bitmap.Config.ARGB_8888);
                    Canvas canvas=new Canvas(bitmap);
                    drawable.setBounds(0,0,size,size);
                    drawable.draw(canvas);
                    ByteArrayOutputStream bytes=new ByteArrayOutputStream();
                    bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY,84,bytes);
                    bitmap.recycle();
                    byte[] data=bytes.toByteArray();
                    try(FileOutputStream out=new FileOutputStream(file)){out.write(data);}
                    icon="data:image/webp;base64,"+Base64.encodeToString(data,Base64.NO_WRAP);
                }
            }catch(Exception ignored){}
            if(icon!=null)iconCache.put(pkg,icon);
            return icon;
        }
    }
    void deliver(JSONObject message){
        if(destroyed)return;
        runOnUiThread(()->{if(web!=null)web.evaluateJavascript("window.__acoDeliver&&window.__acoDeliver(JSON.parse("+JSONObject.quote(message.toString())+"))",null);});
    }
    public final class Bridge {
        @JavascriptInterface public void backupAllData(){runOnUiThread(()->{if(dataTransfer!=null)dataTransfer.backup();});}
        @JavascriptInterface public void restoreAllData(){runOnUiThread(()->{if(dataTransfer!=null)dataTransfer.restore();});}
        @JavascriptInterface public void openErrorLogs(){runOnUiThread(()->MainActivity.this.openErrorLogs());}
        @JavascriptInterface public void clearAllData(){runOnUiThread(()->askClearAllData(1));}
        @JavascriptInterface public void post(String text){
            if(!initialized||destroyed||text==null||text.length()>8192)return;
            try{JSONObject request=new JSONObject(text);if(!request.optString("id").matches("[a-f0-9-]{12,48}"))return;
                synchronized(channelLock){
                    if(!rootReady){if(waitingRequests.size()>=32)throw new IOException("正在连接后台，请稍候。");waitingRequests.add(request);return;}
                    root.request(request);
                }}
            catch(Exception e){try{JSONObject request=new JSONObject(text);deliver(new JSONObject().put("id",request.optString("id")).put("event","error").put("message","root 连接已断开，请重新打开 App。"));}catch(Exception ignored){}}
        }
        @JavascriptInterface public String appIcons(String json){
            if(json==null||json.length()>8192)return"{}";
            try{
                JSONArray packages=new JSONArray(json);if(packages.length()>200)return"{}";
                JSONObject result=new JSONObject();
                for(int i=0;i<packages.length();i++){
                    String pkg=packages.optString(i,"");
                    if(!pkg.matches("[A-Za-z0-9._]{1,200}"))continue;
                    String icon=appIcon(pkg);if(icon!=null)result.put(pkg,icon);
                }
                return result.toString();
            }catch(Exception e){return"{}";}
        }
        @JavascriptInterface public void fullScreen(boolean enabled){runOnUiThread(()->{immersive=enabled;if(contentFrame!=null)contentFrame.requestApplyInsets();setRequestedOrientation(enabled?ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE:ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);if(enabled)getWindow().getInsetsController().hide(WindowInsets.Type.systemBars());else getWindow().getInsetsController().show(WindowInsets.Type.systemBars());});}
        @JavascriptInterface public void setDarkTheme(boolean enabled){applyThemeBars(enabled);}
        @JavascriptInterface public boolean isSystemDark(){return systemDark();}
        @JavascriptInterface public String connectionStatus(){return rootReady?"":connectionStatus;}
        @JavascriptInterface public void repair(){runOnUiThread(()->{if(web!=null){web.destroy();web=null;}showInitialization();initialize();});}
    }
    void back(){
        if(clearingData)return;
        if(clearDataDialog!=null){clearDataDialog.dismiss();return;}
        if(errorPage!=null&&errorPage.getVisibility()==View.VISIBLE){closeErrorPage();return;}
        if(dataTransfer!=null&&dataTransfer.back())return;
        if(errorLogs!=null&&errorLogs.visible()){errorLogs.close();return;}
        if(web==null){finish();return;}
        WindowInsets insets=web.getRootWindowInsets();
        if(insets!=null&&insets.isVisible(WindowInsets.Type.ime())){getWindow().getInsetsController().hide(WindowInsets.Type.ime());return;}
        if(backPending)return;backPending=true;WebView current=web;
        current.evaluateJavascript("(()=>{try{return typeof window.__acoBack==='function'?window.__acoBack():'wait';}catch{return 'wait';}})()",value->{
            if(destroyed||web!=current){backPending=false;return;}
            // Loading, missing history and script errors must never exit a child page.
            if("\"home\"".equals(value))finish();
            current.postDelayed(()->backPending=false,200);
        });
    }
    @Override protected void onDestroy(){destroyed=true;if(dataTransfer!=null)dataTransfer.close();if(clearDataDialog!=null)clearDataDialog.dismiss();if(errorLogs!=null)errorLogs.close();if(startupNotice!=null)startupNotice.dismiss();if(backDispatcher!=null)backDispatcher.unregisterOnBackInvokedCallback(backCallback);rootReady=false;waitingRequests.clear();if(web!=null){web.removeJavascriptInterface("AcoNative");web.destroy();}if(root!=null)root.close();worker.shutdownNow();super.onDestroy();}
}
