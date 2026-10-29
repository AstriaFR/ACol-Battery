package cn.astriafr.acobattery;

import android.graphics.Color;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.*;
import org.json.*;
import cn.astriafr.acobattery.common.ErrorLogStore;

/** Native list is kept underneath report details so Back preserves its scroll. */
final class ErrorLogPages {
    final MainActivity app;final ErrorLogStore local;
    FrameLayout page;LinearLayout rows;TextView status;Button more;
    volatile boolean active;boolean busy,localDone,rootDone;volatile int generation;int localOffset,rootOffset;
    final ArrayDeque<JSONObject> localQueue=new ArrayDeque<>(),rootQueue=new ArrayDeque<>();
    final Set<String> displayed=new HashSet<>();
    ErrorLogPages(MainActivity app){this.app=app;local=new ErrorLogStore(new File(app.getFilesDir(),"error-logs"));}
    boolean visible(){return active&&page!=null&&page.getVisibility()==View.VISIBLE;}
    synchronized void close(){active=false;generation++;if(page!=null)page.setVisibility(View.GONE);}
    synchronized void open(){
        if(app.destroyed||app.contentFrame==null||app.clearingData)return;
        generation++;active=true;busy=false;localOffset=rootOffset=0;localDone=rootDone=false;localQueue.clear();rootQueue.clear();displayed.clear();
        if(page!=null&&page.getParent() instanceof ViewGroup)((ViewGroup)page.getParent()).removeView(page);
        boolean dark=app.activeDarkTheme==null?app.systemDark():app.activeDarkTheme;
        int background=dark?Color.BLACK:Color.rgb(246,248,251),text=dark?Color.rgb(230,237,232):Color.rgb(24,38,56),muted=dark?Color.rgb(165,183,172):Color.rgb(96,113,103);
        LinearLayout layout=new LinearLayout(app);layout.setOrientation(LinearLayout.VERTICAL);layout.setPadding(app.dp(20),app.dp(16),app.dp(20),app.dp(12));layout.setBackgroundColor(background);
        LinearLayout header=new LinearLayout(app);header.setGravity(Gravity.CENTER_VERTICAL);
        Button back=app.pageBackButton(this::close);header.addView(back,new LinearLayout.LayoutParams(app.dp(62),app.dp(40)));
        TextView title=new TextView(app);title.setText("错误日志");title.setTextSize(22);title.setTypeface(null,Typeface.BOLD);title.setTextColor(text);title.setPadding(app.dp(10),0,0,0);header.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        Button clear=plain("清理",dark?Color.rgb(244,147,145):Color.rgb(190,65,65));clear.setOnClickListener(v->confirmClear());header.addView(clear,new LinearLayout.LayoutParams(app.dp(56),app.dp(40)));
        header.addView(app.homeIconButton(()->{close();app.goHomeFromNative();}),new LinearLayout.LayoutParams(app.dp(40),app.dp(40)));layout.addView(header,new LinearLayout.LayoutParams(-1,app.dp(44)));
        ScrollView scroll=new ScrollView(app);scroll.setVerticalScrollBarEnabled(true);scroll.setScrollbarFadingEnabled(false);scroll.setFillViewport(false);
        LinearLayout content=new LinearLayout(app);content.setOrientation(LinearLayout.VERTICAL);rows=new LinearLayout(app);rows.setOrientation(LinearLayout.VERTICAL);content.addView(rows);
        status=new TextView(app);status.setTextSize(13);status.setTextColor(muted);status.setPadding(0,app.dp(18),0,app.dp(12));content.addView(status);
        more=plain("加载更多",muted);more.setOnClickListener(v->load());content.addView(more,new LinearLayout.LayoutParams(-1,app.dp(46)));more.setVisibility(View.GONE);
        scroll.addView(content);LinearLayout.LayoutParams scrollLine=new LinearLayout.LayoutParams(-1,0,1);scrollLine.topMargin=app.dp(12);layout.addView(scroll,scrollLine);
        page=new FrameLayout(app);page.addView(layout,new FrameLayout.LayoutParams(-1,-1));app.contentFrame.addView(page,new FrameLayout.LayoutParams(-1,-1));load();
    }
    Button plain(String text,int color){Button b=new Button(app);b.setText(text);b.setTextSize(14);b.setTextColor(color);b.setBackgroundColor(Color.TRANSPARENT);b.setMinWidth(0);b.setMinimumWidth(0);b.setPadding(app.dp(8),0,app.dp(8),0);return b;}
    void confirmClear(){
        if(busy||!active)return;
        new android.app.AlertDialog.Builder(app).setTitle("清理错误日志？").setMessage("将清除应用和后台保存的错误日志。")
            .setNegativeButton("取消",null).setPositiveButton("清理",(dialog,which)->clear()).show();
    }
    void clear(){
        if(busy||!active)return;busy=true;int token=generation;status.setText("正在清理日志…");more.setEnabled(false);
        app.worker.execute(()->{
            String failure="";
            try{RootSession session=app.root;if(session==null||!app.rootReady)throw new java.io.IOException("后台未连接");JSONObject result=session.api("clear_error_logs");if(!result.optBoolean("ok"))throw new java.io.IOException(result.optString("error","后台清理失败"));}
            catch(Exception e){failure="后台日志清理失败："+e.getMessage();}
            try{local.clear();}catch(Exception e){failure+=(failure.isEmpty()?"":"；")+"应用日志清理失败："+e.getMessage();}
            String message=failure;app.runOnUiThread(()->{if(!active||token!=generation||app.destroyed)return;busy=false;open();if(!message.isEmpty())Toast.makeText(app,message,Toast.LENGTH_LONG).show();else Toast.makeText(app,"错误日志已清理",Toast.LENGTH_SHORT).show();});
        });
    }
    void fill(boolean root,int token)throws Exception{
        int offset;
        synchronized(this){if(token!=generation||!(root?rootQueue:localQueue).isEmpty()||(root?rootDone:localDone))return;offset=root?rootOffset:localOffset;}
        JSONObject page;
        if(root){RootSession session=app.root;if(session==null||!app.rootReady)throw new java.io.IOException("后台未连接，暂时只能显示应用内日志。");page=session.api("error_logs "+offset);}
        else page=local.list(offset,40);
        synchronized(this){
            if(token!=generation)return;ArrayDeque<JSONObject> queue=root?rootQueue:localQueue;
            JSONArray list=page.getJSONArray("items");for(int i=0;i<list.length();i++){JSONObject row=list.getJSONObject(i);row.put("origin",root?"root":"app");queue.add(row);}
            if(root){rootOffset=page.optInt("next",rootOffset+list.length());rootDone=!page.optBoolean("more");}
            else{localOffset=page.optInt("next",localOffset+list.length());localDone=!page.optBoolean("more");}
        }
    }
    void load(){
        if(!active||busy)return;busy=true;int token=generation;status.setText("正在读取日志列表…");more.setEnabled(false);
        app.worker.execute(()->{
            List<JSONObject> result=new ArrayList<>();String warning="";
            try{
                while(result.size()<40){
                    if(token!=generation)return;
                    fill(false,token);
                    try{fill(true,token);}catch(Exception unavailable){synchronized(this){if(token!=generation)return;rootDone=true;}warning="后台日志暂不可读："+unavailable.getMessage();}
                    synchronized(this){
                        if(token!=generation)return;JSONObject a=localQueue.peek(),b=rootQueue.peek();if(a==null&&b==null)break;
                        boolean takeLocal=b==null||a!=null&&a.optLong("time")>=b.optLong("time");JSONObject next=takeLocal?localQueue.poll():rootQueue.poll();if(next!=null&&displayed.add(next.optString("origin")+":"+next.optString("id")))result.add(next);
                    }
                }
            }catch(Exception error){warning="日志列表读取失败："+error.getMessage();}
            String message=warning;app.runOnUiThread(()->{
                if(!active||token!=generation||app.destroyed)return;busy=false;
                for(JSONObject item:result)add(item);
                status.setText(!message.isEmpty()?message:rows.getChildCount()==0?"暂无错误日志":"");
                boolean hasMore=!localDone||!rootDone||!localQueue.isEmpty()||!rootQueue.isEmpty();more.setEnabled(true);more.setVisibility(hasMore?View.VISIBLE:View.GONE);
            });
        });
    }
    void add(JSONObject item){
        boolean dark=app.activeDarkTheme==null?app.systemDark():app.activeDarkTheme;
        LinearLayout row=new LinearLayout(app);row.setOrientation(LinearLayout.VERTICAL);row.setPadding(app.dp(12),app.dp(13),app.dp(12),app.dp(13));
        android.graphics.drawable.GradientDrawable box=new android.graphics.drawable.GradientDrawable();box.setColor(dark?Color.rgb(15,23,18):Color.WHITE);box.setCornerRadius(app.dp(12));box.setStroke(app.dp(1),dark?Color.rgb(35,54,42):Color.rgb(225,231,239));row.setBackground(box);
        TextView title=new TextView(app);title.setText(item.optString("title","错误报告"));title.setTextSize(16);title.setTypeface(null,Typeface.BOLD);title.setTextColor(dark?Color.rgb(230,237,232):Color.rgb(24,38,56));title.setSingleLine(true);title.setEllipsize(TextUtils.TruncateAt.END);row.addView(title);
        TextView time=new TextView(app);time.setText(new SimpleDateFormat("yyyy/MM/dd HH:mm:ss",Locale.US).format(new Date(item.optLong("time")))+" · "+(item.optString("origin").equals("root")?"后台":"应用"));time.setTextSize(12);time.setTextColor(dark?Color.rgb(165,183,172):Color.rgb(96,113,103));time.setPadding(0,app.dp(4),0,0);row.addView(time);
        LinearLayout.LayoutParams line=new LinearLayout.LayoutParams(-1,-2);line.bottomMargin=app.dp(9);rows.addView(row,line);row.setContentDescription(title.getText()+"，"+time.getText());row.setOnClickListener(v->detail(item));
    }
    void detail(JSONObject item){
        if(busy)return;busy=true;int token=generation;status.setText("正在读取报告…");app.worker.execute(()->{
            String report;
            try{report=item.optString("origin").equals("root")?app.root.api("error_log "+item.getString("id")).getString("report"):local.read(item.getString("id"));}
            catch(Exception error){report="报告读取失败："+error.getMessage();}
            String text=report;app.runOnUiThread(()->{if(!active||token!=generation||app.destroyed)return;busy=false;status.setText("");app.openReport(text,true);app.errorTitle.setText("错误日志详情");});
        });
    }
}
