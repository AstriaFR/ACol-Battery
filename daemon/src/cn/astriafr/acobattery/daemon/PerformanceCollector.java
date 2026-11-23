package cn.astriafr.acobattery.daemon;

import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructPollfd;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.regex.Pattern;
import static cn.astriafr.acobattery.daemon.MonitorAppearance.*;

/** One bounded, asynchronous snapshot per fast sample. No polling loop or child processes. */
final class PerformanceCollector implements AutoCloseable {
    private static final int MAX_FPS_FRAMES=60;
    private static final long FPS_WAIT_MS=300;
    private static final long LAYER_ACTIVE_REFRESH_MS=10000,LAYER_IDLE_REFRESH_MS=5000;
    private static final long[] NO_FRAMES=new long[0];
    private static final Pattern NON_FRAME_LAYER=Pattern.compile(
        "^(?:[0-9a-f]+ )?(?:VRI-)?(?:Splash Screen |SnapshotStartingWindow[ #]|ActivityRecordInputSink |Background for |Bounds for )");
    interface Sink { void accept(long session,long start,long wall,JSONArray row,JSONObject meta) throws Exception; }
    private final Sink sink;
    private final BiConsumer<MonitorSample,Double> monitorFps;
    private final ExecutorService worker=Executors.newSingleThreadExecutor(r->{
        Thread t=new Thread(r,"curve-performance");t.setDaemon(true);t.setPriority(Thread.MIN_PRIORITY);return t;
    });
    private final ExecutorService fpsWorker=Executors.newSingleThreadExecutor(r->{
        Thread t=new Thread(r,"curve-fps");t.setDaemon(true);t.setPriority(Thread.MIN_PRIORITY);return t;
    });
    private final AtomicBoolean recordingPending=new AtomicBoolean();
    private final AtomicBoolean fpsPending=new AtomicBoolean();
    private final TaskFpsSampler taskFps=new TaskFpsSampler();
    private volatile FpsSource fpsSource=new FpsSource(false);
    private volatile long screenChangedAtNanos;
    private volatile boolean taskFpsActive;
    private volatile boolean closed;
    private List<Cluster> clusters;
    private Gpu gpu;
    private List<ThermalSensor> thermalSensors;
    private long previousSession=-1,previousAt,recordFramesSince;
    private Map<Integer,long[]> previousCpu=Collections.emptyMap();
    private boolean previousScreen;
    private boolean previousMinimumFps;
    private IBinder flinger;
    private FocusedTask.Lookup focusedTaskLookup;
    private FpsSource layerSource;
    private long layerChecked,fpsRetry;
    private String layer="",layerTarget="";
    private long newestFrame,layerFramesSince;
    private JSONObject meta;
    private Gpu metadataGpu;
    private List<ThermalSensor> metadataSensors;
    private final Object hardwareLock=new Object();
    private HardwareSnapshot hardwareSnapshot;
    private List<Integer> monitorCpus;
    private boolean monitorCpuPoliciesReady;
    private final Map<Integer,Integer> policyByCpu=new HashMap<>();
    private final Map<Integer,File> independentCpuDirectories=new HashMap<>();
    private long memoryTotalBytes;

    /** Each source change rejects in-flight readings, including a switch back to the same method. */
    private static final class FpsSource {
        final boolean callback;
        FpsSource(boolean callback){this.callback=callback;}
    }

    private static final class FpsSample {
        static final FpsSample MISSING=new FpsSample(Double.NaN);
        final double average;
        final long[] times;
        final long sampledAt,windowNanos,layerSince;

        FpsSample(double average){this(average,NO_FRAMES,0,0,0);}

        FpsSample(double average,long[] times,long sampledAt,long windowNanos,long layerSince){
            this.average=average;this.times=times;this.sampledAt=sampledAt;this.windowNanos=windowNanos;this.layerSince=layerSince;
        }

        double minimum(long recordSince){return minimumFrameRate(times,sampledAt,windowNanos,Math.max(recordSince,layerSince));}
    }

