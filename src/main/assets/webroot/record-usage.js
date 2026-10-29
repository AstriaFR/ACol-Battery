import{ScreenTimePanel}from'./screen-time.js?v=acol-2.0';
import{RecordEnergyCache,energyRows,formatWh}from'./record-energy-cache.js?v=acol-2.0';

const nextDay=wall=>{const d=new Date(wall);d.setDate(d.getDate()+1);return d.getTime();};
const clock=hour=>`${String(hour).padStart(2,'0')}:00`;

/** Both views share the existing plot, date controls, hold/scrub and popover. */
export class RecordUsagePanel extends ScreenTimePanel{
  constructor(api,options){
    super(api,{...options,onSwipe:()=>this.changeView()});
    this.mode='energy';this.timeFormat=this.formatDuration;this.energyCache=new RecordEnergyCache();this.configure();
    for(const dot of this.host.querySelectorAll('[data-record-chart]'))dot.onclick=()=>this.changeView(dot.dataset.recordChart);
  }
  update(record,statsStamp){
    if(this.record?.id!==record.id||this.record?.start_wall!==record.start_wall){this.mode='energy';this.configure();}
    super.update(record,statsStamp);
  }
  configure(){
    const energy=this.mode==='energy';this.formatDuration=energy?formatWh:this.timeFormat;
    this.defaultHint=energy?'每小时耗电 · 左右滑动切换亮屏时长':'亮屏时长 · 左右滑动切换耗电';
    this.host.dataset.view=this.mode;
    for(const dot of this.host.querySelectorAll('[data-record-chart]'))dot.setAttribute('aria-pressed',String(dot.dataset.recordChart===this.mode));
    this.host.setAttribute('aria-label',energy?'每日分时耗电':'每日亮屏使用时长');
    this.plot.setAttribute('aria-label',energy?'24 小时耗电柱状图，单位 Wh':'24 小时亮屏时长柱状图');
  }
  changeView(mode=this.mode==='time'?'energy':'time'){
    if(!this.record||mode===this.mode||!['energy','time'].includes(mode))return;
    this.cancelPress();this.hideTip();this.token++;this.busyKey=null;this.dirty=false;this.force=false;
    this.mode=mode;this.key=null;this.data=null;this.hourDetails=null;this.configure();this.load();
  }
  async remove(id){await Promise.all([this.cache.remove(id),this.energyCache.remove(id)]);}
  prune(){
    if(this.pruning||Date.now()<(this.pruneAt??0))return;
    this.pruneAt=Date.now()+60000;
    this.pruning=(async()=>{for(const cache of [this.cache,this.energyCache])await cache.prune(this.api);})().catch(()=>{}).finally(()=>this.pruning=null);
  }
  chartScale(hours){
    if(this.mode!=='energy')return super.chartScale(hours);
    const peak=Math.max(.03,...hours),step=10**Math.floor(Math.log10(peak/3));
    const maximum=Math.ceil(peak/(3*step))*3*step;
    // Three equal grid intervals, all readable to hundredths in the existing
    // narrow axis gutter; avoid repeating-decimal labels such as 0.0333.
    return{maximum,divisor:1,formatAxis:value=>String(Number(value.toFixed(2)))};
  }
  async load(){
    if(this.mode!=='energy')return super.load();
    const record=this.record;if(!record)return;
    const day=this.day,key=`energy:${record.id}:${record.start_wall}:${day}`,now=Date.now();
    if(this.busyKey===key||this.key===key&&now<this.refreshAt)return;
    clearTimeout(this.retryTimer);
    const token=++this.token;this.busyKey=key;this.dirty=false;this.force=false;
    if(this.key!==key){this.node('screenTimeTotal').textContent='—';this.drawEmpty();this.node('screenTimeHint').textContent='正在读取分时耗电…';}
    try{
      const from=Math.max(day,record.start_wall),end=Math.min(nextDay(day),record.start_wall+Math.max(0,record.end_elapsed-record.start_elapsed));
      // In-flight reads are shared across navigation. Confirmed intervals remain
      // cached even if this view closes before the request completes.
      const entry=await this.energyCache.read(record,day,end,this.api);
      if(token!==this.token)return;
      const coveredMs=entry.covered.reduce((n,value)=>n+value,0);
      this.hourDetails=entry.apps.map((values,hour)=>({rows:energyRows(values,entry.labels),known:entry.covered[hour]>0}));
      this.key=key;this.refreshAt=entry.partial||record.active&&day===this.last?Date.now()+60000:Infinity;
      this.draw({hours:entry.hours,covered:entry.covered,totalMs:entry.hours.reduce((n,value)=>n+value,0),coveredMs,unknownMs:Math.max(0,end-from-coveredMs),rangeStart:from,rangeEnd:end});
    }catch{
      if(token!==this.token)return;this.key=key;this.refreshAt=Date.now()+60000;
      this.node('screenTimeHint').textContent='分时耗电暂不可用';
    }finally{
      if(token===this.token){this.busyKey=null;if(this.dirty){this.dirty=false;this.key=null;this.load();}else this.scheduleRetry();}
    }
  }
  showHourApps(hour){
    if(this.mode!=='energy')return super.showHourApps(hour);
    this.hideTip();const tip=document.createElement('div');tip.className='screen-time-popover';tip.setAttribute('role','status');
    const title=document.createElement('strong');title.textContent=`${clock(hour)}—${clock(hour+1)}`;tip.append(title);
    const details=this.hourDetails?.[hour];
    if(details?.rows.length){
      for(const app of details.rows){const row=document.createElement('div');row.className='screen-time-app';const name=document.createElement('span'),value=document.createElement('span');name.textContent=app.label;value.textContent=formatWh(app.wh);row.append(name,value);tip.append(row);}
    }else{const text=document.createElement('div');text.textContent=details?.known?'没有四舍五入后达到 0.02 Wh 的应用':'该小时暂无有效耗电数据';tip.append(text);}
    document.body.append(tip);this.tip=tip;this.positionTip(hour);
  }
  select(hour){
    super.select(hour);if(this.mode!=='energy'||!this.data||this.selected!==null)return;
    this.node('screenTimeHint').textContent=this.energyCache.available?this.defaultHint:'分时耗电缓存暂不可用';
  }
}
