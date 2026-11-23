package cn.astriafr.acobattery.daemon;

import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.Rect;
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
import static cn.astriafr.acobattery.daemon.MonitorAppearance.*;

/** A non-interactive compositor layer owned by the independent root daemon. */
final class PowerIndicator implements AutoCloseable {
    private static final String LAYER_NAME="ACol Performance Monitor";
    private static final float HEIGHT=11, PADDING=3, ICON_SCALE=.72f, ICON_ADVANCE=8.5f;
    private static final float CPU_CHART_WIDTH=24, GPU_BAR_WIDTH=3, BAR_HEIGHT=6.5f, FIELD_GAP=2, GROUP_GAP=3.5f;
    private static final int FPS_CELLS=3;
    private static final int LANDSCAPE_RENDER_SCALE=2;
    private static final int FOREGROUND=Color.rgb(248,248,248);
    private static final int FPS_COLOR=Color.rgb(155,213,255);
    private static final int DARK_BLUE=Color.rgb(31,83,158);
    private static final int BATTERY_CHARGING=Color.rgb(103,232,145);
    private static final int USAGE_TRACK=Color.argb(80,210,221,235);
    private static final int USAGE_BLUE=Color.rgb(109,199,255), YELLOW=Color.rgb(250,218,82);
    private static final int ORANGE=Color.rgb(255,153,68), RED=Color.rgb(255,91,94);
    final PowerDisplayState state;
    private final Handler ui=new Handler(Looper.getMainLooper());
    private Paint paint;
    private int foreground=FOREGROUND;
    private final Context context;
    private final BroadcastReceiver themeReceiver;
    private boolean themeChanged=true,systemDark;
    private volatile MonitorAppearance appearance;
    private Path background;
    private float backgroundWidth;
    private boolean backgroundAtBottom;
    private volatile PerformanceCollector.MonitorSample performance;
    private volatile BatteryValues battery;
    private volatile String error="";
    private boolean interactive,queued,legacyCleared;
    private int displayState=Display.STATE_UNKNOWN;
    private long generation;
    private SurfaceControl layer;
    private Surface surface;
    private int surfaceWidth,surfaceHeight;
    private boolean displayChanged=true,drawnAtBottom,drawnLandscape;
    private Bitmap landscapeBitmap;
    private Canvas landscapeCanvas;
    private Paint landscapeFilter;
    private final Rect landscapeBounds=new Rect();
    private int layerStack;
    private float layerX,layerY,drawnScaleX,drawnScaleY;
    private MonitorAppearance drawnAppearance;
    private String drawnContent;
    private final DisplayManager displays;
    private final DisplayManager.DisplayListener displayListener;

    static final class BatteryValues {
        final double watts,temperature,level;
        final boolean charging;
        BatteryValues(Core.Reading reading){
            this(reading.power(),reading.temp,reading.pct,reading.chargingCurrent()>0&&(!reading.onlineKnown||reading.online));
        }
        BatteryValues(double watts,double temperature,double level,boolean charging){
            this.watts=watts;this.temperature=temperature;this.charging=charging;
            this.level=Core.validPercentage(level)?level:Double.NaN;
        }
    }

