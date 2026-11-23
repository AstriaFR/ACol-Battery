package cn.astriafr.acobattery.daemon;

import java.util.Arrays;
import java.util.List;

public final class GameAppFpsTest {
    static void check(boolean result,String message){if(!result)throw new AssertionError(message);}
    static String row(long x,String fps,int gap,long duration){
        return "["+x+","+fps+",null,null,null,null,"+gap+","+duration+"]";
    }
    public static void main(String[] args)throws Exception{
        long wall=100000;
        List<ScreenUsage.AppSpan> spans=Arrays.asList(
            new ScreenUsage.AppSpan(wall,wall+5000,"app.a"),
            new ScreenUsage.AppSpan(wall+5000,wall+10000,"app.b"),
            new ScreenUsage.AppSpan(wall+10000,wall+12000,Core.OFF));
        GameAppFps fps=new GameAppFps();
        fps.sample(1000,row(1000,"60",0,1000),wall,spans);
        fps.sample(4000,row(4000,"120",0,3000),wall,spans);
        fps.sample(6000,row(6000,"90",0,2000),wall,spans); // switch inside the interval
        fps.sample(7000,row(7000,"null",0,1000),wall,spans);
        fps.sample(8000,row(8000,"120",1,1000),wall,spans);
        fps.sample(9000,row(9000,"0",0,1000),wall,spans);
        fps.sample(11000,row(11000,"60",0,1000),wall,spans);
        check(fps.average("app.a")==105,"Average must be weighted by covered sample time");
        check(fps.average("app.b")==0,"Missing, gap and app transition samples are excluded; zero FPS is valid");
        check(fps.average(Core.OFF)==null,"Standby cannot have app FPS");
        GameAppFps resumed=GameAppFps.restore(fps.sampleX,fps.encode());
        resumed.sample(9000,row(9000,"60",0,1000),wall,spans);
        check(resumed.average("app.b")==0,"An already settled sample cannot be added again");
        System.out.println("Game app FPS: weighted foreground intervals, missing data and persisted cursor passed");
    }
}
