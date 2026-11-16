package cn.astriafr.acobattery.daemon;

import static cn.astriafr.acobattery.daemon.MonitorAppearance.FPS;

final class GameMetrics {
    static final int CAPACITY=1, LEVEL=2, CURRENT=4, VOLTAGE=8, WATTS=16, BATTERY_TEMP=32, ENERGY=64;
    static final int BATTERY_ALL=127, ACCOUNTING=CAPACITY|LEVEL|VOLTAGE;
    static final int MINIMUM_FPS=32, CHIP_TEMP=64, MEMORY=128, PERFORMANCE_ALL=255;
    static final GameMetrics DEFAULT=new GameMetrics(BATTERY_ALL,PERFORMANCE_ALL);
    final int battery, performance;

    GameMetrics(int battery,int performance){
        if((battery&~BATTERY_ALL)!=0||(performance&~PERFORMANCE_ALL)!=0)
            throw new IllegalArgumentException("游戏记录项目无效");
        this.battery=(battery&(CAPACITY|VOLTAGE))==(CAPACITY|VOLTAGE)?battery:battery&~ENERGY;
        this.performance=performance;
    }

    static GameMetrics load(String value){
        try{
            String[] parts=value.split(" ");
            return new GameMetrics(Integer.parseInt(parts[0]),Integer.parseInt(parts[1]));
        }catch(RuntimeException invalid){return DEFAULT;}
    }

    boolean battery(int bit){return (battery&bit)!=0;}
    boolean performance(int bit){return (performance&bit)!=0;}
    boolean recordsFps(){return performance(FPS|MINIMUM_FPS);}
    static double selected(int metrics,int bit,double value){return (metrics&bit)!=0?value:Double.NaN;}
    String encode(){return battery+" "+performance;}
}
