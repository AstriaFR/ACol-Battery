package cn.astriafr.acobattery.daemon;

import java.util.function.Consumer;

/** Screen-state rules independent of display subscription and Android services. */
final class ScreenState {
    static final long POLL_MS=5000;
    interface Source { Boolean read()throws Exception; }
    final Core.Sampling sampling;final Source power,display;final Consumer<Throwable> failure;
    boolean known,available;String source="unavailable",error="";long changedAt=-1;
    ScreenState(Core.Sampling sampling,Source power,Source display,Consumer<Throwable> failure){
        this.sampling=sampling;this.power=power;this.display=display;this.failure=failure;
    }
    boolean refresh(long now,boolean initial){
        Source[] readers={power,display};String[] names={"power","display"};
        for(int i=0;i<readers.length;i++){
            try{Boolean on=readers[i].read();if(on!=null)return accept(on,now,initial,names[i]);}
            catch(Throwable e){failure.accept(e);}
        }
        available=false;error="电源和显示状态暂不可用";return false;
    }
    boolean accept(boolean on,long now,boolean initial,String from){
        boolean first=!known,changed=sampling.interactive!=on;
        known=available=true;source=from;error="";if(first||changed)changedAt=now;
        if(initial){sampling.interactive=on;return false;}
        if(sampling.screen(on,now))return true;
        if(first){sampling.screenSample=true;sampling.next=now;return true;}
        return false;
    }
}
