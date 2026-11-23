package cn.astriafr.acobattery.daemon;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Finds layers owned by a task using SurfaceFlinger's debug layer hierarchy. */
final class TaskLayerList {
    private static final Pattern NODE=Pattern.compile("^RequestedLayerState\\{(.+#(\\d+))(?: parentId=(\\d+))?(?: .*)?\\}$");
    private static final Pattern LEGACY_NODE=Pattern.compile("^\\+ \\S+ \\((.+#\\d+)\\)$");
    private static final String LEGACY_PARENT=" parent=";
    private static final Pattern TASK=Pattern.compile("^Task=(\\d+)(?:\\([^)]*\\))?#\\d+$");
    private static final Pattern ACTIVITY=Pattern.compile("^ActivityRecord\\{.* t(\\d+)\\}#\\d+$");

    private static final class Layer {
        final String name,parent,task;

        Layer(String name,String parent){
            this.name=name;this.parent=parent;
            task=taskId(name);
        }
    }

    private static String taskId(String name){
        Matcher container=TASK.matcher(name),activity=ACTIVITY.matcher(name);
        return container.matches()?container.group(1):activity.matches()?activity.group(1):null;
    }

    static boolean isLegacyFlatList(String report){
        boolean taskFound=false;
        for(String line:report.split("\n")){
            String name=line.trim();
            if(name.startsWith("RequestedLayerState{"))return false;
            if(taskId(name)!=null)taskFound=true;
        }
        return taskFound;
    }

    static List<String> names(String report,int taskId){
        Map<String,Layer> layers=new LinkedHashMap<>();
        for(String line:report.split("\n")){
            Matcher node=NODE.matcher(line.trim());
            if(node.matches())layers.put(node.group(2),new Layer(node.group(1),node.group(3)));
        }
        return names(layers,taskId);
    }

    /** The Android 13 full dump names the actual parent on each layer's own row. */
    static List<String> legacyNames(String report,int taskId){
        Map<String,Layer> layers=new LinkedHashMap<>();
        Set<String> ambiguous=new HashSet<>();
        String name=null;
        for(String line:report.split("\n")){
            if(line.startsWith("+ ")){
                Matcher node=LEGACY_NODE.matcher(line.trim());
                name=node.matches()?node.group(1):null;
            }else if(name!=null&&line.startsWith(LEGACY_PARENT)){
                Layer layer=new Layer(name,line.substring(LEGACY_PARENT.length()).trim());
                if(layers.putIfAbsent(name,layer)!=null)ambiguous.add(name);
                name=null;
            }
        }
        for(String duplicate:ambiguous)layers.remove(duplicate);
        return names(layers,taskId);
    }

    private static List<String> names(Map<String,Layer> layers,int taskId){
        String target=Integer.toString(taskId);
        List<String> result=new ArrayList<>();
        for(Layer layer:layers.values())
            if(layer.task==null&&target.equals(owner(layer,layers)))result.add(layer.name);
        return result;
    }

    private static String owner(Layer layer,Map<String,Layer> layers){
        int remaining=layers.size();
        while(layer!=null&&remaining-- >0){
            if(layer.task!=null)return layer.task;
            layer=layers.get(layer.parent);
        }
        return null;
    }
}
