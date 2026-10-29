import{DailyUsageChart,roundedDuration}from'./daily-usage-chart.js?v=acol-2.0';
import{DailyUsageCache,midnight,nextDate,localZone}from'./daily-usage-cache.js?v=acol-2.0';
const yieldUI=()=>new Promise(resolve=>setTimeout(()=>{if('requestIdleCallback'in window)requestIdleCallback(()=>resolve(),{timeout:400});else resolve();},80));
export class HomeUsagePanel extends DailyUsageChart{
  constructor(api){
    const initial=new DailyUsageCache();super(api,{prefix:'homeUsage',getCache:()=>initial,onDate:day=>this.display(day),formatDate:day=>{const date=new Date(day);return`${String(date.getMonth()+1).padStart(2,'0')}/${String(date.getDate()).padStart(2,'0')}`},formatDuration:ms=>roundedDuration(ms).replaceAll(' ','').replace('小时','时').replace('分钟','分')});
    this.getCache=()=>this.cache;this.generation=0;this.ready=false;this.host.hidden=false;
    this.warm=this.display(this.day);
    this.watch=new MutationObserver(()=>{if(this.visible())this.resume();else this.pause();});
    for(const id of ['homePage','appShell','usageDetail','usageAppDetail','usageWeekDetail','usageMonthDetail','usageAppWeekDetail']){const node=document.getElementById(id);if(node)this.watch.observe(node,{attributes:true,attributeFilter:['hidden']});}
    document.addEventListener('visibilitychange',()=>{if(this.visible())this.resume(true);else this.pause();});
    window.addEventListener('pagehide',()=>this.pause());window.addEventListener('pageshow',event=>{if(event.persisted)this.resume(true);});
  }
  homeVisible(){return !document.hidden&&!this.host.closest('[hidden]');}
  visible(){return !document.hidden&&['homePage','usageDetail','usageAppDetail','usageWeekDetail','usageMonthDetail','usageAppWeekDetail'].some(id=>{const node=document.getElementById(id);return node&&!node.closest('[hidden]')});}
  connected(){if(this.ready)return;this.ready=true;this.resume();}
  pause(){this.generation++;this.cancelPress();this.hideTip();clearTimeout(this.refreshTimer);this.refreshTimer=null;}
  resume(force=false){
    if(!this.ready||!this.visible())return;
    if(this.running){if(force||this.runGeneration!==this.generation)this.rerun=true;return;}
    if(!force&&this.lastStarted&&Date.now()-this.lastStarted<60000){this.scheduleRefresh();return;}
    this.lastStarted=Date.now();const generation=++this.generation;this.runGeneration=generation;this.rerun=false;
    this.running=this.collect(generation).finally(()=>{this.running=null;if(this.rerun&&this.visible()){this.lastStarted=0;this.resume();}else this.scheduleRefresh();});
  }
  scheduleRefresh(){clearTimeout(this.refreshTimer);if(this.visible()&&this.ready)this.refreshTimer=setTimeout(()=>this.resume(true),60000);}
  async collect(generation){
    try{
      await this.warm;await yieldUI();if(!this.visible()||generation!==this.generation)return;
      if(localZone()!==this.cache.zone){this.close();this.day=midnight(Date.now());this.selected=null;this.data=null;this.cache=new DailyUsageCache();await this.display(this.day);}
      const today=midnight(Date.now()),first=nextDate(today,-9);
      if(this.lastToday&&this.day===this.lastToday&&this.lastToday!==today)await this.display(today);this.lastToday=today;
      for(let i=0;i<10;i++){
        if(!this.visible()||generation!==this.generation)return;
        const day=nextDate(first,i),summary=await this.cache.summary(day),from=summary?.through??day,target=Math.min(nextDate(day),Date.now()-5000);
        // Finished dates require only a tiny summary record, never their app/hour layers.
        let changed=false;
        if(from<target){
          const progress=await this.cache.getProgress(from>day?day:nextDate(day,-1));
          await yieldUI();if(!this.visible()||generation!==this.generation)return;
          const result=await this.api('daily_usage',from,target,progress.through===from?progress.state||'0':'0');
          await this.cache.apply(result);changed=true;
        }
        if(await this.repairDay(day,generation))changed=true;
        if(changed){
          if(this.homeVisible()&&this.day===day)await this.display(day);
          Promise.resolve(this.onDataChange?.(day)).catch(()=>{});
        }
      }
    }catch(error){if(this.visible()&&generation===this.generation)this.node('screenTimeHint').textContent=this.entry?.through>this.day?'已显示缓存，新增时段暂未读取':'使用情况暂不可用，稍后重试';}
  }
  async repairDay(day,generation){
    const summary=await this.cache.summary(day);
    if(!summary||summary.coveredMs>=summary.through-day)return false;
    const entry=await this.cache.getHours(day);let changed=false,attempts=0;
    this.repairAttempts??=new Map();
    for(let hour=0;hour<24;hour++){
      if(!this.visible()||generation!==this.generation)break;
      const a=new Date(day),b=new Date(day);a.setHours(hour,0,0,0);b.setHours(hour+1,0,0,0);
      const from=a.getTime(),to=Math.min(b.getTime(),entry.through),key=`${day}:${hour}`,now=Date.now();
      if(to<=from||entry.hours[hour].coveredMs>=to-from||now-(this.repairAttempts.get(key)??0)<300000)continue;
      // Only incomplete hours are revisited. Bound foreground catch-up work and
      // leave genuinely unavailable history unknown rather than guessing zero.
      this.repairAttempts.delete(key);this.repairAttempts.set(key,now);
      while(this.repairAttempts.size>240)this.repairAttempts.delete(this.repairAttempts.keys().next().value);
      await yieldUI();if(!this.visible()||generation!==this.generation)break;
      try{
        const result=await this.api('daily_usage',from,to,'0');
        if(await this.cache.repairHour(result,hour))changed=true;
      }catch{ /* Preserve the confirmed part and retry this hour on a later foreground pass. */ }
      if(++attempts>=2)break;
    }
    return changed;
  }
}
