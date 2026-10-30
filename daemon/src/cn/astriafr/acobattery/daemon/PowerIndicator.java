package cn.astriafr.acobattery.daemon;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.view.Surface;
import android.view.SurfaceControl;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.WindowManager;
import cn.astriafr.acobattery.common.FallbackDiagnostics;
import cn.astriafr.acobattery.common.PowerDisplayState;

/** A non-interactive compositor layer owned by the independent root daemon. */
final class PowerIndicator implements AutoCloseable {
    final PowerDisplayState state;
    private final Context context;
    private final Handler ui=new Handler(Looper.getMainLooper());
    private volatile String text="—W",error="";
    private volatile int textColor=Color.rgb(120,120,120);
    private boolean wanted,queued,legacyCleared;
    private long generation;
    private SurfaceControl layer;
    private Surface surface;
    private int surfaceWidth,surfaceHeight;
    private final DisplayManager displays;
    private final DisplayManager.DisplayListener displayListener;
    PowerIndicator(Context context,boolean enabled){
        this.context=context;state=new PowerDisplayState(enabled);wanted=enabled;
        displays=context.getSystemService(DisplayManager.class);
        displayListener=new DisplayManager.DisplayListener(){
            public void onDisplayAdded(int id){}
            public void onDisplayRemoved(int id){}
            public void onDisplayChanged(int id){synchronized(PowerIndicator.this){if(id==Display.DEFAULT_DISPLAY&&wanted)queue();}}
        };
        try{displays.registerDisplayListener(displayListener,ui);}catch(Exception failure){FallbackDiagnostics.record("功耗悬浮层屏幕监听",failure);}
        queue();
    }
    String error(){return error;}
    private synchronized void queue(){
        generation++;
        if(queued)return;
        queued=true;ui.post(this::applyWindow);
    }
    private void clearLegacy(){
        if(legacyCleared)return;
        try{
            IBinder binder=(IBinder)Class.forName("android.os.ServiceManager").getMethod("checkService",String.class).invoke(null,"notification");
            Object service=Class.forName("android.app.INotificationManager$Stub").getMethod("asInterface",IBinder.class).invoke(null,binder);
            int user=0;try{user=Integer.parseInt(Owner.read(new java.io.File(Owner.BASE,"owner.user")));}catch(Exception ignored){}
            Class.forName("android.app.INotificationManager").getMethod("cancelNotificationWithTag",String.class,String.class,String.class,int.class,int.class)
                .invoke(service,"android","android","cn.astriafr.acobattery.power",0x41434f50,user);
            legacyCleared=true;
        }catch(Exception e){FallbackDiagnostics.record("清理旧功耗通知",FrameworkWatch.cause(e));}
    }
    private void removeLayer()throws Exception{
        if(surface!=null){surface.release();surface=null;}
        if(layer!=null){try(SurfaceControl.Transaction tx=new SurfaceControl.Transaction()){
            SurfaceControl.Transaction.class.getMethod("remove",SurfaceControl.class).invoke(tx,layer);tx.apply();
        }finally{layer.release();layer=null;}}
    }
    private void drawOverlay()throws Exception{
        Display display=context.getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY);
        DisplayMetrics metrics=new DisplayMetrics();display.getRealMetrics(metrics);
        float density=metrics.density;int width=Math.round(66*density),height=Math.round(22*density);
        if(layer==null||width!=surfaceWidth||height!=surfaceHeight){
            removeLayer();surfaceWidth=width;surfaceHeight=height;
            layer=new SurfaceControl.Builder().setName("ACol Power Overlay").setBufferSize(width,height).setFormat(PixelFormat.TRANSLUCENT).setHidden(true).build();
            surface=Surface.class.getConstructor(SurfaceControl.class).newInstance(layer);
        }
        Canvas canvas=surface.lockCanvas(null);
        try{
            canvas.drawColor(Color.TRANSPARENT,PorterDuff.Mode.CLEAR);
            String value=text;
            Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
            float measured=PowerGlyphs.width(value);float scale=.85f*Math.min((width-4)/Math.max(1,measured),height/1250f);
            paint.setColor(textColor);paint.setStyle(Paint.Style.FILL);
            canvas.save();canvas.translate((width-measured*scale)/2,height/2f+350*scale);canvas.scale(scale,-scale);
            PowerGlyphs.draw(canvas,paint,value);canvas.restore();
        }finally{surface.unlockCanvasAndPost(canvas);}
        int resource=context.getResources().getIdentifier("status_bar_height","dimen","android");
        int bar=resource==0?Math.round(28*density):context.getResources().getDimensionPixelSize(resource);
        try(SurfaceControl.Transaction tx=new SurfaceControl.Transaction()){
            int stack=(Integer)Display.class.getMethod("getLayerStack").invoke(display);
            SurfaceControl.Transaction.class.getMethod("setLayerStack",SurfaceControl.class,int.class).invoke(tx,layer,stack);
            tx.setLayer(layer,Integer.MAX_VALUE-100).setPosition(layer,Math.max(0,metrics.widthPixels*.35f-width/2),Math.max(0,(bar-height)/2));
            SurfaceControl.Transaction.class.getMethod(display.getState()==Display.STATE_ON?"show":"hide",SurfaceControl.class).invoke(tx,layer);tx.apply();
        }
    }
    private void applyWindow(){
        long revision;boolean show;synchronized(this){revision=generation;show=wanted;}
        try{
            clearLegacy();
            if(show)drawOverlay();else removeLayer();
            error="";
        }catch(Throwable failure){failed(failure);}
        finally{synchronized(this){if(revision!=generation)ui.post(this::applyWindow);else queued=false;}}
    }
    void enabled(boolean enabled,Core.Reading reading){
        state.enabled=enabled;state.refresh();synchronized(this){wanted=enabled;}
        if(reading!=null)setReading(reading);queue();
    }
    void update(Core.Reading reading,long now,long seconds){
        if(!state.enabled)return;setReading(reading);queue();state.sampled(now,seconds);
    }
    private void setReading(Core.Reading reading){double power=reading==null?Double.NaN:reading.power();text=PowerDisplayState.text(power);textColor=!Core.finite(power)?Color.rgb(160,160,160):power>=0?Color.rgb(55,196,142):Color.rgb(255,185,126);}
    void unavailable(Throwable failure,long now,long seconds){failed(failure);state.sampled(now,seconds);}
    private void failed(Throwable failure){
        Throwable cause=FrameworkWatch.cause(failure);
        error=cause instanceof SecurityException||cause instanceof WindowManager.BadTokenException
            ?"系统未允许 Root 功耗悬浮窗，请查看错误日志。":"功耗悬浮窗暂不可用，稍后重试。";
        FallbackDiagnostics.record("状态栏功耗悬浮窗",cause);
    }
    @Override public void close(){try{displays.unregisterDisplayListener(displayListener);}catch(Exception ignored){}synchronized(this){wanted=false;}queue();}
}
