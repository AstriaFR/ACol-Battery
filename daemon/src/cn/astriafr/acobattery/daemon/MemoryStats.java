package cn.astriafr.acobattery.daemon;

/** One /proc/meminfo snapshot. Kernel kB fields are KiB; display GB is decimal. */
final class MemoryStats {
    final long totalBytes,usedBytes;
    private MemoryStats(long total,long available){totalBytes=total;usedBytes=total-available;}
    double totalGB(){return totalBytes/1e9;}
    double usedGB(){return usedBytes/1e9;}
    static MemoryStats parse(String text){
        long total=-1,available=-1;
        try{
            for(String line:text.split("\n")){
                String[] parts=line.trim().split("\\s+");
                if(parts.length<1||!(parts[0].equals("MemTotal:")||parts[0].equals("MemAvailable:")))continue;
                if(parts.length!=3||!parts[2].equals("kB"))return null;
                long bytes=Math.multiplyExact(Long.parseLong(parts[1]),1024L);
                if(parts[0].equals("MemTotal:"))total=bytes;else available=bytes;
            }
        }catch(NumberFormatException|ArithmeticException e){return null;}
        return total>0&&available>=0&&available<=total?new MemoryStats(total,available):null;
    }
}
