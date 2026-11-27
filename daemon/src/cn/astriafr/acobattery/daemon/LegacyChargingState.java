package cn.astriafr.acobattery.daemon;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import cn.astriafr.acobattery.common.FallbackDiagnostics;

/** Removes same-boot overrides left by older builds during a runtime replacement. */
final class LegacyChargingState {
    private static final String CONFIG="charge_thermal_v1";
    private static final String PAUSE="charge_thermal_pause_v1";
    private static final String VOLTAGE_PAUSE="charge_voltage_pause_v1";
    private static final String BCC="charge_thermal_current_v1";
    private static final String COOL_DOWN="charge_thermal_cooldown_force_v1";
    private static final String PROTOCOL_CURRENT="charge_thermal_protocol_force_v1";
    private static final String[] KEYS={CONFIG,PAUSE,VOLTAGE_PAUSE,BCC,COOL_DOWN,PROTOCOL_CURRENT};
    private static final String WHERE="key IN (?,?,?,?,?,?)";

    private LegacyChargingState(){}

    static void discardImported(SQLiteDatabase db){db.delete("runtime",WHERE,KEYS);}

    static void restore(SQLiteDatabase db,String boot){
        Map<String,String> state=new HashMap<>();
        try(Cursor c=db.rawQuery("SELECT key,value FROM runtime WHERE "+WHERE,KEYS)){
            while(c.moveToNext())state.put(c.getString(0),c.getString(1));
        }
        if(state.isEmpty())return;
        restore(db,state,PROTOCOL_CURRENT,value->{
            String[] parts=value.split(":",-1);
            if(parts.length!=4||!Core.sameBoot(parts[0],boot))return;
            if(!parts[1].matches("(?:UFCS|PPS|VOOC)_CURR"))return;
            int applied=milliamps(parts[2]),pending=milliamps(parts[3]);
            if(applied>0||pending>0)
                OplusVotes.releaseLegacyForce(OplusVotes.Node.valueOf(parts[1]),applied>0?applied:pending,pending>0?pending:applied);
        });
        restore(db,state,COOL_DOWN,value->{
            if(Core.sameBoot(value,boot))OplusVotes.releaseLegacyForce(OplusVotes.Node.COOL_DOWN,0,0);
        });
        restore(db,state,BCC,value->{
            String[] parts=value.split(":",-1);
            if(parts.length==4&&Core.sameBoot(parts[0],boot))
                OplusVotes.releaseLegacyBcc(milliamps(parts[1]),milliamps(parts[2]),milliamps(parts[3]));
        });
        boolean voltageProtection=Core.sameBoot(state.get(VOLTAGE_PAUSE),boot);
        restore(db,state,PAUSE,value->{
            // Keep an existing manual or overvoltage pause in effect on handover.
            if(!boot.isEmpty()&&!voltageProtection&&value.equals(boot+":0"))OplusVotes.releaseLegacyPause();
        });
        db.delete("runtime","key IN (?,?)",new String[]{CONFIG,VOLTAGE_PAUSE});
    }

    private interface Restore {void run(String value)throws IOException;}

    private static void restore(SQLiteDatabase db,Map<String,String> state,String key,Restore cleanup){
        String value=state.get(key);
        if(value==null)return;
        try{
            cleanup.run(value);
            db.delete("runtime","key=?",new String[]{key});
        }catch(IOException failure){FallbackDiagnostics.record("旧版充电参数恢复",failure);}
    }

    private static int milliamps(String value){
        Integer number=BatteryService.integer(value);
        return number!=null&&number>0&&number<=30000?number:0;
    }
}
