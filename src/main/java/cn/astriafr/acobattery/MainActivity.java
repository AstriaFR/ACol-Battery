package cn.astriafr.acobattery;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.BitmapFactory;
import android.graphics.fonts.Font;
import android.graphics.fonts.FontFamily;
import android.graphics.fonts.FontStyle;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.KeyEvent;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;
import android.webkit.*;
import android.widget.*;
import cn.astriafr.acobattery.common.FriendlyError;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.*;

public final class MainActivity extends Activity {
    // Stable package, signing identity, root paths and WebView origin preserve local data during renaming.
    static final String BASE="/data/adb/aco_battery_app",RUNTIME=BASE+"/runtime";
    final ExecutorService worker=Executors.newSingleThreadExecutor();
    RootSession root;
    WebView web;
    FrameLayout contentFrame;boolean immersive,backPending;String currentPhase="初始化";
    TextView stage,detail;
    Button retry;
    ProgressBar progress;
    volatile boolean destroyed,initialized,rootReady;
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
        if(web!=null)web.getSettings().setTextZoom(100);
    }
    int dp(float value){return Math.round(getResources().getDisplayMetrics().density*value);}
    @Override public void onCreate(Bundle state){
        super.onCreate(state);getWindow().setDecorFitsSystemWindows(false);getWindow().setStatusBarColor(Color.WHITE);getWindow().setNavigationBarColor(Color.WHITE);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE|View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
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
    void showInitialization(){
        initialized=false;
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
        TextView version=new TextView(this);try{version.setText(getPackageManager().getPackageInfo(getPackageName(),0).versionName+" · AstriaFR");}catch(Exception ignored){version.setText("1.3 · AstriaFR");}version.setTextColor(Color.rgb(96,113,133));version.setTextSize(13);names.addView(version);title.addView(names);layout.addView(title);
        stage=new TextView(this);stage.setTextSize(20);stage.setTypeface(null,Typeface.BOLD);stage.setTextColor(Color.rgb(7,130,103));stage.setPadding(0,dp(32),0,dp(12));layout.addView(stage);
        detail=new TextView(this);detail.setTextSize(14);detail.setTextColor(Color.rgb(96,113,133));detail.setLineSpacing(dp(5),1);layout.addView(detail);
        progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);progress.setIndeterminate(true);LinearLayout.LayoutParams line=new LinearLayout.LayoutParams(-1,dp(4));line.topMargin=dp(24);layout.addView(progress,line);
        retry=new Button(this);retry.setText("重试初始化");retry.setVisibility(View.GONE);retry.setOnClickListener(v->initialize());LinearLayout.LayoutParams button=new LinearLayout.LayoutParams(-1,dp(48));button.topMargin=dp(22);layout.addView(retry,button);mount(layout);
    }
    void phase(String name,String text){currentPhase=name;if(destroyed)return;runOnUiThread(()->{stage.setText(name);detail.setText(text);});}
    static String digest(byte[] bytes)throws Exception{StringBuilder out=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes))out.append(String.format("%02x",b&255));return out.toString();}
    String assetText(String name)throws IOException{try(InputStream in=getAssets().open(name)){ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[4096];int n;while((n=in.read(b))>0)out.write(b,0,n);return out.toString("UTF-8").trim();}}
    File payload()throws Exception{
        File file=new File(getCacheDir(),"aco-payload.zip");try(InputStream in=getAssets().open("payload.zip");OutputStream out=new FileOutputStream(file)){byte[] b=new byte[8192];int n;while((n=in.read(b))>0)out.write(b,0,n);}return file;
    }
    String admin(String action){return "CLASSPATH="+RUNTIME+"/bin/daemon.dex.jar /system/bin/app_process /system/bin cn.astriafr.acobattery.daemon.Admin "+action;}
    void initialize(){
        rootReady=false;waitingRequests.clear();
        retry.setVisibility(View.GONE);progress.setVisibility(View.VISIBLE);
        worker.execute(()->{
            try{
                if(root!=null)root.close();
                phase("识别系统","已识别 Android "+Build.VERSION.RELEASE+"（API "+Build.VERSION.SDK_INT+"），自动选择兼容接口。");
                if(Build.VERSION.SDK_INT<35)throw new IOException("需要 Android 15 或更新系统。");
                if(!java.util.Arrays.asList(Build.SUPPORTED_64_BIT_ABIS).contains("arm64-v8a"))throw new IOException("当前构建仅支持 arm64 设备。");
                phase("等待 root 授权","已识别 Android "+Build.VERSION.RELEASE+"。请在 KernelSU 提示中允许 ACol电池获取 root 权限。");
                root=new RootSession();String uid=root.command("id -u",90);if(!uid.equals("0"))throw new IOException("未获得 root 权限。");
                if(destroyed)return;
                root.command("[ -x /data/adb/ksud ] && [ -x /data/adb/ksu/bin/busybox ] || { echo '当前版本需要 KernelSU。'; false; }",10);
                phase("准备独立运行环境","程序与记录保存在独立的 root 目录。");
                File archive=payload();String version=assetText("payload.sha256");
                String cert=digest(getPackageManager().getPackageInfo(getPackageName(),PackageManager.GET_SIGNING_CERTIFICATES).signingInfo.getApkContentsSigners()[0].toByteArray());
                int user=android.os.Process.myUid()/100000;
                root.command("umask 077; mkdir -p "+BASE+"; chmod 700 "+BASE+"; if [ -f "+BASE+"/owner.cert ] && [ \"$(cat "+BASE+"/owner.cert)\" != "+RootSession.quote(cert)+" ]; then echo '历史目录签名不一致，已停止初始化。'; false; else printf '%s' "+RootSession.quote(cert)+" > "+BASE+"/owner.cert; printf '%s' "+user+" > "+BASE+"/owner.user; fi",10);
                String current=root.command("cat "+BASE+"/runtime.version 2>/dev/null || true",10);
                if(!version.equals(current)){
                    // Run the new updater so even old recorders are suspended without
                    // executing their old finish-and-auto-delete shutdown behavior.
                    String stage=RUNTIME+"/update-stage";
                    root.command("mkdir -p "+stage+"; /data/adb/ksu/bin/busybox unzip -oq "+RootSession.quote(archive.getAbsolutePath())+" -d "+stage+"; touch "+BASE+"/update.pending; CLASSPATH="+stage+"/bin/daemon.dex.jar /system/bin/app_process /system/bin cn.astriafr.acobattery.daemon.Admin stop-update",20);
                    root.command("mkdir -p "+RUNTIME+"; /data/adb/ksu/bin/busybox unzip -oq "+RootSession.quote(archive.getAbsolutePath())+" -d "+RUNTIME+"; chmod 700 "+RUNTIME+"/bin/aco-launcher; chmod 600 "+RUNTIME+"/bin/daemon.dex.jar; chmod 700 "+RUNTIME+"/*.sh; printf '%s' "+RootSession.quote(version)+" > "+BASE+"/runtime.version; rm -f "+BASE+"/update.pending "+BASE+"/cleanup.request",20);
                    root.command("rm -f "+stage+"/bin/daemon.dex.jar "+stage+"/bin/libcurvewait.so "+stage+"/bin/aco-launcher "+stage+"/boot.sh "+stage+"/service.sh "+stage+"/cutoff.sh; rmdir "+stage+"/bin "+stage+" 2>/dev/null || true",10);
                }
                if(destroyed)return;
                phase("检查电池接口","检查底层读数、单双电芯与采样能力。");
                String probe=root.command("CLASSPATH="+RUNTIME+"/bin/daemon.dex.jar /system/bin/app_process /system/bin cn.astriafr.acobattery.daemon.Main --probe "+RUNTIME,20);
                JSONObject report=null;for(String line:probe.split("\\n"))try{JSONObject candidate=new JSONObject(line);if(candidate.has("reading"))report=candidate;}catch(JSONException ignored){}
                if(report==null)throw new IOException("未能读取电池接口。");JSONObject reading=report.getJSONObject("reading");
                if(reading.isNull("rm")||reading.isNull("pct"))throw new IOException("设备缺少剩余容量或电量接口，无法完成初始化。");
                if((report.optInt("nativeCapabilities")&8)==0)throw new IOException("采样定时器不可用，无法启动记录。");
                if(!report.optBoolean("screenListener",false))throw new IOException("亮屏、息屏监听不可用："+report.optString("screenError","显示服务未能完成初始化。"));
                int cells=reading.optInt("cellCount",0);
                phase("电池接口已就绪",(cells==2?"已识别双电芯。":cells==1?"已识别单电芯。":"电芯数量待确认，将继续自动识别。")+(report.optBoolean("cutoffAvailable")?" 截止电压调整可用。":" 截止电压调整不可用，其余功能继续。"));
                phase("启动独立后台","关闭 App 不停止记录；记录开关可在设置中控制。");
                root.command("rm -f "+BASE+"/update.pending "+BASE+"/cleanup.request; mkdir -p /data/adb/service.d; cp "+RUNTIME+"/boot.sh /data/adb/service.d/aco_battery_app.sh; chmod 700 /data/adb/service.d/aco_battery_app.sh; "+admin("ensure")+" && touch "+BASE+"/initialized",20);
                root.broker(this::deliver);rootReady=true;
                getSharedPreferences("startup",MODE_PRIVATE).edit().putBoolean("initialized",true).putInt("androidSdk",Build.VERSION.SDK_INT).commit();
                archive.delete();
                phase("初始化完成","正在打开电池记录。");
                runOnUiThread(()->{if(!destroyed)showWeb();});
            }catch(Exception e){
                if(root!=null)root.close();String failedAt=currentPhase;
                try(PrintWriter report=new PrintWriter(new File(getFilesDir(),"initialization-error.txt"),"UTF-8")){report.println(failedAt);if(e instanceof RootSession.CommandFailure)report.println(((RootSession.CommandFailure)e).diagnostics);e.printStackTrace(report);}catch(Exception ignored){}
                phase("初始化未完成",failedAt+"："+FriendlyError.message(e));
                runOnUiThread(()->{if(!destroyed){progress.setVisibility(View.GONE);retry.setVisibility(View.VISIBLE);}});
            }
        });
    }
    void connectExisting(){
        rootReady=false;
        worker.execute(()->{
            try{
                root=new RootSession();if(!root.command("id -u",90).equals("0"))throw new IOException("未获得 root 权限。");
                if(destroyed)return;
                String version=assetText("payload.sha256");
                String state=root.command("if [ -f "+BASE+"/initialized ] && [ -f "+RUNTIME+"/bin/daemon.dex.jar ] && [ ! -e "+BASE+"/update.pending ] && [ ! -e "+BASE+"/cleanup.request ] && [ \"$(cat "+BASE+"/runtime.version 2>/dev/null)\" = "+RootSession.quote(version)+" ]; then echo READY; else echo SETUP; fi",10);
                if(!state.equals("READY")){
                    runOnUiThread(()->{if(destroyed)return;if(web!=null){web.destroy();web=null;}showInitialization();initialize();});return;
                }
                // Reuse a healthy recorder, or restart it without re-probing.
                root.command(admin("ensure"),20);root.broker(this::deliver);
                synchronized(channelLock){
                    if(destroyed)return;rootReady=true;
                    JSONObject request;while((request=waitingRequests.poll())!=null)root.request(request);
                }
            }catch(Exception e){
                if(root!=null)root.close();rootReady=false;waitingRequests.clear();
                runOnUiThread(()->{if(destroyed)return;if(web!=null){web.destroy();web=null;}showInitialization();phase("连接未完成",FriendlyError.message(e));progress.setVisibility(View.GONE);retry.setVisibility(View.VISIBLE);});
            }
        });
    }
    @SuppressLint({"SetJavaScriptEnabled","AddJavascriptInterface"}) void showWeb(){
        initialized=true;web=new WebView(this);web.setOverScrollMode(View.OVER_SCROLL_NEVER);web.setVerticalScrollBarEnabled(false);web.setHorizontalScrollBarEnabled(false);web.setBackgroundColor(Color.rgb(246,248,251));WebSettings settings=web.getSettings();settings.setTextZoom(100);settings.setJavaScriptEnabled(true);settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);settings.setAllowContentAccess(false);settings.setSupportMultipleWindows(false);settings.setMediaPlaybackRequiresUserGesture(true);web.addJavascriptInterface(new Bridge(),"AcoNative");
        web.setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest request){return !trusted(request.getUrl());}
            @Override public WebResourceResponse shouldInterceptRequest(WebView view,WebResourceRequest request){
                Uri uri=request.getUrl();if(!trusted(uri))return new WebResourceResponse("text/plain","UTF-8",403,"Blocked",null,new ByteArrayInputStream(new byte[0]));
                String path=uri.getPath().substring(1);
                try{String ext=path.substring(path.lastIndexOf('.')+1);String mime=ext.equals("js")?"application/javascript":ext.equals("css")?"text/css":ext.equals("png")?"image/png":ext.equals("ttf")?"font/ttf":ext.equals("otf")?"font/otf":"text/html";
                    return new WebResourceResponse(mime,(ext.equals("png")||ext.equals("ttf")||ext.equals("otf"))?null:"UTF-8",getAssets().open(path));
                }catch(Exception e){return new WebResourceResponse("text/plain","UTF-8",404,"Not found",null,new ByteArrayInputStream(new byte[0]));}
            }
        });
        web.setWebChromeClient(new WebChromeClient());
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
    static boolean trusted(Uri uri){String path=uri.getPath();return "https".equals(uri.getScheme())&&"aco.local".equals(uri.getHost())&&path!=null&&path.startsWith("/webroot/")&&!path.contains("..")&&!path.contains("\\");}
    void deliver(JSONObject message){
        if(destroyed)return;
        runOnUiThread(()->{if(web!=null)web.evaluateJavascript("window.__acoDeliver&&window.__acoDeliver(JSON.parse("+JSONObject.quote(message.toString())+"))",null);});
    }
    public final class Bridge {
        @JavascriptInterface public void post(String text){
            if(!initialized||destroyed||text==null||text.length()>8192)return;
            try{JSONObject request=new JSONObject(text);if(!request.optString("id").matches("[a-f0-9-]{12,48}"))return;
                synchronized(channelLock){
                    if(!rootReady){if(waitingRequests.size()>=32)throw new IOException("正在连接后台，请稍候。");waitingRequests.add(request);return;}
                    root.request(request);
                }}
            catch(Exception e){try{JSONObject request=new JSONObject(text);deliver(new JSONObject().put("id",request.optString("id")).put("event","error").put("message","root 连接已断开，请重新打开 App。"));}catch(Exception ignored){}}
        }
        @JavascriptInterface public void fullScreen(boolean enabled){runOnUiThread(()->{immersive=enabled;if(contentFrame!=null)contentFrame.requestApplyInsets();setRequestedOrientation(enabled?ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE:ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);if(enabled)getWindow().getInsetsController().hide(WindowInsets.Type.systemBars());else getWindow().getInsetsController().show(WindowInsets.Type.systemBars());});}
        @JavascriptInterface public void repair(){runOnUiThread(()->{if(web!=null){web.destroy();web=null;}showInitialization();initialize();});}
    }
    void back(){
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
    @Override protected void onDestroy(){destroyed=true;if(backDispatcher!=null)backDispatcher.unregisterOnBackInvokedCallback(backCallback);rootReady=false;waitingRequests.clear();if(web!=null){web.removeJavascriptInterface("AcoNative");web.destroy();}if(root!=null)root.close();worker.shutdownNow();super.onDestroy();}
}