    PowerIndicator(Context context,boolean enabled,MonitorAppearance appearance){
        state=new PowerDisplayState(enabled);
        this.context=context;
        this.appearance=appearance;
        themeReceiver=new BroadcastReceiver(){
            @Override public void onReceive(Context context,Intent intent){
                if(!Intent.ACTION_CONFIGURATION_CHANGED.equals(intent.getAction()))return;
                synchronized(PowerIndicator.this){
                    themeChanged=true;
                    if(state.enabled&&PowerIndicator.this.appearance.followSystem)queue();
                }
            }
        };
        try{
            context.registerReceiver(themeReceiver,new IntentFilter(Intent.ACTION_CONFIGURATION_CHANGED),null,ui,Context.RECEIVER_EXPORTED);
        }catch(Exception ignored){}
        displays=context.getSystemService(DisplayManager.class);
        displayListener=new DisplayManager.DisplayListener(){
            public void onDisplayAdded(int id){}
            public void onDisplayRemoved(int id){}
            public void onDisplayChanged(int id){
                if(id!=Display.DEFAULT_DISPLAY)return;
                synchronized(PowerIndicator.this){displayChanged=true;if(state.enabled)queue();}
            }
        };
        try{displays.registerDisplayListener(displayListener,ui);}catch(Exception failure){FallbackDiagnostics.record("功耗悬浮层屏幕监听",failure);}
        queue();
    }
    String error(){return error;}
    MonitorAppearance appearance(){return appearance;}
    synchronized void appearance(MonitorAppearance value){
        if(value.followSystem&&!appearance.followSystem)themeChanged=true;
        if(value.metrics!=appearance.metrics)resetSamples();
        appearance=value;
        if(state.enabled)queue();
    }
    private synchronized void queue(){
        queue(0);
    }
    private synchronized void queueReading(){
        // Let a performance sample, its FPS result and a battery sample share a redraw.
        queue(16);
    }
    private void queue(long delay){
        generation++;
        if(queued)return;
        queued=true;ui.postDelayed(this::applyWindow,delay);
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
        drawnContent=null;drawnAppearance=null;
        releaseLandscapeBitmap();
        if(surface!=null){surface.release();surface=null;}
        if(layer!=null){try(SurfaceControl.Transaction tx=new SurfaceControl.Transaction()){
            SurfaceControl.Transaction.class.getMethod("remove",SurfaceControl.class).invoke(tx,layer);tx.apply();
        }finally{layer.release();layer=null;}}
    }
    private void releaseLandscapeBitmap(){
        if(landscapeBitmap==null)return;
        landscapeCanvas=null;
        landscapeBitmap.recycle();
        landscapeBitmap=null;
    }
    private Canvas prepareLandscapeCanvas(int width,int height){
        if(landscapeCanvas==null){
            landscapeBitmap=Bitmap.createBitmap(width*LANDSCAPE_RENDER_SCALE,height*LANDSCAPE_RENDER_SCALE,Bitmap.Config.ARGB_8888);
            landscapeCanvas=new Canvas(landscapeBitmap);
            landscapeBounds.set(0,0,width,height);
            if(landscapeFilter==null)landscapeFilter=new Paint(Paint.FILTER_BITMAP_FLAG);
        }
        return landscapeCanvas;
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
    private void drawOverlay(MonitorAppearance appearance)throws Exception{
        Display display=displays.getDisplay(Display.DEFAULT_DISPLAY);
        if(display==null||display.getState()!=Display.STATE_ON){removeLayer();return;}
        if(paint==null)paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        DisplayMetrics metrics=new DisplayMetrics();display.getRealMetrics(metrics);
        float cell=MonitorGlyphs.width('0')+.35f;
        boolean cpu=appearance.shows(CPU_CHART|CPU_FREQUENCY),gpu=appearance.shows(GPU_CHART|GPU_FREQUENCY);
        boolean power=appearance.shows(POWER),temperature=appearance.shows(TEMPERATURE);
        int groups=(appearance.shows(FPS)?1:0)+(cpu?1:0)+(gpu?1:0)+(power||temperature?1:0);
        float contentWidth=2*PADDING+(groups-1)*GROUP_GAP;
        if(appearance.shows(FPS))contentWidth+=FPS_CELLS*cell;
        if(cpu)contentWidth+=processorWidth(appearance,CPU_CHART,CPU_FREQUENCY,CPU_CHART_WIDTH,cell);
        if(gpu)contentWidth+=processorWidth(appearance,GPU_CHART,GPU_FREQUENCY,GPU_BAR_WIDTH,cell);
        if(power||temperature)contentWidth+=ICON_ADVANCE;
        if(power)contentWidth+=5*cell+MonitorGlyphs.width("W");
        if(temperature)contentWidth+=5*cell+MonitorGlyphs.width("°C");
        if(power&&temperature)contentWidth+=FIELD_GAP;
        float fullWidth=2*PADDING+3*ICON_ADVANCE+CPU_CHART_WIDTH+GPU_BAR_WIDTH+3*FIELD_GAP+3*GROUP_GAP+(18+FPS_CELLS)*cell
            +MonitorGlyphs.width("W")+MonitorGlyphs.width("°C");
        float baseScaleX=Math.min(metrics.density,metrics.widthPixels/fullWidth);
        if(!(baseScaleX>0)){removeLayer();return;}
        float size=Math.min(appearance.sizePercent/100f,metrics.widthPixels/(contentWidth*baseScaleX));
        float scaleX=baseScaleX*size,scaleY=metrics.density*size;
        int width=Math.max(1,Math.round(contentWidth*scaleX));
        int height=Math.max(1,Math.round(HEIGHT*scaleY));
        boolean landscape=metrics.widthPixels>metrics.heightPixels;
        boolean atBottom=appearance.landscapeBottom&&landscape;
        boolean newLayer=layer==null||width!=surfaceWidth||height!=surfaceHeight;
        float x=(metrics.widthPixels-width)/2f,y=atBottom?Math.max(0,metrics.heightPixels-height):0;
        // In landscape the compositor can otherwise sample the text at a half-pixel offset.
        if(landscape)x=Math.round(x);
        int stack=layerStack;
        if(newLayer||displayChanged){
            stack=(Integer)Display.class.getMethod("getLayerStack").invoke(display);
            displayChanged=false;
        }
        boolean positionChanged=newLayer||stack!=layerStack||x!=layerX||y!=layerY;
        if(newLayer){
            removeLayer();surfaceWidth=width;surfaceHeight=height;
            layer=new SurfaceControl.Builder().setName(LAYER_NAME).setBufferSize(width,height).setFormat(PixelFormat.TRANSLUCENT).setHidden(true).build();
            surface=Surface.class.getConstructor(SurfaceControl.class).newInstance(layer);
        }
        if(!landscape)releaseLandscapeBitmap();
        PerformanceCollector.MonitorSample sample=performance;
        BatteryValues reading=battery;
        double fps=sample==null?Double.NaN:sample.fps;
        boolean blackText=appearance.followSystem?!systemDark:appearance.blackText;
        foreground=blackText?Color.BLACK:FOREGROUND;
        String content=visibleContent(appearance,sample,reading,fps,scaleY,blackText);
        if(drawnAppearance!=appearance||!content.equals(drawnContent)||scaleX!=drawnScaleX||scaleY!=drawnScaleY
                ||atBottom!=drawnAtBottom||landscape!=drawnLandscape){
            Canvas raster=landscape?prepareLandscapeCanvas(width,height):null;
            Canvas canvas=surface.lockCanvas(null);
            try{
                // Draw at twice the output resolution, then filter back to the original layer size.
                Canvas target=landscape?raster:canvas;
                int factor=landscape?LANDSCAPE_RENDER_SCALE:1;
                target.drawColor(Color.TRANSPARENT,PorterDuff.Mode.CLEAR);
                int checkpoint=target.save();
                try{
                    target.scale(scaleX*factor,scaleY*factor);
                    drawMonitor(target,contentWidth,cell,sample,reading,appearance,atBottom,blackText,fps);
                }finally{target.restoreToCount(checkpoint);}
                if(landscape){
                    canvas.drawColor(Color.TRANSPARENT,PorterDuff.Mode.CLEAR);
                    canvas.drawBitmap(landscapeBitmap,null,landscapeBounds,landscapeFilter);
                }
            }finally{surface.unlockCanvasAndPost(canvas);}
            drawnAppearance=appearance;drawnContent=content;drawnScaleX=scaleX;drawnScaleY=scaleY;drawnAtBottom=atBottom;
            drawnLandscape=landscape;
        }
        if(positionChanged)try(SurfaceControl.Transaction tx=new SurfaceControl.Transaction()){
            if(newLayer||stack!=layerStack)SurfaceControl.Transaction.class.getMethod("setLayerStack",SurfaceControl.class,int.class).invoke(tx,layer,stack);
            tx.setPosition(layer,x,y);
            if(newLayer){
                tx.setLayer(layer,Integer.MAX_VALUE-100);
                configureTouchPassthrough(tx,display.getDisplayId());
                SurfaceControl.Transaction.class.getMethod("show",SurfaceControl.class).invoke(tx,layer);
            }
            tx.apply();layerStack=stack;layerX=x;layerY=y;
        }
    }

    private String visibleContent(MonitorAppearance appearance,PerformanceCollector.MonitorSample sample,BatteryValues reading,
            double fps,float scaleY,boolean blackText){
        StringBuilder content=new StringBuilder(100).append(blackText).append('|');
        if(appearance.shows(FPS))content.append(PowerDisplayState.fps(fps)).append('|');
        if(appearance.shows(CPU_FREQUENCY))content.append(PowerDisplayState.frequency(sample==null?Double.NaN:sample.cpuMHz)).append('|');
        if(appearance.shows(CPU_CHART)){
            if(sample!=null)for(int i=0;i<sample.cpuUsage.length;i++){
                double usage=sample.online[i]?sample.cpuUsage[i]:Double.NaN;
                appendBar(content,usage,cpuUsageColor(usage,appearance.cpu),scaleY);
            }
            content.append('|');
        }
        if(appearance.shows(GPU_FREQUENCY))content.append(PowerDisplayState.frequency(sample==null?Double.NaN:sample.gpuMHz)).append('|');
        if(appearance.shows(GPU_CHART)){
            double usage=sample==null?Double.NaN:sample.gpuUsage;
            appendBar(content,usage,usage>=90?YELLOW:usageBlue(),scaleY);
        }
        if(appearance.shows(BATTERY)){
            if(reading!=null&&reading.level>0)content.append(reading.level).append('/').append(reading.charging);
            content.append('|');
            if(appearance.shows(POWER)){
                double watts=reading==null?Double.NaN:reading.watts;
                appendField(content,PowerDisplayState.power(watts),watts,true,warningColor(-watts,appearance.power));
            }
            if(appearance.shows(TEMPERATURE)){
                double temperature=reading==null?Double.NaN:reading.temperature;
                appendField(content,PowerDisplayState.temperature(temperature),temperature,false,warningColor(temperature,appearance.temperature));
            }
        }
        return content.toString();
    }

    private static void appendField(StringBuilder content,String text,double reading,boolean plus,int color){
        if(!text.equals("—"))content.append(PowerDisplayState.sign(reading,plus));
        content.append(text).append('/').append(color).append('|');
    }

    private static void appendBar(StringBuilder content,double usage,int color,float scaleY){
        float height=barHeight(usage);
        if(height>0)content.append(Math.round(height*scaleY)).append('/').append(color);
        content.append(',');
    }

    private static float processorWidth(MonitorAppearance appearance,int chart,int frequency,float chartWidth,float cell){
        return ICON_ADVANCE+(appearance.shows(chart)?chartWidth:0)+(appearance.shows(frequency)?4*cell:0)
            +(appearance.shows(chart)&&appearance.shows(frequency)?FIELD_GAP:0);
    }

    private void drawMonitor(Canvas canvas,float width,float cell,PerformanceCollector.MonitorSample sample,BatteryValues battery,
            MonitorAppearance appearance,boolean atBottom,boolean blackText,double fps){
        int opacity=blackText?appearance.blackOpacity:appearance.whiteOpacity;
        int alpha=Math.round(opacity*255/100f);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(blackText?Color.argb(alpha,248,248,248):Color.argb(alpha,19,20,23));
        if(backgroundWidth!=width||backgroundAtBottom!=atBottom){
            background=new Path();
            background.addRoundRect(new RectF(0,0,width,HEIGHT),
                atBottom?new float[]{2,2,2,2,0,0,0,0}:new float[]{0,0,0,0,2,2,2,2},Path.Direction.CW);
            backgroundWidth=width;
            backgroundAtBottom=atBottom;
        }
        canvas.drawPath(background,paint);
        float baseline=(HEIGHT+MonitorGlyphs.CAP_HEIGHT)/2;
        float x=PADDING;
        int previousGroup=0;
        for(int index=0;index<appearance.order.length();index++){
            int metric=appearance.metricAt(index);
            if(!appearance.shows(metric))continue;
            int group=groupOf(metric);
            if(previousGroup==group)x+=FIELD_GAP;
            else{
                if(previousGroup!=0)x+=GROUP_GAP;
                if(group!=FPS){
                    if(group==BATTERY)drawBatteryIcon(canvas,x,battery);
                    else drawProcessorIcon(canvas,x,group==GPU);
                    x+=ICON_ADVANCE;
                }
                previousGroup=group;
            }
            switch(metric){
                case FPS:
                    x=drawField(canvas,PowerDisplayState.fps(fps),FPS_CELLS,cell,x,baseline,blackText?DARK_BLUE:FPS_COLOR);
                    break;
                case CPU_CHART:
                    drawCpuUsage(canvas,x,sample,appearance);
                    x+=CPU_CHART_WIDTH;
                    break;
                case GPU_CHART:
                    double usage=sample==null?Double.NaN:sample.gpuUsage;
                    drawUsageBar(canvas,x,x+GPU_BAR_WIDTH,usage,usage>=90?YELLOW:usageBlue());
                    x+=GPU_BAR_WIDTH;
                    break;
                case CPU_FREQUENCY:
                case GPU_FREQUENCY:
                    double frequency=sample==null?Double.NaN:group==GPU?sample.gpuMHz:sample.cpuMHz;
                    x=drawFrequency(canvas,frequency,cell,x,baseline);
                    break;
                case POWER:
                    double watts=battery==null?Double.NaN:battery.watts;
                    x=drawSignedField(canvas,PowerDisplayState.power(watts),watts,true,cell,x,baseline,warningColor(-watts,appearance.power));
                    x=drawUnit(canvas,"W",x,baseline);
                    break;
                case TEMPERATURE:
                    double temperature=battery==null?Double.NaN:battery.temperature;
                    x=drawSignedField(canvas,PowerDisplayState.temperature(temperature),temperature,false,cell,x,baseline,warningColor(temperature,appearance.temperature));
                    x=drawUnit(canvas,"°C",x,baseline);
                    break;
            }
        }
    }

    private int warningColor(double value,MonitorAppearance.Thresholds thresholds){
        if(!Double.isFinite(value))return foreground;
        if(value>thresholds.red)return RED;
        if(value>thresholds.orange)return ORANGE;
        return value>thresholds.yellow?YELLOW:foreground;
    }

    private float drawFrequency(Canvas canvas,double mhz,float cell,float x,float baseline){
        String value=PowerDisplayState.frequency(mhz);
        drawField(canvas,value,value.length()==3?3:4,cell,x,baseline);
        return x+4*cell;
    }

    private float drawField(Canvas canvas,String value,int cells,float cell,float x,float baseline){
        return drawField(canvas,value,cells,cell,x,baseline,foreground);
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

    private float drawSignedField(Canvas canvas,String field,double value,boolean plus,float cell,float x,float baseline,int color){
        if(!field.equals("—")){
            drawField(canvas,PowerDisplayState.sign(value,plus),1,cell,x+(4-field.length())*cell,baseline,color);
        }
        return drawField(canvas,field,4,cell,x+cell,baseline,color);
    }

    private float drawUnit(Canvas canvas,String unit,float x,float baseline){
        return x+MonitorGlyphs.draw(canvas,paint,unit,x,baseline);
    }

    private void drawCpuUsage(Canvas canvas,float x,PerformanceCollector.MonitorSample sample,MonitorAppearance appearance){
        if(sample==null||sample.cpuUsage.length==0)return;
        float step=CPU_CHART_WIDTH/sample.cpuUsage.length,gap=Math.min(.55f,step*.25f);
        for(int i=0;i<sample.cpuUsage.length;i++){
            float left=x+i*step,right=left+step-gap;
            double usage=sample.online[i]?sample.cpuUsage[i]:Double.NaN;
            drawUsageBar(canvas,left,right,usage,cpuUsageColor(usage,appearance.cpu));
        }
    }

    private int cpuUsageColor(double usage,MonitorAppearance.Thresholds thresholds){
        return usage>=thresholds.red?RED:usage>=thresholds.orange?ORANGE:usage>=thresholds.yellow?YELLOW:usageBlue();
    }
    private int usageBlue(){return foreground==Color.BLACK?DARK_BLUE:USAGE_BLUE;}

    private static float barHeight(double usage){
        return usage>0&&usage<=100?Math.max(.5f,BAR_HEIGHT*(float)(usage/100)):0;
    }

    private void drawUsageBar(Canvas canvas,float left,float right,double usage,int color){
        float bottom=(HEIGHT+BAR_HEIGHT)/2;
        paint.setColor(foreground==Color.BLACK?Color.argb(48,0,0,0):USAGE_TRACK);
        canvas.drawRect(left,bottom-BAR_HEIGHT,right,bottom,paint);
        float height=barHeight(usage);
        if(height==0)return;
        paint.setColor(color);
        canvas.drawRect(left,bottom-height,right,bottom,paint);
    }

    private void beginIcon(Canvas canvas,float x){
        canvas.save();
        canvas.translate(x,(HEIGHT-9*ICON_SCALE)/2);
        canvas.scale(ICON_SCALE,ICON_SCALE);
        paint.setColor(foreground);
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
        int color=battery!=null&&battery.charging?BATTERY_CHARGING:level<10?RED:foreground;
        paint.setStrokeWidth(.85f);
        canvas.drawRoundRect(.5f,1.8f,8.7f,7.2f,.75f,.75f,paint);
        paint.setStyle(Paint.Style.FILL);
        canvas.drawRoundRect(9.1f,3.25f,10.1f,5.75f,.25f,.25f,paint);
        float left=1.55f,right=7.65f,top=2.85f,bottom=6.15f;
        paint.setColor(Color.argb(48,Color.red(foreground),Color.green(foreground),Color.blue(foreground)));
        canvas.drawRect(left,top,right,bottom,paint);
        if(level>0){
            paint.setColor(color);
            canvas.drawRect(left,top,left+(right-left)*(float)(level/100),bottom,paint);
        }
        canvas.restore();
    }
    private boolean readSystemDark(){
        Configuration configuration=context.getResources().getConfiguration();
        try{
            // The independent daemon is not bound as an application process.
            Object service=ActivityManager.class.getMethod("getService").invoke(null);
            configuration=(Configuration)Class.forName("android.app.IActivityManager").getMethod("getConfiguration").invoke(service);
        }catch(Exception ignored){}
        return (configuration.uiMode&Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES;
    }

    private void applyWindow(){
        long revision;boolean show,readTheme;MonitorAppearance current;
        synchronized(this){
            revision=generation;current=appearance;show=state.enabled&&interactive&&current.metrics!=0;
            readTheme=show&&current.followSystem&&themeChanged;
            if(readTheme)themeChanged=false;
        }
        try{
            clearLegacy();
            if(readTheme)systemDark=readSystemDark();
            if(show)drawOverlay(current);else removeLayer();
            error="";
        }catch(Throwable failure){
            try{removeLayer();}catch(Throwable cleanup){failure.addSuppressed(cleanup);}
            failed(failure);
        }
        finally{synchronized(this){if(revision!=generation)ui.post(this::applyWindow);else queued=false;}}
    }
    synchronized void enabled(boolean enabled){
        if(state.enabled==enabled)return;
        if(enabled)themeChanged=true;
        state.enabled=enabled;resetSamples();queue();
    }
    synchronized boolean canSample(){
        return interactive&&(displayState==Display.STATE_ON||displayState==Display.STATE_UNKNOWN);
    }
    synchronized boolean screen(boolean interactive){
        int nextDisplayState=readDisplayState();
        boolean interactiveChanged=this.interactive!=interactive;
        if(!interactiveChanged&&displayState==nextDisplayState)return false;
        boolean wasSampling=canSample();
        this.interactive=interactive;displayState=nextDisplayState;
        boolean sampling=canSample();
        if(interactiveChanged){
            if(interactive)themeChanged=true;
            resetSamples();
        }
        if(sampling&&!wasSampling)state.refresh();
        if(state.enabled)queue();
        return state.enabled&&sampling!=wasSampling;
    }
    private int readDisplayState(){
        try{
            Display display=displays==null?null:displays.getDisplay(Display.DEFAULT_DISPLAY);
            if(display!=null)return display.getState();
        }catch(RuntimeException ignored){}
        return Display.STATE_UNKNOWN;
    }
    private void resetSamples(){performance=null;battery=null;state.refresh();}
    synchronized void fpsSourceChanged(){
        state.refreshPerformance();
        updatePerformance(null);
    }
    PerformanceCollector.MonitorSample performance(){return performance;}
    synchronized void updatePerformance(PerformanceCollector.MonitorSample sample){performance=sample;queueReading();}
    synchronized void updateFps(PerformanceCollector.MonitorSample sample,double fps){
        if(performance!=sample)return;
        String previous=PowerDisplayState.fps(sample.fps);
        sample.fps=fps;
        if(!previous.equals(PowerDisplayState.fps(fps)))queueReading();
    }
    synchronized void updateBattery(BatteryValues reading){battery=reading;queueReading();}
    private void failed(Throwable failure){
        Throwable cause=FrameworkWatch.cause(failure);
        error=cause instanceof SecurityException||cause instanceof WindowManager.BadTokenException
            ?"系统未允许 Root 性能监视器，请查看错误日志。":"性能监视器暂不可用，稍后重试。";
        FallbackDiagnostics.record("性能监视器悬浮层",cause);
    }
    @Override public void close(){
        try{context.unregisterReceiver(themeReceiver);}catch(Exception ignored){}
        try{displays.unregisterDisplayListener(displayListener);}catch(Exception ignored){}
        enabled(false);
    }
}
