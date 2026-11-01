package cn.astriafr.acobattery;

import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.widget.Toast;
import cn.astriafr.acobattery.common.ErrorLogStore;
import cn.astriafr.acobattery.common.OperationTimeout;
import cn.astriafr.acobattery.common.QuickTileState;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

/** A listening-only root connection. The independent recorder is never owned by this service. */
public abstract class BatteryTileService extends TileService {
    protected abstract boolean gameMode();
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private volatile RootSession connection;
    private volatile boolean destroyed;
    private boolean listening,busy;private int queued=-1;private long generation;
    private final Runnable refresh=()->request(0);
    private String label(){return gameMode()?"游戏性能统计":"功耗悬浮显示";}
    @Override public void onStartListening(){super.onStartListening();listening=true;generation++;request(0);}
    @Override public void onStopListening(){
        super.onStopListening();listening=false;generation++;ui.removeCallbacks(refresh);if(!busy)disconnect();
    }
    @Override public void onTileRemoved(){super.onTileRemoved();listening=false;ui.removeCallbacks(refresh);if(!busy)disconnect();}
    @Override public void onClick(){
        super.onClick();if(destroyed)return;
        if(isLocked())unlockAndRun(()->request(1));else request(1);
    }
    private void disconnect(){RootSession old=connection;connection=null;if(old!=null)old.close();}
    private void checkInitialized()throws IOException {
        if(!getSharedPreferences("startup",MODE_PRIVATE).getBoolean("initialized",false)
            ||getSharedPreferences("startup",MODE_PRIVATE).getInt("androidSdk",0)!=Build.VERSION.SDK_INT)
            throw new IOException("请先打开应用完成初始化");
        if(getSharedPreferences("maintenance",MODE_PRIVATE).getBoolean("clearPending",false)
            ||new File(getNoBackupFilesDir(),"acol-transfer.pending").exists())throw new IOException("数据操作尚未完成，请打开应用");
    }
    private RootSession connect(long until)throws Exception {
        checkInitialized();if(connection!=null)return connection;
        String version;
        try(java.io.InputStream input=getAssets().open("payload.sha256")){
            java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();byte[] buffer=new byte[128];int n;
            while((n=input.read(buffer))!=-1){if(bytes.size()+n>128)throw new IOException("运行文件版本信息无效");bytes.write(buffer,0,n);}
            version=new String(bytes.toByteArray(),StandardCharsets.US_ASCII).trim();
        }
        if(!version.matches("[a-f0-9]{64}"))throw new IOException("运行文件版本信息无效");
        OperationTimeout.remaining(until);RootSession session=new RootSession();connection=session;
        Runnable timeout=session::close;ui.postDelayed(timeout,OperationTimeout.remaining(until));
        try{
            String check="if [ -f /data/adb/aco_battery_app/initialized ] && [ ! -e /data/adb/aco_battery_app/update.pending ] && [ ! -e /data/adb/aco_battery_app/cleanup.request ] && [ \"$(cat /data/adb/aco_battery_app/runtime.version 2>/dev/null)\" = "+RootSession.quote(version)+" ]; then id -u; else echo SETUP; fi";
            String uid=session.command(check,Math.max(1,(OperationTimeout.remaining(until)+999)/1000));
            if(!uid.equals("0"))throw new IOException("请先打开应用完成初始化或更新");
            session.broker(message->{},until);return session;
        }catch(Exception failure){disconnect();throw failure;}
        finally{ui.removeCallbacks(timeout);}
    }
    // 0 refresh; 1 user click.
    private void request(int action){
        if(destroyed||action==0&&!listening)return;
        ui.removeCallbacks(refresh);
        if(busy){if(action>0&&queued<0)queued=action;return;}
        busy=true;long epoch=generation,deadline=OperationTimeout.deadline();
        Runnable watchdog=this::disconnect;ui.postDelayed(watchdog,OperationTimeout.MAX_MS);
        worker.execute(()->{
            QuickTileState state=null;String error=null;
            try{
                RootSession root=connect(deadline);JSONObject status=root.api("settings");state=QuickTileState.read(status,gameMode());
                OperationTimeout.remaining(deadline);
                if(action>0){
                    if(!state.available)throw new IOException(state.subtitle);
                    boolean target=!state.enabled;
                    root.api(QuickTileState.command(gameMode(),target));
                    state=QuickTileState.read(root.api("settings"),gameMode());
                    if(state.enabled!=target)throw new IOException("后台状态尚未切换，请重试");
                }
            }catch(Exception failure){
                String message=failure.getMessage();error=message==null||message.isEmpty()?"连接失败，请打开应用重试":message;
                disconnect();
                if(action>0)try{new ErrorLogStore(new File(getFilesDir(),"error-logs")).append("quick_settings","控制中心开关操作失败",label()+"\n"+failure);}catch(Exception ignored){}
            }
            ui.removeCallbacks(watchdog);
            QuickTileState result=state;String failure=error;
            ui.post(()->{
                if(destroyed)return;busy=false;
                if(listening&&epoch==generation)paint(result,failure);
                if(failure!=null&&action>0)Toast.makeText(this,failure,Toast.LENGTH_LONG).show();
                else if(action>0&&result!=null)Toast.makeText(this,label()+(result.enabled?"已开启":"已关闭"),Toast.LENGTH_SHORT).show();
                int next=queued;queued=-1;
                if(next>0){request(next);return;}
                if(listening&&failure==null)ui.postDelayed(refresh,epoch==generation?2000:0);else if(!listening)disconnect();
            });
        });
    }
    private void paint(QuickTileState state,String error){
        Tile tile=getQsTile();if(tile==null)return;
        tile.setLabel(label());
        if(error!=null||state==null){tile.setState(Tile.STATE_UNAVAILABLE);tile.setSubtitle("打开应用重试");tile.setStateDescription(error==null?"后台未连接":error);}
        else{tile.setState(!state.available?Tile.STATE_UNAVAILABLE:state.enabled?Tile.STATE_ACTIVE:Tile.STATE_INACTIVE);tile.setSubtitle(state.subtitle.isEmpty()?null:state.subtitle);tile.setStateDescription(state.subtitle.isEmpty()?null:state.subtitle);}
        String description=error!=null?error:state==null?"后台未连接":state.subtitle;
        tile.setContentDescription(label()+(description.isEmpty()?"":"，"+description));tile.updateTile();
    }
    @Override public void onDestroy(){
        destroyed=true;listening=false;ui.removeCallbacksAndMessages(null);disconnect();worker.shutdownNow();super.onDestroy();
    }
}
