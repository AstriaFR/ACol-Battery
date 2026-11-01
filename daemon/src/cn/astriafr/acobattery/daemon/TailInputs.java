package cn.astriafr.acobattery.daemon;

import java.util.Locale;

/** Full is a candidate signal, never a replacement for the sustained tail gate. */
final class TailInputs {
    interface Reader {String read(String path)throws Exception;}
    interface StatusSource {int read()throws Exception;}
    static final class Result {
        final int status;final String source,diagnostics;
        Result(int status,String source,String diagnostics){this.status=status;this.source=source;this.diagnostics=diagnostics;}
        boolean known(){return validStatus(status);}
    }
    static boolean validStatus(int status){return status>=2&&status<=5;}
    static int parseStatus(String raw){
        if(raw==null)return 0;
        switch(raw.trim().toLowerCase(Locale.ROOT)){
            case "charging":return 2;
            case "discharging":return 3;
            case "not charging":case "not_charging":return 4;
            case "full":return 5;
            default:return 0;
        }
    }
    static String shortText(String raw){return raw==null?"<空>":raw.substring(0,Math.min(256,raw.length())).trim();}
    static Result resolve(String directory,Reader reader,StatusSource android){
        StringBuilder details=new StringBuilder();
        for(String name:new String[]{"battery","bms"}){
            String path=directory+name+"/status";
            try{
                String raw=reader.read(path);int status=parseStatus(raw);
                details.append(path).append(" = ").append(shortText(raw)).append('\n');
                // A valid non-Full result is authoritative. Do not seek a stale Full elsewhere.
                if(validStatus(status))return new Result(status,"sysfs_status",details.toString());
            }catch(Exception error){details.append(path).append(": ").append(shortText(error.toString())).append('\n');}
        }
        try{
            int status=android.read();details.append("Android status = ").append(status).append('\n');
            if(validStatus(status))return new Result(status,"android_status",details.toString());
        }catch(Exception error){details.append("Android status: ").append(shortText(error.toString())).append('\n');}
        return new Result(0,"unavailable",details.toString());
    }
    static double targetVoltage(String directory,Reader reader,int cells,StringBuilder details){
        for(String name:new String[]{"battery","bms"}){
            String path=directory+name+"/constant_charge_voltage";
            try{
                String raw=reader.read(path);double mv=Core.parse(raw)/1000;
                if(cells==2&&mv>6000)mv/=2;
                details.append(path).append(" = ").append(shortText(raw)).append('\n');
                if(cells>0&&Core.cellVoltage(mv))return mv;
            }catch(Exception error){details.append(path).append(": ").append(shortText(error.toString())).append('\n');}
        }
        return Double.NaN;
    }
}