    private static final class HardwareSnapshot {
        final long elapsed,wall;
        final Set<Integer> online;
        final Map<Integer,long[]> cpu;
        final double[] policyMHz;
        final double gpuMHz, gpuUsage;
        final int metrics;
        final FpsSource fpsSource;
        // Guarded by the collector so sharing and source switches use the same lock.
        CompletableFuture<FpsSample> fps;

        HardwareSnapshot(long elapsed,long wall,Set<Integer> online,Map<Integer,long[]> cpu,double[] policyMHz,double gpuMHz,double gpuUsage,int metrics,FpsSource fpsSource){
            this.elapsed=elapsed;this.wall=wall;this.online=online;this.cpu=cpu;this.policyMHz=policyMHz;
            this.gpuMHz=gpuMHz;this.gpuUsage=gpuUsage;this.metrics=metrics;
            this.fpsSource=fpsSource;
        }

        boolean online(int id){return online.isEmpty()?cpu.containsKey(id):online.contains(id);}
    }

    static final class MonitorSample {
        private final HardwareSnapshot hardware;
        final double cpuMHz, gpuMHz, gpuUsage;
        final double[] cpuUsage;
        final boolean[] online;
        volatile double fps;
        final boolean showFps;

        MonitorSample(HardwareSnapshot hardware,double cpuMHz,double[] cpuUsage,boolean[] online,double fps,boolean showFps){
            this.hardware=hardware;this.cpuMHz=cpuMHz;this.cpuUsage=cpuUsage;this.online=online;
            gpuMHz=hardware.gpuMHz;gpuUsage=hardware.gpuUsage;this.fps=fps;this.showFps=showFps;
        }
    }

