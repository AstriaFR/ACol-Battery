import{ScreenTimePanel}from'./screen-time.js?v=acol-2.1';
import{midnight,dailyView,neighboringDates,canDeleteUsageDay}from'./daily-usage-cache.js?v=acol-2.1';
import{appRowsFromTotals}from'./screen-time-cache.js?v=acol-2.1';
export const clock=hour=>`${String(hour).padStart(2,'0')}:00`;
export const dateText=wall=>{const d=new Date(wall);return`${d.getFullYear()}/${String(d.getMonth()+1).padStart(2,'0')}/${String(d.getDate()).padStart(2,'0')}`};
export function roundedDuration(ms){
  if(ms>0&&ms<30000)return'不足1分';
  const minutes=Math.round(Math.max(0,ms)/60000),hours=Math.floor(minutes/60),rest=minutes%60;
  return hours?`${hours}时${rest?`${rest}分`:''}`:`${minutes}分`;
}
export class DailyUsageChart extends ScreenTimePanel{
  constructor(api,options){
    super(api,{prefix:options.prefix,hint:'每日亮屏时长 · 长按小时查看应用',inspect:options.inspect,tipHost:options.tipHost,minuteUnit:'分'});
    this.formatDate=options.formatDate??dateText;this.formatDuration=options.formatDuration??roundedDuration;
    if(['homeUsage','usageDay'].includes(options.prefix)){
      this.deleteButton=document.createElement('button');this.deleteButton.type='button';this.deleteButton.className='usage-day-delete';this.deleteButton.textContent='删除当天数据';this.deleteButton.hidden=true;
      const hint=this.node('screenTimeHint'),footer=document.createElement('div');footer.className='usage-day-footer';hint.before(footer);footer.append(hint,this.deleteButton);
      this.deleteButton.onclick=()=>this.onDeleteDay?.(this.day);
    }
    this.getCache=options.getCache;this.cache=this.getCache();this.onDate=options.onDate;this.day=midnight(Date.now());this.dayToken=0;this.navToken=0;this.pkg=null;
  }
  close(){this.dayToken++;this.navToken++;this.cancelPress();this.hideTip();}
  async navigation(){
    const day=this.day,token=++this.navToken;this.node('screenTimeDate').textContent=this.formatDate(day);this.node('screenTimeDate').setAttribute('aria-label',dateText(day));
    this.node('screenTimePrev').disabled=true;this.node('screenTimeNext').disabled=true;
    try{
      const neighbors=neighboringDates(day,await this.getCache().neighbors(day));if(token!==this.navToken)return;
      this.previousDay=neighbors.previous;this.nextDay=neighbors.next;
      this.node('screenTimePrev').disabled=neighbors.previous===null;this.node('screenTimeNext').disabled=neighbors.next===null;
    }catch{if(token===this.navToken)this.previousDay=this.nextDay=null;}
  }
  changeDay(amount){const day=amount<0?this.previousDay:this.nextDay;if(Number.isFinite(day))this.onDate(day);}
  async display(day,pkg=null,label=''){
    const changed=day!==this.day||pkg!==this.pkg,token=++this.dayToken;this.day=day;this.pkg=pkg;this.label=label;this.cache=this.getCache();
    if(changed){this.hourDetails=null;this.selected=null;this.entry=null;this.data=null;this.cancelPress();this.hideTip();}
    this.host.hidden=false;if(this.deleteButton)this.deleteButton.hidden=!canDeleteUsageDay(day);this.navigation();
    if(!this.entry){this.drawEmpty();this.node('screenTimeTotal').textContent='—';this.node('screenTimeHint').textContent='正在读取当天缓存…';}
    try{
      const [entry,app,hourApps]=await Promise.all([this.cache.getHours(day),pkg?this.cache.getAppHours(day,pkg):null,pkg?null:this.cache.getAllHourApps(day)]);if(token!==this.dayToken)return;
      this.entry=entry;this.app=app;
      this.hourDetails=this.inspectionEnabled?Array.from({length:24},(_,hour)=>{
        const values=app?[{pkg,ms:app.ms[hour]??0,label:app.label||label||pkg}]:(hourApps[hour]?.apps??[]),labels=Object.fromEntries(values.map(row=>[row.pkg,row.label]));
        const a=new Date(day),b=new Date(day);a.setHours(hour,0,0,0);b.setHours(hour+1,0,0,0);const expected=Math.max(0,Math.min(b.getTime(),entry.through)-a.getTime());
        return{rows:appRowsFromTotals(new Map(values.map(row=>[row.pkg,row.ms])),labels),known:entry.hours[hour].coveredMs>0,partial:entry.hours[hour].coveredMs+1000<expected};
      }):null;
      if(entry.through===entry.dayStart){this.data=null;this.drawEmpty();this.node('screenTimeHint').textContent='尚无已缓存的使用数据';return;}
      const data=dailyView(entry);
      if(app){data.hours=app.ms;data.totalMs=app.ms.reduce((n,ms)=>n+ms,0);}
      this.draw(data);if(data.coveredMs>0)this.node('screenTimeTotal').textContent=this.formatDuration(data.totalMs);
    }catch{if(token===this.dayToken)this.node('screenTimeHint').textContent='当天缓存暂不可用';}
  }
  select(hour){
    super.select(hour);if(!this.entry||this.entry.through===this.entry.dayStart)return;
    if(this.selected===null)this.node('screenTimeHint').textContent=this.data?.unknownMs>1000?'部分历史时段暂无数据':this.pkg?'当天的应用使用时长':'每日亮屏时长 · 长按小时查看应用';
    else this.setTimeHint(`${clock(this.selected)}—${clock(this.selected+1)}`,(this.data?.covered[this.selected]??0)>0?this.formatDuration(this.data.hours[this.selected]):'暂无分时数据');
  }
}
