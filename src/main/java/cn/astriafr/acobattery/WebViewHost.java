package cn.astriafr.acobattery;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.util.Base64;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.webkit.*;
import android.widget.Toast;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;

/** Local WebView, native bridge, display theme and page navigation. */
final class WebViewHost {
    final MainActivity app;
    volatile WebView web;boolean immersive,backPending,pageLoaded,tileSettingsPending;
    volatile long pageGeneration;
    Boolean activeDarkTheme;long themeBarGeneration;
    final ConcurrentHashMap<String,String> iconCache=new ConcurrentHashMap<>();
    final Object iconLock=new Object();
    WebViewHost(MainActivity app){this.app=app;}
    void destroy(){
        pageGeneration++;themeBarGeneration++;pageLoaded=false;backPending=false;activeDarkTheme=null;
        if(immersive&&!app.destroyed)fullScreen(false);
        WebView previous=web;web=null;
        if(previous!=null){
            previous.removeJavascriptInterface("AcoNative");previous.stopLoading();
            if(previous.getParent() instanceof android.view.ViewGroup)((android.view.ViewGroup)previous.getParent()).removeView(previous);
            previous.destroy();
        }
    }
    void openTileSettings(){
        if(!tileSettingsPending||web==null||!pageLoaded)return;
        tileSettingsPending=false;app.startup.closeErrorPage();if(app.errorLogs!=null)app.errorLogs.close();web.evaluateJavascript("window.__acoOpenSettings&&window.__acoOpenSettings()",null);
    }
    @SuppressLint({"SetJavaScriptEnabled","AddJavascriptInterface"}) void showWeb(){
        if(app.destroyed)return;destroy();
        app.startup.initialized=true;web=new WebView(app);web.setOverScrollMode(View.OVER_SCROLL_NEVER);web.setVerticalScrollBarEnabled(false);web.setHorizontalScrollBarEnabled(false);web.setBackgroundColor(Color.rgb(246,248,251));WebSettings settings=web.getSettings();settings.setTextZoom(100);settings.setUseWideViewPort(true);settings.setSupportZoom(false);settings.setJavaScriptEnabled(true);settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);settings.setAllowContentAccess(false);settings.setSupportMultipleWindows(false);settings.setMediaPlaybackRequiresUserGesture(true);web.addJavascriptInterface(new Bridge(),"AcoNative");
        web.setWebViewClient(new WebViewClient(){
            @Override public void onPageFinished(WebView view,String url){if(view==web){pageLoaded=true;openTileSettings();app.startup.showStartupNotice();}}
            @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest request){
                Uri url=request.getUrl();
                if(request.isForMainFrame()&&request.hasGesture()&&"https://github.com/AstriaFR/ACol-Battery".equals(url.toString())){
                    try{Intent intent=new Intent(Intent.ACTION_VIEW,url);intent.addCategory(Intent.CATEGORY_BROWSABLE);app.startActivity(intent);}
                    catch(android.content.ActivityNotFoundException unavailable){Toast.makeText(app,"无法打开浏览器",Toast.LENGTH_SHORT).show();}
                    return true;
                }
                return !trusted(url);
            }
            @Override public WebResourceResponse shouldInterceptRequest(WebView view,WebResourceRequest request){
                Uri uri=request.getUrl();if(!trusted(uri))return new WebResourceResponse("text/plain","UTF-8",403,"Blocked",null,new ByteArrayInputStream(new byte[0]));
                String path=uri.getPath().substring(1);
                try{String ext=path.substring(path.lastIndexOf('.')+1);String mime=ext.equals("js")?"application/javascript":ext.equals("css")?"text/css":ext.equals("png")?"image/png":ext.equals("ttf")?"font/ttf":ext.equals("otf")?"font/otf":"text/html";
                    return new WebResourceResponse(mime,(ext.equals("png")||ext.equals("ttf")||ext.equals("otf"))?null:"UTF-8",app.getAssets().open(path));
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
        app.mount(web);web.loadUrl("https://aco.local/webroot/index.html"+(tileSettingsPending?"#settings":""));
    }
    void applyThemeBars(boolean dark){
        app.runOnUiThread(()->{
            if(app.destroyed||app.isFinishing())return;
            final long generation=++themeBarGeneration;
            if(activeDarkTheme!=null&&activeDarkTheme==dark)return;
            final WebView target=web;
            if(target!=null&&target.isAttachedToWindow()&&target.getVisibility()==View.VISIBLE){
                try{
                    // Change the system bars only when the matching WebView state
                    // is ready for its next draw, rather than at DOM mutation time.
                    target.postVisualStateCallback(generation,new WebView.VisualStateCallback(){
                        @Override public void onComplete(long requestId){
                            if(generation==themeBarGeneration&&web==target&&!app.destroyed&&!app.isFinishing())commitThemeBars(dark);
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
        app.getWindow().setStatusBarColor(surface);app.getWindow().setNavigationBarColor(surface);
        WindowInsetsController controller=app.getWindow().getInsetsController();
        if(controller!=null){int appearance=WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS|WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;controller.setSystemBarsAppearance(dark?0:appearance,appearance);}
        if(app.contentFrame!=null)app.contentFrame.setBackgroundColor(surface);
        if(web!=null)web.setBackgroundColor(surface);
    }
    static boolean trusted(Uri uri){String path=uri.getPath();return "https".equals(uri.getScheme())&&"aco.local".equals(uri.getHost())&&path!=null&&path.startsWith("/webroot/")&&!path.contains("..")&&!path.contains("\\");}
    String appIcon(String pkg){
        String cached=iconCache.get(pkg);if(cached!=null)return cached;
        synchronized(iconLock){
            cached=iconCache.get(pkg);if(cached!=null)return cached;
            File dir=new File(app.getFilesDir(),"app-icons");
            if(!dir.isDirectory()&&!dir.mkdirs())return null;
            long updated=0;
            try{updated=app.getPackageManager().getPackageInfo(pkg,0).lastUpdateTime;}catch(Exception ignored){}
            String name;
            try{name=StartupController.digest(pkg.getBytes(StandardCharsets.UTF_8))+"-"+updated;}catch(Exception e){return null;}
            File file=new File(dir,name+".webp");
            String icon=null;
            try{
                if(file.isFile()&&file.length()>0){
                    icon="data:image/webp;base64,"+Base64.encodeToString(java.nio.file.Files.readAllBytes(file.toPath()),Base64.NO_WRAP);
                }else{
                    Drawable drawable=app.getPackageManager().getApplicationIcon(pkg);
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
        if(app.destroyed)return;
        WebView target=web;
        app.runOnUiThread(()->{if(!app.destroyed&&target!=null&&web==target)target.evaluateJavascript("window.__acoDeliver&&window.__acoDeliver(JSON.parse("+JSONObject.quote(message.toString())+"))",null);});
    }
    void fullScreen(boolean enabled){
        immersive=enabled;if(app.contentFrame!=null)app.contentFrame.requestApplyInsets();
        app.setRequestedOrientation(enabled?ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE:ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        WindowInsetsController controller=app.getWindow().getInsetsController();
        if(controller!=null){if(enabled)controller.hide(WindowInsets.Type.systemBars());else controller.show(WindowInsets.Type.systemBars());}
    }
    public final class Bridge {
        final long generation=pageGeneration;
        boolean active(){return !app.destroyed&&web!=null&&generation==pageGeneration;}
        void ui(Runnable action){if(active())app.runOnUiThread(()->{if(active())action.run();});}
        @JavascriptInterface public void backupAllData(){ui(()->{if(app.dataTransfer!=null)app.dataTransfer.backup();});}
        @JavascriptInterface public void restoreAllData(){ui(()->{if(app.dataTransfer!=null)app.dataTransfer.restore();});}
        @JavascriptInterface public void openErrorLogs(){ui(app::openErrorLogs);}
        @JavascriptInterface public void clearAllData(){ui(()->app.askClearAllData(1));}
        @JavascriptInterface public String readRecordListCache(String key){return active()?app.recordListCache.read(key):"";}
        @JavascriptInterface public void writeRecordListCache(String key,String json){
            if(!active()||json==null||json.length()>32768)return;
            long generation=app.recordListCache.generation();
            try{app.worker.execute(()->{if(active())app.recordListCache.write(key,json,generation);});}
            catch(java.util.concurrent.RejectedExecutionException ignored){}
        }
        @JavascriptInterface public void clearRecordListCache(){if(active())app.recordListCache.clear();}
        @JavascriptInterface public void post(String text){if(active())app.startup.post(text);}
        @JavascriptInterface public String beginChartExport(String name){return active()?app.chartExport.begin(name):"";}
        @JavascriptInterface public boolean appendChartExport(String token,String text){return active()&&app.chartExport.append(token,text);}
        @JavascriptInterface public boolean finishChartExport(String token){return active()&&app.chartExport.finish(token);}
        @JavascriptInterface public void cancelChartExport(String token){if(active())app.chartExport.cancel(token);}
        @JavascriptInterface public String appIcons(String json){
            if(!active()||json==null||json.length()>8192)return"{}";
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
        @JavascriptInterface public void fullScreen(boolean enabled){ui(()->WebViewHost.this.fullScreen(enabled));}
        @JavascriptInterface public void setDarkTheme(boolean enabled){ui(()->applyThemeBars(enabled));}
        @JavascriptInterface public boolean isSystemDark(){return app.systemDark();}
        @JavascriptInterface public String connectionStatus(){return app.startup.rootReady?"":app.startup.connectionStatus;}
        @JavascriptInterface public void repair(){ui(()->{destroy();app.startup.showInitialization();app.startup.initialize();});}
    }
    void back(){
        if(web==null){app.finish();return;}
        WindowInsets insets=web.getRootWindowInsets();
        if(insets!=null&&insets.isVisible(WindowInsets.Type.ime())){app.getWindow().getInsetsController().hide(WindowInsets.Type.ime());return;}
        if(backPending)return;backPending=true;WebView current=web;
        current.evaluateJavascript("(()=>{try{return typeof window.__acoBack==='function'?window.__acoBack():'wait';}catch{return 'wait';}})()",value->{
            if(app.destroyed||web!=current)return;
            // Loading, missing history and script errors must never exit a child page.
            if("\"home\"".equals(value))app.finish();
            current.postDelayed(()->{if(web==current)backPending=false;},200);
        });
    }
}