    PerformanceCollector(Sink sink,BiConsumer<MonitorSample,Double> monitorFps){this.sink=sink;this.monitorFps=monitorFps;}
    void screenChanged(){screenChangedAtNanos=System.nanoTime();}
    void sample(long session,long start,boolean screen,long interval,MonitorSample shared,GameMetrics selection){
        FpsSource requestedSource=fpsSource;
        boolean scheduled=submit(worker,recordingPending,()->{
            try{
                HardwareSnapshot hardware=shared==null?hardware(selection.performance&PERFORMANCE):shared.hardware;
                long elapsed=hardware.elapsed,wall=hardware.wall;
                boolean same=previousSession==session;
                if(same&&elapsed<=previousAt)return;
                boolean gap=!same||elapsed-previousAt>Math.max(5000,interval*3);
                boolean minimumFps=selection.performance(GameMetrics.MINIMUM_FPS);
                if(gap||!previousScreen||minimumFps&&!previousMinimumFps)recordFramesSince=System.nanoTime();
                long fpsDeadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(FPS_WAIT_MS);
                CompletableFuture<FpsSample> frames=screen&&selection.recordsFps()&&hardware.fpsSource==requestedSource
                    ?sampleFps(hardware,interval):null;
                if(frames==null&&screen)sampleMonitorFps(shared,interval);
                double maxTemperature=selection.performance(GameMetrics.CHIP_TEMP)?maximumCpuGpuTemperature():Double.NaN;
                MemoryStats memory=selection.performance(GameMetrics.MEMORY)?MemoryStats.parse(read("/proc/meminfo")):null;
                if(memory!=null&&memory.totalBytes!=memoryTotalBytes){memoryTotalBytes=memory.totalBytes;meta=null;}
                // A stalled Binder query must neither hold the recording worker nor queue more FPS work.
                boolean useFrames=frames!=null&&(!same||previousScreen);
                FpsSample frameSample=useFrames?awaitFps(frames,fpsDeadline):FpsSample.MISSING;
                if(hardware.fpsSource!=fpsSource)frameSample=FpsSample.MISSING;
                double fps=frameSample.average;
                if(frames!=null&&shared!=null&&shared.showFps&&!closed){
                    monitorFps.accept(shared,fps);
                    if(useFrames&&!Double.isFinite(fps))frames.thenAccept(value->{
                        if(!closed&&hardware.fpsSource==fpsSource&&Double.isFinite(value.average))monitorFps.accept(shared,value.average);
                    });
                }
                JSONArray row=new JSONArray().put(Math.max(0,elapsed-start)).put(number(selection.performance(FPS)?fps:Double.NaN,10))
                    .put(number(selection.performance(GPU_FREQUENCY)?hardware.gpuMHz:Double.NaN))
                    .put(number(selection.performance(GPU_CHART)?hardware.gpuUsage:Double.NaN)).put(JSONObject.NULL).put(JSONObject.NULL)
                    .put(gap?1:0).put(same?elapsed-previousAt:0);
                List<Cluster> cpuClusters=cpuClusters();
                for(int i=0;i<cpuClusters.size();i++){
                    Cluster c=cpuClusters.get(i);
                    row.put(number(selection.performance(CPU_FREQUENCY)?hardware.policyMHz[i]:Double.NaN));
                    row.put(number(selection.performance(CPU_CHART)&&same?cpuUsage(c.cpus,hardware.online,previousCpu,hardware.cpu):Double.NaN));
                }
                row.put(number(maxTemperature));
                row.put(memory==null?JSONObject.NULL:memory.usedGB());
                row.put(number(minimumFps?frameSample.minimum(Math.max(recordFramesSince,screenChangedAtNanos)):Double.NaN,10));
                previousCpu=selection.performance(CPU_CHART)?hardware.cpu:Collections.emptyMap();
                previousAt=elapsed;previousSession=session;previousScreen=screen;previousMinimumFps=minimumFps;
                if(!closed)sink.accept(session,start,wall,row,metadata());
            }catch(Throwable e){System.err.println("Performance snapshot skipped: "+e.getClass().getSimpleName());}
        });
        if(!scheduled)sampleMonitorFps(shared,interval);
    }
    void sampleMonitorFps(MonitorSample sample,long interval){
        if(sample!=null&&sample.showFps)sampleFps(sample.hardware,interval).thenAccept(fps->{
            if(!closed&&sample.hardware.fpsSource==fpsSource)monitorFps.accept(sample,fps.average);
        });
    }
    private boolean submit(ExecutorService executor,AtomicBoolean pending,Runnable work){
        if(closed||!pending.compareAndSet(false,true))return false;
        try{
            executor.execute(()->{
                try{if(!closed)work.run();}
                finally{pending.set(false);}
            });
            return true;
        }catch(RejectedExecutionException e){pending.set(false);return false;}
    }
    private synchronized CompletableFuture<FpsSample> sampleFps(HardwareSnapshot hardware,long interval){
        if(hardware.fpsSource!=fpsSource)return CompletableFuture.completedFuture(FpsSample.MISSING);
        if(hardware.fps!=null)return hardware.fps;
        CompletableFuture<FpsSample> result=new CompletableFuture<>();
        hardware.fps=result;
        if(!submit(fpsWorker,fpsPending,()->{
            try{
                FpsSample value=fps(interval,hardware.fpsSource);
                result.complete(hardware.fpsSource==fpsSource?value:FpsSample.MISSING);
            }
            catch(Throwable unavailable){result.complete(FpsSample.MISSING);}
        }))result.complete(FpsSample.MISSING);
        return result;
    }
    private static FpsSample awaitFps(CompletableFuture<FpsSample> frames,long deadline){
        try{return frames.get(Math.max(0,deadline-System.nanoTime()),TimeUnit.NANOSECONDS);}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
        catch(ExecutionException|TimeoutException unavailable){}
        return FpsSample.MISSING;
    }
    @Override public synchronized void close(){
        if(closed)return;
        closed=true;worker.shutdownNow();
        fpsWorker.execute(taskFps::stop);
        fpsWorker.shutdown();
    }
    synchronized boolean setTaskFpsCallback(boolean preferredCallback,boolean minimumFpsRecording){
        boolean callback=preferredCallback&&!minimumFpsRecording;
        if(closed||fpsSource.callback==callback)return false;
        fpsSource=new FpsSource(callback);
        if(!callback)fpsWorker.execute(taskFps::stop);
        return true;
    }
    synchronized void setTaskFpsActive(boolean active){
        if(closed||taskFpsActive==active)return;
        taskFpsActive=active;
        if(!active&&fpsSource.callback)fpsWorker.execute(taskFps::stop);
    }
    private HardwareSnapshot hardware(int metrics){
        synchronized(hardwareLock){
            long now=SystemClock.elapsedRealtime();
            FpsSource source=fpsSource;
            // Close requests from the monitor and recorder share one driver read.
            if(hardwareSnapshot!=null&&hardwareSnapshot.fpsSource==source&&now-hardwareSnapshot.elapsed<50
                &&(hardwareSnapshot.metrics&metrics)==metrics)return hardwareSnapshot;
            long wall=System.currentTimeMillis();
            boolean cpu=(metrics&CPU)!=0,frequency=(metrics&CPU_FREQUENCY)!=0,load=(metrics&CPU_CHART)!=0;
            if(frequency&&clusters==null)clusters=discoverCpu();
            if((metrics&GPU)!=0&&gpu==null)gpu=discoverGpu();
            Set<Integer> online=cpu?cpuList(read("/sys/devices/system/cpu/online")):Collections.emptySet();
            double[] frequencies=new double[frequency?clusters.size():0];
            for(int i=0;i<frequencies.length;i++)frequencies[i]=clusters.get(i).frequency(online);
            double gpuMHz=(metrics&GPU_FREQUENCY)!=0?gpu.frequency():Double.NaN;
            double gpuUsage=(metrics&GPU_CHART)!=0?gpu.utilization():Double.NaN;
            hardwareSnapshot=new HardwareSnapshot(now,wall,online,load?cpuTimes():Collections.emptyMap(),frequencies,gpuMHz,gpuUsage,metrics,source);
            return hardwareSnapshot;
        }
    }

