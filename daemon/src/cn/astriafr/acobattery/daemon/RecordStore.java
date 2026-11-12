package cn.astriafr.acobattery.daemon;
import android.database.Cursor;
import android.database.sqlite.*;
import org.json.*;
import java.io.*;
import java.util.*;
import cn.astriafr.acobattery.common.*;
import static cn.astriafr.acobattery.daemon.BatteryService.*;
/** One shared connection: lifecycle transactions remain owned by the recorder. */
final class RecordStore {
    static final String SAMPLING_DEFAULTS_MIGRATION="sampling_defaults_14184";
    final SQLiteDatabase db;
    long rangeId=-1,rangeEnd=-1,rangeCount;
    JSONObject rangeValues;
    RecordStore(File data)throws Exception{
        DatabaseRebuild.checkPending(data);
        db=SafeDatabase.open(new File(data,"records.db"));
        db.execSQL("PRAGMA foreign_keys=ON");
        db.execSQL("CREATE TABLE IF NOT EXISTS sessions(id INTEGER PRIMARY KEY AUTOINCREMENT,kind TEXT NOT NULL,start_wall INTEGER,start_elapsed INTEGER,boot TEXT,end_elapsed INTEGER,pct_start REAL,pct_end REAL,rm_start REAL,rm_end REAL,active INTEGER NOT NULL,reason TEXT,missed INTEGER DEFAULT 0,anomalies INTEGER DEFAULT 0)");
        db.execSQL("CREATE TABLE IF NOT EXISTS samples(session INTEGER NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,x INTEGER NOT NULL,rm REAL,pct REAL,current REAL,voltage REAL,power REAL,quality INTEGER NOT NULL,interval_ms INTEGER,PRIMARY KEY(session,x)) WITHOUT ROWID");
        db.execSQL("CREATE TABLE IF NOT EXISTS apps(session INTEGER NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,pkg TEXT NOT NULL,ms INTEGER NOT NULL,mah REAL NOT NULL,energy_mwh REAL NOT NULL DEFAULT 0,energy_ms INTEGER NOT NULL DEFAULT 0,mah_ms INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(session,pkg)) WITHOUT ROWID");
        db.execSQL("CREATE TABLE IF NOT EXISTS screen_spans(session INTEGER NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,start_wall INTEGER NOT NULL,end_wall INTEGER NOT NULL,screen_on INTEGER NOT NULL,PRIMARY KEY(session,start_wall,end_wall)) WITHOUT ROWID");
        db.execSQL("CREATE TABLE IF NOT EXISTS screen_app_spans(session INTEGER NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,start_wall INTEGER NOT NULL,end_wall INTEGER NOT NULL,pkg TEXT NOT NULL,PRIMARY KEY(session,start_wall,end_wall)) WITHOUT ROWID");
        db.execSQL("CREATE TABLE IF NOT EXISTS game_rm_intervals(session INTEGER NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,x INTEGER NOT NULL,start_x INTEGER NOT NULL,energy_mwh REAL,PRIMARY KEY(session,x)) WITHOUT ROWID");
        db.execSQL("CREATE TABLE IF NOT EXISTS runtime(key TEXT PRIMARY KEY,value TEXT NOT NULL) WITHOUT ROWID");
        db.execSQL("CREATE TABLE IF NOT EXISTS performance_samples(session INTEGER NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,x INTEGER NOT NULL,data TEXT NOT NULL,PRIMARY KEY(session,x)) WITHOUT ROWID");
        db.execSQL("CREATE TABLE IF NOT EXISTS performance_profiles(session INTEGER PRIMARY KEY REFERENCES sessions(id) ON DELETE CASCADE,meta TEXT NOT NULL)");
        db.execSQL("CREATE TABLE IF NOT EXISTS battery_history(id INTEGER PRIMARY KEY AUTOINCREMENT,source_session INTEGER UNIQUE NOT NULL,completed_wall INTEGER NOT NULL,cycles INTEGER,snapshot TEXT)");
        db.execSQL("CREATE TABLE IF NOT EXISTS battery_history_deleted(source_session INTEGER PRIMARY KEY) WITHOUT ROWID");
    }
    void upgrade(){correctDischargePower();ensureSampleColumns();}
    static void markSamplingDefaultsMigrated(SQLiteDatabase db){
        db.execSQL("INSERT OR IGNORE INTO runtime(key,value) VALUES(?,'1')",new Object[]{SAMPLING_DEFAULTS_MIGRATION});
    }
    void correctDischargePower() {
        db.beginTransaction();
        try {
            if(db.getVersion()==3) {
                db.execSQL("UPDATE samples SET power=power/2 WHERE session IN (SELECT id FROM sessions WHERE kind='discharge') AND power IS NOT NULL");
                db.execSQL("UPDATE apps SET energy_mwh=energy_mwh/2 WHERE session IN (SELECT id FROM sessions WHERE kind='discharge')");
                System.err.println("Corrected existing discharge power and app energy by factor 0.5");
            }
            if(db.getVersion()<4)db.setVersion(4);
            db.setTransactionSuccessful();
        } finally {db.endTransaction();}
    }
    void ensureSampleColumns() {
        db.beginTransaction();
        try {
            Set<String> columns=new HashSet<>();
            try(Cursor c=db.rawQuery("PRAGMA table_info(samples)",null)) {while(c.moveToNext())columns.add(c.getString(1));}
            for(String name:new String[]{"cell1","cell2","temp"})if(!columns.contains(name))db.execSQL("ALTER TABLE samples ADD COLUMN "+name+" REAL");
            if(!columns.contains("online"))db.execSQL("ALTER TABLE samples ADD COLUMN online INTEGER");
            if(!columns.contains("wall"))db.execSQL("ALTER TABLE samples ADD COLUMN wall INTEGER");
            for(String name:new String[]{"memory_used","memory_total"})if(!columns.contains(name))db.execSQL("ALTER TABLE samples ADD COLUMN "+name+" REAL");
            Set<String> performanceColumns=new HashSet<>();
            try(Cursor c=db.rawQuery("PRAGMA table_info(performance_samples)",null)){while(c.moveToNext())performanceColumns.add(c.getString(1));}
            if(!performanceColumns.contains("wall"))db.execSQL("ALTER TABLE performance_samples ADD COLUMN wall INTEGER");
            Set<String> sessionColumns=new HashSet<>();
            try(Cursor c=db.rawQuery("PRAGMA table_info(sessions)",null)){while(c.moveToNext())sessionColumns.add(c.getString(1));}
            if(!sessionColumns.contains("cell_count"))db.execSQL("ALTER TABLE sessions ADD COLUMN cell_count INTEGER NOT NULL DEFAULT 2");
            if(!sessionColumns.contains("tail_cycles"))db.execSQL("ALTER TABLE sessions ADD COLUMN tail_cycles INTEGER");
            if(!sessionColumns.contains("fast_seconds"))db.execSQL("ALTER TABLE sessions ADD COLUMN fast_seconds INTEGER NOT NULL DEFAULT 0");
            if(!sessionColumns.contains("performance_seconds")){
                db.execSQL("ALTER TABLE sessions ADD COLUMN performance_seconds INTEGER NOT NULL DEFAULT 0");
                db.execSQL("UPDATE sessions SET performance_seconds=fast_seconds WHERE fast_seconds>0");
            }
            if(!sessionColumns.contains("stats_basis"))db.execSQL("ALTER TABLE sessions ADD COLUMN stats_basis TEXT NOT NULL DEFAULT 'rm'");
            if(!sessionColumns.contains("app_revision"))db.execSQL("ALTER TABLE sessions ADD COLUMN app_revision INTEGER NOT NULL DEFAULT 0");
            if(!sessionColumns.contains("clock_adjusted"))db.execSQL("ALTER TABLE sessions ADD COLUMN clock_adjusted INTEGER NOT NULL DEFAULT 0");
            Set<String> appColumns=new HashSet<>();
            try(Cursor c=db.rawQuery("PRAGMA table_info(apps)",null)){while(c.moveToNext())appColumns.add(c.getString(1));}
            if(!appColumns.contains("mah_ms")){
                db.execSQL("ALTER TABLE apps ADD COLUMN mah_ms INTEGER NOT NULL DEFAULT 0");
                db.execSQL("UPDATE apps SET mah_ms=ms");
            }
            db.setVersion(16);db.setTransactionSuccessful();
        } finally {db.endTransaction();}
    }
    void backfillHistory(){
        // Existing tail records have dates/cycles only. Never fill them with today's battery values.
        db.execSQL("INSERT OR IGNORE INTO battery_history(source_session,completed_wall,cycles,snapshot) SELECT id,start_wall+(end_elapsed-start_elapsed),tail_cycles,NULL FROM sessions WHERE kind='charge' AND reason='full_tail_confirmed' AND start_wall IS NOT NULL AND end_elapsed>=start_elapsed AND NOT EXISTS(SELECT 1 FROM battery_history_deleted WHERE source_session=sessions.id)");
    }
    JSONObject recordBatteryHistory(JSONObject info,long wall)throws Exception {
        JSONObject snapshot=BatteryHistorySnapshot.capture(info);
        boolean available=false;
        for(String key:new String[]{"cycles","qmax","fcc","soh","cutoffMv"})if(!snapshot.isNull(key)){available=true;break;}
        if(!available)throw new IOException("当前电池信息暂不可用，请稍后重试。");
        Integer cycles=integer(snapshot.opt("cycles").toString());long id;
        db.beginTransaction();try{
            // Positive source IDs belong to automatic charge-session snapshots.
            // Negative IDs identify independent manual snapshots without a schema migration.
            long source;
            try(Cursor c=db.rawQuery("SELECT COALESCE(MIN(source_session),0) FROM battery_history",null)){
                c.moveToFirst();source=Math.min(0,c.getLong(0))-1;
            }
            try(SQLiteStatement insert=db.compileStatement("INSERT INTO battery_history(source_session,completed_wall,cycles,snapshot) VALUES(?,?,?,?)")){
                insert.bindLong(1,source);insert.bindLong(2,wall);
                if(cycles==null)insert.bindNull(3);else insert.bindLong(3,cycles);
                insert.bindString(4,snapshot.toString());id=insert.executeInsert();
            }
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
        return new JSONObject().put("id",id).put("completed_wall",wall).put("cycles",jsonValue(cycles)).put("snapshot",snapshot);
    }
    JSONObject performancePoints(long id,long cursor,int requestedLimit)throws Exception{
        JSONObject out=new JSONObject().put("ok",true);long end;
        try(Cursor c=db.rawQuery("SELECT end_elapsed-start_elapsed,active,performance_seconds FROM sessions WHERE id=?",new String[]{""+id})){
            if(!c.moveToFirst())throw new IOException("记录不存在或已删除");end=c.getLong(0);out.put("active",c.getInt(1)!=0).put("intervalMs",Math.round(c.getDouble(2)*1000));
        }
        try(Cursor c=db.rawQuery("SELECT meta FROM performance_profiles WHERE session=?",new String[]{""+id})){
            out.put("meta",c.moveToFirst()?new JSONObject(c.getString(0)):JSONObject.NULL);
        }
        JSONArray points=new JSONArray(),walls=new JSONArray();long after=Math.max(0,cursor)-1;boolean more=false;
        int limit=Math.max(1,Math.min(8000,requestedLimit));
        try(Cursor c=db.rawQuery("SELECT x,data,wall FROM performance_samples WHERE session=? AND x>? ORDER BY x LIMIT ?",new String[]{""+id,""+after,""+(limit+1)})){
            while(c.moveToNext()){
                if(points.length()==limit){more=true;break;}
                end=Math.max(end,c.getLong(0));points.put(new JSONArray(c.getString(1)));after=c.getLong(0);
                if(!c.isNull(2))walls.put(new JSONArray().put(after).put(c.getLong(2)));
            }
        }
        return out.put("points",points).put("wallTimes",walls).put("end",end).put("cursor",after+1).put("more",more);
    }
    /** Charging is transmitted without decimation. Pages limit IPC size, not data fidelity. */
    void rawPoints(JSONObject out,long id,long from,long to,long after)throws Exception {
        JSONArray result=new JSONArray();boolean more=false;long last=-1;
        try(Cursor c=db.rawQuery("SELECT x,rm,pct,current,voltage,power,quality,interval_ms,cell1,cell2,temp,online,wall,memory_used,memory_total FROM samples WHERE session=? AND x>=? AND x<=? AND x>? ORDER BY x LIMIT 12001",
            new String[]{""+id,""+from,""+to,""+after})) {
            while(c.moveToNext()) {
                if(result.length()==12000){more=true;break;}
                result.put(rawPoint(c));last=c.getLong(0);
            }
        }
        out.put("points",result).put("nextAfter",more?last:JSONObject.NULL);
    }
    private static JSONArray rawPoint(Cursor c)throws JSONException {
        JSONArray row=new JSONArray();
        for(int i=0;i<11;i++)row.put(c.isNull(i)?JSONObject.NULL:(i==0||i==6||i==7?c.getLong(i):c.getDouble(i)));
        // Index 11 is reserved for frontend-derived remaining energy.
        row.put(JSONObject.NULL).put(c.isNull(11)?JSONObject.NULL:c.getInt(11)).put(c.isNull(12)?JSONObject.NULL:c.getLong(12));
        return row.put(c.isNull(13)?JSONObject.NULL:c.getDouble(13)).put(c.isNull(14)?JSONObject.NULL:c.getDouble(14));
    }
    JSONArray nearestSample(long id,long from,long to,long x)throws JSONException {
        long target=Math.max(from,Math.min(to,x));
        JSONArray nearest=null;
        String columns="SELECT x,rm,pct,current,voltage,power,quality,interval_ms,cell1,cell2,temp,online,wall,memory_used,memory_total FROM samples WHERE session=?";
        try(Cursor c=db.rawQuery(columns+" AND x>=? AND x<=? ORDER BY x DESC LIMIT 1",new String[]{""+id,""+from,""+target})){
            if(c.moveToFirst())nearest=rawPoint(c);
        }
        try(Cursor c=db.rawQuery(columns+" AND x>? AND x<=? ORDER BY x LIMIT 1",new String[]{""+id,""+target,""+to})){
            if(c.moveToFirst()&&(nearest==null||c.getLong(0)-target<target-nearest.getLong(0)))nearest=rawPoint(c);
        }
        return nearest;
    }
    /** Full-record bounds keep vertical scales stable when the time window changes. */
    void signalRanges(JSONObject out,long id,long from,long to,boolean charge)throws Exception {
        if(rangeId==id&&rangeValues!=null){
            long count=rangeCount;
            if(from>0||to<rangeEnd)try(Cursor c=db.rawQuery("SELECT COUNT(*) FROM samples WHERE session=? AND x>=? AND x<=?",new String[]{""+id,""+from,""+to})){
                c.moveToFirst();count=c.getLong(0);
            }
            out.put("rawCount",rangeCount).put("windowCount",count).put("ranges",rangeValues);
            return;
        }
        String[] keys={"rm","pct","current","voltage","power","temp","memory_used","memory_total"};
        StringBuilder sql=new StringBuilder("SELECT COUNT(*),SUM(CASE WHEN x>=? AND x<=? THEN 1 ELSE 0 END),MAX(x)");
        for(String key:keys) {
            String value=key;
            if(!charge) {
                String allowed="(interval_ms IS NULL OR interval_ms>=20000)";
                if(key.equals("power"))allowed+=" AND power>=0 AND power<=15";
                value="CASE WHEN "+allowed+" THEN "+key+" END";
            }
            sql.append(",MIN(").append(value).append("),MAX(").append(value).append(")");
        }
        sql.append(" FROM samples WHERE session=?");JSONObject ranges=new JSONObject();
        try(Cursor c=db.rawQuery(sql.toString(),new String[]{""+from,""+to,""+id})) {
            c.moveToFirst();out.put("rawCount",c.getLong(0)).put("windowCount",c.getLong(1));
            for(int i=0;i<keys.length;i++) {
                JSONArray bounds=new JSONArray();
                for(int j=0;j<2;j++){int column=3+i*2+j;bounds.put(c.isNull(column)?JSONObject.NULL:c.getDouble(column));}
                ranges.put(keys[i].equals("memory_used")?"ram":keys[i].equals("memory_total")?"ramTotal":keys[i],bounds);
            }
            rangeId=id;rangeEnd=c.isNull(2)?-1:c.getLong(2);rangeCount=c.getLong(0);rangeValues=ranges;
        }
        out.put("ranges",ranges);
    }
    JSONArray rows(String sql,String[] args)throws JSONException {
        JSONArray result=new JSONArray();
        try(Cursor c=db.rawQuery(sql,args)) {
            String[] keys=c.getColumnNames();
            while(c.moveToNext()) {
                JSONObject row=new JSONObject();
                for(int i=0;i<keys.length;i++) {
                    if(c.isNull(i))row.put(keys[i],JSONObject.NULL);
                    else if(c.getType(i)==Cursor.FIELD_TYPE_INTEGER)row.put(keys[i],c.getLong(i));
                    else if(c.getType(i)==Cursor.FIELD_TYPE_FLOAT)row.put(keys[i],c.getDouble(i));
                    else row.put(keys[i],c.getString(i));
                }
                result.put(row);
            }
        }return result;
    }
    /** Union of per-bucket extrema across all five signals; endpoints and gaps are retained. */
    JSONArray points(long id,long from,long to,int budget,boolean rawPreview)throws Exception {
        long end;
        try(Cursor c=db.rawQuery("SELECT MAX(x) FROM samples WHERE session=?",new String[]{""+id})){c.moveToFirst();end=c.isNull(0)?1:c.getLong(0);}
        to=Math.min(to,end+1);long width=Math.max(1,(to-from)/Math.max(1,budget/16));
        long queryFrom=from,queryTo=to;
        // Keep the nearest eligible sample outside either edge so a zoom window
        // between two discharge samples can still draw their connecting segment.
        String eligible=rawPreview?"1":"(interval_ms IS NULL OR interval_ms>=20000)";
        try(Cursor c=db.rawQuery("SELECT (SELECT x FROM samples WHERE session=? AND x<? AND "+eligible+" ORDER BY x DESC LIMIT 1),(SELECT x FROM samples WHERE session=? AND x>? AND "+eligible+" ORDER BY x LIMIT 1)",
                new String[]{""+id,""+from,""+id,""+to})) {
            c.moveToFirst();if(!c.isNull(0))queryFrom=c.getLong(0);if(!c.isNull(1))queryTo=c.getLong(1);
        }
        JSONArray result=new JSONArray();TreeMap<Long,double[]> bucket=new TreeMap<>();
        double[][] lo=new double[7][],hi=new double[7][];long group=-1;double[] first=null,last=null;
        try(Cursor c=db.rawQuery("SELECT x,rm,pct,current,voltage,power,quality,interval_ms,cell1,cell2,temp,online,wall,memory_used,memory_total FROM samples WHERE session=? AND x>=? AND x<=? ORDER BY x",new String[]{""+id,""+queryFrom,""+queryTo})) {
            while(c.moveToNext()) {
                double[] point=new double[16];for(int i=0;i<11;i++)point[i]=c.isNull(i)?Double.NaN:c.getDouble(i);
                point[11]=Double.NaN;point[12]=c.isNull(11)?Double.NaN:c.getDouble(11);point[13]=c.isNull(12)?Double.NaN:c.getLong(12);
                point[14]=c.isNull(13)?Double.NaN:c.getDouble(13);point[15]=c.isNull(14)?Double.NaN:c.getDouble(14);
                if(!rawPreview&&Core.finite(point[7])&&point[7]<20000)continue;
                if(!rawPreview&&(point[5]>15||point[5]<0))point[5]=Double.NaN;
                long g=((long)point[0]-from)/width;
                if(first!=null&&g!=group) {flushBucket(result,bucket,first,last,lo,hi);bucket.clear();Arrays.fill(lo,null);Arrays.fill(hi,null);first=last=null;}
                group=g;if(first==null)first=point;
                if(last!=null) {
                    boolean split=((int)point[6]&Core.REBOOT_GAP)!=0;
                    for(int i=1;i<=5;i++)if(Core.finite(last[i])!=Core.finite(point[i])){split=true;break;}
                    if(Core.finite(last[10])!=Core.finite(point[10]))split=true;
                    if(Core.finite(last[14])!=Core.finite(point[14]))split=true;
                    if(rawPreview&&(Double.compare(last[12],point[12])!=0
                        ||(((int)last[6]^(int)point[6])&Core.UNCLASSIFIED_CELLS)!=0
                        ||Math.abs((point[13]-last[13])-(point[0]-last[0]))>2000))split=true;
                    if(split){bucket.put((long)last[0],last);bucket.put((long)point[0],point);}
                }
                last=point;
                for(int i=0;i<7;i++){
                    int column=i<5?i+1:i==5?10:14;
                    if(Core.finite(point[column])){
                        if(lo[i]==null||point[column]<lo[i][column])lo[i]=point;
                        if(hi[i]==null||point[column]>hi[i][column])hi[i]=point;
                    }
                }
                if(((int)point[6]&3)!=0)bucket.put((long)point[0],point);
            }
        }
        if(first!=null)flushBucket(result,bucket,first,last,lo,hi);return result;
    }
    static void flushBucket(JSONArray out,TreeMap<Long,double[]> rows,double[] first,double[] last,double[][] lo,double[][] hi)throws JSONException {
        if(first==null)return;
        rows.put((long)first[0],first);rows.put((long)last[0],last);
        for(int i=0;i<lo.length;i++){if(lo[i]!=null)rows.put((long)lo[i][0],lo[i]);if(hi[i]!=null)rows.put((long)hi[i][0],hi[i]);}
        for(double[] p:rows.values()){JSONArray row=new JSONArray();for(double v:p)row.put(Core.finite(v)?v:JSONObject.NULL);out.put(row);}
    }    void updateRanges(long session,long startElapsed,int state,boolean gameContext,Core.Reading previous,Core.Reading r,double recordedPower)throws Exception {
        long x=r.elapsed-startElapsed;if(rangeId!=session||x<=rangeEnd||rangeValues==null)return;
        JSONObject next=new JSONObject(rangeValues.toString());
        String[] keys={"rm","pct","current","voltage","power","temp","ram","ramTotal"};
        double[] values={r.rm,r.pct,state==1||gameContext?r.chargingCurrent():Double.NaN,r.displayVoltage(),recordedPower,r.temp,state==0&&!gameContext?r.memoryUsedGB:Double.NaN,state==0&&!gameContext?r.memoryTotalGB:Double.NaN};
        if(state==0&&!gameContext) {
            if(previous!=null&&r.elapsed-previous.elapsed<20000)Arrays.fill(values,Double.NaN);
            else if(recordedPower>15||recordedPower<0)values[4]=Double.NaN;
        }
        for(int i=0;i<keys.length;i++)if(Core.finite(values[i])) {
            JSONArray bounds=next.getJSONArray(keys[i]);
            bounds.put(0,bounds.isNull(0)?values[i]:Math.min(bounds.getDouble(0),values[i]));
            bounds.put(1,bounds.isNull(1)?values[i]:Math.max(bounds.getDouble(1),values[i]));
        }
        rangeValues=next;rangeCount++;rangeEnd=x;
    }

    JSONObject cacheRecords(JSONArray p)throws Exception{JSONObject o=new JSONObject().put("ok",true);
        if(p.length()<1||p.length()>12)throw new IOException("缓存检查参数无效");
        String[] ids=new String[p.length()];StringJoiner placeholders=new StringJoiner(",");
        for(int i=0;i<ids.length;i++){long id=p.getLong(i);if(id<1)throw new IOException("缓存记录无效");ids[i]=""+id;placeholders.add("?");}
        JSONArray existing=new JSONArray(),identities=new JSONArray();try(Cursor c=db.rawQuery("SELECT id,start_wall FROM sessions WHERE id IN ("+placeholders+")",ids)){while(c.moveToNext()){existing.put(c.getLong(0));identities.put(new JSONArray().put(c.getLong(0)).put(c.isNull(1)?JSONObject.NULL:c.getLong(1)));}}
        return o.put("ids",existing).put("records",identities);
    }
    JSONObject list(JSONArray p,int hidePct,long settingsVersion)throws Exception{JSONObject o=new JSONObject().put("ok",true);
        boolean all=p.optString(0,"0").equals("1"),continuation=p.length()>5;
        o.put("listVersion",settingsVersion);
        if(continuation&&p.getLong(5)!=settingsVersion)return o.put("listChanged",true);
        int year=p.optInt(2,0),month=p.optInt(3,0);long from=0,to=0;
        if(year>=1970&&year<=9999&&month>=1&&month<=12){
            Calendar c=Calendar.getInstance();c.clear();c.set(year,month-1,1,0,0,0);from=c.getTimeInMillis();c.add(Calendar.MONTH,1);to=c.getTimeInMillis();
        }
        String filter=RecordFilter.where(all,hidePct,p.optInt(4,0),from,to);
        String select="SELECT *,ABS(pct_end-pct_start) AS delta_pct FROM sessions";
        if(continuation){
            long before=p.getLong(1);int rank=p.getInt(6);
            if(before<=0||rank<0||rank>2)throw new IOException("记录分页游标无效");
            String pageFilter=filter+(filter.isEmpty()?" WHERE ":" AND ")+RecordOrder.AFTER;
            o.put("records",rows(select+pageFilter+RecordOrder.SQL+" LIMIT 40",new String[]{""+rank,""+rank,""+before}));
        }else{
            int offset=Math.max(0,Math.min(1_000_000,p.optInt(1,0)));
            o.put("records",rows(select+filter+RecordOrder.SQL+" LIMIT 40 OFFSET ?",new String[]{""+offset}));
        }
        try(Cursor c=db.rawQuery("SELECT COUNT(*) FROM sessions"+filter,null)){c.moveToFirst();o.put("total",c.getInt(0));}
        try(Cursor c=db.rawQuery("SELECT COUNT(*) FROM sessions",null)){c.moveToFirst();o.put("totalRecords",c.getInt(0));}return o;
    }
    JSONObject history(String action,JSONArray p)throws Exception{JSONObject o=new JSONObject().put("ok",true);
        if(action.equals("battery_history_trend")){
            long after=Math.max(0,p.optLong(0,0)),through=p.optLong(1,0);
            if(through==0)try(Cursor c=db.rawQuery("SELECT COALESCE(MAX(id),0) FROM battery_history",null)){c.moveToFirst();through=c.getLong(0);}
            JSONArray values=new JSONArray();long last=after;boolean more=false;
            try(Cursor c=db.rawQuery("SELECT id,completed_wall,cycles,snapshot FROM battery_history WHERE id>? AND id<=? ORDER BY id LIMIT 201",new String[]{""+after,""+through})){
                while(c.moveToNext()){
                    if(values.length()==200){more=true;break;}last=c.getLong(0);
                    String text=c.isNull(3)?"":c.getString(3);JSONObject saved=text.isEmpty()||text.equals("null")?new JSONObject():new JSONObject(text);
                    Double fcc=BatteryHistorySnapshot.trustedFcc(saved);
                    values.put(new JSONObject().put("id",last).put("wall",c.isNull(1)?JSONObject.NULL:c.getLong(1)).put("cycles",c.isNull(2)?JSONObject.NULL:c.getLong(2))
                        .put("qmax",saved.opt("qmax")).put("fcc",fcc==null?JSONObject.NULL:fcc));
                }
            }
            return o.put("items",values).put("through",through).put("nextAfter",more?last:JSONObject.NULL);
        }
        if(action.equals("battery_history")){
            int offset=Math.max(0,Math.min(1_000_000,p.optInt(0,0)));
            JSONArray items=rows("SELECT id,completed_wall,cycles FROM battery_history ORDER BY id DESC LIMIT 20 OFFSET ?",new String[]{""+offset});
            try(Cursor count=db.rawQuery("SELECT COUNT(*) FROM battery_history",null)){count.moveToFirst();o.put("total",count.getInt(0));}
            return o.put("items",items);
        }
        if(action.equals("battery_history_detail")){
            long historyId=p.optLong(0,0);if(historyId<1)throw new IOException("历史记录 ID 无效");
            JSONArray items=rows("SELECT id,completed_wall,cycles,snapshot FROM battery_history WHERE id=?",new String[]{""+historyId});
            if(items.length()==0)throw new IOException("电池历史记录不存在");
            JSONObject item=items.getJSONObject(0);String snapshot=item.optString("snapshot","");
            item.put("snapshot",snapshot.isEmpty()||snapshot.equals("null")?JSONObject.NULL:new JSONObject(snapshot));return o.put("item",item);
        }
        if(action.equals("battery_history_delete")){
            long historyId=p.optLong(0,0);if(historyId<1)throw new IOException("历史记录 ID 无效");
            db.beginTransaction();try{
                // Keep only the source ID to prevent legacy backfill from recreating deleted snapshots.
                db.execSQL("INSERT OR IGNORE INTO battery_history_deleted(source_session) SELECT source_session FROM battery_history WHERE id=? AND source_session>0",new Object[]{historyId});
                db.execSQL("DELETE FROM battery_history WHERE id=?",new Object[]{historyId});
                db.setTransactionSuccessful();
            }finally{db.endTransaction();}
            return o;
        }
        throw new IOException("未知电池历史操作");
    }
    void deleteSession(long id){db.execSQL("DELETE FROM sessions WHERE id=?",new Object[]{id});}
}
