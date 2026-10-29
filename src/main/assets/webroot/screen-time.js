import{ScreenTimeCache,normalizeSpans,missingSpans,hourlyTotals,normalizeAppSpans,hourAppRows}from'./screen-time-cache.js?v=acol-2.0';
const ns='http://www.w3.org/2000/svg';
const startOfDay=wall=>{const d=new Date(wall);d.setHours(0,0,0,0);return d.getTime()};
const moveDay=(wall,amount)=>{const d=new Date(wall);d.setDate(d.getDate()+amount);return d.getTime()};
const clock=hour=>`${String(hour).padStart(2,'0')}:00`;
const dateText=wall=>{const d=new Date(wall);return`${d.getFullYear()}/${String(d.getMonth()+1).padStart(2,'0')}/${String(d.getDate()).padStart(2,'0')}`};
function duration(ms){const minutes=Math.floor(Math.max(0,ms)/60000),hours=Math.floor(minutes/60);return hours?`${hours}小时${minutes%60?`${minutes%60}分钟`:''}`:minutes?`${minutes}分钟`:ms>0?'不足1分钟':'0分钟'}
function svg(tag,attributes){const node=document.createElementNS(ns,tag);for(const [key,value]of Object.entries(attributes))node.setAttribute(key,String(value));return node}

