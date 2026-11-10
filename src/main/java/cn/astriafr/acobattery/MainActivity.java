package cn.astriafr.acobattery;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.Canvas;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.KeyEvent;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;
import android.widget.*;
import org.json.JSONObject;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    final ExecutorService worker=Executors.newSingleThreadExecutor();
    StartupController startup;WebViewHost browser;ChartExport chartExport;RecordListCache recordListCache;
    FrameLayout contentFrame;ErrorLogPages errorLogs;DataTransferUi dataTransfer;
    AlertDialog clearDataDialog;boolean clearingData,transferringData;
    volatile boolean destroyed;
    final OnBackInvokedCallback backCallback=this::back;
    OnBackInvokedDispatcher backDispatcher;
    @Override protected void attachBaseContext(Context base){
        // Override font scaling only; keep density, locale and orientation live.
        Configuration fixedFont=new Configuration();fixedFont.fontScale=1.0f;
        super.attachBaseContext(base.createConfigurationContext(fixedFont));
    }
    @Override public void onConfigurationChanged(Configuration configuration){
        super.onConfigurationChanged(configuration);
        if(browser!=null&&browser.web!=null){
            browser.web.getSettings().setTextZoom(100);
            browser.web.evaluateJavascript("window.dispatchEvent(new CustomEvent('aco-system-theme',{detail:"+systemDark()+"}))",null);
        }
    }
    boolean systemDark(){return (getResources().getConfiguration().uiMode&Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES;}
    int dp(float value){return Math.round(getResources().getDisplayMetrics().density*value);}
    @Override public void onCreate(Bundle state){
        super.onCreate(state);getWindow().setDecorFitsSystemWindows(false);getWindow().setStatusBarColor(Color.WHITE);getWindow().setNavigationBarColor(Color.WHITE);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE|View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        recordListCache=new RecordListCache(getCacheDir());
        startup=new StartupController(this);browser=new WebViewHost(this);chartExport=new ChartExport(this);browser.tileSettingsPending=isTileSettingsIntent(getIntent());
        dataTransfer=new DataTransferUi(this);if(dataTransfer.resume())return;
        if(getSharedPreferences("maintenance",MODE_PRIVATE).getBoolean("clearPending",false)){clearAllData();return;}
        worker.execute(startup::discardOldReports);
        if(getSharedPreferences("startup",MODE_PRIVATE).getBoolean("initialized",false)&&getSharedPreferences("startup",MODE_PRIVATE).getInt("androidSdk",0)==Build.VERSION.SDK_INT){browser.showWeb();startup.connectExisting();}
        else{startup.showInitialization();startup.initialize();}
    }
    static boolean isTileSettingsIntent(Intent intent){return intent!=null&&"android.service.quicksettings.action.QS_TILE_PREFERENCES".equals(intent.getAction());}
    @Override protected void onNewIntent(Intent intent){
        super.onNewIntent(intent);setIntent(intent);
        if(isTileSettingsIntent(intent)){browser.tileSettingsPending=true;browser.openTileSettings();}
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
        else if(request==StartupController.SAVE_DATABASE_REBUILD&&result==RESULT_OK&&intent!=null&&intent.getData()!=null)startup.rebuildDatabase(intent.getData());
        else if(request==ChartExport.SAVE&&chartExport!=null)chartExport.result(result,intent);
    }
    Button pageBackButton(Runnable action){
        boolean dark=browser.activeDarkTheme==null?systemDark():browser.activeDarkTheme;
        Button button=new Button(this);button.setText("返回");button.setAllCaps(false);button.setTextSize(15);button.setTextColor(dark?Color.rgb(206,232,218):Color.rgb(25,126,103));button.setMinWidth(0);button.setMinimumWidth(0);button.setMinHeight(0);button.setMinimumHeight(0);button.setPadding(dp(8),0,dp(8),0);
        GradientDrawable background=new GradientDrawable();background.setColor(dark?Color.rgb(11,17,13):Color.WHITE);background.setCornerRadius(dp(10));background.setStroke(dp(1),dark?Color.rgb(35,54,42):Color.rgb(232,236,241));button.setBackground(background);button.setOnClickListener(v->action.run());return button;
    }
    View homeIconButton(Runnable action){
        ImageButton button=new ImageButton(this);button.setContentDescription("返回主页");button.setBackgroundColor(Color.TRANSPARENT);button.setPadding(dp(7),dp(7),dp(7),dp(7));
        button.setImageDrawable(new android.graphics.drawable.Drawable(){
            final android.graphics.Paint paint=new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            public void draw(Canvas canvas){android.graphics.Rect bounds=getBounds();canvas.save();canvas.translate(bounds.left,bounds.top);canvas.scale(bounds.width()/24f,bounds.height()/24f);paint.setColor(Color.rgb(7,130,103));paint.setStyle(android.graphics.Paint.Style.STROKE);paint.setStrokeWidth(1.8f);paint.setStrokeCap(android.graphics.Paint.Cap.ROUND);paint.setStrokeJoin(android.graphics.Paint.Join.ROUND);android.graphics.Path path=new android.graphics.Path();path.moveTo(3,10);path.lineTo(12,3);path.lineTo(21,10);path.moveTo(5,9);path.lineTo(5,20);path.lineTo(10,20);path.lineTo(10,13);path.lineTo(14,13);path.lineTo(14,20);path.lineTo(19,20);path.lineTo(19,9);canvas.drawPath(path,paint);canvas.restore();}
            public void setAlpha(int alpha){paint.setAlpha(alpha);}public void setColorFilter(android.graphics.ColorFilter filter){paint.setColorFilter(filter);}public int getOpacity(){return android.graphics.PixelFormat.TRANSLUCENT;}
        });button.setOnClickListener(v->action.run());return button;
    }
    void goHomeFromNative(){startup.closeErrorPage();if(errorLogs!=null)errorLogs.close();if(browser.web!=null)browser.web.evaluateJavascript("window.__acoGoHome&&window.__acoGoHome()",null);}
    void openErrorLogs(){if(!startup.initialized||clearingData)return;if(errorLogs==null)errorLogs=new ErrorLogPages(this);errorLogs.open();}
    void askClearAllData(int step){
        if(!startup.initialized||clearingData||clearDataDialog!=null)return;
        boolean dark=browser.activeDarkTheme==null?systemDark():browser.activeDarkTheme;int textColor=dark?Color.rgb(230,237,232):Color.rgb(24,38,56),muted=dark?Color.rgb(170,185,176):Color.rgb(96,113,103);
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
        clearingData=true;if(errorLogs!=null)errorLogs.close();startup.closeErrorPage();
        startup.disconnect();browser.destroy();
        startup.showInitialization();startup.retry.setVisibility(View.GONE);if(startup.errorActions!=null)startup.errorActions.setVisibility(View.GONE);startup.phase("清除所有数据","正在停止独立后台并清除记录、设置和日志。");
        worker.execute(()->{
            try{
                recordListCache.clear();
                startup.openRoot();if(!startup.root.command("id -u",10).equals("0"))throw new IOException("未获得 root 权限，尚未清除数据。");
                JSONObject result=new JSONObject(startup.root.command(startup.admin("clear-all-data"),10));if(!result.optBoolean("ok"))throw new IOException("后台数据清除未完成。");
                startup.disconnect();
                runOnUiThread(()->{
                    if(destroyed)return;
                    try{
                        android.app.ActivityManager manager=(android.app.ActivityManager)getSystemService(ACTIVITY_SERVICE);
                        if(manager==null||!manager.clearApplicationUserData())throw new IOException("系统未接受清除应用数据请求，重新打开后会继续清除。");
                    }catch(Exception failure){clearingData=false;startup.fail("清除应用数据",failure);}
                });
            }catch(Exception failure){startup.disconnect();clearingData=false;startup.fail("清除所有数据",failure);}
        });
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
    void back(){
        if(clearingData)return;
        if(clearDataDialog!=null){clearDataDialog.dismiss();return;}
        if(startup.errorPage!=null&&startup.errorPage.getVisibility()==View.VISIBLE){startup.closeErrorPage();return;}
        if(dataTransfer!=null&&dataTransfer.back())return;
        if(errorLogs!=null&&errorLogs.visible()){errorLogs.close();return;}
        browser.back();
    }
    @Override protected void onDestroy(){
        destroyed=true;
        if(dataTransfer!=null)dataTransfer.close();if(clearDataDialog!=null)clearDataDialog.dismiss();if(errorLogs!=null)errorLogs.close();
        if(backDispatcher!=null)backDispatcher.unregisterOnBackInvokedCallback(backCallback);
        if(chartExport!=null)chartExport.close();if(startup!=null)startup.close();if(browser!=null)browser.destroy();
        worker.shutdownNow();super.onDestroy();
    }
}
