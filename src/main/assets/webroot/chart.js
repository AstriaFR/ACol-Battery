import{DEFAULT_SETTINGS}from'./config.js?v=acol-2.2s';
import{remainingEstimate,unclassifiedCells}from'./estimate.js?v=acol-2.2s';
import{batteryColor}from'./curve-colors.js?v=acol-2.2s';
const cssColor=(name,fallback)=>getComputedStyle(document.documentElement).getPropertyValue(name).trim()||fallback;
let chartSettings={...DEFAULT_SETTINGS};
export function setChartSettings(value){chartSettings={...DEFAULT_SETTINGS,...value};}
const scaleSettingsKey=()=>JSON.stringify([chartSettings.energyWh,chartSettings.capacityAh,chartSettings.capacityAuto,chartSettings.voltageMin,chartSettings.voltageMax,chartSettings.tempMin,chartSettings.tempMax]);
const reachesHeadroom=(peak,base,full)=>Number.isFinite(peak)&&full>base
  &&peak-base>=(full-base)*1.1-Math.max(1,Math.abs(full-base))*1e-12;
export const series=[
  {key:'rm',index:1,name:'剩余容量',axis:'容量',unit:'mAh',digits:0},
  {key:'pct',index:2,name:'系统电量',axis:'电量',unit:'%',digits:0},
  {key:'current',index:3,name:'充电电流',axis:'电流',unit:'mA',digits:0},
  {key:'voltage',index:4,name:'电芯均压',axis:'电压',unit:'mV',digits:1},
  {key:'power',index:5,name:'实时功率',axis:'功率',unit:'W',digits:6},
  {key:'temp',index:10,name:'电池温度',axis:'温度',unit:'℃',digits:1},
  {key:'energy',index:11,name:'剩余能量(估算)',axis:'剩余能量(估算)',unit:'Wh',digits:2},
  {key:'ram',index:14,name:'RAM 已用',axis:'RAM 已用',unit:'GB',digits:2}
];
export const valid=v=>typeof v==='number'&&Number.isFinite(v);
export function seriesFor(kind,cellCount=2,fast=false){return series.filter(s=>s.key==='ram'?kind==='discharge'&&!fast:kind==='charge'?s.key!=='energy':fast||s.key!=='current').map(s=>{
  let item={...s,color:batteryColor(s.key)};
  if(kind==='discharge'&&s.key==='current')item={...item,name:'电流'};
  if(kind==='discharge'&&s.key==='power')item={...item,name:fast?'实时功率':'平均功耗',axis:fast?'功率':'功耗',digits:2};
  if(kind==='discharge'&&s.key==='temp')item={...item,axis:'电池温度'};
  if(s.key==='voltage'&&cellCount!==2)item={...item,name:'电池电压'};
  return item;
});}
export function plotPoints(points,kind,cellCount=2,fast=false){
  if(kind!=='discharge')return points.map(p=>{if(!valid(p[3])||cellCount!==2||(p[6]&unclassifiedCells))return p;const row=[...p];row[3]*=2;return row;});
  const withEnergy=p=>{
    const row=[...p],charging=fast&&(p[12]===1||p[12]===true||p[12]==null&&valid(p[5])&&p[5]<0);
    const cells=(p[6]&unclassifiedCells)?0:cellCount;
    if(fast&&valid(row[3]))row[3]*=-(cells===2?2:1);
    row[11]=charging?null:remainingEstimate({rm:p[1],voltage:p[4],cellCount:cells},null)?.wh??null;return row;
  };
  if(fast)return points.map(withEnergy);
  return points.filter(p=>!valid(p[7])||p[7]>=20000).map(p=>{
    const row=withEnergy(p);if(valid(row[5])&&(row[5]>15||row[5]<0))row[5]=null;
    return row;
  });
}
export function niceBounds(values,key){
  if(key==='pct')return[0,100,20];
  if(key==='voltage')return[0,5000,1000];
  let lo=0,hi=0;for(const v of values)if(valid(v)){lo=Math.min(lo,v);hi=Math.max(hi,v);}
  const extent=Math.max(Math.abs(lo),hi),target=extent>0?extent/4:1;
  const base=10**Math.floor(Math.log10(target));
  const step=([1,2,2.5,5,10].find(n=>n*base>=target)||10)*base;
  return[Math.floor(lo/step)*step,Math.max(step,Math.ceil(hi/step)*step),step];
}
export function sampleText(value,digits=6){
  return valid(value)?value.toLocaleString('zh-CN',{maximumFractionDigits:digits,useGrouping:false}):'缺失';
}
// Game plot polarity is discharge-positive; details retain charge-positive units.
export function gameSampleText(value,key,digits=2){
  if(!valid(value))return'缺失';
  const display=(key==='current'||key==='power')?-value:value;
  return`${display>0?'+':display<0?'−':''}${Math.abs(display).toFixed(digits)}`;
}
export function signalScale(s,values,kind='charge',fast=false,previous=null){
  const known=values.filter(valid);
  if(s.key==='energy'){
    let peak=0;for(const value of known)peak=Math.max(peak,value);
    const configured=chartSettings.energyWh;
    const observed=Math.max(peak,previous?.observed?previous.full:0);
    const full=valid(configured)&&configured>0?(chartSettings.capacityAuto?Math.max(configured,observed):configured):observed;
    return{base:0,full:full||1,min:0,observed:full>0,label:full>0?`${full.toFixed(valid(configured)&&configured>0?2:full<2?2:1)} Wh`:'暂无数据'};
  }
  if(s.key==='rm'){
    const configured=chartSettings.capacityAh*1000,full=chartSettings.capacityAuto?Math.max(configured,previous?.full??0,...known):configured;
    return{base:0,full,min:0,label:`${(full/1000).toFixed(2)} Ah`};
  }
  if(s.key==='voltage'){
    const base=Math.floor(Math.min(chartSettings.voltageMin*1000,previous?.base??Infinity,...known)/10)*10;
    let full=Math.max(chartSettings.voltageMax*1000,previous?.full??0);
    const peak=Math.max(...known);if(reachesHeadroom(peak,base,full))full=Math.ceil(peak/10)*10;
    return{base,full,min:base,label:`${(base/1000).toFixed(2)}–${(full/1000).toFixed(2)} V`};
  }
  if(kind==='discharge'&&s.key==='power'){
    let peak=0,min=fast?(previous?.min??0):0;
    for(const value of known){if(fast){peak=Math.max(peak,Math.abs(value));min=Math.min(min,value);}else if(value>=0&&value<=15)peak=Math.max(peak,value);}
    const observed=peak>0||!!previous?.observed;
    const full=Math.max(previous?.observed?previous.full:0,peak)||1;
    return{base:0,full,min,observed,label:observed?`${full.toFixed(2)} W`:'暂无峰值'};
  }
  if(s.key==='current'||s.key==='power'){
    const step=s.key==='current'?2000:10;
    let peak=0,min=previous?.min??0;
    for(const value of known){
      peak=Math.max(peak,Math.abs(value));min=Math.min(min,value);
    }
    const observed=peak>0||!!previous?.observed;
    // The first render must honor the same 110% headroom as a live update.
    let rounded=Math.max(step,Math.floor(peak/(step*1.1))*step);
    while(reachesHeadroom(peak,0,rounded))rounded+=step;
    let full=previous?.full>0?previous.full:rounded;
    if(reachesHeadroom(peak,0,full))full=rounded;
    const label=s.key==='current'?`${sampleText(full/1000)} A`:`${sampleText(full)} W`;
    return{base:0,full,min,observed,label};
  }
  if(s.key==='temp'){
    const base=Math.floor(Math.min(chartSettings.tempMin,previous?.base??Infinity,...known));
    let full=Math.max(chartSettings.tempMax,previous?.full??-Infinity);
    const peak=Math.max(...known);if(reachesHeadroom(peak,base,full))full=Math.ceil(peak);
    return{base,full,min:base,label:`${base}–${full} ℃`};
  }
  const [lo,hi]=niceBounds(values,s.key);let full=Math.max(Math.abs(lo),hi);
  // Percent stays tied to the system's 0–100 scale. Other unconfigured
  // signals keep their initial nice bounds until the 110% boundary is reached.
  if(s.key!=='pct'&&previous&&full>0){
    const peak=Math.max(0,...known.map(Math.abs));
    if(!reachesHeadroom(peak,0,previous.full))full=previous.full;
  }
  const compact=full>=1000&&['mAh','mA','mV'].includes(s.unit),factor=compact?1000:1;
  const unit=compact?{mAh:'Ah',mA:'A',mV:'V'}[s.unit]:s.unit;
  let min=previous?.min??0;for(const value of values)if(valid(value))min=Math.min(min,value);
  return{base:0,full,min,label:`${sampleText(full/factor)} ${unit}`};
}
export const scalePercent=(value,scale)=>(value-scale.base)/(scale.full-scale.base)*100;
export const axisMinimum=(kind,scales)=>kind==='charge'?0:Math.floor(Math.min(0,...scales.map(s=>scalePercent(s.min,s)))/10)*10;
export const plotAxisMinimum=(kind,scales,reverse=false,maximum=110)=>['charge','discharge'].includes(kind)?(reverse?-maximum:0):axisMinimum(kind,scales);
export function actualAxis(s,scale){
  if(s.key==='temp')return{title:`${s.axis} ℃`,label:percent=>(scale.base+(scale.full-scale.base)*percent/100).toFixed(1)};
  const factor=['rm','voltage'].includes(s.key)||s.key==='current'&&scale.full>=1000?1000:1;
  const unit=s.key==='rm'?'Ah':s.key==='voltage'?'V':s.key==='current'?(factor===1000?'A':'mA'):s.unit;
  const digits=s.key==='pct'?0:s.key==='current'&&factor===1?1:2;
  return{title:`${s.axis} ${unit}`,label:percent=>((scale.base+(scale.full-scale.base)*percent/100)/factor).toFixed(digits)};
}
export function timeLabel(ms){
  const whole=Math.max(0,Math.floor(ms/1000)),h=Math.floor(whole/3600),m=Math.floor(whole%3600/60),s=whole%60;
  const label=h?`${h}:${String(m).padStart(2,'0')}:${String(s).padStart(2,'0')}`:`${m}:${String(s).padStart(2,'0')}`;
  return label;
}
export function elapsedMinuteLabel(ms){
  const minutes=Math.max(0,Math.floor(ms/60000));
  return `${Math.floor(minutes/60)}:${String(minutes%60).padStart(2,'0')}`;
}
export function systemTimeLabel(wall,precise=false){
  if(!valid(wall))return'—';const date=new Date(wall);if(!Number.isFinite(date.getTime()))return'—';
  const pad=value=>String(value).padStart(2,'0'),clock=`${pad(date.getHours())}:${pad(date.getMinutes())}`;
  return precise?`${pad(date.getMonth()+1)}/${pad(date.getDate())} ${clock}:${pad(date.getSeconds())}`:clock;
}
export function sampleSystemWall(row,kind,wallByX){
  const wall=kind==='performance'?wallByX?.get(row[0]):row[13];
  return valid(wall)&&wall>=0?wall:null;
}
export function chartSystemWall(points,startWall,x,kind='discharge',wallByX){
  let lo=0,hi=points.length;
  while(lo<hi){const mid=(lo+hi)>>1;if(points[mid][0]<=x)lo=mid+1;else hi=mid;}
  const anchor=points[Math.max(0,lo-1)],wall=anchor?sampleSystemWall(anchor,kind,wallByX):null;
  return wall!==null?wall+(x-anchor[0]):valid(startWall)?startWall+x:null;
}
// Align discharge/game grids to local clock boundaries. Performance timestamps
// live separately from metric columns, so never interpret a metric as a date.
export function wallTimeTicks(points,startWall,window,width,kind='discharge',wallByX){
  const [from,to]=window,span=to-from;if(!(span>0)||!Number.isFinite(span))return[];
  const first=chartSystemWall(points,startWall,from,kind,wallByX);if(first===null)return[];
  const segments=[];let begin=from,offset=first-from;
  for(const row of windowPoints(points,from,to)){
    const x=row[0];if(x<=from)continue;if(x>=to)break;
    const wall=sampleSystemWall(row,kind,wallByX);
    const next=wall!==null?wall-x:valid(startWall)?startWall:null;
    // Sub-second wall/monotonic clock read jitter is irrelevant to minute ticks.
    // Collapsing it avoids constructing a segment for every fast sample.
    if(next===null||Math.abs(next-offset)<1000)continue;
    segments.push([begin,x,offset]);begin=x;offset=next;
  }
  segments.push([begin,to,offset]);
  return selectTimeTicks(segments,window,width,true);
}
export function elapsedTimeTicks(window,width){
  return selectTimeTicks([[window[0],window[1],0]],window,width,false);
}
export function timeAxisLabels(start,end,ticks,width,right,measure,startX=3){
  const edge=width-right,startRight=startX+measure(start),labels=[{text:start,x:startX,align:'left',dy:0}];
  const grid=[];
  for(const tick of ticks){
    const half=measure(tick.text)/2,x=Math.max(3+half,Math.min(edge-half,tick.x));
    if(x-half<startRight+4)continue;
    const label={text:tick.text,x,align:'center',dy:0};labels.push(label);grid.push(label);
  }
  const last=grid.at(-1),overlap=last&&last.x+measure(last.text)/2+4>width-3-measure(end);
  if(overlap){
    // Two baselines fit inside the existing 25px axis gutter: no plot resize.
    last.dy=-6;
    labels.push({text:end,x:width-3,align:'right',dy:6});
  }else labels.push({text:end,x:width-3,align:'right',dy:0});
  return labels;
}
function selectTimeTicks(segments,[from,to],width,localClock){
  const span=to-from;if(!(span>0)||!Number.isFinite(span))return[];
  const minutes=[5,10,15,20,30,60,120,180,240,360,720,1440];
  while(minutes.at(-1)*60000<span*2)minutes.push(minutes.at(-1)*2);
  const maxLabels=Math.max(1,Math.min(6,Math.floor(width/50))),target=Math.min(4,maxLabels);
  let best=[],bestScore=Infinity;
  for(const minute of minutes){
    const step=minute*60000,ticks=[];
    for(const [a,b,shift] of segments){
      const wallStart=a+shift,wallEnd=b+shift;
      const zone=localClock?new Date(wallStart).getTimezoneOffset()*60000:0;
      let wall=Math.ceil((wallStart-zone)/step)*step+zone;
      for(;wall<wallEnd&&ticks.length<=maxLabels;wall+=step){
        const x=wall-shift;if(x>from&&x<to)ticks.push({x,wall});
      }
      if(ticks.length>maxLabels)break;
    }
    if(ticks.length>maxLabels)continue;
    const spacing=width/Math.max(1,span);
    if(ticks.some((tick,i)=>i&&(tick.x-ticks[i-1].x)*spacing<45))continue;
    const score=Math.abs(ticks.length-target)+(ticks.length<Math.min(3,maxLabels)?3:0);
    if(score<bestScore){best=ticks;bestScore=score;}
    if(score===0)break;
  }
  return best;
}
// Collapse quantized plateaus into temporal anchors, retaining endpoints
// and every actual rise/fall. Raw samples remain available for inspection.
export function plateauAnchors(points){
  if(points.length<3)return points;
  const groups=[];
  for(let start=0;start<points.length;){
    let end=start;while(end+1<points.length&&points[end+1][1]===points[start][1])end++;
    groups.push({start,end});start=end+1;
  }
  if(groups.length===1)return[points[0],points.at(-1)];
  return groups.map(({start,end},i)=>i===0?points[0]:i===groups.length-1?points.at(-1):[(points[start][0]+points[end][0])/2,points[start][1]]);
}
// Soften small reversals without moving the ends of a temperature plateau.
export function softenTemperature(points,noise){
  return points.map(([x,y],i)=>{
    if(i===0||i===points.length-1)return points[i];
    const [beforeX,before]=points[i-1],[afterX,after]=points[i+1];
    const left=before-y,right=after-y;
    if(left*right<=0||Math.max(Math.abs(left),Math.abs(right))>noise||afterX<=beforeX)return points[i];
    const estimate=before+(after-before)*(x-beforeX)/(afterX-beforeX);
    return [x,(y+estimate)/2];
  });
}
// Quantized paths use time-weighted monotone tangents after plateau consolidation.
export function smoothPath(ctx,points,quantized=false){
  if(quantized)points=plateauAnchors(points);
  if(!points.length)return;ctx.moveTo(...points[0]);if(points.length===1)return;
  const d=points.slice(1).map((p,i)=>(p[1]-points[i][1])/(p[0]-points[i][0]||1));
  const m=points.map((_,i)=>i===0?d[0]:i===points.length-1?d[d.length-1]:(d[i-1]*d[i]<=0?0:2*d[i-1]*d[i]/(d[i-1]+d[i])));
  if(quantized&&points.length>2){
    const h=points.slice(1).map((p,i)=>Math.max(1e-9,p[0]-points[i][0]));
    for(let i=1;i<m.length-1;i++){
      if(d[i-1]*d[i]<=0){m[i]=0;continue;}
      const a=2*h[i]+h[i-1],b=h[i]+2*h[i-1];
      m[i]=(a+b)/(a/d[i-1]+b/d[i]);
    }
    const edge=(a,b,ha,hb)=>{const value=((2*ha+hb)*a-ha*b)/(ha+hb);return value*a<=0?0:a*b<=0&&Math.abs(value)>3*Math.abs(a)?3*a:value;};
    m[0]=edge(d[0],d[1],h[0],h[1]);
    const last=d.length-1;m[m.length-1]=edge(d[last],d[last-1],h[last],h[last-1]);
  }
  for(let i=0;i<d.length;i++){
    if(d[i]===0){m[i]=m[i+1]=0;continue;}
    const a=m[i]/d[i],b=m[i+1]/d[i],norm=a*a+b*b;
    if(norm>9){const t=3/Math.sqrt(norm);m[i]=t*a*d[i];m[i+1]=t*b*d[i];}
  }
  for(let i=0;i<points.length-1;i++){
    const a=points[i],b=points[i+1],dx=(b[0]-a[0])/3;
    ctx.bezierCurveTo(a[0]+dx,a[1]+m[i]*dx,b[0]-dx,b[1]-m[i+1]*dx,...b);
  }
}
export function duration(ms){const s=Math.max(0,Math.round(ms/1000)),h=Math.floor(s/3600),m=Math.floor(s%3600/60);return h?`${h}小时${m}分`:m?`${m}分${s%60}秒`:`${s}秒`;}
function pointBound(points,x,after=false){
  let lo=0,hi=points.length;
  while(lo<hi){const mid=(lo+hi)>>>1,t=points[mid][0];if(t<x||after&&t===x)lo=mid+1;else hi=mid;}
  return lo;
}
export function windowPoints(points,from,to){
  return points.slice(Math.max(0,pointBound(points,from)-1),Math.min(points.length,pointBound(points,to,true)+1));
}

