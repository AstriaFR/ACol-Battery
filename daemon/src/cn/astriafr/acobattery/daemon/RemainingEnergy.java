package cn.astriafr.acobattery.daemon;

/** Same cell-mean voltage estimate used by webroot/estimate.js. */
final class RemainingEnergy {
    // 4.45 V to 3.00 V, in 50 mV steps.
    private static final int[] MEAN_MV={
        3875,3855,3820,3795,3768,3742,3718,3692,3665,3640,
        3612,3582,3550,3520,3490,3460,3430,3400,3370,3340,
        3310,3280,3250,3210,3180,3150,3120,3080,3040,3000
    };
    private static final int[] LOW_MEAN_MV={2925,2850,2780,2700};

    static double wh(double mah,double voltageMv,boolean highCapacity){
        if(!Core.finite(mah)||mah<0||!Core.finite(voltageMv)||voltageMv<=0)return Double.NaN;
        long centivolts=Math.round(voltageMv/10);
        int adjustment=highCapacity?75:0;
        double mean;
        if(centivolts>=445)mean=MEAN_MV[0]-adjustment;
        else if(centivolts>=300){
            int distance=(int)(445-centivolts);
            mean=interpolate(MEAN_MV,distance,5)-adjustment;
        }else if(highCapacity&&centivolts>=270)mean=interpolate(LOW_MEAN_MV,(int)(300-centivolts),10);
        else mean=centivolts*10.0;
        return mah*mean/1_000_000;
    }

    private static long interpolate(int[] table,int distance,int step){
        int i=distance/step,offset=distance%step;
        return offset==0?table[i]:Math.round(table[i]+(table[i+1]-table[i])*offset/(double)step);
    }
}
