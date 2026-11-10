import{valid}from'./chart.js?v=acol-2.2s';
import{roundedDuration}from'./daily-usage-chart.js?v=acol-2.2s';
import{fmt,el,recordFast,pad2,longPressFeedback}from'./record-display.js?v=acol-2.2s';
import{RecordUsageRefresh}from'./record-usage-refresh.js?v=acol-2.2s';
import{ScreenTimePanel}from'./screen-time.js?v=acol-2.2s';
import{RecordEnergyCache,energyRows,formatWh}from'./record-energy-cache.js?v=acol-2.2s';

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

/** Owns all record drain presentation, derived charts and foreground settlement. */
export function createRecordDrain(api,{getData,onSettled,readAppIcons}){
  const $=id=>document.getElementById(id),appIcons=new Map(),rows=new Map(),iconQueue=new Map(),missingIcons=new Set();
  let recordKey=null,summaryKey,appsKey,timeKey,lastPayload,iconTimer=null;
  const iconsVisible=()=>!document.hidden&&!$('detail').hidden&&!$('apps').hidden;
  const iconObserver=typeof IntersectionObserver==='function'?new IntersectionObserver(entries=>{
    for(const {target,isIntersecting}of entries){
      const pkg=target.dataset.pkg;
      if(isIntersecting&&rows.get(pkg)?.icon===target)queueIcon(pkg,target);
      else if(iconQueue.get(pkg)===target)iconQueue.delete(pkg);
    }
  },{root:$('detailScroll'),rootMargin:'72px 0px'}):null;
  const screenTimePanel=new RecordUsagePanel(api,{formatDuration:roundedDuration,formatDate:wall=>{const date=new Date(wall);return`${pad2(date.getMonth()+1)}/${pad2(date.getDate())}`;},minuteUnit:'分'});
  const usageRefresh=new RecordUsageRefresh(api,()=>getData()?.record,()=>!document.hidden&&!$('detail').hidden,onSettled);
  document.addEventListener('pointerdown',e=>{if(!e.target.closest('.appEnergy,.drain-item,.drain-chart'))hideAppEnergyTip();});
  function renderApps(data){
    const r=data.record;
    usageRefresh.update(data);
    const key=`${r.id}:${r.start_wall}`,stamp=data.statsVersion??(recordFast(r)?Math.floor(r.end_elapsed/60000):r.end_elapsed);
    if(key!==recordKey){resetApps();recordKey=key;}
    const time=[stamp,data.statsEpoch,r.end_elapsed,r.active,r.app_revision].join(':');
    if(time!==timeKey){timeKey=time;screenTimePanel.update(r,`${data.statsEpoch??''}:${stamp}`);}
    const summary=JSON.stringify(data.drainSummary??[]);
    if(summary!==summaryKey){summaryKey=summary;renderDrainSummary(data);}
    $('apps').hidden=r.kind==='charge';
    if(r.kind==='charge')return;
    const basis=r.stats_basis;
    if(lastPayload&&lastPayload.apps===data.apps&&lastPayload.basis===basis&&lastPayload.pending===data.usagePending)return;
    lastPayload={apps:data.apps,basis,pending:data.usagePending};
    const signature=JSON.stringify([basis,data.usagePending,data.apps??[]]);
    if(signature===appsKey)return;appsKey=signature;
    $('appStatsNote').textContent=basis==='current_integral'?'能量按电压、电流积分，按前台时长分摊；占比按 Wh 计算。≤1 分钟归入其他。':basis==='rm_minute'?'仅统计放电区间，按 RM 变化和平均电压计算 Wh，每分钟结算并按使用时段分摊。≤1 分钟归入其他。':'按放电能量与使用时段分摊；占比按 Wh 计算。≤1 分钟归入其他。平均功耗仅统计有效数据。';
    const apps=[...(data.apps??[])],sum=apps.reduce((total,a)=>total+appEnergy(a),0);
    apps.sort((a,b)=>sum>0?appEnergy(b)-appEnergy(a):(b.mah??0)-(a.mah??0));
    const host=$('appRows'),packages=new Set(apps.map(a=>a.pkg));
    for(const [pkg,entry]of rows)if(!packages.has(pkg)){
      iconObserver?.unobserve(entry.icon);iconQueue.delete(pkg);entry.row.remove();rows.delete(pkg);
    }
    if(!apps.length){
      const placeholder=host.firstChild??el('p',undefined,'small');
      setText(placeholder,data.usagePending?'正在读取耗电统计…':'暂无统计。');
      if(!placeholder.parentNode)host.append(placeholder);
      return;
    }
    host.querySelector(':scope > p')?.remove();
    let anchor=host.firstChild;
    for(const a of apps){
      let entry=rows.get(a.pkg);
      if(!entry){entry=appRow(a);rows.set(a.pkg,entry);watchIcon(entry);}
      updateAppRow(entry,a,sum);
      if(entry.row===anchor)anchor=anchor.nextSibling;else host.insertBefore(entry.row,anchor);
    }
  }
  function setText(node,text){if(node.textContent!==text)node.textContent=text;}
  function appEnergy(a){return valid(a.energyMwh)&&a.energyMs>0?a.energyMwh:0;}
  function appWhText(a){
    if(!valid(a.energyMwh)||!(a.energyMs>0))return'—';
    return (a.energyMwh/1000).toFixed(2);
  }
  function appRow(a){
    const row=el('div',undefined,'appRow'),icon=appIconNode(a),info=el('div',undefined,'appInfo');
    const heading=el('div',undefined,'appNameLine'),name=el('span',undefined,'appName'),share=el('span',undefined,'app-share'),meta=el('span',undefined,'appMeta');
    heading.append(name,share);info.append(heading,meta);
    const energy=el('div',undefined,'appEnergy'),value=el('strong'),wh=el('span',undefined,'app-energy-value');
    value.append(wh,el('span','Wh','app-energy-unit'));energy.append(value);row.append(icon,info,energy);
    const entry={row,icon,name,share,meta,wh,energy,data:a};
    bindAppEnergyTip(energy,()=>entry.data);
    return entry;
  }
  function updateAppRow(entry,a,sum){
    entry.data=a;
    const share=sum>0?`${fmt(appEnergy(a)/sum*100,1)}%`:'—',wh=appWhText(a);
    const meta=[roundedDuration(a.ms)];
    if(valid(a.avgPower))meta.push(`平均${a.avgPower.toFixed(2)}W`);
    setText(entry.name,a.label??a.pkg);setText(entry.share,share);setText(entry.meta,meta.join(' · '));setText(entry.wh,wh);
    const label=`${wh} Wh${sum>0?`，占 ${share}`:''}，长按查看 mAh 统计`;
    if(entry.energy.getAttribute('aria-label')!==label)entry.energy.setAttribute('aria-label',label);
    const fallback=entry.icon.querySelector('.app-icon-fallback');if(fallback)setText(fallback,Array.from(a.label||'?')[0]||'?');
  }
  let energyTipTimer;
  function bindLongPressTip(node,text){
    let timer,start;
    const cancel=()=>{clearTimeout(timer);start=null;};
    node.addEventListener('pointerdown',e=>{
      if(e.button!==0||!e.isPrimary)return;
      clearTimeout(timer);start=[e.clientX,e.clientY];
      timer=setTimeout(()=>{
        if(!node.isConnected||!start)return;
        showEnergyTip(typeof text==='function'?text():text,start[0],start[1]);longPressFeedback();
      },500);
    });
    node.addEventListener('pointermove',e=>{if(start&&Math.hypot(e.clientX-start[0],e.clientY-start[1])>8)cancel();});
    for(const type of['pointerup','pointercancel','pointerleave'])node.addEventListener(type,cancel);
  }
  function bindAppEnergyTip(node,getApp){
    bindLongPressTip(node,()=>{
      const a=getApp();
      if(!(valid(a.mah)&&a.mahMs>0))return'暂无 mAh 统计';
      return`${fmt(Math.round(a.mah))} mAh\n平均 ${fmt(Math.round(a.mah*3600000/a.mahMs))} mA`;
    });
  }
  function showEnergyTip(text,x,y){
    const tip=$('appEnergyTip');
    tip.textContent=text;tip.hidden=false;
    const w=tip.offsetWidth,h=tip.offsetHeight;
    tip.style.left=`${Math.max(8,Math.min(innerWidth-w-8,x-w-12))}px`;
    tip.style.top=`${Math.max(8,Math.min(innerHeight-h-8,y-h-12))}px`;
    clearTimeout(energyTipTimer);energyTipTimer=setTimeout(()=>tip.hidden=true,3500);
  }
  function hideAppEnergyTip(){clearTimeout(energyTipTimer);$('appEnergyTip').hidden=true;}
  function appIconNode(a){
    const box=el('span',undefined,'app-icon');box.dataset.pkg=a.pkg;
    if(a.pkg==='__screenoff__'){box.append(appGlyph('moon'));return box;}
    if(a.pkg==='__other__'){box.append(appGlyph('grid'));return box;}
    const icon=appIcons.get(a.pkg);
    if(icon)paintIcon(box,icon);
    else box.append(el('span',Array.from(a.label||'?')[0]||'?','app-icon-fallback'));
    return box;
  }
  function paintIcon(box,source){
    const img=el('img');img.src=source;img.alt='';img.decoding='async';img.draggable=false;box.replaceChildren(img);
  }
  function appGlyph(kind){
    const ns='http://www.w3.org/2000/svg',svg=document.createElementNS(ns,'svg');
    svg.setAttribute('viewBox','0 0 24 24');svg.setAttribute('aria-hidden','true');svg.setAttribute('class','app-glyph');
    const paths=kind==='moon'?['M20.3 15.1A8.6 8.6 0 0 1 8.9 3.7 8.7 8.7 0 1 0 20.3 15.1Z']:['M5.5 5.5h4v4h-4zM14.5 5.5h4v4h-4zM5.5 14.5h4v4h-4zM14.5 14.5h4v4h-4z'];
    for(const d of paths){const path=document.createElementNS(ns,'path');path.setAttribute('d',d);svg.append(path);}
    return svg;
  }
  function watchIcon(entry){
    const pkg=entry.data.pkg;
    if(!pkg||pkg.startsWith('__')||appIcons.has(pkg)||missingIcons.has(pkg)||entry.icon.querySelector('img'))return;
    if(iconObserver)iconObserver.observe(entry.icon);else queueIcon(pkg,entry.icon);
  }
  function queueIcon(pkg,node){
    if(!iconsVisible()||missingIcons.has(pkg)||node.querySelector('img'))return;
    const cached=appIcons.get(pkg);if(cached){paintIcon(node,cached);iconObserver?.unobserve(node);return;}
    iconQueue.set(pkg,node);
    if(iconTimer===null)iconTimer=setTimeout(flushIcons,40);
  }
  function flushIcons(){
    iconTimer=null;if(!iconsVisible()){iconQueue.clear();return;}
    const batch=[...iconQueue].slice(0,6);if(!batch.length)return;
    const icons=readAppIcons(batch.map(([pkg])=>pkg));
    for(const [pkg,node]of batch){
      iconQueue.delete(pkg);iconObserver?.unobserve(node);
      const icon=icons[pkg];
      if(typeof icon!=='string'||!icon.startsWith('data:image/')){missingIcons.add(pkg);continue;}
      appIcons.set(pkg,icon);if(appIcons.size>128)appIcons.delete(appIcons.keys().next().value);
      if(node.isConnected&&rows.get(pkg)?.icon===node)paintIcon(node,icon);
    }
    if(iconQueue.size)iconTimer=setTimeout(flushIcons,40);
  }
  function stopIcons(){
    clearTimeout(iconTimer);iconTimer=null;iconQueue.clear();iconObserver?.disconnect();
  }
  function resetApps(){
    stopIcons();rows.clear();missingIcons.clear();recordKey=summaryKey=appsKey=timeKey=lastPayload=null;$('appRows').replaceChildren();
  }
  function renderDrainSummary(data){
    $('drainSummary').hidden=data.record.kind==='charge';if(data.record.kind==='charge')return;
    const host=$('drainRows');host.replaceChildren();
    const stats=data.drainSummary??[],energy=a=>valid(a?.energyMwh)&&a.energyMs>0?Math.max(0,a.energyMwh):0;
    const total=stats.reduce((sum,a)=>sum+energy(a),0);
    const layout=el('div',undefined,'drain-layout'),chart=el('div',undefined,'drain-chart');
    const ns='http://www.w3.org/2000/svg',ring=document.createElementNS(ns,'svg');
    ring.setAttribute('viewBox','0 0 100 100');ring.setAttribute('class','drain-ring');ring.setAttribute('aria-hidden','true');
    const circumference=2*Math.PI*40,onLength=total?Math.min(circumference,energy(stats[0])/total*circumference):0,offLength=total?Math.max(0,circumference-onLength):0;
    const segment=(cls,dash,offset)=>{
      const circle=document.createElementNS(ns,'circle');
      circle.setAttribute('cx','50');circle.setAttribute('cy','50');circle.setAttribute('r','40');
      circle.setAttribute('class',`drain-ring-seg ${cls}`);circle.setAttribute('stroke-dasharray',`${dash} ${circumference}`);
      if(offset)circle.setAttribute('stroke-dashoffset',`${-offset}`);
      circle.setAttribute('transform','rotate(-90 50 50)');return circle;
    };
    ring.append(segment('drain-ring-track',circumference,0),segment('drain-ring-on',onLength,0),segment('drain-ring-off',offLength,onLength));
    const center=el('div',undefined,'drain-center'),totalText=el('div');
    totalText.append(el('strong',total?fmt(total/1000,2):'—'),el('span','Wh','drain-center-unit'));
    center.append(totalText,el('small','合计'));chart.append(ring,center);
    const totalMs=stats.reduce((sum,a)=>sum+(valid(a?.ms)?Math.max(0,a.ms):0),0);
    const totalEnergyMs=stats.reduce((sum,a)=>sum+(valid(a?.energyMs)?Math.max(0,a.energyMs):0),0);
    const totalPower=totalEnergyMs>0?total*3600/totalEnergyMs:null;
    bindLongPressTip(chart,()=>totalMs>0||totalPower!==null?`总时长  ${roundedDuration(totalMs)}\n总平均功耗  ${totalPower!==null?totalPower.toFixed(2)+' W':'—'}`:'暂无统计');
    const items=el('div',undefined,'drain-items');
    for(const [i,label]of['亮屏耗电','息屏耗电'].entries()){
      const a=stats[i]??{ms:0,energyMwh:0,energyMs:0,avgPower:null},value=energy(a),share=total?value/total*100:null;
      const item=el('div',undefined,'drain-item '+(i?'off':'on')),head=el('div',undefined,'drain-item-head'),name=el('div',undefined,'drain-item-name');
      name.append(el('span',undefined,'drain-dot'),el('span',label,'drain-item-label'));
      head.append(name,el('strong',share===null?'—':`${fmt(share,1)}%`,'drain-item-share'));
      const values=el('div',undefined,'drain-item-values'),wh=el('span'),avg=el('span');
      wh.append(el('b',value?fmt(value/1000,2):'—'),document.createTextNode(' Wh'));
      avg.append(el('b',valid(a.avgPower)?a.avgPower.toFixed(2):'—'),document.createTextNode(' W'));
      values.append(wh,el('span',roundedDuration(a.ms),'drain-duration'),avg);
      bindAppEnergyTip(item,()=>a);
      item.append(head,values);items.append(item);
    }
    layout.append(items,chart);host.append(layout);
  }

  return{render:renderApps,close(){screenTimePanel.close();usageRefresh.close();hideAppEnergyTip();resetApps();},
    pause(){usageRefresh.pause();hideAppEnergyTip();stopIcons();},resume(){usageRefresh.resume();for(const entry of rows.values())watchIcon(entry);},
    prune:()=>screenTimePanel.prune(),remove:id=>screenTimePanel.remove(id),hideTip:hideAppEnergyTip};
}
