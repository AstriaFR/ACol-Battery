package cn.astriafr.acobattery.daemon;

import org.json.JSONArray;
import org.json.JSONException;

final class MonitorAppearance {
    static final int FPS=1, CPU_CHART=2, CPU_FREQUENCY=4, GPU_CHART=8, GPU_FREQUENCY=16, POWER=32, TEMPERATURE=64, VOLTAGE=128;
    static final int CPU=CPU_CHART|CPU_FREQUENCY, GPU=GPU_CHART|GPU_FREQUENCY;
    static final int PERFORMANCE=FPS|CPU|GPU, BATTERY=POWER|TEMPERATURE|VOLTAGE;
    static final String DEFAULT_ORDER="12345678", ORDER_KEY="monitor_metric_order";
    private static final int DEFAULT_OPACITY=55, DEFAULT_SIZE_PERCENT=100;
    static final MonitorAppearance DEFAULT=new MonitorAppearance(PERFORMANCE|POWER|TEMPERATURE,DEFAULT_OPACITY,DEFAULT_OPACITY,DEFAULT_SIZE_PERCENT,true,false,false,false,
        new Thresholds(60,75,90),new Thresholds(8,12,15),new Thresholds(42,45,48),DEFAULT_ORDER);
    final int metrics, whiteOpacity, blackOpacity, sizePercent;
    final boolean blackText, landscapeBottom, followSystem, taskFpsCallback;
    final Thresholds cpu, power, temperature;
    final String order;

    static final class Thresholds {
        final int yellow, orange, red;

        Thresholds(int yellow,int orange,int red){
            if(yellow>=orange||orange>=red)throw invalid();
            this.yellow=yellow;this.orange=orange;this.red=red;
        }
    }

    private MonitorAppearance(int metrics,int whiteOpacity,int blackOpacity,int sizePercent,boolean blackText,boolean landscapeBottom,boolean followSystem,
            boolean taskFpsCallback,Thresholds cpu,Thresholds power,Thresholds temperature,String order){
        this.metrics=metrics;this.whiteOpacity=whiteOpacity;this.blackOpacity=blackOpacity;
        this.sizePercent=sizePercent;
        this.blackText=blackText;this.landscapeBottom=landscapeBottom;
        this.followSystem=followSystem;
        this.taskFpsCallback=taskFpsCallback;
        this.cpu=cpu;this.power=power;this.temperature=temperature;
        this.order=order;
    }

    boolean shows(int group){return (metrics&group)!=0;}
    MonitorAppearance withTaskFpsCallback(boolean callback){
        return callback==taskFpsCallback?this:new MonitorAppearance(metrics,whiteOpacity,blackOpacity,sizePercent,blackText,
            landscapeBottom,followSystem,callback,cpu,power,temperature,order);
    }

    int metricAt(int index){return 1<<(order.charAt(index)-'1');}
    static int groupOf(int metric){
        if((metric&CPU)!=0)return CPU;
        if((metric&GPU)!=0)return GPU;
        return metric==FPS?FPS:BATTERY;
    }

    MonitorAppearance withOrder(String next){
        if(order.equals(next))return this;
        if(!next.matches("[1-8]{8}")||next.chars().distinct().count()!=8)throw invalidOrder();
        int previous=0,seen=0;
        for(int i=0;i<next.length();i++){
            int group=groupOf(1<<(next.charAt(i)-'1'));
            if(group!=previous&&(seen&group)!=0)throw invalidOrder();
            previous=group;
            seen|=group;
        }
        return new MonitorAppearance(metrics,whiteOpacity,blackOpacity,sizePercent,blackText,
            landscapeBottom,followSystem,taskFpsCallback,cpu,power,temperature,next);
    }

    JSONArray values(){
        return appearanceValues().put(0).put(taskFpsCallback?1:0);
    }

    JSONArray appearanceValues(){
        return new JSONArray().put(metrics).put(cpu.yellow).put(cpu.orange).put(cpu.red)
            .put(whiteOpacity).put(blackText?1:0).put(landscapeBottom?1:0)
            .put(power.yellow).put(power.orange).put(power.red)
            .put(temperature.yellow).put(temperature.orange).put(temperature.red).put(followSystem?1:0)
            .put(blackOpacity).put(sizePercent);
    }

    static MonitorAppearance load(String saved,boolean taskFpsCallback){
        if(!saved.isEmpty()){
            try{return parse(new JSONArray(saved),taskFpsCallback);}
            catch(IllegalArgumentException|JSONException ignored){}
        }
        return DEFAULT.withTaskFpsCallback(taskFpsCallback);
    }

    static MonitorAppearance load(String saved,boolean taskFpsCallback,String order){
        MonitorAppearance result=load(saved,taskFpsCallback);
        if(order.length()==7){
            // Preserve existing group positions and place voltage after the saved battery items.
            int batteryEnd=Math.max(order.indexOf('6'),order.indexOf('7'))+1;
            order=order.substring(0,batteryEnd)+'8'+order.substring(batteryEnd);
        }
        try{return result.withOrder(order);}
        catch(IllegalArgumentException ignored){return result;}
    }

    static MonitorAppearance parse(JSONArray values){
        return parse(values,DEFAULT.taskFpsCallback);
    }

    static MonitorAppearance parse(JSONArray values,boolean savedTaskFpsCallback){
        int length=values.length();
        if(length!=6&&length!=7&&(length<13||length>18))throw invalid();
        // Slot 16 is the retired screen-off flag. Keep it reserved for old settings.
        String source=length==18?values.optString(17):savedTaskFpsCallback?"1":"0";
        if(!source.equals("0")&&!source.equals("1"))throw invalid();
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
        if(metrics>(PERFORMANCE|BATTERY)||numbers[3]>100||opacity>100||blackOpacity>100||numbers[5]>1||landscape>1||followSystem>1)throw invalid();
        if(sizePercent<75||sizePercent>125||sizePercent%5!=0)throw invalid();
        Thresholds cpu=new Thresholds(numbers[1],numbers[2],numbers[3]);
        Thresholds power=length>=13?new Thresholds(numbers[7],numbers[8],numbers[9]):DEFAULT.power;
        Thresholds temperature=length>=13?new Thresholds(numbers[10],numbers[11],numbers[12]):DEFAULT.temperature;
        int whiteOpacity=length>=15?opacity:DEFAULT_OPACITY;
        return new MonitorAppearance(metrics,whiteOpacity,blackOpacity,sizePercent,numbers[5]==1,landscape==1,followSystem==1,
            source.equals("1"),cpu,power,temperature,DEFAULT_ORDER);
    }

    private static IllegalArgumentException invalid(){return new IllegalArgumentException("性能监视器设置无效。");}
    private static IllegalArgumentException invalidOrder(){return new IllegalArgumentException("性能监视器显示顺序无效。");}
}
