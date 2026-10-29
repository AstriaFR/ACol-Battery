package cn.astriafr.acobattery.daemon;

/** Shared predicates for both history rows and their count, before pagination. */
final class RecordFilter {
    static String where(boolean all,int hidePct,int type,long from,long to){
        if(type<0||type>3)throw new IllegalArgumentException("无效记录类型");
        StringBuilder where=new StringBuilder();
        if(!all)where.append("(active=1 OR fast_seconds>0 OR ABS(pct_end-pct_start)>").append(hidePct).append(")");
        String kind=type==1?"kind='charge'":type==2?"kind='discharge' AND COALESCE(fast_seconds,0)=0":type==3?"kind='discharge' AND fast_seconds>0":"";
        if(!kind.isEmpty()){if(where.length()>0)where.append(" AND ");where.append('(').append(kind).append(')');}
        if(to>from){if(where.length()>0)where.append(" AND ");where.append("start_wall>=").append(from).append(" AND start_wall<").append(to);}
        return where.length()>0?" WHERE "+where:"";
    }
}
