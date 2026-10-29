package cn.astriafr.acobattery;

import android.app.Application;
import android.os.FileObserver;
import java.io.File;

public final class BatteryApplication extends Application {
    private static final String SPARE="BrowserMetrics-spare.pma";
    private final FileObserver[] metricsObservers=new FileObserver[2];

    @Override public void onCreate(){
        super.onCreate();
        if(new File(getNoBackupFilesDir(),"acol-transfer.pending").isFile())return;
        resumeMetricsCleanup();
    }
    void resumeMetricsCleanup(){
        // MetricsOptOut stops reporting, but Chromium still allocates this spare.
        // It is unused until a future launch; never truncate an active mapping.
        watchSpare(new File(getNoBackupFilesDir(),".webview"),0);
        watchSpare(new File(getDataDir(),"app_webview"),1);
    }
    private void watchSpare(File directory,int index){
        if(metricsObservers[index]!=null)return;
        if(!directory.isDirectory()&&!directory.mkdirs())return;
        File spare=new File(directory,SPARE);
        metricsObservers[index]=new FileObserver(directory,FileObserver.MOVED_TO|FileObserver.CLOSE_WRITE){
            @Override public void onEvent(int event,String path){
                // Chromium closes its temporary mapping before renaming to SPARE.
                if(SPARE.equals(path)&&spare.isFile())spare.delete();
            }
        };
        metricsObservers[index].startWatching();
        if(spare.isFile())spare.delete();
    }
}
