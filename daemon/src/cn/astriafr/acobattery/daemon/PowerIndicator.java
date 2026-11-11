package cn.astriafr.acobattery.daemon;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.RectF;
import android.view.Surface;
import android.view.SurfaceControl;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.os.IBinder;
import android.os.Process;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.WindowManager;
import cn.astriafr.acobattery.common.FallbackDiagnostics;
import cn.astriafr.acobattery.common.PowerDisplayState;

/** A non-interactive compositor layer owned by the independent root daemon. */
final class PowerIndicator implements AutoCloseable {
    private static final String LAYER_NAME="ACol Performance Monitor";
    private static final float HEIGHT=11, PADDING=3, ICON_SCALE=.72f, ICON_ADVANCE=8.5f;
    private static final float CPU_CHART_WIDTH=24, GPU_BAR_WIDTH=3, BAR_HEIGHT=6.5f, FIELD_GAP=2, GROUP_GAP=3.5f;
    private static final int FPS_CELLS=3;
    private static final int FOREGROUND=Color.rgb(248,248,248);
    private static final int FPS_COLOR=Color.rgb(155,213,255);
    private static final int BACKGROUND=Color.argb(140,19,20,23);
    private static final int BATTERY_TRACK=Color.argb(48,248,248,248);
    private static final int BATTERY_LOW=Color.rgb(255,91,94), BATTERY_CHARGING=Color.rgb(103,232,145);
    private static final int USAGE_TRACK=Color.argb(80,210,221,235);
    private static final int USAGE_BLUE=Color.rgb(109,199,255), USAGE_YELLOW=Color.rgb(250,218,82);
    private static final int USAGE_ORANGE=Color.rgb(255,153,68), USAGE_RED=Color.rgb(255,91,94);
    final PowerDisplayState state;
    private final Handler ui=new Handler(Looper.getMainLooper());
    private Paint paint;
    private Path background;
    private float backgroundWidth;
    private volatile PerformanceCollector.MonitorSample performance;
    private volatile BatteryValues battery;
    private volatile String error="";
    private boolean interactive,queued,legacyCleared;
    private long generation;
    private SurfaceControl layer;
    private Surface surface;
    private int surfaceWidth,surfaceHeight;
    private final DisplayManager displays;
    private final DisplayManager.DisplayListener displayListener;

    private static final class BatteryValues {
        final double watts,temperature,level;
        final boolean charging;
        BatteryValues(Core.Reading reading){
            watts=reading.power();temperature=reading.temp;
            level=Core.validPercentage(reading.pct)?reading.pct:Double.NaN;
            charging=reading.chargingCurrent()>0&&(!reading.onlineKnown||reading.online);
        }
    }

