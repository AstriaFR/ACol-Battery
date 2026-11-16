package cn.astriafr.acobattery.daemon;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Finds layers owned by a task using SurfaceFlinger's debug layer hierarchy. */
final class TaskLayerList {
    private static final Pattern NODE=Pattern.compile("^RequestedLayerState\\{(.+#(\\d+))(?: parentId=(\\d+))?(?: .*)?\\}$");
    private static final Pattern TASK=Pattern.compile("^Task=(\\d+)(?:\\([^)]*\\))?#\\d+$");
    private static final Pattern ACTIVITY=Pattern.compile("^ActivityRecord\\{.* t(\\d+)\\}#\\d+$");

    private static final class Layer {
        final String name,parent,task;

        Layer(String name,String parent){
            this.name=name;this.parent=parent;
            Matcher container=TASK.matcher(name),activity=ACTIVITY.matcher(name);
            task=container.matches()?container.group(1):activity.matches()?activity.group(1):null;
        }
    }

    static List<String> names(String report,int taskId){
        Map<String,Layer> layers=new LinkedHashMap<>();
        for(String line:report.split("\n")){
            Matcher node=NODE.matcher(line.trim());
            if(node.matches())layers.put(node.group(2),new Layer(node.group(1),node.group(3)));
        }
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
