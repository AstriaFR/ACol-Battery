package cn.astriafr.acobattery.daemon;

import android.content.ComponentName;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructPollfd;
import org.json.*;
import java.io.*;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** One bounded, asynchronous snapshot per fast sample. No polling loop or child processes. */
final class PerformanceCollector implements AutoCloseable {
    interface Sink { void accept(long session,long start,JSONArray row,JSONObject meta) throws Exception; }
    private final Sink sink;
    private final ExecutorService worker=Executors.newSingleThreadExecutor(r->{
        Thread t=new Thread(r,"curve-performance");t.setDaemon(true);t.setPriority(Thread.MIN_PRIORITY);return t;
    });
    private final AtomicBoolean pending=new AtomicBoolean();
    private volatile boolean closed;
    private List<Cluster> clusters;
    private Gpu gpu;
    private List<ThermalSensor> thermalSensors;
    private long previousSession=-1,previousAt;
    private Map<Integer,long[]> previousCpu=Collections.emptyMap();
    private boolean previousScreen;
    private IBinder flinger;
    private Method taskService,focusedTask;
    private long taskRetry,layerChecked,fpsRetry;
    private String layer="",layerPackage="";
    private long newestFrame;
    private JSONObject meta;
    private final Object hardwareLock=new Object();
    private JSONObject frequencySnapshot;
    private long frequencyAt=-1000;

