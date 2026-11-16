package cn.astriafr.acobattery.daemon;

import org.json.JSONArray;
import org.json.JSONException;

final class MonitorAppearance {
    static final int FPS=1, CPU_CHART=2, CPU_FREQUENCY=4, GPU_CHART=8, GPU_FREQUENCY=16, POWER=32, TEMPERATURE=64;
    static final int CPU=CPU_CHART|CPU_FREQUENCY, GPU=GPU_CHART|GPU_FREQUENCY;
    static final int PERFORMANCE=FPS|CPU|GPU, BATTERY=POWER|TEMPERATURE;
    private static final int DEFAULT_OPACITY=55, DEFAULT_SIZE_PERCENT=100;
    static final MonitorAppearance DEFAULT=new MonitorAppearance(127,DEFAULT_OPACITY,DEFAULT_OPACITY,DEFAULT_SIZE_PERCENT,true,false,false,
        new Thresholds(60,75,90),new Thresholds(8,12,15),new Thresholds(42,45,48));
    final int metrics, whiteOpacity, blackOpacity, sizePercent;
    final boolean blackText, landscapeBottom, followSystem;
    final Thresholds cpu, power, temperature;

    static final class Thresholds {
        final int yellow, orange, red;

        Thresholds(int yellow,int orange,int red){
            if(yellow>=orange||orange>=red)throw invalid();
            this.yellow=yellow;this.orange=orange;this.red=red;
        }
    }

    private MonitorAppearance(int metrics,int whiteOpacity,int blackOpacity,int sizePercent,boolean blackText,boolean landscapeBottom,boolean followSystem,
            Thresholds cpu,Thresholds power,Thresholds temperature){
        this.metrics=metrics;this.whiteOpacity=whiteOpacity;this.blackOpacity=blackOpacity;
        this.sizePercent=sizePercent;
        this.blackText=blackText;this.landscapeBottom=landscapeBottom;
        this.followSystem=followSystem;
        this.cpu=cpu;this.power=power;this.temperature=temperature;
    }

    boolean shows(int group){return (metrics&group)!=0;}

    JSONArray values(){
        return new JSONArray().put(metrics).put(cpu.yellow).put(cpu.orange).put(cpu.red)
            .put(whiteOpacity).put(blackText?1:0).put(landscapeBottom?1:0)
            .put(power.yellow).put(power.orange).put(power.red)
            .put(temperature.yellow).put(temperature.orange).put(temperature.red).put(followSystem?1:0)
            .put(blackOpacity).put(sizePercent);
    }

    static MonitorAppearance load(String saved){
        if(!saved.isEmpty()){
            try{return parse(new JSONArray(saved));}
            catch(IllegalArgumentException|JSONException ignored){}
        }
        return DEFAULT;
    }

    static MonitorAppearance parse(JSONArray values){
        int length=values.length();
        if(length!=6&&length!=7&&(length<13||length>17))throw invalid();
        // Ignore the retired screen-off flag at the end of earlier settings.
        int[] numbers=new int[Math.min(length,16)];
        for(int i=0;i<numbers.length;i++){
            String value=values.optString(i);
            if(!value.matches("[0-9]{1,3}"))throw invalid();
            numbers[i]=Integer.parseInt(value);
        }
        int metrics=numbers[0],opacity=numbers[4],landscape=length>=7?numbers[6]:0;
        int followSystem=length>=14?numbers[13]:0;
        int blackOpacity=length>=15?numbers[14]:DEFAULT_OPACITY;
        int sizePercent=length>=16?numbers[15]:DEFAULT_SIZE_PERCENT;
        if(metrics>127||numbers[3]>100||opacity>100||blackOpacity>100||numbers[5]>1||landscape>1||followSystem>1)throw invalid();
        if(sizePercent<75||sizePercent>125||sizePercent%5!=0)throw invalid();
        Thresholds cpu=new Thresholds(numbers[1],numbers[2],numbers[3]);
        Thresholds power=length>=13?new Thresholds(numbers[7],numbers[8],numbers[9]):DEFAULT.power;
        Thresholds temperature=length>=13?new Thresholds(numbers[10],numbers[11],numbers[12]):DEFAULT.temperature;
        int whiteOpacity=length>=15?opacity:DEFAULT_OPACITY;
        return new MonitorAppearance(metrics,whiteOpacity,blackOpacity,sizePercent,numbers[5]==1,landscape==1,followSystem==1,
            cpu,power,temperature);
    }

    private static IllegalArgumentException invalid(){return new IllegalArgumentException("性能监视器设置无效。");}
}