export class ScreenTimePanel{
  constructor(api,options={}){
    this.node=id=>document.getElementById(id.replace(/^screenTime/,options.prefix??'screenTime'));this.defaultHint=options.hint??'本次记录内的亮屏时长';
    this.formatDuration=options.formatDuration??duration;this.formatDate=options.formatDate??dateText;
    this.inspectionEnabled=options.inspect!==false;this.tipHost=options.tipHost??null;this.minuteUnit=options.minuteUnit??'分钟';
    this.onSwipe=options.onSwipe;
    this.api=api;this.host=this.node('screenTimePanel');this.plot=this.node('screenTimePlot');this.cache=new ScreenTimeCache();this.token=0;this.tipToken=0;
    this.node('screenTimePrev').onclick=()=>this.changeDay(-1);this.node('screenTimeNext').onclick=()=>this.changeDay(1);
    this.plot.addEventListener('click',event=>{const bar=event.target.closest('[data-hour]');if(Date.now()<this.suppressClickUntil){event.preventDefault();return;}if(bar){const hour=Number(bar.dataset.hour);if(this.tipHost&&this.tip===this.tipHost&&!this.tipHost.hidden)this.requestHourDetails(hour);else this.select(hour);}});
    this.plot.addEventListener('keydown',event=>{const bar=event.target.closest('[data-hour]');if(bar&&(event.key==='Enter'||event.key===' ')){event.preventDefault();this.requestHourDetails(Number(bar.dataset.hour));}});
    this.plot.addEventListener('pointerdown',event=>{
      const bar=event.target.closest('[data-hour]');if(!bar||event.isPrimary===false||event.button!==0){if(event.isPrimary===false)this.cancelPress();return;}
      if(this.press?.id===null&&this.press.touchId!==undefined){this.press.id=event.pointerId;return;}
      this.startPress(event.pointerId,event.clientX,event.clientY,Number(bar.dataset.hour));
    });
    this.plot.addEventListener('pointermove',event=>{
      if(!this.press||this.press.id!==event.pointerId)return;
      if(this.movePress(event.clientX,event.clientY)&&event.cancelable)event.preventDefault();
    },{passive:false});
    this.plot.addEventListener('touchstart',event=>{
      if(event.touches.length!==1){this.cancelPress();return;}
      const touch=event.touches[0];
      if(!this.press)this.startPress(null,touch.clientX,touch.clientY,this.hourAt(touch.clientX));
      if(this.press)this.press.touchId=touch.identifier;
    },{passive:true});
    // Android WebView may cancel pointer delivery while the finger touch stream
    // is still alive. Continue the held gesture using that original touch ID.
    this.plot.addEventListener('touchmove',event=>{
      const press=this.press;if(!press)return;
      if(event.touches.length!==1){this.cancelPress();return;}
      const touch=Array.from(event.touches).find(item=>item.identifier===press.touchId);
      if(touch&&this.movePress(touch.clientX,touch.clientY)&&event.cancelable)event.preventDefault();
    },{passive:false});
    for(const name of ['touchend','touchcancel'])this.plot.addEventListener(name,()=>this.cancelPress(),{passive:true});
    this.plot.addEventListener('pointerup',event=>{if(this.press?.id===event.pointerId)this.cancelPress()});
    this.plot.addEventListener('pointercancel',event=>{
      const press=this.press;if(!press||press.id!==event.pointerId)return;
      if(press.touchId!==undefined)return;
      this.cancelPress();
    });
    this.plot.addEventListener('lostpointercapture',event=>{
      const press=this.press;
      // Loss of a child's implicit capture bubbles; it is not loss of the SVG's capture.
      if(!press||event.target!==this.plot||event.pointerId!==press.id)return;
      if(press.touchId!==undefined)return;
      try{if(this.plot.hasPointerCapture(press.id))return;}catch{}
      this.cancelPress();
    });
    this.plot.addEventListener('pointerleave',()=>{if(!this.press?.active)this.cancelPress()});
    document.addEventListener('pointerdown',event=>{if(!this.tip?.contains(event.target))this.hideTip();},{capture:true,passive:true});
    document.addEventListener('scroll',event=>{if(!this.tip?.contains(event.target)){this.cancelPress();this.hideTip();}},{capture:true,passive:true});
    window.addEventListener('resize',()=>{this.cancelPress();this.hideTip();});
    document.addEventListener('visibilitychange',()=>{if(document.hidden){clearTimeout(this.retryTimer);this.cancelPress();this.hideTip();}else if(this.record&&!this.host.closest('[hidden]'))this.load();});

  }
  close(){clearTimeout(this.retryTimer);this.cancelPress();this.hideTip();this.record=null;this.token++;this.busyKey=null;this.host.hidden=true;}
  scheduleRetry(){
    clearTimeout(this.retryTimer);
    if(typeof document==='undefined'||!this.record||document.hidden||this.host.closest('[hidden]')||!Number.isFinite(this.refreshAt))return;
    this.retryTimer=setTimeout(()=>{if(this.record&&!document.hidden&&!this.host.closest('[hidden]'))this.load();},Math.max(1000,this.refreshAt-Date.now()));
  }
  update(record,statsStamp){
    if(record.kind!=='discharge'){this.close();return;}
    const first=startOfDay(record.start_wall),end=record.start_wall+Math.max(0,record.end_elapsed-record.start_elapsed),last=startOfDay(Math.max(record.start_wall,end-1));
    if(!Number.isFinite(first)||!Number.isFinite(last)){this.close();return;}
    const changed=this.record?.id!==record.id||this.record?.start_wall!==record.start_wall,settled=!changed&&(this.statsStamp!==statsStamp||Boolean(this.record?.active)!==Boolean(record.active));this.record=record;this.statsStamp=statsStamp;this.first=first;this.last=last;
    if(changed){this.hourDetails=null;this.hideTip();this.autoDay=true;this.day=last;this.selected=null;this.data=null;this.key=null;this.busyKey=null;this.force=false;this.token++;}
    if(this.autoDay&&this.day!==last){this.hourDetails=null;this.hideTip();this.day=last;this.selected=null;this.data=null;this.key=null;}
    if(settled&&this.day===last){this.key=null;this.force=true;if(this.busyKey)this.dirty=true;}
    this.host.hidden=false;this.navigation();this.load();
  }
  navigation(){
    this.node('screenTimeDate').textContent=this.formatDate(this.day);this.node('screenTimePrev').disabled=this.day<=this.first;this.node('screenTimeNext').disabled=this.day>=this.last;
  }
  changeDay(amount){
    if(!this.record)return;const day=moveDay(this.day,amount);if(day<this.first||day>this.last)return;
    this.cancelPress();this.hideTip();this.hourDetails=null;this.day=day;this.autoDay=false;this.selected=null;this.data=null;this.key=null;this.busyKey=null;this.token++;this.navigation();this.load();
  }
  async load(){
    const record=this.record;if(!record)return;const key=`${record.id}:${record.start_wall}:${this.day}`,now=Date.now();
    if(this.busyKey===key)return;
    if(this.key===key&&now<this.refreshAt)return;
    clearTimeout(this.retryTimer);
    const day=this.day,token=++this.token,force=this.force;this.force=false;this.busyKey=key;this.dirty=false;let entry;
    if(this.key!==key){this.node('screenTimeTotal').textContent='—';this.node('screenTimeHint').textContent='正在读取亮屏时长…';this.drawEmpty();}
    try{
      entry=await this.cache.get(record);if(token!==this.token)return;
      const from=Math.max(day,record.start_wall),end=Math.min(moveDay(day,1),record.start_wall+Math.max(0,record.end_elapsed-record.start_elapsed));
      const missing=missingSpans(entry.spans,from,end),attempted=Number(entry.attempts[day])||0;
      if(missing.length&&(force||attempted>now||now-attempted>=60000)){
        // Confirmed spans are never requested again. Bound catch-up work while
        // always including the growing tail of an active multi-day record.
        const gaps=missing.length>4?[...missing.slice(0,3),missing.at(-1)]:missing;
        for(const [start,stop]of gaps){
          if(token!==this.token)break;
          let seed=0;for(let i=entry.spans.length-1;i>=0;i--)if(entry.spans[i][1]<=start){if(entry.spans[i][1]===start)seed=entry.spans[i][2]?2:1;break;}
          const result=await this.api('screen_usage',record.id,day,start,stop,seed);
          entry.spans=normalizeSpans([...entry.spans,...(result.segments??[])]);entry.settledEnd=Math.max(entry.settledEnd,result.settledEnd??from);
        }
        entry.attempts[day]=Date.now();await this.cache.save(entry);
      }
      if(token!==this.token||this.record?.id!==record.id||this.day!==day)return;
      const appEnd=Math.max(from,Math.min(end,record.active?entry.settledEnd:end)),appMissing=missingSpans(entry.appSpans,from,appEnd),appAttempt=Number(entry.appAttempts[day])||0;
      if(appMissing.length&&entry.appFinal[day]!==appEnd&&(force||appAttempt>now||now-appAttempt>=60000)){
        try{
          const result=await this.api('hour_apps',record.id,appMissing[0][0],appMissing.at(-1)[1]);
          entry.appSpans=normalizeAppSpans([...entry.appSpans,...(result.segments??[])]);Object.assign(entry.appLabels,result.labels??{});
          if(result.complete!==false&&!missingSpans(entry.appSpans,from,appEnd).length&&(!record.active||appEnd>=moveDay(day,1)))entry.appFinal[day]=appEnd;
          else delete entry.appFinal[day];
          if(Number.isFinite(result.appRevision))record.app_revision=result.appRevision;
        }catch{}finally{entry.appAttempts[day]=Date.now();await this.cache.save(entry);}
      }
      if(token!==this.token||this.record?.id!==record.id||this.day!==day)return;
      this.hourDetails=Array.from({length:24},(_,hour)=>{
        const a=new Date(day),b=new Date(day);a.setHours(hour,0,0,0);b.setHours(hour+1,0,0,0);
        const lo=Math.max(from,a.getTime()),hi=Math.max(lo,Math.min(appEnd,b.getTime())),missing=missingSpans(entry.appSpans,lo,hi).reduce((sum,[x,y])=>sum+y-x,0);
        return{rows:hourAppRows(entry.appSpans,lo,hi,entry.appLabels),known:hi-lo>missing,partial:missing>1000};
      });
      this.key=key;this.refreshAt=missingSpans(entry.spans,from,end).length||missingSpans(entry.appSpans,from,appEnd).length||record.active&&day===this.last?Date.now()+60000:Infinity;
      this.draw(hourlyTotals(entry.spans,from,Math.max(from,Math.min(end,record.active?entry.settledEnd:end))));
    }catch(error){
      if(entry){entry.attempts[day]=Date.now();await this.cache.save(entry);}
      if(token!==this.token)return;this.key=key;this.refreshAt=Date.now()+60000;
      if(entry)this.draw(hourlyTotals(entry.spans,Math.max(day,record.start_wall),Math.max(Math.max(day,record.start_wall),Math.min(moveDay(day,1),record.start_wall+record.end_elapsed-record.start_elapsed))));
      else{this.data=null;this.node('screenTimeTotal').textContent='—';this.drawEmpty();}
      this.node('screenTimeHint').textContent='尚未确认的时段暂不可用';
    }finally{
      if(token===this.token){this.busyKey=null;if(this.dirty){this.dirty=false;this.key=null;this.load();}else this.scheduleRetry();}
    }
  }
  drawEmpty(){this.plot.replaceChildren();this.node('screenTimeEmpty').hidden=true;this.draw({hours:Array(24).fill(0),covered:Array(24).fill(0),coveredMs:0,totalMs:0,unknownMs:1,rangeStart:0,rangeEnd:1},true);}
  draw(data,loading=false){
    if(!loading)this.data=data;
    this.plot.replaceChildren();const hours=data.hours??[],covered=data.covered??[],{maximum,divisor,formatAxis}=this.chartScale(hours);
    for(let i=0;i<4;i++){
      this.plot.append(svg('line',{x1:0,x2:288,y1:7+i*42,y2:7+i*42,class:`screen-time-grid${i===3?' baseline':''}`}));
      this.node('screenTimeY').children[i].textContent=formatAxis(maximum*(3-i)/3);
    }
    for(const hour of [0,3,6,9,12,15,18,21,23])this.plot.append(svg('line',{x1:(hour+.5)*12,x2:(hour+.5)*12,y1:7,y2:133,class:'screen-time-grid vertical'}));
    for(let hour=0;hour<24;hour++){
      const value=Math.max(0,Number(hours[hour])||0),known=(covered[hour]??0)>0;
      const group=svg('g',{'data-hour':hour,class:`screen-time-hour${hour===this.selected?' selected':''}`,role:'button',tabindex:0,'aria-label':`${clock(hour)}—${clock(hour+1)}，${known?this.formatDuration(value):'暂无分时数据'}`});
      group.append(svg('rect',{x:hour*12,y:0,width:12,height:140,fill:'transparent'}));
      if(known&&value>0){const height=Math.max(.5,value/divisor/maximum*126);group.append(svg('rect',{x:hour*12+2.5,y:133-height,width:7,height,rx:2,class:'screen-time-bar'}));}
      this.plot.append(group);
    }
    if(loading)return;
    this.node('screenTimeTotal').textContent=data.coveredMs>0||data.rangeEnd===data.rangeStart?this.formatDuration(data.totalMs):'—';
    this.node('screenTimeEmpty').hidden=data.coveredMs>0||data.rangeEnd===data.rangeStart;this.select(this.selected);
  }
  chartScale(hours){return{maximum:Math.max(60,Math.ceil(Math.max(0,...hours)/3600000)*60),divisor:60000,formatAxis:value=>String(Math.round(value))};}
  hourAt(x){const bounds=this.plot.getBoundingClientRect();return bounds.width>0?Math.max(0,Math.min(23,Math.floor((x-bounds.left)/bounds.width*24))):0;}
  startPress(id,x,y,hour){
    if(!this.inspectionEnabled)return;
    this.cancelPress();if(!this.tipHost)this.hideTip();const press={id,x,y,currentX:x,currentY:y,hour,active:false,horizontal:false};this.press=press;
    this.hold=setTimeout(()=>{
      if(this.press!==press)return;this.hold=null;
      if(!this.record&&!(this.entry?.through>this.entry?.dayStart))return;
      press.active=true;press.hour=this.hourAt(press.currentX);this.suppressClickUntil=Date.now()+800;
      try{if(press.id!==null)this.plot.setPointerCapture(press.id)}catch{}
      this.requestHourDetails(press.hour);
    },350);
  }
  movePress(x,y){
    const press=this.press;if(!press)return false;press.currentX=x;press.currentY=y;
    if(!press.active){
      const dx=Math.abs(x-press.x),dy=Math.abs(y-press.y);
      if(!press.horizontal&&dy>14&&dy>dx*1.1){this.cancelPress();return false;}
      if(this.onSwipe&&dx>=42&&dx>=dy*1.5){
        const direction=x<press.x?1:-1;this.cancelPress();this.suppressClickUntil=Date.now()+800;this.onSwipe(direction);return true;
      }
      // Reserve a clear horizontal drag while the hold timer runs. Small finger
      // jitter no longer cancels the timer; an ordinary vertical swipe still does.
      if(dx>=10&&dx>=dy*1.3)press.horizontal=true;
      return press.horizontal;
    }
    this.suppressClickUntil=Date.now()+800;const hour=this.hourAt(x);
    if(hour!==press.hour){press.hour=hour;this.requestHourDetails(hour,true);}
    return true;
  }
  cancelPress(){
    clearTimeout(this.hold);this.hold=null;const press=this.press;this.press=null;
    if(press?.active)this.suppressClickUntil=Date.now()+800;
    try{if(press&&press.id!==null&&this.plot.hasPointerCapture(press.id))this.plot.releasePointerCapture(press.id)}catch{}
  }
  hideTip(){
    this.tipToken++;
    if(this.tipHost){this.tipHost.hidden=true;this.tipHost.replaceChildren();}else this.tip?.remove();
    this.tip=null;
  }
  requestHourDetails(hour){this.select(hour);if(this.inspectionEnabled)this.showHourApps(hour);}
  positionTip(hour){
    if(!this.tip)return;
    if(this.tipHost){
      const chart=this.plot.getBoundingClientRect(),card=this.host.getBoundingClientRect(),axis=this.host.querySelector('.screen-time-axis')?.getBoundingClientRect(),hint=this.node('screenTimeHint')?.getBoundingClientRect();
      const viewport=window.visualViewport,leftEdge=viewport?.offsetLeft??0,rightEdge=leftEdge+(viewport?.width??innerWidth),bottom=(viewport?.offsetTop??0)+(viewport?.height??innerHeight);
      const width=Math.max(0,Math.min(250,card.width-24,rightEdge-leftEdge-16)),left=Math.max(leftEdge+8,Math.min(hint?.left??card.left+12,rightEdge-width-8)),top=Math.max(chart.bottom,axis?.bottom??chart.bottom,hint?.bottom??chart.bottom)+5;
      this.tip.style.left=`${left}px`;this.tip.style.top=`${top}px`;this.tip.style.width=`${width}px`;this.tip.style.maxHeight=`${Math.max(0,Math.min(240,bottom-top-8))}px`;
      return;
    }
    const bounds=this.plot.getBoundingClientRect(),width=this.tip.offsetWidth,height=this.tip.offsetHeight;
    const x=bounds.left+bounds.width*(hour+.5)/24,y=bounds.top+Math.min(32,bounds.height/3);
    this.tip.style.left=`${Math.max(8,Math.min(innerWidth-width-8,x-width/2))}px`;
    this.tip.style.top=`${Math.max(8,Math.min(innerHeight-height-8,y-height-10>8?y-height-10:y+12))}px`;
  }
  showHourApps(hour){
    if(!this.inspectionEnabled)return;
    const details=this.hourDetails?.[hour];let tip;
    if(this.tipHost){this.tipToken++;tip=this.tipHost;tip.replaceChildren();tip.hidden=false;tip.scrollTop=0;}
    else{this.hideTip();tip=document.createElement('div');tip.className='screen-time-popover';tip.setAttribute('role','status');}
    if(!this.tipHost){const title=document.createElement('strong');title.textContent=`${clock(hour)}—${clock(hour+1)}`;tip.append(title);}
    const content=document.createElement('div');tip.append(content);
    if(!details)content.textContent='当天小时详情尚未准备完成';
    else{
      for(const app of details.rows){const row=document.createElement('div');row.className='screen-time-app';const name=document.createElement('span');name.textContent=app.label;const value=document.createElement('span');value.textContent=`${app.minutes}${this.minuteUnit}`;row.append(name,value);content.append(row);}
      if(!details.rows.length)content.textContent=details.known?'没有使用满30秒的应用':'该小时暂无已确认数据';
      else if(details.partial){const note=document.createElement('small');note.textContent='部分时段暂无数据';content.append(note);}
    }
    if(!this.tipHost)document.body.append(tip);this.tip=tip;this.positionTip(hour);
  }
  setTimeHint(period,value){
    const hint=this.node('screenTimeHint'),duration=document.createElement('span');duration.className='usage-hint-duration';duration.textContent=value;hint.replaceChildren(`${period} · `,duration);
  }
  select(hour){
    this.selected=Number.isInteger(hour)&&hour>=0&&hour<24?hour:null;
    for(const node of this.plot.querySelectorAll('[data-hour]'))node.classList.toggle('selected',Number(node.dataset.hour)===this.selected);
    const data=this.data;if(!data)return;
    if(this.selected===null)this.node('screenTimeHint').textContent=data.unknownMs>1000?'部分时段暂无分时数据':this.cache.available?this.defaultHint:'分时缓存暂不可用';
    else this.setTimeHint(`${clock(this.selected)}—${clock(this.selected+1)}`,(data.covered[this.selected]??0)>0?this.formatDuration(data.hours[this.selected]):'暂无分时数据');
  }
}