    PerformanceCollector(Sink sink){this.sink=sink;}
    void sample(long session,long start,boolean screen){sample(session,start,screen,60000);}
    void sample(long session,long start,boolean screen,long interval){
        if(closed||!pending.compareAndSet(false,true))return; // Skip overload; never queue old observations.
        try{worker.execute(()->{
            try{
                long elapsed=SystemClock.elapsedRealtime();
                JSONObject frequency=frequencies();
                Map<Integer,long[]> cpu=cpuTimes();Set<Integer> online=cpuList(read("/sys/devices/system/cpu/online"));
                double maxTemperature=maximumCpuGpuTemperature();
                boolean same=previousSession==session;
                JSONArray row=new JSONArray().put(Math.max(0,elapsed-start)).put(JSONObject.NULL)
                    .put(frequency.opt("gpuMHz")).put(number(gpu.utilization())).put(JSONObject.NULL).put(JSONObject.NULL)
                    .put(!same||elapsed-previousAt>Math.max(5000,interval*3)?1:0).put(same?elapsed-previousAt:0);
                JSONArray cpuFrequencies=frequency.getJSONArray("clusters");
                for(int i=0;i<clusters.size();i++){
                    Cluster c=clusters.get(i);
                    row.put(cpuFrequencies.getJSONObject(i).opt("mhz"));
                    row.put(number(same?cpuUsage(c.cpus,online,previousCpu,cpu):Double.NaN));
                }
                row.put(number(maxTemperature));
                // CPU/GPU are captured before Binder calls, so slow rendering services cannot delay them.
                double fps=screen?fps():Double.NaN;
                if(screen&&same&&!previousScreen)fps=Double.NaN; // Discard a stale surface immediately after wake.
                row.put(1,number(fps));
                previousCpu=cpu;previousAt=elapsed;previousSession=session;previousScreen=screen;
                if(!closed)sink.accept(session,start,row,metadata());
            }catch(Throwable e){System.err.println("Performance snapshot skipped: "+e.getClass().getSimpleName());}
            finally{pending.set(false);}
        });}catch(RejectedExecutionException e){pending.set(false);}
    }
    @Override public void close(){closed=true;worker.shutdownNow();}
    /** Visible game pages read frequencies only; they never trigger FPS/utilization collection or DB writes. */
    JSONObject frequencies()throws JSONException{
        synchronized(hardwareLock){
            long now=SystemClock.elapsedRealtime();
            if(frequencySnapshot!=null&&now-frequencyAt<250)return frequencySnapshot;
            if(clusters==null){gpu=discoverGpu();clusters=discoverCpu();}
            Set<Integer> online=cpuList(read("/sys/devices/system/cpu/online"));
            JSONArray values=new JSONArray();
            for(Cluster c:clusters)values.put(new JSONObject().put("label",c.label).put("mhz",number(c.frequency(online))));
            frequencySnapshot=new JSONObject().put("elapsed",now).put("clusters",values).put("gpuMHz",number(gpu.frequency()));
            frequencyAt=now;return frequencySnapshot;
        }
    }
    private JSONObject metadata()throws JSONException{
        if(meta!=null)return meta;
        JSONArray list=new JSONArray();for(Cluster c:clusters)list.put(new JSONObject().put("label",c.label)
            .put("cpus",new JSONArray(c.cpus)).put("maxMHz",number(c.maxMHz)).put("frequencySource",c.source));
        JSONArray thermal=new JSONArray();for(ThermalSensor sensor:thermalSensors)thermal.put(new JSONObject().put("label",sensor.label).put("path",sensor.path));
        meta=new JSONObject().put("clusters",list).put("gpuMaxMHz",number(gpu.maxMHz))
            .put("gpuFrequencySource",gpu.freq).put("gpuUsageSource",gpu.load.isEmpty()?gpu.busy:gpu.load)
            .put("fpsSource","SurfaceFlinger / actualPresentTime").put("cpuUsageSource","/proc/stat")
            .put("temperatureIndex",8+clusters.size()*2).put("temperatureSources",thermal)
            .put("method","频率为采样时读数；CPU 使用率为相邻采样的忙闲计数差，GPU 使用率为驱动当前负载；CPU/GPU 温度取识别到的温度节点最大值；帧率按前台画面最近的实际呈现帧计算。缺失数据留空。");
        return meta;
    }
    static Object number(double n){return Double.isFinite(n)?Math.round(n*100.0)/100.0:JSONObject.NULL;}
    private static String read(String path){
        if(path==null||path.isEmpty())return "";
        try(BufferedReader in=new BufferedReader(new FileReader(path))){
            StringBuilder out=new StringBuilder();String line;while((line=in.readLine())!=null&&out.length()<65536)out.append(line).append('\n');return out.toString().trim();
        }catch(IOException|SecurityException e){return "";}
    }
    private static double first(String text){
        try{for(String part:text.split("[^0-9.]+"))if(!part.isEmpty())return Double.parseDouble(part);}catch(NumberFormatException ignored){}return Double.NaN;
    }
    private static String existing(String... paths){for(String p:paths)if(new File(p).canRead())return p;return "";}
    static Set<Integer> cpuList(String text){
        Set<Integer> out=new TreeSet<>();for(String part:text.trim().split("[ ,\\s]+")){
            try{String[] range=part.split("-");int a=Integer.parseInt(range[0]),b=range.length>1?Integer.parseInt(range[1]):a;
                if(a<0||b>1023||b<a)continue;for(int n=a;n<=b;n++)out.add(n);
            }catch(NumberFormatException ignored){}
        }return out;
    }
    private static final class ThermalSensor {
        final String label,path;
        ThermalSensor(String label,String path){this.label=label;this.path=path;}
    }
    private double maximumCpuGpuTemperature(){
        if(thermalSensors==null)thermalSensors=discoverThermalSensors();
        double maximum=Double.NaN;
        for(ThermalSensor sensor:thermalSensors){
            double value=first(read(sensor.path));
            if(!Double.isFinite(value))continue;
            if(Math.abs(value)>=1000)value/=1000.0;
            if(value< -50||value>200)continue;
            if(!Double.isFinite(maximum)||value>maximum)maximum=value;
        }
        return maximum;
    }
    private static boolean cpuOrGpu(String value){
        String name=value==null?"":value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]","");
        return name.contains("cpu")||name.contains("gpu");
    }
    private static List<ThermalSensor> discoverThermalSensors(){
        LinkedHashMap<String,ThermalSensor> found=new LinkedHashMap<>();
        File[] zones=new File("/sys/class/thermal").listFiles();
        if(zones!=null)for(File zone:zones){
            if(!zone.getName().matches("thermal_zone[0-9]+"))continue;
            String type=read(zone+"/type");if(!cpuOrGpu(type))continue;
            String path=existing(zone+"/temp");if(!path.isEmpty())found.put(path,new ThermalSensor(type,path));
        }
        File[] hwmons=new File("/sys/class/hwmon").listFiles();
        if(hwmons!=null)for(File hwmon:hwmons){
            String device=read(hwmon+"/name");File[] inputs=hwmon.listFiles();if(inputs==null)continue;
            for(File input:inputs){
                String name=input.getName();if(!name.matches("temp[0-9]+_input"))continue;
                String stem=name.substring(0,name.length()-6),label=read(hwmon+"/"+stem+"_label");
                if(!cpuOrGpu(label)&&!cpuOrGpu(device))continue;
                String path=existing(input.toString());if(!path.isEmpty())found.putIfAbsent(path,new ThermalSensor(label.isEmpty()?device+" "+stem:label,path));
            }
        }
        String kgsl=existing("/sys/class/kgsl/kgsl-3d0/temp","/sys/class/kgsl/kgsl-3d0/gpu_temp");
        if(!kgsl.isEmpty())found.putIfAbsent(kgsl,new ThermalSensor("KGSL GPU",kgsl));
        return new ArrayList<>(found.values());
    }
    private static final class Cluster {
        final List<Integer> cpus;final String label,source,fallback;final double maxMHz;
        Cluster(Set<Integer> ids,String dir){
            cpus=new ArrayList<>(ids);label="CPU "+cpus.get(0)+(cpus.size()>1?"–"+cpus.get(cpus.size()-1):"");
            source=existing(dir+"/cpuinfo_cur_freq",dir+"/scaling_cur_freq");fallback=dir+"/scaling_cur_freq";
            maxMHz=first(read(existing(dir+"/cpuinfo_max_freq",dir+"/scaling_max_freq")))/1000;
        }
        double frequency(Set<Integer> online){
            boolean any=false;for(int cpu:cpus)if(online.contains(cpu)){any=true;break;}
            if(!any&&!online.isEmpty())return 0;
            // Linux cpufreq policies share a clock; their online cores have the same policy frequency.
            double value=first(read(source));if(!Double.isFinite(value)||value<=0)value=first(read(fallback));
            return value>0?value/1000:Double.NaN;
        }
    }
    private static List<Cluster> discoverCpu(){
        List<Cluster> out=new ArrayList<>();File[] policies=new File("/sys/devices/system/cpu/cpufreq").listFiles();
        if(policies!=null)for(File policy:policies){if(!policy.getName().matches("policy[0-9]+"))continue;
            Set<Integer> cpus=cpuList(read(policy+"/related_cpus"));if(cpus.isEmpty())cpus=cpuList(read(policy+"/affected_cpus"));
            if(!cpus.isEmpty())out.add(new Cluster(cpus,policy.toString()));
        }
        if(out.isEmpty())for(int cpu:cpuList(read("/sys/devices/system/cpu/present"))){
            String dir="/sys/devices/system/cpu/cpu"+cpu+"/cpufreq";Set<Integer> cpus=cpuList(read(dir+"/related_cpus"));
            if(cpus.isEmpty())cpus.add(cpu);boolean known=false;for(Cluster c:out)if(c.cpus.contains(cpu))known=true;
            if(!known)out.add(new Cluster(cpus,dir));
        }
        out.sort(Comparator.comparingInt(c->c.cpus.get(0)));return out;
    }
    static Map<Integer,long[]> cpuTimes(){
        Map<Integer,long[]> out=new HashMap<>();for(String line:read("/proc/stat").split("\n")){
            String[] p=line.trim().split("\\s+");if(p.length<9||!p[0].matches("cpu[0-9]+"))continue;
            try{long total=0,idle=0;for(int i=1;i<=8;i++){long value=Long.parseLong(p[i]);total+=value;if(i==4||i==5)idle+=value;}
                out.put(Integer.parseInt(p[0].substring(3)),new long[]{total,idle});
            }catch(NumberFormatException ignored){}
        }return out;
    }
    static double cpuUsage(List<Integer> ids,Set<Integer> online,Map<Integer,long[]> before,Map<Integer,long[]> after){
        double sum=0;int count=0;
        for(int id:ids){long[] a=before.get(id),b=after.get(id);
            if(a==null||b==null)continue;long total=b[0]-a[0],idle=b[1]-a[1];
            if(total<0||idle<0||idle>total)continue;
            if(total==0){if(online.contains(id))continue;count++;continue;}
            sum+=(total-idle)*100.0/total;count++;
        }
        // Missing online cores invalidate the cluster instead of biasing the mean downwards.
        for(int id:ids)if(online.contains(id)&&(!before.containsKey(id)||!after.containsKey(id)))return Double.NaN;
        return count==ids.size()?sum/count:Double.NaN;
    }
    private static final class Gpu {
        String freq="",load="",busy="",available="";double divisor=1000000,maxMHz=Double.NaN;
        double frequency(){
            String text=read(freq);double n=first(text);
            // GED commonly returns a table index followed by the frequency in kHz.
            if(freq.endsWith("current_freqency")){
                String[] values=text.split("[^0-9.]+");for(String value:values)if(!value.isEmpty())n=first(value);
            }
            return n>=0?n/divisor:Double.NaN;
        }
        double utilization(){
            String text=read(load);double n=first(text);
            if(!text.isEmpty()&&text.contains("/")){
                String[] parts=text.split("/");double total=first(parts[1]);n=total>0?first(parts[0])*100/total:Double.NaN;
            }
            if(!Double.isFinite(n)&&!busy.isEmpty()){
                String[] parts=read(busy).split("\\s+");if(parts.length>=2){double a=first(parts[0]),b=first(parts[1]);n=b>0?a*100/b:Double.NaN;}
            }
            return n>=0&&n<=100?n:Double.NaN;
        }
    }
    private static Gpu discoverGpu(){
        Gpu g=new Gpu();String kgsl="/sys/class/kgsl/kgsl-3d0/";
        g.freq=existing(kgsl+"gpuclk",kgsl+"devfreq/cur_freq");
        if(!g.freq.isEmpty()){
            g.load=existing(kgsl+"gpu_busy_percentage",kgsl+"devfreq/load");g.busy=existing(kgsl+"gpubusy");
            g.available=existing(kgsl+"gpu_available_frequencies",kgsl+"devfreq/available_frequencies");
        }else{
            File[] devs=new File("/sys/class/devfreq").listFiles();if(devs!=null)for(File dev:devs){
                String name=(dev.getName()+" "+read(dev+"/name")).toLowerCase(Locale.ROOT);
                if(name.contains("bus")||!(name.contains("gpu")||name.contains("mali")||name.contains("kgsl")||name.contains("3d")))continue;
                String freq=existing(dev+"/cur_freq");if(freq.isEmpty())continue;g.freq=freq;
                g.load=existing(dev+"/load",dev+"/utilization",dev+"/device/utilization");
                g.available=existing(dev+"/available_frequencies",dev+"/max_freq");break;
            }
        }
        if(g.freq.isEmpty()){
            g.freq=existing("/sys/kernel/ged/hal/current_freqency","/sys/kernel/gpu/gpu_clock");
            g.divisor=g.freq.endsWith("gpu_clock")?1:1000;
        }
        if(g.load.isEmpty())g.load=existing("/sys/kernel/ged/hal/gpu_utilization","/sys/class/misc/mali0/device/utilization");
        double max=0;for(String value:read(g.available).split("\\s+")){double n=first(value);if(Double.isFinite(n))max=Math.max(max,n);}
        if(max>0)g.maxMHz=max/g.divisor;return g;
    }
    private String foreground(){
        if(SystemClock.elapsedRealtime()<taskRetry)return "";
        try{
            if(taskService==null)taskService=Class.forName("android.app.ActivityTaskManager").getDeclaredMethod("getService");
            Object service=taskService.invoke(null);
            if(focusedTask==null)focusedTask=Class.forName("android.app.IActivityTaskManager").getMethod("getFocusedRootTaskInfo");
            Object task=focusedTask.invoke(service);if(task==null)return "";
            ComponentName component=(ComponentName)task.getClass().getField("topActivity").get(task);
            return component==null?"":component.getPackageName();
        }catch(Throwable e){taskRetry=SystemClock.elapsedRealtime()+30000;return "";}
    }
    /** dumpAsync uses the root Binder directly; bounded poll prevents stalled services blocking sampling. */
    private String dump(String... args)throws Exception{
        if(flinger==null||!flinger.isBinderAlive()){
            flinger=(IBinder)Class.forName("android.os.ServiceManager").getMethod("checkService",String.class).invoke(null,"SurfaceFlinger");
            if(flinger==null)throw new IOException("SurfaceFlinger unavailable");
        }
        ParcelFileDescriptor[] pipe=ParcelFileDescriptor.createPipe();
        try{
            flinger.dumpAsync(pipe[1].getFileDescriptor(),args);pipe[1].close();
            StructPollfd fd=new StructPollfd();fd.fd=pipe[0].getFileDescriptor();fd.events=(short)OsConstants.POLLIN;
            ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buf=new byte[8192];long until=SystemClock.uptimeMillis()+180;
            while(!closed){
                int remain=(int)(until-SystemClock.uptimeMillis());if(remain<=0)throw new IOException("SurfaceFlinger timeout");
                if(Os.poll(new StructPollfd[]{fd},remain)==0)throw new IOException("SurfaceFlinger timeout");
                if((fd.revents&(OsConstants.POLLIN|OsConstants.POLLHUP))==0)throw new IOException("SurfaceFlinger pipe unavailable");
                int n=Os.read(fd.fd,buf,0,buf.length);if(n<=0)break;
                if(out.size()+n>262144)throw new IOException("SurfaceFlinger response too large");out.write(buf,0,n);
            }return out.toString("UTF-8");
        }finally{try{pipe[1].close();}finally{pipe[0].close();}}
    }
    static String layerName(String line){
        String s=line.trim();if(s.startsWith("RequestedLayerState{")){
            s=s.substring("RequestedLayerState{".length());int end=s.indexOf(" parentId=");
            if(end<0)end=s.indexOf(" z=");if(end>=0)s=s.substring(0,end);else if(s.endsWith("}"))s=s.substring(0,s.length()-1);
        }return s;
    }
    static long[] frameTimes(String text,long now){
        TreeSet<Long> values=new TreeSet<>();for(String line:text.split("\n")){
            String[] p=line.trim().split("\\s+");if(p.length!=3)continue;
            try{long n=Long.parseLong(p[1]);if(n>0&&n<Long.MAX_VALUE&&n<=now)values.add(n);}catch(NumberFormatException ignored){}
        }
        long[] result=new long[values.size()];int i=0;for(long value:values)result[i++]=value;return result;
    }
    static double frameRate(long[] times,long now){
        if(times.length==0)return Double.NaN;
        if(now-times[times.length-1]>1000000000L)return 0; // Valid, stale frame history means no new presented frames.
        int first=0;while(first<times.length&&times[first]<now-1000000000L)first++;
        int count=times.length-first;
        if(times.length>=120&&first==0&&count>1){ // The ring may cover less than 1 s at high frame rates.
            long span=times[times.length-1]-times[0];return span>0?(count-1)*1e9/span:Double.NaN;
        }
        return count;
    }
    private double fps(){
        long elapsed=SystemClock.elapsedRealtime();if(elapsed<fpsRetry)return Double.NaN;
        try{
            String pkg=foreground();if(pkg.isEmpty())return Double.NaN;
            boolean changed=!pkg.equals(layerPackage);
            if(changed){layer="";layerPackage=pkg;newestFrame=0;}
            long now=System.nanoTime();
            if(!layer.isEmpty()){
                long[] frames=frameTimes(dump("--latency",layer),now);
                if(frames.length>0){
                    long latest=frames[frames.length-1];
                    if(latest>newestFrame||elapsed-layerChecked<10000){newestFrame=latest;return frameRate(frames,now);}
                }
            }
            // Discover only on foreground changes, missing layers, or stale cached layers. No per-tick --list.
            if(!changed&&elapsed-layerChecked<5000)return Double.NaN;
            layerChecked=elapsed;List<String> names=new ArrayList<>();
            for(String line:dump("--list").split("\n")){
                String name=layerName(line),lower=name.toLowerCase(Locale.ROOT);
                if(!name.contains(pkg)||lower.contains("inputsink")||lower.contains("activityrecord")||lower.contains("splash")||lower.contains("snapshot"))continue;
                names.add(name);
            }
            names.sort(Comparator.comparingInt(n->n.contains("SurfaceView")?0:n.startsWith("VRI-")?1:2));
            String best="";long[] chosen=new long[0];long latest=0;
            for(int i=0;i<Math.min(3,names.size());i++){
                long[] frames=frameTimes(dump("--latency",names.get(i)),System.nanoTime());
                if(frames.length>0&&frames[frames.length-1]>latest){latest=frames[frames.length-1];best=names.get(i);chosen=frames;}
                if(i==0&&names.get(i).contains("SurfaceView")&&latest>now-1000000000L)break;
            }
            layer=best;newestFrame=latest;return frameRate(chosen,System.nanoTime());
        }catch(Throwable e){layer="";flinger=null;fpsRetry=elapsed+10000;return Double.NaN;}
    }
}