export class Chart{
  constructor(canvas,tooltip,onWindow){
    this.canvas=canvas;this.ctx=canvas.getContext('2d');this.tooltip=tooltip;this.onWindow=onWindow;
    this.markerLine=document.createElement('span');this.markerLine.className='plot-marker';this.markerLine.hidden=true;
    this.markerLine.setAttribute('aria-hidden','true');canvas.after(this.markerLine);
    this.points=[];this.enabled=new Set(['rm','pct']);this.window=[0,1];this.total=1;this.pointers=new Map();this.marker=null;this.kind='discharge';this.ranges={};this.axisKey=null;this.cellCount=2;
    this.scaleCache=new Map();this.scaleSettings=scaleSettingsKey();this.recordId=null;this.screenOffSpans=[];this.startWall=null;this.wallByX=new Map();
    new ResizeObserver(()=>this.draw()).observe(canvas.parentElement);
    globalThis.visualViewport?.addEventListener('resize',()=>this.draw());
    canvas.addEventListener('pointerdown',e=>{canvas.setPointerCapture(e.pointerId);const p=this.local(e);this.pointers.set(e.pointerId,p);this.gesture=this.capture();this.moved=false;this.dragMarker=this.pointers.size===1&&this.marker!==null&&Math.abs(p[0]-this.markerX())<=14?e.pointerId:null;this.beginInspectionHold(e.pointerId,p);});
    canvas.addEventListener('pointermove',e=>{const hold=this.inspectionHold;if(hold&&!hold.active&&Math.hypot(...this.local(e).map((v,i)=>v-hold.point[i]))>14){if(Date.now()-hold.started>=180)this.activateInspectionHold(hold);else this.cancelInspectionHold();}this.move(e);if(this.inspectionHold?.active&&e.cancelable)e.preventDefault();},{passive:false});
    const end=e=>{this.cancelInspectionHold();const dragging=this.dragMarker===e.pointerId;if(!this.moved)this.inspect(...this.local(e));this.pointers.delete(e.pointerId);this.gesture=this.capture();this.dragMarker=null;if(this.moved&&!dragging)this.onWindow?.(this.window);};
    canvas.addEventListener('pointerup',end);canvas.addEventListener('pointercancel',e=>{const hold=this.inspectionHold;if(hold?.id===e.pointerId&&hold.active&&hold.touchId!==undefined)return;this.cancelInspectionHold();this.pointers.delete(e.pointerId);this.gesture=this.capture();this.dragMarker=null;});
    canvas.addEventListener('wheel',e=>{if(!canvas.closest('.expanded')&&!e.ctrlKey&&!e.metaKey)return;e.preventDefault();this.zoom(e.deltaY>0?1.25:.8,(this.local(e)[0]-this.left)/(this.width-this.left-this.right));this.onWindow?.(this.window);},{passive:false});
    canvas.addEventListener('touchstart',e=>{if(e.touches.length===1&&this.inspectionHold)this.inspectionHold.touchId=e.touches[0].identifier;},{passive:true});
    canvas.addEventListener('touchmove',e=>{
      const hold=this.inspectionHold;
      if(hold?.active&&e.touches.length===1){const touch=Array.from(e.touches).find(item=>item.identifier===hold.touchId);if(touch){if(e.cancelable)e.preventDefault();const p=this.local(touch);this.pointers.set(hold.id,p);this.inspect(...p,true);}return;}
      if(e.touches.length>1&&e.cancelable)e.preventDefault();
    },{passive:false});
    for(const name of ['touchend','touchcancel'])canvas.addEventListener(name,e=>{const hold=this.inspectionHold;if(hold&&Array.from(e.changedTouches).some(item=>item.identifier===hold.touchId)){this.pointers.delete(hold.id);this.gesture=this.capture();this.dragMarker=null;this.cancelInspectionHold();}},{passive:true});
    document.addEventListener('visibilitychange',()=>{if(document.hidden){this.cancelInspectionHold();this.pointers.clear();this.dragMarker=null;}});
    document.addEventListener('pointerdown',e=>{if(e.target!==canvas&&!tooltip.contains(e.target))this.clearInspect();});
  }
  cancelInspectionHold(){clearTimeout(this.inspectionTimer);this.inspectionTimer=null;this.inspectionHold=null;}
  beginInspectionHold(id,point){
    this.cancelInspectionHold();if(this.pointers.size!==1)return;
    const hold={id,point,active:false,started:Date.now()};this.inspectionHold=hold;
    this.inspectionTimer=setTimeout(()=>this.activateInspectionHold(hold),350);
  }
  activateInspectionHold(hold){
    if(this.inspectionHold!==hold||!this.pointers.has(hold.id)||this.pointers.size!==1)return;
    clearTimeout(this.inspectionTimer);this.inspectionTimer=null;
    hold.active=true;this.dragMarker=hold.id;this.moved=true;this.onInteract?.();this.inspect(...this.pointers.get(hold.id),true);
  }
  local(e){const r=this.canvas.getBoundingClientRect();if(this.canvas.closest('.rotate'))return[e.clientY-r.top,r.right-e.clientX];return[e.clientX-r.left,e.clientY-r.top];}
  signals(){return seriesFor(this.kind,this.cellCount,this.fast);}
  timeAxis(width){
    const wall=this.kind==='discharge'||this.kind==='performance';
    const label=x=>wall?systemTimeLabel(chartSystemWall(this.points,this.startWall,x,this.kind,this.wallByX)):elapsedMinuteLabel(x);
    return{label,ticks:(wall?wallTimeTicks(this.points,this.startWall,this.window,width,this.kind,this.wallByX):elapsedTimeTicks(this.window,width)).map(t=>({...t,text:wall?systemTimeLabel(t.wall):elapsedMinuteLabel(t.x)}))};
  }
  axisMaximum(){return 110;}
  preparePoints(points){return plotPoints(points,this.kind,this.cellCount,this.fast);}
  scaleFor(s){
    const settings=scaleSettingsKey();if(settings!==this.scaleSettings){this.scaleCache.clear();this.scaleSettings=settings;}
    if(s.key==='ram'){
      const totals=this.ranges.ramTotal??this.points.map(p=>p[15]);let full=0;for(const total of totals)if(valid(total)&&total>0)full=Math.max(full,total);
      return{base:0,full:full||1,min:0,label:full?`${full.toFixed(2)} GB`:'暂无数据'};
    }
    const range=this.ranges[s.key],factor=(this.cellCount===2?2:1)*(this.kind==='discharge'&&this.fast?-1:1);
    const values=range?(s.key==='current'?range.map(v=>valid(v)?v*factor:v):range):this.points.map(p=>p[s.index]).filter(valid);
    const scale=signalScale(s,values,this.kind,this.fast,this.scaleCache.get(s.key));
    if(values.some(valid))this.scaleCache.set(s.key,scale);
    return scale;
  }
  markerX(){return this.left+(this.marker-this.window[0])/(this.window[1]-this.window[0])*(this.width-this.left-this.right);}
  clearInspect(){this.cancelInspectionHold();this.dragMarker=null;this.inspectionTarget=null;if(this.marker===null&&this.tooltip.hidden)return;this.marker=null;this.tooltip.hidden=true;this.onInspect?.(null);this.refreshInspection();}
  refreshInspection(){
    this.markerLine.hidden=this.marker===null;
    if(this.marker!==null){
      this.markerLine.style.transform=`translateX(${this.markerX()}px)`;
      this.positionTooltip();
    }
  }
  positionTooltip(){
    if(this.marker===null||this.tooltip.hidden)return;
    const w=this.canvas.clientWidth,h=this.canvas.clientHeight,tw=this.tooltip.offsetWidth,th=this.tooltip.offsetHeight,x=this.markerX();
    this.tooltip.style.left=`${Math.max(6,Math.min(w-tw-6,x+12+tw>w-6?x-tw-12:x+12))}px`;
    this.tooltip.style.top=`${Math.max(6,Math.min(h-th-6,(this.inspectY??22)-th/3))}px`;
  }
  capture(){return{points:[...this.pointers.values()],window:[...this.window]};}
  set(points,total,window,options={}){
    const kind=options.kind??this.kind,fast=!!options.fast,cells=options.cellCount??2;
    if(kind!==this.kind||fast!==this.fast||cells!==this.cellCount||options.recordId!==undefined&&options.recordId!==this.recordId)this.scaleCache.clear();
    if(options.recordId!==undefined){if(options.recordId!==this.recordId){this.screenOffSpans=[];this.wallByX?.clear();}this.recordId=options.recordId;}
    this.startWall=valid(options.startWall)?options.startWall:null;
    this.kind=kind;this.fast=fast;this.cellCount=cells;this.points=this.preparePoints(points);this.total=Math.max(1,total);this.window=window??[0,this.total];this.ranges=options.ranges??this.ranges;
    if(!options.live||this.marker<this.window[0]||this.marker>this.window[1]){this.inspectionTarget=null;this.marker=null;this.tooltip.hidden=true;this.onInspect?.(null);}this.draw();
  }
  setWindow(a,b){const span=Math.max(Math.min(this.kind==='charge'||this.fast?1000:60000,this.total),Math.min(this.total,b-a));a=Math.max(0,Math.min(this.total-span,a));this.window=[a,a+span];this.inspectionTarget=null;this.marker=null;this.tooltip.hidden=true;this.onInspect?.(null);this.draw();}
  zoom(factor,anchor=.5){anchor=Math.max(0,Math.min(1,anchor));const[a,b]=this.window,span=(b-a)*factor;this.setWindow(a+(b-a)*anchor-span*anchor,a+(b-a)*anchor+span*(1-anchor));}
  move(e){
    if(!this.pointers.has(e.pointerId))return;this.pointers.set(e.pointerId,this.local(e));
    const p=[...this.pointers.values()],g=this.gesture;if(!g?.points.length)return;
    if(p.length===1&&this.dragMarker===e.pointerId){
      if(Math.abs(p[0][0]-g.points[0][0])<=3&&!this.moved)return;
      if(!this.moved)this.onInteract?.();this.moved=true;this.inspect(...p[0],true);return;
    }
    if(p.length===1&&g.points.length===1&&!this.moved&&!this.canvas.closest('.expanded')
      &&e.pointerType==='touch'&&Math.abs(p[0][1]-g.points[0][1])>Math.abs(p[0][0]-g.points[0][0]))return;
    if(p.length===1&&g.points.length===1){const dx=p[0][0]-g.points[0][0];if(Math.abs(dx)<=5&&!this.moved)return;if(!this.moved)this.onInteract?.();this.moved=true;const shift=dx/(this.width-this.left-this.right)*(g.window[1]-g.window[0]);this.setWindow(g.window[0]-shift,g.window[1]-shift);}
    if(p.length===2&&g.points.length===2){if(!this.moved)this.onInteract?.();this.moved=true;const old=Math.abs(g.points[0][0]-g.points[1][0]),now=Math.abs(p[0][0]-p[1][0]);if(old<5||now<5)return;const mid=(g.points[0][0]+g.points[1][0])/2,ratio=Math.max(0,Math.min(1,(mid-this.left)/(this.width-this.left-this.right))),anchor=g.window[0]+ratio*(g.window[1]-g.window[0]),span=(g.window[1]-g.window[0])*old/now;this.setWindow(anchor-ratio*span,anchor+(1-ratio)*span);}
  }
  hitCurve(pixel,y,visible){
    if(pixel<this.left||pixel>this.width-this.right||y<this.top-2||y>this.bottom+2)return false;
    const connected=this.kind==='discharge'&&!this.fast,ctx=this.ctx;
    ctx.save();
    try{
      // Pointer coordinates are CSS pixels, independent of the display density.
      ctx.setTransform(1,0,0,1,0,0);ctx.lineWidth=26;ctx.lineCap='round';ctx.lineJoin='round';ctx.setLineDash([]);
      for(const s of this.signals().filter(s=>this.enabled.has(s.key))){
        let segment=[];const scale=this.scaleFor(s);
        const hit=()=>{
          if(!segment.length)return false;
          if(segment.length===1)return Math.hypot(pixel-segment[0][0],y-segment[0][1])<=13;
          ctx.beginPath();this.traceSignal(ctx,segment,s,scale);return ctx.isPointInStroke(pixel,y);
        };
        for(const row of visible){
          if(!valid(row[s.index])||!connected&&(row[6]&3)){
            if(hit())return true;segment=[];
            if(!valid(row[s.index]))continue;
          }
          segment.push(this.pointPixel(row,s,scale));
        }
        if(hit())return true;
      }
      return false;
    }finally{ctx.restore();}
  }
  traceSignal(ctx,points,signal,scale){
    if(signal.unit==='℃'){
      const pixelsPerDegree=100*(this.bottom-this.top)/((this.axisHi-this.axisLo)*(scale.full-scale.base));
      points=softenTemperature(points,0.3*pixelsPerDegree);
    }
    smoothPath(ctx,points,signal.key==='pct');
  }
  pointPixel(row,s,scale){return[this.left+(row[0]-this.window[0])/(this.window[1]-this.window[0])*(this.width-this.left-this.right),this.bottom-(scalePercent(row[s.index],scale)-this.axisLo)/((this.axisHi??100)-this.axisLo)*(this.bottom-this.top)];}
  inspect(pixel,y,force=false){
    const start=pointBound(this.points,this.window[0]),end=pointBound(this.points,this.window[1],true);if(start>=end)return;
    if(!force&&!this.hitCurve(pixel,y,this.points.slice(start,end))){this.clearInspect();return;}
    const x=this.window[0]+(pixel-this.left)/(this.width-this.left-this.right)*(this.window[1]-this.window[0]);
    if(this.readRawInspection){
      const from=Math.ceil(this.window[0]),to=Math.floor(this.window[1]),target=Math.max(from,Math.min(to,Math.round(x)));
      const old=this.inspectionTarget;
      if(old?.target===target&&old.from===from&&old.to===to){this.inspectY=y;this.positionTooltip();return;}
      this.inspectionTarget={target,from,to,read:this.readRawInspection};
      this.marker=target;this.inspectY=y;this.tooltip.textContent='正在读取采样点…';this.tooltip.hidden=false;this.refreshInspection();
      if(!this.inspectionBusy)this.resolveRawInspection();
      return;
    }
    const right=Math.min(end-1,Math.max(start,pointBound(this.points,x))),left=Math.max(start,right-1);
    const a=this.points[left],b=this.points[right],row=x-a[0]<=b[0]-x?a:b;
    this.showInspection(row,y);
  }
  async resolveRawInspection(){
    this.inspectionBusy=true;
    try{
      let pending;
      while(this.inspectionTarget&&pending!==this.inspectionTarget){
        pending=this.inspectionTarget;
        let row,failed=false;
        try{row=await pending.read(pending.from,pending.to,pending.target);}catch{failed=true;}
        if(pending!==this.inspectionTarget)continue;
        if(row)this.showInspection(this.preparePoints([row])[0],this.inspectY);
        else{
          if(failed)this.inspectionTarget=null;
          this.tooltip.textContent=failed?'采样点读取失败':'该区间暂无采样点';this.positionTooltip();
        }
      }
    }finally{this.inspectionBusy=false;}
  }
  showInspection(row,y){
    this.marker=row[0];this.inspectY=y;
    const recordedWall=sampleSystemWall(row,this.kind,this.wallByX),wall=recordedWall??(valid(this.startWall)?this.startWall+row[0]:null);
    this.tooltip.textContent=`时间  ${systemTimeLabel(wall,true)}\n计时  ${timeLabel(row[0])}${row[6]&8?' · 后台衔接':row[6]&~unclassifiedCells?' · 数据异常':''}\n`+
      (this.kind==='discharge'&&valid(row[7])?`区间  ${duration(row[7])}\n`:'')+
      this.signals().filter(s=>this.enabled.has(s.key)).map(s=>{
        const voltage=this.kind==='discharge'&&s.key==='voltage',value=voltage&&valid(row[s.index])?row[s.index]/1000:row[s.index];
        if(this.kind==='discharge'&&this.fast&&(s.key==='current'||s.key==='power'))return`${s.name}  ${gameSampleText(value,s.key,s.key==='current'?0:2)} ${s.unit}`;
        return`${s.name}  ${s.key==='temp'?(valid(value)?value.toFixed(1):'缺失'):s.key==='power'&&this.kind==='discharge'?(valid(value)?value.toFixed(2):'缺失'):s.key==='energy'?(valid(value)?value.toFixed(value<2?2:1):'缺失'):sampleText(value,voltage?3:s.digits)}${s.key==='rm'?'':' '+(voltage?'V':s.unit)}`;
      }).join('\n')+
      (this.cellCount===2&&this.enabled.has('voltage')?[[8,'电芯 1'],[9,'电芯 2']].filter(([i])=>valid(row[i])).map(([i,name])=>`\n${name}  ${(row[i]/1000).toFixed(3)} V`).join(''):'');this.tooltip.hidden=false;this.onInspect?.(row);this.refreshInspection();
  }
  drawScreenOff(ctx,top,height,width){
    if(document.hidden||this.kind!=='discharge'||!this.enabled.has('screenOff'))return;
    const [from,to]=this.window;if(to<=from)return;
    ctx.save();ctx.fillStyle=cssColor('--plot-screen-off','rgba(112,119,128,.10)');
    for(const [start,end] of this.screenOffSpans){
      if(!this.fast&&end-start<chartSettings.screenOnMinutes*60000)continue;
      if(end<=from)continue;if(start>=to)break;
      const a=Math.max(from,start),b=Math.min(to,end);if(b<=a)continue;
      ctx.fillRect(this.left+(a-from)/(to-from)*width,top,(b-a)/(to-from)*width,height);
    }
    ctx.restore();
  }
  draw(){
    const w=this.canvas.clientWidth,h=this.canvas.clientHeight;if(!w||!h)return;
    const ratio=(devicePixelRatio||1)*(globalThis.visualViewport?.scale||1),width=Math.round(w*ratio),height=Math.round(h*ratio);
    if(this.canvas.width!==width)this.canvas.width=width;
    if(this.canvas.height!==height)this.canvas.height=height;
    const ctx=this.ctx;ctx.setTransform(ratio,0,0,ratio,0,0);ctx.clearRect(0,0,w,h);this.width=w;
    const selected=this.signals().filter(s=>this.enabled.has(s.key));
    const scales=new Map(this.signals().map(s=>[s.key,this.scaleFor(s)]));
    const hi=this.axisMaximum(),lo=plotAxisMinimum(this.kind,[...scales.values()],this.reversePlot,hi);
    const axisSeries=this.signals().find(s=>s.key===this.axisKey);
    const axis=axisSeries?actualAxis(axisSeries,scales.get(axisSeries.key)):null;
    this.canvas.dataset.axis=axisSeries?.key??'percent';
    const tickValues=[];for(let v=lo;v<=hi;v+=10)tickValues.push(v);
    const tickLabel=v=>axis?axis.label(v):String(v);
    // Reserve the same gutter before/after expanding the charge plot.
    const gutterTicks=['charge','discharge'].includes(this.kind)?Array.from({length:23},(_,i)=>i*10-110):tickValues;
    ctx.font='10px system-ui';this.left=Math.max(34,Math.ceil(Math.max(...gutterTicks.map(v=>ctx.measureText(tickLabel(v)).width)))+9);this.right=8;
    const top=22,bottom=h-25,pw=w-this.left-this.right,ph=bottom-top;if(pw<=0||ph<=0)return;
    this.top=top;this.bottom=bottom;this.axisLo=lo;this.axisHi=hi;
    this.markerLine.style.top=`${top}px`;this.markerLine.style.height=`${ph}px`;
    const mapY=value=>bottom-(value-lo)/(hi-lo)*ph;
    const visible=windowPoints(this.points,...this.window);
    ctx.font='10px system-ui';ctx.lineWidth=.65;ctx.fillStyle=cssColor('--plot-bg','#ffffff');ctx.fillRect(this.left,top,pw,ph);
    this.drawScreenOff(ctx,top,ph,pw);
    ctx.fillStyle=axisSeries?.color??cssColor('--plot-muted','#607185');ctx.textAlign='left';ctx.fillText(axis?.title??'量程 %',3,12);
    for(const value of tickValues){
      const y=mapY(value),major=value===0||Math.abs(value)===100,middle=Math.abs(Math.abs(value)-50)<1e-6;
      ctx.strokeStyle=major?cssColor('--plot-major','#8493a6'):middle?cssColor('--plot-middle','#a9b8c9'):cssColor('--plot-minor','#e5eaf0');ctx.lineWidth=major?.9:middle?.8:.55;ctx.beginPath();ctx.moveTo(this.left,y);ctx.lineTo(w-this.right,y);ctx.stroke();
      ctx.fillStyle=major?cssColor('--plot-label-major','#243b57'):middle?cssColor('--plot-label-middle','#40566e'):cssColor('--plot-label','#607185');ctx.font=major?'700 10px system-ui':middle?'600 10px system-ui':'10px system-ui';ctx.textAlign='right';ctx.fillText(tickLabel(value),this.left-6,y+3);
      ctx.strokeStyle=cssColor('--plot-border','#95a2b1');ctx.beginPath();ctx.moveTo(this.left-3,y);ctx.lineTo(this.left,y);ctx.stroke();
    }
    ctx.font='10px system-ui';ctx.lineWidth=.65;
    {
      const{label,ticks}=this.timeAxis(pw);
      const gridLabels=[];
      for(const tick of ticks){
        const x=this.left+pw*(tick.x-this.window[0])/(this.window[1]-this.window[0]);
        ctx.strokeStyle=cssColor('--plot-timegrid','#edf0f4');ctx.beginPath();ctx.moveTo(x,top);ctx.lineTo(x,bottom);ctx.stroke();
        ctx.strokeStyle=cssColor('--plot-border','#95a2b1');ctx.beginPath();ctx.moveTo(x,bottom);ctx.lineTo(x,bottom+4);ctx.stroke();
        gridLabels.push({x,text:tick.text});
      }
      ctx.fillStyle=cssColor('--plot-label','#526172');
      for(const text of timeAxisLabels(label(this.window[0]),label(this.window[1]),gridLabels,w,this.right,value=>ctx.measureText(value).width,this.kind==='charge'?this.left:3)){
        ctx.textAlign=text.align;ctx.fillText(text.text,text.x,bottom+16+text.dy);
      }
    }
    ctx.strokeStyle=cssColor('--plot-border','#9aa7b6');ctx.lineWidth=.85;
    ctx.beginPath();ctx.moveTo(this.left,top);ctx.lineTo(this.left,bottom);ctx.moveTo(w-this.right,bottom);ctx.lineTo(w-this.right,top);ctx.stroke();
    if(lo!==-110){ctx.beginPath();ctx.moveTo(this.left,bottom);ctx.lineTo(w-this.right,bottom);ctx.stroke();}
    // The 110% grid line retains the same weight as other minor grid lines.
    if(hi!==110){ctx.beginPath();ctx.moveTo(this.left,top);ctx.lineTo(w-this.right,top);ctx.stroke();}
    const connected=this.kind==='discharge'&&!this.fast;
    selected.forEach(s=>{
      const scale=scales.get(s.key)??this.scaleFor(s);
      const map=p=>[this.left+(p[0]-this.window[0])/(this.window[1]-this.window[0])*pw,mapY(scalePercent(p[s.index],scale))];
      const special=this.kind==='performance'||s.key==='power'||s.key==='current';
      // Leave the full stroke visible at 0% and the top boundary, above the axis border.
      ctx.save();ctx.beginPath();ctx.rect(this.left,top-2,pw,ph+4);ctx.clip();ctx.strokeStyle=s.color;ctx.lineWidth=special?.75:.9;ctx.globalAlpha=special?.7:.86;ctx.lineJoin='miter';ctx.lineCap='round';
      let segment=[];const flush=()=>{
        if(!segment.length)return;ctx.beginPath();
        this.traceSignal(ctx,segment,s,scale);
        ctx.stroke();if(segment.length===1){ctx.beginPath();ctx.arc(...segment[0],1.5,0,Math.PI*2);ctx.fillStyle=s.color;ctx.fill();}segment=[];
      };
      for(const p of visible){if(!valid(p[s.index])){flush();continue;}if(!connected&&(p[6]&3))flush();segment.push(map(p));}flush();ctx.restore();
    });
    if(!visible.length||!selected.length){ctx.fillStyle=cssColor('--plot-label','#64748b');ctx.textAlign='center';ctx.font='12px system-ui';ctx.fillText(selected.length?'该区间暂无数据':'勾选上方指标显示曲线',this.left+pw/2,top+ph/2);}
    this.refreshInspection();
  }
}
