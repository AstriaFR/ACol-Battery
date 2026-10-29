import{isoWeek,moveWeek,firstUsageWeek,calendarMonth,moveMonth,firstUsageMonth}from'./usage-calendar.js?v=acol-2.0';
import{roundedDuration,dateText}from'./daily-usage-chart.js?v=acol-2.0';
import{midnight}from'./daily-usage-cache.js?v=acol-2.0';
const ns='http://www.w3.org/2000/svg',names=['一','二','三','四','五','六','日'];
function svg(tag,attrs){const node=document.createElementNS(ns,tag);for(const[key,value]of Object.entries(attrs))node.setAttribute(key,String(value));return node;}
export class UsageWeekPanel{
  constructor(getCache,navigate,options={}){
    this.node=id=>document.getElementById(id.replace(/^usageWeek/,options.prefix??'usageWeek'));this.appMode=!!options.app;this.monthMode=!!options.month;this.page=this.monthMode?'usage-month':this.appMode?'usage-app-week':'usage-week';this.period=this.monthMode?calendarMonth:isoWeek;this.firstPeriod=this.monthMode?firstUsageMonth:firstUsageWeek;this.periodText=this.monthMode?'本月':'本周';
    this.getCache=getCache;this.navigate=navigate;this.plot=this.node('usageWeekPlot');this.token=0;this.selected=null;
    this.node('usageWeekPrev').onclick=()=>this.change(-1);this.node('usageWeekNext').onclick=()=>this.change(1);
    this.plot.addEventListener('click',event=>{if(Date.now()<this.suppressClickUntil)return;const bar=event.target.closest('[data-day]');if(bar)this.select(Number(bar.dataset.day));});
    this.plot.addEventListener('keydown',event=>{const bar=event.target.closest('[data-day]');if(!bar)return;if(event.key===' '){event.preventDefault();this.select(Number(bar.dataset.day));}else if(event.key==='Enter'){event.preventDefault();this.openDay(Number(bar.dataset.day));}});
    this.plot.addEventListener('pointerdown',event=>{const bar=event.target.closest('[data-day]');if(!bar||event.isPrimary===false||event.button!==0)return;if(this.press?.id===null){this.press.id=event.pointerId;return;}this.begin(event.pointerId,event.clientX,event.clientY,Number(bar.dataset.day));});
    this.plot.addEventListener('pointermove',event=>{if(this.press?.id===event.pointerId)this.move(event.clientX,event.clientY)});
    this.plot.addEventListener('pointerup',event=>{if(this.press?.id===event.pointerId)this.end()});
    this.plot.addEventListener('pointercancel',()=>{if(this.press?.touchId===undefined)this.cancel()});
    this.plot.addEventListener('touchstart',event=>{if(event.touches.length!==1){this.cancel();return;}const touch=event.touches[0];if(!this.press){const bounds=this.plot.getBoundingClientRect(),index=Math.max(0,Math.min((this.week?.days.length??7)-1,Math.floor((touch.clientX-bounds.left)/bounds.width*(this.week?.days.length??7))));this.begin(null,touch.clientX,touch.clientY,index);}this.press.touchId=touch.identifier;},{passive:true});
    this.plot.addEventListener('touchmove',event=>{if(!this.press)return;const touch=Array.from(event.touches).find(item=>item.identifier===this.press.touchId);if(event.touches.length!==1)this.cancel();else if(touch)this.move(touch.clientX,touch.clientY);},{passive:true});
    this.plot.addEventListener('touchend',()=>this.end(),{passive:true});this.plot.addEventListener('touchcancel',()=>this.cancel(),{passive:true});
    document.addEventListener('visibilitychange',()=>{if(document.hidden)this.cancel()});
  }
  begin(id,x,y,index){
    this.cancel();const press={id,x,y,index};this.press=press;
    this.hold=setTimeout(()=>{if(this.press!==press)return;this.suppressClickUntil=Date.now()+800;this.cancel();this.openDay(index);},350);
  }
  move(x,y){if(this.press&&Math.hypot(x-this.press.x,y-this.press.y)>14){this.suppressClickUntil=Date.now()+400;this.cancel();}}
  end(){const press=this.press;if(!press)return;this.cancel();this.suppressClickUntil=Date.now()+400;this.select(press.index);}
  cancel(){clearTimeout(this.hold);this.hold=null;this.press=null;}
  close(){this.token++;this.cancel();}
  change(amount){if(!this.week)return;const week=(this.monthMode?moveMonth:moveWeek)(this.week.start,amount),first=this.firstPeriod(),current=this.period(Date.now());if(week.start<first.start||week.start>current.start)return;this.navigate({page:this.page,day:week.start,...(this.appMode?{pkg:this.pkg,label:this.label}:{})},false);}
  async display(day,pkg=null,label=''){
    const token=++this.token;let week=this.period(day);if(week.year<2026)week=this.firstPeriod();
    const changed=this.week?.start!==week.start||this.appMode&&this.pkg!==pkg;this.week=week;this.pkg=this.appMode?pkg:null;this.label=label||pkg;if(changed)this.cancel();
    if(changed)this.selected=week.days.indexOf(midnight(day));
    this.node('usageWeekRange').textContent=`${dateText(week.start)}—${dateText(week.days.at(-1))}`;
    this.node('usageWeekDate').textContent=week.label;this.node('usageWeekDate').setAttribute('aria-label',this.monthMode?`${week.year}年${week.number}月`:`${week.year}年第${week.number}周`);
    this.node('usageWeekPrev').disabled=week.start<=this.firstPeriod().start;this.node('usageWeekNext').disabled=week.start>=this.period(Date.now()).start;
    if(changed){this.rows=null;this.data=null;this.node('usageWeekAverage').textContent='—';this.node('usageWeekAverageLabel').textContent='日均';this.node('usageWeekTotal').textContent='总计 —';this.node('usageWeekHint').textContent=`正在读取${this.periodText}汇总…`;this.draw();}
    try{
      // Monthly charts read only daily summaries; weekly app views use their bounded totals.
      const result=this.monthMode?{summaries:await this.getCache().summaries(week.start,week.end,31)}:this.appMode?await this.getCache().weeklyApp(week.start,week.end,this.pkg):await this.getCache().weeklyOverview(week.start,week.end);if(token!==this.token)return;
      this.data=result;const byDay=new Map(result.summaries.map(row=>[row.dayStart,row])),byApp=new Map((result.appTotals??[]).map(row=>[row.dayStart,row])),today=midnight(Date.now());
      this.rows=week.days.map(date=>{const data=byDay.get(date),known=date<=today&&Number.isFinite(data?.onMs)&&data.onMs>=0&&data.coveredMs>0,ms=this.appMode?(byApp.get(date)?.ms??0):(data?.onMs??0);return{day:date,known,ms:known?ms:0,future:date>today}});
      const known=this.rows.filter(row=>row.known),total=known.reduce((n,row)=>n+row.ms,0),completed=known.filter(row=>row.day<today);
      this.node('usageWeekAverageLabel').textContent=completed.length?`${completed.length}日均`:'日均';
      this.node('usageWeekAverage').textContent=completed.length?roundedDuration(completed.reduce((n,row)=>n+row.ms,0)/completed.length):'—';
      this.node('usageWeekTotal').textContent=known.length?`总计 ${roundedDuration(total)}`:'总计 —';
      this.draw();this.select(this.selected);return result;
    }catch{if(token===this.token)this.node('usageWeekHint').textContent=`${this.periodText}汇总暂不可用`;}
  }
  refresh(day){if(this.week&&day>=this.week.start&&day<this.week.end&&!this.node('usageWeekDetail').hidden)return this.display(this.week.start,this.pkg,this.label);}
  draw(){
    this.plot.replaceChildren();const maximum=Math.max(3,Math.ceil(Math.max(0,...(this.rows??[]).map(row=>row.ms/3600000))/3)*3),count=this.week.days.length,width=288/count;
    for(let i=0;i<4;i++){const y=7+i*42;this.plot.append(svg('line',{x1:0,x2:288,y1:y,y2:y,class:'screen-time-grid'}));this.node('usageWeekY').children[i].textContent=String(maximum*(3-i)/3);}
    const axis=this.node('usageWeekAxis');axis.replaceChildren();
    for(let i=0;i<count;i++){
      const date=this.week.days[i],row=this.rows?.[i],known=!!row?.known,x=width*(i+.5),group=svg('g',{'data-day':i,class:`usage-week-day${i===this.selected?' selected':''}`,role:'button',tabindex:0,'aria-label':`${dateText(date)}${this.monthMode?'':`，周${names[i]}`}，${known?roundedDuration(row.ms):'暂无数据'}`});
      this.plot.append(svg('line',{x1:x,x2:x,y1:7,y2:133,class:'screen-time-grid vertical'}));group.append(svg('rect',{x:i*width,y:0,width,height:140,fill:'transparent'}));
      if(known){const height=Math.max(1,row.ms/3600000/maximum*126);group.append(svg('rect',{x:i*width+width*(this.monthMode ? .19 : .22),y:133-height,width:width*(this.monthMode ? .62 : .56),height,rx:this.monthMode?1.2:3,class:'screen-time-bar'}));}
      this.plot.append(group);
      if(!this.monthMode||i===0||(i+1)%5===0&&i<count-3||i===count-1){const label=document.createElement('span');label.style.left=`${(i+.5)/count*100}%`;if(!this.monthMode){const weekday=document.createElement('b');weekday.textContent=`周${names[i]}`;label.append(weekday);}const number=document.createElement('small');number.textContent=String(new Date(date).getDate()).padStart(2,'0');label.append(number);axis.append(label);}
    }
    this.node('usageWeekEmpty').hidden=!!this.rows?.some(row=>row.known);
  }
  select(index){
    this.selected=Number.isInteger(index)&&index>=0&&index<(this.week?.days.length??0)?index:null;
    for(const node of this.plot.querySelectorAll('[data-day]'))node.classList.toggle('selected',Number(node.dataset.day)===this.selected);
    if(this.selected===null){this.node('usageWeekHint').textContent='短按查看时长，长按打开当天';return;}
    const row=this.rows?.[this.selected],date=this.week.days[this.selected];
    const duration=document.createElement('span');duration.className='usage-hint-duration';duration.textContent=row?.known?roundedDuration(row.ms):row?.future?'尚未到这一天':'暂无已缓存数据';
    this.node('usageWeekHint').replaceChildren(`${dateText(date)} · `,duration);
  }
  openDay(index){if(!this.week||index<0||index>=this.week.days.length)return;const day=this.week.days[index];this.select(index);if(day<=midnight(Date.now()))this.navigate({page:this.appMode?'usage-app':'usage-day',day,...(this.appMode?{pkg:this.pkg,label:this.label}:{})},true);}
}