    private List<Cluster> cpuClusters(){
        synchronized(hardwareLock){
            if(clusters==null)clusters=discoverCpu();
            return clusters;
        }
    }

    MonitorSample monitor(MonitorSample previous,int metrics,int recordingMetrics){
        boolean showFrequency=(metrics&CPU_FREQUENCY)!=0;
        HardwareSnapshot current=hardware(((metrics|recordingMetrics)&PERFORMANCE)|(showFrequency?CPU_CHART:0));
        if(previous!=null&&previous.hardware==current)return previous;
        boolean showFps=(metrics&FPS)!=0;
        double previousFps=showFps&&previous!=null?previous.fps:Double.NaN;
        if((metrics&CPU)==0)return new MonitorSample(current,Double.NaN,new double[0],new boolean[0],previousFps,showFps);
        if(monitorCpus==null||monitorCpus.isEmpty()||showFrequency&&!monitorCpuPoliciesReady)discoverMonitorCpus(current,showFrequency);
        double maximum=Double.NaN;
        double[] usage=new double[monitorCpus.size()];
        boolean[] online=new boolean[usage.length];
        for(int i=0;i<usage.length;i++){
            int id=monitorCpus.get(i);
            online[i]=current.online(id);
            usage[i]=Double.NaN;
            if(!online[i]||previous==null||!previous.hardware.online(id))continue;
            usage[i]=coreUsage(previous.hardware.cpu.get(id),current.cpu.get(id));
            if(!showFrequency||!(usage[i]>=1))continue;
            File directory=independentCpuDirectories.get(id);
            double frequency=directory==null?Double.NaN:readCpuMHz(directory+"/cpuinfo_cur_freq",directory+"/scaling_cur_freq");
            Integer policy=policyByCpu.get(id);
            if(!Double.isFinite(frequency)&&policy!=null)frequency=current.policyMHz[policy];
            if(frequency>0&&(!Double.isFinite(maximum)||frequency>maximum))maximum=frequency;
        }
        return new MonitorSample(current,maximum,usage,online,previousFps,showFps);
    }