    PowerIndicator(Context context,boolean enabled){
        state=new PowerDisplayState(enabled);
        displays=context.getSystemService(DisplayManager.class);
        displayListener=new DisplayManager.DisplayListener(){
            public void onDisplayAdded(int id){}
            public void onDisplayRemoved(int id){}
            public void onDisplayChanged(int id){synchronized(PowerIndicator.this){if(id==Display.DEFAULT_DISPLAY&&state.enabled)queue();}}
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
    private void configureTouchPassthrough(SurfaceControl.Transaction tx,int displayId)throws ReflectiveOperationException{
        Class<?> handleClass=Class.forName("android.view.InputWindowHandle");
        Class<?> applicationHandleClass=Class.forName("android.view.InputApplicationHandle");
        Object handle=handleClass.getConstructor(applicationHandleClass,int.class).newInstance(null,displayId);
        Class<?> config=Class.forName("android.os.InputConfig");
        int flags=config.getField("NOT_TOUCHABLE").getInt(null)|config.getField("NOT_FOCUSABLE").getInt(null)
            |config.getField("NO_INPUT_CHANNEL").getInt(null);
        handleClass.getField("inputConfig").setInt(handle,flags);
        handleClass.getField("name").set(handle,LAYER_NAME);
        handleClass.getField("ownerPid").setInt(handle,Process.myPid());
        handleClass.getField("ownerUid").setInt(handle,Process.myUid());
        Class<SurfaceControl.Transaction> transaction=SurfaceControl.Transaction.class;
        transaction.getMethod("setInputWindowInfo",SurfaceControl.class,handleClass).invoke(tx,layer,handle);
        // A non-touchable layer can still occlude touch targets beneath it.
        transaction.getMethod("setTrustedOverlay",SurfaceControl.class,boolean.class).invoke(tx,layer,true);
    }
    private void drawOverlay()throws Exception{
        Display display=displays.getDisplay(Display.DEFAULT_DISPLAY);
        if(display==null||display.getState()!=Display.STATE_ON){removeLayer();return;}
        if(paint==null)paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        DisplayMetrics metrics=new DisplayMetrics();display.getRealMetrics(metrics);
        float cell=MonitorGlyphs.width('0')+.35f;
        float contentWidth=2*PADDING+3*ICON_ADVANCE+CPU_CHART_WIDTH+GPU_BAR_WIDTH+3*FIELD_GAP+3*GROUP_GAP+(18+FPS_CELLS)*cell
            +MonitorGlyphs.width("W")+MonitorGlyphs.width("°C");
        float scaleX=Math.min(metrics.density,metrics.widthPixels/contentWidth);
        if(!(scaleX>0)){removeLayer();return;}
        int width=Math.max(1,Math.round(contentWidth*scaleX));
        int height=Math.max(1,Math.round(HEIGHT*metrics.density));
        boolean newLayer=layer==null||width!=surfaceWidth||height!=surfaceHeight;
        if(newLayer){
            removeLayer();surfaceWidth=width;surfaceHeight=height;
            layer=new SurfaceControl.Builder().setName(LAYER_NAME).setBufferSize(width,height).setFormat(PixelFormat.TRANSLUCENT).setHidden(true).build();
            surface=Surface.class.getConstructor(SurfaceControl.class).newInstance(layer);
        }
        Canvas canvas=surface.lockCanvas(null);
        try{
            canvas.drawColor(Color.TRANSPARENT,PorterDuff.Mode.CLEAR);
            canvas.save();canvas.scale(scaleX,metrics.density);
            drawMonitor(canvas,contentWidth,cell,performance,battery);
            canvas.restore();
        }finally{surface.unlockCanvasAndPost(canvas);}
        try(SurfaceControl.Transaction tx=new SurfaceControl.Transaction()){
            int stack=(Integer)Display.class.getMethod("getLayerStack").invoke(display);
            SurfaceControl.Transaction.class.getMethod("setLayerStack",SurfaceControl.class,int.class).invoke(tx,layer,stack);
            tx.setLayer(layer,Integer.MAX_VALUE-100).setPosition(layer,(metrics.widthPixels-width)/2f,0);
            if(newLayer)configureTouchPassthrough(tx,display.getDisplayId());
            SurfaceControl.Transaction.class.getMethod("show",SurfaceControl.class).invoke(tx,layer);tx.apply();
        }
    }

    private void drawMonitor(Canvas canvas,float width,float cell,PerformanceCollector.MonitorSample sample,BatteryValues battery){
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(BACKGROUND);
        if(backgroundWidth!=width){
            background=new Path();
            background.addRoundRect(new RectF(0,0,width,HEIGHT),new float[]{0,0,0,0,2,2,2,2},Path.Direction.CW);
            backgroundWidth=width;
        }
        canvas.drawPath(background,paint);
        float baseline=(HEIGHT+MonitorGlyphs.CAP_HEIGHT)/2;
        String fps=PowerDisplayState.fps(sample==null?Double.NaN:sample.fps);
        float x=drawField(canvas,fps,FPS_CELLS,cell,PADDING,baseline,FPS_COLOR)+GROUP_GAP;
        drawProcessorIcon(canvas,x,false);
        x+=ICON_ADVANCE;
        drawCpuUsage(canvas,x,sample);
        x+=CPU_CHART_WIDTH+FIELD_GAP;
        x=drawFrequency(canvas,sample==null?Double.NaN:sample.cpuMHz,cell,x,baseline)+GROUP_GAP;
        drawProcessorIcon(canvas,x,true);
        x+=ICON_ADVANCE;
        drawUsageBar(canvas,x,x+GPU_BAR_WIDTH,sample==null?Double.NaN:sample.gpuUsage);
        x+=GPU_BAR_WIDTH+FIELD_GAP;
        x=drawFrequency(canvas,sample==null?Double.NaN:sample.gpuMHz,cell,x,baseline)+GROUP_GAP;
        drawBatteryIcon(canvas,x,battery);
        x+=ICON_ADVANCE;
        double watts=battery==null?Double.NaN:battery.watts;
        double temperature=battery==null?Double.NaN:battery.temperature;
        String power=PowerDisplayState.power(watts),temp=PowerDisplayState.temperature(temperature);
        x=drawSignedField(canvas,power,watts,true,cell,x,baseline);
        x=drawUnit(canvas,"W",x,baseline)+FIELD_GAP;
        x=drawSignedField(canvas,temp,temperature,false,cell,x,baseline);
        drawUnit(canvas,"°C",x,baseline);
    }

    private float drawFrequency(Canvas canvas,double mhz,float cell,float x,float baseline){
        String value=PowerDisplayState.frequency(mhz);
        drawField(canvas,value,value.length()==3?3:4,cell,x,baseline);
        return x+4*cell;
    }

    private float drawField(Canvas canvas,String value,int cells,float cell,float x,float baseline){
        return drawField(canvas,value,cells,cell,x,baseline,FOREGROUND);
    }

    private float drawField(Canvas canvas,String value,int cells,float cell,float x,float baseline,int color){
        paint.setColor(color);
        for(int i=0;i<value.length();i++){
            char glyph=value.charAt(i);
            float center=x+(cells-value.length()+i+.5f)*cell;
            MonitorGlyphs.draw(canvas,paint,glyph,center-MonitorGlyphs.width(glyph)/2,baseline);
        }
        return x+cells*cell;
    }

    private float drawSignedField(Canvas canvas,String field,double value,boolean plus,float cell,float x,float baseline){
        if(!field.equals("—")){
            drawField(canvas,PowerDisplayState.sign(value,plus),1,cell,x+(4-field.length())*cell,baseline);
        }
        return drawField(canvas,field,4,cell,x+cell,baseline);
    }

    private float drawUnit(Canvas canvas,String unit,float x,float baseline){
        return x+MonitorGlyphs.draw(canvas,paint,unit,x,baseline);
    }

    private void drawCpuUsage(Canvas canvas,float x,PerformanceCollector.MonitorSample sample){
        if(sample==null||sample.cpuUsage.length==0)return;
        float step=CPU_CHART_WIDTH/sample.cpuUsage.length,gap=Math.min(.55f,step*.25f);
        for(int i=0;i<sample.cpuUsage.length;i++){
            float left=x+i*step,right=left+step-gap;
            drawUsageBar(canvas,left,right,sample.online[i]?sample.cpuUsage[i]:Double.NaN);
        }
    }

    private void drawUsageBar(Canvas canvas,float left,float right,double usage){
        float bottom=(HEIGHT+BAR_HEIGHT)/2;
        paint.setColor(USAGE_TRACK);
        canvas.drawRect(left,bottom-BAR_HEIGHT,right,bottom,paint);
        if(!(usage>0&&usage<=100))return;
        paint.setColor(usage>=90?USAGE_RED:usage>=75?USAGE_ORANGE:usage>=60?USAGE_YELLOW:USAGE_BLUE);
        float height=Math.max(.5f,BAR_HEIGHT*(float)(usage/100));
        canvas.drawRect(left,bottom-height,right,bottom,paint);
    }

    private void beginIcon(Canvas canvas,float x){
        canvas.save();
        canvas.translate(x,(HEIGHT-9*ICON_SCALE)/2);
        canvas.scale(ICON_SCALE,ICON_SCALE);
        paint.setColor(FOREGROUND);
        paint.setStrokeWidth(1);
        paint.setStyle(Paint.Style.STROKE);
    }

    private void drawProcessorIcon(Canvas canvas,float x,boolean gpu){
        beginIcon(canvas,x);
        if(gpu){
            // Dual-fan graphics card with a mounting bracket and edge connector.
            paint.setStrokeWidth(.85f);
            canvas.drawRoundRect(1.3f,1.4f,9.5f,6.7f,.65f,.65f,paint);
            canvas.drawLine(1.3f,1,1.3f,8.1f,paint);
            canvas.drawCircle(3.7f,4.05f,1.05f,paint);
            canvas.drawCircle(7,4.05f,1.05f,paint);
            paint.setStyle(Paint.Style.FILL);
            canvas.drawRect(3,6.7f,7.2f,8,paint);
        }else{
            canvas.drawRoundRect(1.5f,1.5f,7.5f,7.5f,.8f,.8f,paint);
            canvas.drawRect(3.3f,3.3f,5.7f,5.7f,paint);
            for(int pin=3;pin<=6;pin+=3){
                canvas.drawLine(pin,0,pin,1.5f,paint);
                canvas.drawLine(pin,7.5f,pin,9,paint);
                canvas.drawLine(0,pin,1.5f,pin,paint);
                canvas.drawLine(7.5f,pin,9,pin,paint);
            }
        }
        paint.setStyle(Paint.Style.FILL);
        canvas.restore();
    }

    private void drawBatteryIcon(Canvas canvas,float x,BatteryValues battery){
        beginIcon(canvas,x);
        double level=battery==null?Double.NaN:battery.level;
        int color=battery!=null&&battery.charging?BATTERY_CHARGING:level<10?BATTERY_LOW:FOREGROUND;
        paint.setColor(color);
        paint.setStrokeWidth(.85f);
        canvas.drawRoundRect(.5f,1.8f,8.7f,7.2f,.75f,.75f,paint);
        paint.setStyle(Paint.Style.FILL);
        canvas.drawRoundRect(9.1f,3.25f,10.1f,5.75f,.25f,.25f,paint);
        float left=1.55f,right=7.65f,top=2.85f,bottom=6.15f;
        paint.setColor(BATTERY_TRACK);
        canvas.drawRect(left,top,right,bottom,paint);
        if(level>0){
            paint.setColor(color);
            canvas.drawRect(left,top,left+(right-left)*(float)(level/100),bottom,paint);
        }
        canvas.restore();
    }
    private void applyWindow(){
        long revision;boolean show;synchronized(this){revision=generation;show=state.enabled&&interactive;}
        try{
            clearLegacy();
            if(show)drawOverlay();else removeLayer();
            error="";
        }catch(Throwable failure){
            try{removeLayer();}catch(Throwable cleanup){failure.addSuppressed(cleanup);}
            failed(failure);
        }
        finally{synchronized(this){if(revision!=generation)ui.post(this::applyWindow);else queued=false;}}
    }
    synchronized void enabled(boolean enabled){
        if(state.enabled==enabled)return;
        state.enabled=enabled;resetSamples();queue();
    }
    synchronized void screen(boolean interactive){
        if(this.interactive==interactive)return;
        this.interactive=interactive;resetSamples();if(state.enabled)queue();
    }
    private void resetSamples(){performance=null;battery=null;state.refresh();}
    PerformanceCollector.MonitorSample performance(){return performance;}
    synchronized void updatePerformance(PerformanceCollector.MonitorSample sample){performance=sample;queue();}
    synchronized void updateFps(PerformanceCollector.MonitorSample sample,double fps){
        if(performance!=sample)return;
        sample.fps=fps;queue();
    }
    void updateBattery(Core.Reading reading){battery=reading==null?null:new BatteryValues(reading);queue();}
    private void failed(Throwable failure){
        Throwable cause=FrameworkWatch.cause(failure);
        error=cause instanceof SecurityException||cause instanceof WindowManager.BadTokenException
            ?"系统未允许 Root 性能监视器，请查看错误日志。":"性能监视器暂不可用，稍后重试。";
        FallbackDiagnostics.record("性能监视器悬浮层",cause);
    }
    @Override public void close(){
        try{displays.unregisterDisplayListener(displayListener);}catch(Exception ignored){}
        enabled(false);
    }
}