    private void discoverMonitorCpus(HardwareSnapshot current,boolean frequency){
        Set<Integer> present=cpuList(read("/sys/devices/system/cpu/present"));
        present.addAll(current.online);present.addAll(current.cpu.keySet());
        for(int i=0;frequency&&i<clusters.size();i++){
            for(int id:clusters.get(i).cpus){present.add(id);policyByCpu.putIfAbsent(id,i);}
        }
        monitorCpus=new ArrayList<>(present);
        if(!frequency)return;
        monitorCpuPoliciesReady=true;
        for(int id:monitorCpus){
            File directory=new File("/sys/devices/system/cpu/cpu"+id+"/cpufreq");
            Integer policy=policyByCpu.get(id);
            try{
                // Most per-core paths are symlinks to the policy directory.
                if(policy==null||!directory.getCanonicalFile().equals(clusters.get(policy).directory.getCanonicalFile())){
                    independentCpuDirectories.put(id,directory);
                }
            }catch(IOException ignored){
                if(policy==null)independentCpuDirectories.put(id,directory);
            }
        }
    }

    static double coreUsage(long[] before,long[] after){
        if(before==null||after==null)return Double.NaN;
        long total=after[0]-before[0],idle=after[1]-before[1];
        return total>0&&idle>=0&&idle<=total?(total-idle)*100.0/total:Double.NaN;
    }
    private JSONObject metadata()throws JSONException{
        Gpu currentGpu;
        synchronized(hardwareLock){currentGpu=gpu;}
        if(meta!=null&&metadataGpu==currentGpu&&metadataSensors==thermalSensors)return meta;
        JSONArray list=new JSONArray();for(Cluster c:clusters)list.put(new JSONObject().put("label",c.label)
            .put("cpus",new JSONArray(c.cpus)).put("maxMHz",number(c.maxMHz)).put("frequencySource",c.source));
        JSONArray thermal=new JSONArray();if(thermalSensors!=null)for(ThermalSensor sensor:thermalSensors)thermal.put(new JSONObject().put("label",sensor.label).put("path",sensor.path));
        meta=new JSONObject().put("clusters",list).put("gpuMaxMHz",number(currentGpu==null?Double.NaN:currentGpu.maxMHz))
            .put("gpuFrequencySource",currentGpu==null?"":currentGpu.freq).put("gpuUsageSource",currentGpu==null?"":currentGpu.load.isEmpty()?currentGpu.busy:currentGpu.load)
            .put("fpsSource","SurfaceFlinger / actualPresentTime or WindowManager / TaskFpsCallback").put("cpuUsageSource","/proc/stat")
            .put("temperatureIndex",8+clusters.size()*2).put("temperatureSources",thermal)
            .put("memoryIndex",9+clusters.size()*2).put("memoryTotalGB",memoryTotalBytes>0?memoryTotalBytes/1e9:JSONObject.NULL)
            .put("minimumFpsIndex",10+clusters.size()*2)
            .put("method","频率为采样时读数；CPU 使用率为相邻采样的忙闲计数差，GPU 使用率为驱动当前负载；CPU/GPU 温度取识别到的温度节点最大值；帧率按前台画面最近的实际呈现帧计算。缺失数据留空。");
        metadataGpu=currentGpu;metadataSensors=thermalSensors;
        return meta;
    }
    static Object number(double n){return number(n,100);}
    private static Object number(double n,double scale){return Double.isFinite(n)?Math.round(n*scale)/scale:JSONObject.NULL;}
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
        final boolean millidegrees;
        ThermalSensor(String label,String path,boolean millidegrees){this.label=label;this.path=path;this.millidegrees=millidegrees;}
    }
    static double temperatureCelsius(String text,boolean millidegrees){
        try{
            double value=Double.parseDouble(text.trim());
            if(millidegrees||Math.abs(value)>=1000)value/=1000;
            if(value>=-50&&value<=200)return value;
        }catch(NumberFormatException ignored){}
        return Double.NaN;
    }
    private double maximumCpuGpuTemperature(){
        if(thermalSensors==null)thermalSensors=discoverThermalSensors();
        double maximum=Double.NaN;
        for(ThermalSensor sensor:thermalSensors){
            double value=temperatureCelsius(read(sensor.path),sensor.millidegrees);
            if(!Double.isFinite(value))continue;
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
            String path=existing(zone+"/temp");if(!path.isEmpty())found.put(path,new ThermalSensor(type,path,true));
        }
        File[] hwmons=new File("/sys/class/hwmon").listFiles();
        if(hwmons!=null)for(File hwmon:hwmons){
            String device=read(hwmon+"/name");File[] inputs=hwmon.listFiles();if(inputs==null)continue;
            for(File input:inputs){
                String name=input.getName();if(!name.matches("temp[0-9]+_input"))continue;
                String stem=name.substring(0,name.length()-6),label=read(hwmon+"/"+stem+"_label");
                if(!cpuOrGpu(label)&&!cpuOrGpu(device))continue;
                String path=existing(input.toString());if(!path.isEmpty())found.putIfAbsent(path,new ThermalSensor(label.isEmpty()?device+" "+stem:label,path,true));
            }
        }
        String kgsl=existing("/sys/class/kgsl/kgsl-3d0/temp","/sys/class/kgsl/kgsl-3d0/gpu_temp");
        if(!kgsl.isEmpty())found.putIfAbsent(kgsl,new ThermalSensor("KGSL GPU",kgsl,false));
        return new ArrayList<>(found.values());
    }
    private static final class Cluster {
        final List<Integer> cpus;final String label,source,fallback;final double maxMHz;
        final File directory;
        Cluster(Set<Integer> ids,String dir){
            directory=new File(dir);
            cpus=new ArrayList<>(ids);label="CPU "+cpus.get(0)+(cpus.size()>1?"–"+cpus.get(cpus.size()-1):"");
            source=existing(dir+"/cpuinfo_cur_freq",dir+"/scaling_cur_freq");fallback=dir+"/scaling_cur_freq";
            maxMHz=first(read(existing(dir+"/cpuinfo_max_freq",dir+"/scaling_max_freq")))/1000;
        }
        double frequency(Set<Integer> online){
            boolean any=false;for(int cpu:cpus)if(online.contains(cpu)){any=true;break;}
            if(!any&&!online.isEmpty())return 0;
            // Linux cpufreq policies share a clock; their online cores have the same policy frequency.
            return readCpuMHz(source,fallback);
        }
    }
    private static double readCpuMHz(String source,String fallback){
        double value=first(read(source));
        if(!(value>0)&&!source.equals(fallback))value=first(read(fallback));
        return value>0?value/1000:Double.NaN;
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
        if(ids.isEmpty()||online.isEmpty()&&after.isEmpty())return Double.NaN;
        Set<Integer> activeCpus=online.isEmpty()?after.keySet():online;
        double sum=0;
        for(int id:ids){
            // /proc/stat may omit CPUs that are offline. They contribute zero to the cluster average.
            if(!activeCpus.contains(id))continue;
            double usage=coreUsage(before.get(id),after.get(id));
            if(!Double.isFinite(usage))return Double.NaN;
            sum+=usage;
        }
        return sum/ids.size();
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
    /** dumpAsync uses the root Binder directly; bounded poll prevents stalled services blocking sampling. */
    private String dump(String... args)throws Exception{
        return dump(180,262144,args);
    }

    private String dump(int timeoutMs,int maxBytes,String... args)throws Exception{
        if(flinger==null||!flinger.isBinderAlive()){
            flinger=(IBinder)Class.forName("android.os.ServiceManager").getMethod("checkService",String.class).invoke(null,"SurfaceFlinger");
            if(flinger==null)throw new IOException("SurfaceFlinger unavailable");
        }
        ParcelFileDescriptor[] pipe=ParcelFileDescriptor.createPipe();
        try{
            flinger.dumpAsync(pipe[1].getFileDescriptor(),args);pipe[1].close();
            StructPollfd fd=new StructPollfd();fd.fd=pipe[0].getFileDescriptor();fd.events=(short)OsConstants.POLLIN;
            ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buf=new byte[8192];long until=SystemClock.uptimeMillis()+timeoutMs;
            while(!closed){
                int remain=(int)(until-SystemClock.uptimeMillis());if(remain<=0)throw new IOException("SurfaceFlinger timeout");
                if(Os.poll(new StructPollfd[]{fd},remain)==0)throw new IOException("SurfaceFlinger timeout");
                if((fd.revents&(OsConstants.POLLIN|OsConstants.POLLHUP))==0)throw new IOException("SurfaceFlinger pipe unavailable");
                int n=Os.read(fd.fd,buf,0,buf.length);if(n<=0)break;
                if(out.size()+n>maxBytes)throw new IOException("SurfaceFlinger response too large");out.write(buf,0,n);
            }return out.toString("UTF-8");
        }finally{try{pipe[1].close();}finally{pipe[0].close();}}
    }
    static Pattern layerPackagePattern(String pkg){
        return Pattern.compile("(?<![A-Za-z0-9_.])"+Pattern.quote(pkg)+"(?![A-Za-z0-9_.])");
    }
    static List<String> frameLayerCandidates(String report,int taskId,String pkg){
        return frameLayerCandidates(TaskLayerList.names(report,taskId),pkg);
    }

    private static List<String> frameLayerCandidates(List<String> names,String pkg){
        Pattern packageName=layerPackagePattern(pkg);
        // Match system layer names at the start so activity names such as SplashActivity still count.
        names.removeIf(name->!packageName.matcher(name).find()||NON_FRAME_LAYER.matcher(name).find());
        names.sort(Comparator.comparingInt(PerformanceCollector::frameLayerPriority));
        return names;
    }
    private static int frameLayerPriority(String name){
        if(name.startsWith("VRI-"))return 0;
        if(name.contains("(BLAST)#"))return 1;
        return name.contains("SurfaceView[")?2:3;
    }
    static long[] frameTimes(String text,long now){
        TreeSet<Long> values=new TreeSet<>();for(String line:text.split("\n")){
            String[] p=line.trim().split("\\s+");if(p.length!=3)continue;
            try{long n=Long.parseLong(p[1]);if(n>0&&n<Long.MAX_VALUE&&n<=now)values.add(n);}catch(NumberFormatException ignored){}
        }
        long[] result=new long[values.size()];int i=0;for(long value:values)result[i++]=value;return result;
    }
    private static int recentFrameStart(long[] times,long now,long windowNanos){
        long windowStart=now-windowNanos;
        int first=Math.max(0,times.length-MAX_FPS_FRAMES);
        while(first<times.length&&times[first]<=windowStart)first++;
        return first;
    }
    static double frameRate(long[] times,long now,long windowNanos){
        if(times.length==0)return Double.NaN;
        int first=recentFrameStart(times,now,windowNanos);
        int count=times.length-first;
        if(count<2)return count*(TimeUnit.SECONDS.toNanos(1)/(double)windowNanos);
        // N presented frames span N-1 intervals. The dump may retain less than one sample window.
        long span=times[times.length-1]-times[first];
        return (count-1)*(double)TimeUnit.SECONDS.toNanos(1)/span;
    }
    static double minimumFrameRate(long[] times,long now,long windowNanos,long segmentStart){
        int first=recentFrameStart(times,now,windowNanos);
        while(first<times.length&&times[first]<segmentStart)first++;
        long longest=0;
        for(int i=first+1;i<times.length;i++)longest=Math.max(longest,times[i]-times[i-1]);
        return longest>0?(double)TimeUnit.SECONDS.toNanos(1)/longest:Double.NaN;
    }
    static int bestFrameHistory(long[][] histories,long now,long windowNanos){
        int best=-1,bestCount=-1;
        long bestSpan=Long.MAX_VALUE,newest=0;
        for(int i=0;i<histories.length;i++){
            long[] times=histories[i];
            if(times.length==0)continue;
            int first=recentFrameStart(times,now,windowNanos),count=times.length-first;
            long last=times[times.length-1];
            // Two full histories can contain the same number of frames at very different rates.
            long span=count==MAX_FPS_FRAMES?last-times[first]:Long.MAX_VALUE;
            if(count<bestCount||count==bestCount&&(span>bestSpan||span==bestSpan&&last<=newest))continue;
            best=i;bestCount=count;bestSpan=span;newest=last;
        }
        return best;
    }
    static boolean emptyHistoryCanBeIdle(String report,long lastPresented,long now,long windowNanos){
        if(lastPresented<=0||now-lastPresented<windowNanos)return false;
        int end=report.indexOf('\n');
        // A successful --latency report starts with the display period even without frame rows.
        try{return Long.parseLong((end<0?report:report.substring(0,end)).trim())>0;}
        catch(NumberFormatException invalid){return false;}
    }
    private FpsSample fps(long intervalMs,FpsSource source){
        if(source!=fpsSource)return FpsSample.MISSING;
        if(source.callback)return taskFpsActive?new FpsSample(taskFps.read(intervalMs)):FpsSample.MISSING;
        long windowNanos=TimeUnit.MILLISECONDS.toNanos(intervalMs);
        if(layerSource!=source){
            layerSource=source;
            layer=layerTarget="";
            newestFrame=fpsRetry=0;
        }
        long elapsed=SystemClock.elapsedRealtime();if(elapsed<fpsRetry)return FpsSample.MISSING;
        try{
            if(focusedTaskLookup==null)focusedTaskLookup=new FocusedTask.Lookup();
            FocusedTask task=focusedTaskLookup.read();
            if(task==null){layer=layerTarget="";newestFrame=0;return FpsSample.MISSING;}
            String target=task.id+":"+task.windowingMode+":"+task.activity.flattenToShortString();
            boolean changed=!target.equals(layerTarget);
            if(changed){layer="";layerTarget=target;newestFrame=0;}
            long[] cachedFrames=NO_FRAMES;
            double cachedFps=Double.NaN;
            long cachedAt=0;
            if(!layer.isEmpty()){
                String history=dump("--latency",layer);
                cachedAt=System.nanoTime();
                cachedFrames=frameTimes(history,cachedAt);
                if(cachedFrames.length>0){
                    newestFrame=cachedFrames[cachedFrames.length-1];
                    cachedFps=frameRate(cachedFrames,cachedAt,windowNanos);
                }else if(emptyHistoryCanBeIdle(history,newestFrame,cachedAt,windowNanos))cachedFps=0;
            }
            // Recheck active layers too: a secondary surface can keep presenting as the main surface changes.
            if(!changed&&elapsed-layerChecked<(cachedFps>0?LAYER_ACTIVE_REFRESH_MS:LAYER_IDLE_REFRESH_MS))
                return new FpsSample(cachedFps,cachedFrames,cachedAt,windowNanos,layerFramesSince);
            layerChecked=elapsed;
            String report=dump("--list"),pkg=task.activity.getPackageName();
            // Older dumps list names only. Use the full dump's parent links, never infer ownership from list order.
            List<String> names=TaskLayerList.isLegacyFlatList(report)
                ?frameLayerCandidates(TaskLayerList.legacyNames(dump(350,2*1024*1024),task.id),pkg)
                :frameLayerCandidates(report,task.id,pkg);
            int count=Math.min(3,names.size()),cachedIndex=names.indexOf(layer);
            if(cachedIndex>=count){
                names.add(count,names.remove(cachedIndex));
                count++;
            }
            long[][] histories=new long[count][];
            for(int i=0;i<count;i++){
                String candidate=names.get(i);
                try{histories[i]=candidate.equals(layer)?cachedFrames:frameTimes(dump("--latency",candidate),System.nanoTime());}
                catch(Exception ignored){histories[i]=NO_FRAMES;}
            }
            long sampledAt=System.nanoTime();
            int best=bestFrameHistory(histories,sampledAt,windowNanos);
            long[] chosen=best<0?NO_FRAMES:histories[best];
            // Retain an idle layer after history eviction, but prefer a layer with newly presented frames.
            if(cachedFps==0&&recentFrameStart(chosen,sampledAt,windowNanos)==chosen.length&&cachedIndex>=0)return new FpsSample(0);
            String selected=best<0?"":names.get(best);
            if(!selected.equals(layer))layerFramesSince=sampledAt;
            layer=selected;
            newestFrame=best<0?0:chosen[chosen.length-1];
            return new FpsSample(frameRate(chosen,sampledAt,windowNanos),chosen,sampledAt,windowNanos,layerFramesSince);
        }catch(Throwable e){layer="";flinger=null;fpsRetry=elapsed+10000;return FpsSample.MISSING;}
    }
}
