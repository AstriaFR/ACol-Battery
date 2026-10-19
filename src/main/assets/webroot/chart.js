import{DEFAULT_SETTINGS}from'./config.js?v=acol-1.3.1';
import{batteryColors}from'./curve-colors.js?v=acol-1.3.1';
let chartSettings={...DEFAULT_SETTINGS};
export function setChartSettings(value){chartSettings={...DEFAULT_SETTINGS,...value};}
const scaleSettingsKey=()=>JSON.stringify([chartSettings.capacityAh,chartSettings.capacityAuto,chartSettings.voltageMin,chartSettings.voltageMax,chartSettings.tempMin,chartSettings.tempMax]);
const reachesHeadroom=(peak,base,full)=>Number.isFinite(peak)&&full>base
  &&peak-base>=(full-base)*1.1-Math.max(1,Math.abs(full-base))*1e-12;
export const series=[
  {key:'rm',index:1,name:'剩余容量',axis:'容量',unit:'mAh',color:batteryColors.rm,digits:0},
  {key:'pct',index:2,name:'系统电量',axis:'电量',unit:'%',color:batteryColors.pct,digits:0},
  {key:'current',index:3,name:'充电电流',axis:'电流',unit:'mA',color:batteryColors.current,digits:0},
  {key:'voltage',index:4,name:'电芯均压',axis:'电压',unit:'mV',color:batteryColors.voltage,digits:1},
  {key:'power',index:5,name:'实时功率',axis:'功率',unit:'W',color:batteryColors.power,digits:6},
  {key:'temp',index:10,name:'电池温度',axis:'温度',unit:'℃',color:batteryColors.temp,digits:1}
];
export const valid=v=>typeof v==='number'&&Number.isFinite(v);
export function seriesFor(kind,cellCount=2,fast=false){return series.filter(s=>kind==='charge'||!['current','temp'].includes(s.key)).map(s=>
  kind==='discharge'&&s.key==='power'?{...s,name:fast?'实时功耗':'平均功耗',axis:'功耗',digits:2}:s.key==='voltage'&&cellCount!==2?{...s,name:'电池电压'}:s);}
export function plotPoints(points,kind,cellCount=2,fast=false){
  if(kind!=='discharge')return points.map(p=>{if(!valid(p[3]))return p;const row=[...p];row[3]*=cellCount===2?2:1;return row;});
  if(fast)return points.map(p=>{if(valid(p[5])&&p[5]<0){const row=[...p];row[5]=null;return row;}return p;});
  return points.filter(p=>!valid(p[7])||p[7]>=10000||(p[6]&8)!==0).map(p=>{
    if(valid(p[5])&&(p[5]>15||p[5]<0)){const row=[...p];row[5]=null;return row;}
    return p;
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
export function signalScale(s,values,kind='charge',fast=false,previous=null){
  const known=values.filter(valid);
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
    let peak=0;for(const value of known)if(value>=0&&(fast||value<=15))peak=Math.max(peak,value);
    const observed=peak>0||!!previous?.observed;
    const full=Math.max(previous?.observed?previous.full:0,peak)||1;
    return{base:0,full,min:0,observed,label:observed?`${full.toFixed(2)} W`:'暂无峰值'};
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
export function actualAxis(s,scale){
  if(s.key==='temp')return{title:'温度 ℃',label:percent=>(scale.base+(scale.full-scale.base)*percent/100).toFixed(1)};
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
// Discharge only: shape-preserving interpolation. Charging always uses lineTo.
export function smoothPath(ctx,points){
  if(!points.length)return;ctx.moveTo(...points[0]);if(points.length===1)return;
  const d=points.slice(1).map((p,i)=>(p[1]-points[i][1])/(p[0]-points[i][0]||1));
  const m=points.map((_,i)=>i===0?d[0]:i===points.length-1?d[d.length-1]:(d[i-1]*d[i]<=0?0:2*d[i-1]*d[i]/(d[i-1]+d[i])));
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
export function windowPoints(points,from,to){
  let lo=0,hi=points.length;
  while(lo<hi){const mid=(lo+hi)>>>1;if(points[mid][0]<from)lo=mid+1;else hi=mid;}
  const start=Math.max(0,lo-1);hi=points.length;
  while(lo<hi){const mid=(lo+hi)>>>1;if(points[mid][0]<=to)lo=mid+1;else hi=mid;}
  return points.slice(start,Math.min(points.length,lo+1));
}

export class Chart{
  constructor(canvas,tooltip,onWindow){
    this.canvas=canvas;this.ctx=canvas.getContext('2d');this.tooltip=tooltip;this.onWindow=onWindow;
    this.points=[];this.enabled=new Set(['rm','pct']);this.window=[0,1];this.total=1;this.pointers=new Map();this.marker=null;this.kind='discharge';this.ranges={};this.axisKey=null;this.cellCount=2;
    this.scaleCache=new Map();this.scaleSettings=scaleSettingsKey();this.recordId=null;
    new ResizeObserver(()=>this.draw()).observe(canvas.parentElement);
    canvas.addEventListener('pointerdown',e=>{canvas.setPointerCapture(e.pointerId);const p=this.local(e);this.pointers.set(e.pointerId,p);this.gesture=this.capture();this.moved=false;this.dragMarker=this.pointers.size===1&&this.marker!==null&&Math.abs(p[0]-this.markerX())<=14?e.pointerId:null;});
    canvas.addEventListener('pointermove',e=>this.move(e));
    const end=e=>{const dragging=this.dragMarker===e.pointerId;if(!this.moved)this.inspect(...this.local(e));this.pointers.delete(e.pointerId);this.gesture=this.capture();this.dragMarker=null;if(this.moved&&!dragging)this.onWindow?.(this.window);};
    canvas.addEventListener('pointerup',end);canvas.addEventListener('pointercancel',e=>{this.pointers.delete(e.pointerId);this.gesture=this.capture();});
    canvas.addEventListener('wheel',e=>{if(!canvas.closest('.expanded')&&!e.ctrlKey&&!e.metaKey)return;e.preventDefault();this.zoom(e.deltaY>0?1.25:.8,(this.local(e)[0]-this.left)/(this.width-this.left-this.right));this.onWindow?.(this.window);},{passive:false});
    canvas.addEventListener('touchmove',e=>{if(e.touches.length>1)e.preventDefault();},{passive:false});
    document.addEventListener('pointerdown',e=>{if(e.target!==canvas&&!tooltip.contains(e.target))this.clearInspect();});
  }
  local(e){const r=this.canvas.getBoundingClientRect();if(this.canvas.closest('.rotate'))return[e.clientY-r.top,r.right-e.clientX];return[e.clientX-r.left,e.clientY-r.top];}
  signals(){return seriesFor(this.kind,this.cellCount,this.fast);}
  axisMaximum(){return 110;}
  preparePoints(points){return plotPoints(points,this.kind,this.cellCount,this.fast);}
  scaleFor(s){
    const settings=scaleSettingsKey();if(settings!==this.scaleSettings){this.scaleCache.clear();this.scaleSettings=settings;}
    const range=this.ranges[s.key],values=range?(s.key==='current'?range.map(v=>valid(v)?v*(this.cellCount===2?2:1):v):range):this.points.map(p=>p[s.index]).filter(valid);
    const scale=signalScale(s,values,this.kind,this.fast,this.scaleCache.get(s.key));
    if(values.some(valid))this.scaleCache.set(s.key,scale);
    return scale;
  }
  markerX(){return this.left+(this.marker-this.window[0])/(this.window[1]-this.window[0])*(this.width-this.left-this.right);}
  clearInspect(){if(this.marker===null&&this.tooltip.hidden)return;this.marker=null;this.tooltip.hidden=true;this.draw();}
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
    if(options.recordId!==undefined)this.recordId=options.recordId;
    this.kind=kind;this.fast=fast;this.cellCount=cells;this.points=this.preparePoints(points);this.total=Math.max(1,total);this.window=window??[0,this.total];this.ranges=options.ranges??this.ranges;
    if(!options.live||this.marker<this.window[0]||this.marker>this.window[1]){this.marker=null;this.tooltip.hidden=true;}this.draw();
  }
  setWindow(a,b){const span=Math.max(Math.min(this.kind==='charge'||this.fast?1000:60000,this.total),Math.min(this.total,b-a));a=Math.max(0,Math.min(this.total-span,a));this.window=[a,a+span];this.marker=null;this.tooltip.hidden=true;this.draw();}
  zoom(factor,anchor=.5){anchor=Math.max(0,Math.min(1,anchor));const[a,b]=this.window,span=(b-a)*factor;this.setWindow(a+(b-a)*anchor-span*anchor,a+(b-a)*anchor+span*(1-anchor));}
  move(e){
    if(!this.pointers.has(e.pointerId))return;this.pointers.set(e.pointerId,this.local(e));
    const p=[...this.pointers.values()],g=this.gesture;if(!g?.points.length)return;
    if(p.length===1&&g.points.length===1&&!this.moved&&!this.canvas.closest('.expanded')
      &&e.pointerType==='touch'&&Math.abs(p[0][1]-g.points[0][1])>Math.abs(p[0][0]-g.points[0][0]))return;
    if(p.length===1&&this.dragMarker===e.pointerId){
      if(Math.abs(p[0][0]-g.points[0][0])<=3&&!this.moved)return;
      if(!this.moved)this.onInteract?.();this.moved=true;this.inspect(...p[0],true);return;
    }
    if(p.length===1&&g.points.length===1){const dx=p[0][0]-g.points[0][0];if(Math.abs(dx)<=5&&!this.moved)return;if(!this.moved)this.onInteract?.();this.moved=true;const shift=dx/(this.width-this.left-this.right)*(g.window[1]-g.window[0]);this.setWindow(g.window[0]-shift,g.window[1]-shift);}
    if(p.length===2&&g.points.length===2){if(!this.moved)this.onInteract?.();this.moved=true;const old=Math.abs(g.points[0][0]-g.points[1][0]),now=Math.abs(p[0][0]-p[1][0]);if(old<5||now<5)return;const mid=(g.points[0][0]+g.points[1][0])/2,ratio=Math.max(0,Math.min(1,(mid-this.left)/(this.width-this.left-this.right))),anchor=g.window[0]+ratio*(g.window[1]-g.window[0]),span=(g.window[1]-g.window[0])*old/now;this.setWindow(anchor-ratio*span,anchor+(1-ratio)*span);}
  }
  hitCurve(pixel,y,visible){
    if(pixel<this.left||pixel>this.width-this.right||y<this.top-2||y>this.bottom+2)return false;
    for(const s of this.signals().filter(s=>this.enabled.has(s.key))){
      let previous=null;const scale=this.scaleFor(s);
      for(const row of visible){
        if(!valid(row[s.index])){previous=null;continue;}
        const point=this.pointPixel(row,s,scale);
        if(Math.hypot(pixel-point[0],y-point[1])<=13)return true;
        if(previous&&!(row[6]&3)){
          const dx=point[0]-previous[0],dy=point[1]-previous[1],length=dx*dx+dy*dy;
          const t=length?Math.max(0,Math.min(1,((pixel-previous[0])*dx+(y-previous[1])*dy)/length)):0;
          if(Math.hypot(pixel-previous[0]-t*dx,y-previous[1]-t*dy)<=13)return true;
        }
        previous=point;
      }
    }return false;
  }
  pointPixel(row,s,scale){return[this.left+(row[0]-this.window[0])/(this.window[1]-this.window[0])*(this.width-this.left-this.right),this.bottom-(scalePercent(row[s.index],scale)-this.axisLo)/((this.axisHi??100)-this.axisLo)*(this.bottom-this.top)];}
  inspect(pixel,y,force=false){
    const visible=this.points.filter(p=>p[0]>=this.window[0]&&p[0]<=this.window[1]);if(!visible.length)return;
    if(!force&&!this.hitCurve(pixel,y,visible)){this.clearInspect();return;}
    const x=this.window[0]+(pixel-this.left)/(this.width-this.left-this.right)*(this.window[1]-this.window[0]);
    let row=visible[0];for(const p of visible)if(Math.abs(p[0]-x)<Math.abs(row[0]-x))row=p;
    this.marker=row[0];this.inspectY=y;
    this.tooltip.textContent=`时间  ${timeLabel(row[0])}${row[6]&8?' · 后台衔接':row[6]?' · 数据异常':''}\n`+
      (this.kind==='discharge'&&valid(row[7])?`区间  ${duration(row[7])}\n`:'')+
      this.signals().filter(s=>this.enabled.has(s.key)).map(s=>{
        const voltage=this.kind==='discharge'&&s.key==='voltage',value=voltage&&valid(row[s.index])?row[s.index]/1000:row[s.index];
        return`${s.name}  ${s.key==='temp'?(valid(value)?value.toFixed(1):'缺失'):s.key==='power'&&this.kind==='discharge'?(valid(value)?value.toFixed(2):'缺失'):sampleText(value,voltage?3:s.digits)}${s.key==='rm'?'':' '+(voltage?'V':s.unit)}`;
      }).join('\n')+
      (this.cellCount===2&&this.enabled.has('voltage')?[[8,'电芯 1'],[9,'电芯 2']].filter(([i])=>valid(row[i])).map(([i,name])=>`\n${name}  ${(row[i]/1000).toFixed(3)} V`).join(''):'');this.tooltip.hidden=false;this.draw();
  }
  draw(){
    const w=this.canvas.clientWidth,h=this.canvas.clientHeight;if(!w||!h)return;
    const ratio=devicePixelRatio||1;this.canvas.width=Math.round(w*ratio);this.canvas.height=Math.round(h*ratio);
    const ctx=this.ctx;ctx.setTransform(ratio,0,0,ratio,0,0);ctx.clearRect(0,0,w,h);this.width=w;
    const selected=this.signals().filter(s=>this.enabled.has(s.key));
    const scales=new Map(this.signals().map(s=>[s.key,this.scaleFor(s)]));
    const hi=this.axisMaximum(),lo=this.kind==='charge'&&this.reverseCharge?-hi:axisMinimum(this.kind,[...scales.values()]);
    const axisSeries=this.signals().find(s=>s.key===this.axisKey);
    const axis=axisSeries?actualAxis(axisSeries,scales.get(axisSeries.key)):null;
    this.canvas.dataset.axis=axisSeries?.key??'percent';
    const tickValues=[];for(let v=lo;v<=hi;v+=10)tickValues.push(v);
    const tickLabel=v=>axis?axis.label(v):String(v);
    // Reserve the same gutter before/after expanding the charge plot.
    const gutterTicks=this.kind==='charge'?Array.from({length:23},(_,i)=>i*10-110):tickValues;
    ctx.font='10px system-ui';this.left=Math.max(34,Math.ceil(Math.max(...gutterTicks.map(v=>ctx.measureText(tickLabel(v)).width)))+9);this.right=8;
    const top=22,bottom=h-25,pw=w-this.left-this.right,ph=bottom-top;if(pw<=0||ph<=0)return;
    this.top=top;this.bottom=bottom;this.axisLo=lo;this.axisHi=hi;
    const mapY=value=>bottom-(value-lo)/(hi-lo)*ph;
    const visible=windowPoints(this.points,...this.window);
    ctx.font='10px system-ui';ctx.lineWidth=.65;ctx.fillStyle='#ffffff';ctx.fillRect(this.left,top,pw,ph);
    ctx.fillStyle=axisSeries?.color??'#607185';ctx.textAlign='left';ctx.fillText(axis?.title??'量程 %',3,12);
    for(const value of tickValues){
      const y=mapY(value),major=value===0||Math.abs(value)===100,middle=Math.abs(Math.abs(value)-50)<1e-6;
      ctx.strokeStyle=major?'#8493a6':middle?'#a9b8c9':'#e5eaf0';ctx.lineWidth=major?.9:middle?.8:.55;ctx.beginPath();ctx.moveTo(this.left,y);ctx.lineTo(w-this.right,y);ctx.stroke();
      ctx.fillStyle=major?'#243b57':middle?'#40566e':'#607185';ctx.font=major?'700 10px system-ui':middle?'600 10px system-ui':'10px system-ui';ctx.textAlign='right';ctx.fillText(tickLabel(value),this.left-6,y+3);
      ctx.strokeStyle='#95a2b1';ctx.beginPath();ctx.moveTo(this.left-3,y);ctx.lineTo(this.left,y);ctx.stroke();
    }
    ctx.font='10px system-ui';ctx.lineWidth=.65;
    const timeTicks=Math.max(1,Math.min(pw<160?1:pw<320?2:4,Math.floor((this.window[1]-this.window[0])/1000)||1));
    for(let i=0;i<=timeTicks;i++){
      const x=this.left+pw*i/timeTicks;ctx.strokeStyle='#edf0f4';ctx.beginPath();ctx.moveTo(x,top);ctx.lineTo(x,bottom);ctx.stroke();
      ctx.strokeStyle='#95a2b1';ctx.beginPath();ctx.moveTo(x,bottom);ctx.lineTo(x,bottom+4);ctx.stroke();
      ctx.fillStyle='#526172';ctx.textAlign=i===0?'left':i===timeTicks?'right':'center';ctx.fillText(timeLabel(this.window[0]+(this.window[1]-this.window[0])*i/timeTicks),x,bottom+16);
    }
    ctx.strokeStyle='#9aa7b6';ctx.lineWidth=.85;
    ctx.beginPath();ctx.moveTo(this.left,top);ctx.lineTo(this.left,bottom);ctx.moveTo(w-this.right,bottom);ctx.lineTo(w-this.right,top);ctx.stroke();
    if(lo!==-110){ctx.beginPath();ctx.moveTo(this.left,bottom);ctx.lineTo(w-this.right,bottom);ctx.stroke();}
    // The 110% grid line retains the same weight as other minor grid lines.
    if(hi!==110){ctx.beginPath();ctx.moveTo(this.left,top);ctx.lineTo(w-this.right,top);ctx.stroke();}
    selected.forEach(s=>{
      const scale=scales.get(s.key)??this.scaleFor(s);
      const map=p=>[this.left+(p[0]-this.window[0])/(this.window[1]-this.window[0])*pw,mapY(scalePercent(p[s.index],scale))];
      // Leave the full stroke visible at 0% and the top boundary, above the axis border.
      ctx.save();ctx.beginPath();ctx.rect(this.left,top-2,pw,ph+4);ctx.clip();ctx.strokeStyle=s.color;ctx.lineWidth=1;ctx.lineJoin='miter';
      let segment=[];const flush=()=>{
        if(!segment.length)return;ctx.beginPath();
        if(this.kind==='charge'||this.kind==='performance'||s.key==='power'){ctx.moveTo(...segment[0]);for(let j=1;j<segment.length;j++)ctx.lineTo(...segment[j]);}
        else smoothPath(ctx,segment);
        ctx.stroke();if(segment.length===1){ctx.beginPath();ctx.arc(...segment[0],1.5,0,Math.PI*2);ctx.fillStyle=s.color;ctx.fill();}segment=[];
      };
      for(const p of visible){if(!valid(p[s.index])){flush();continue;}if(p[6]&3)flush();segment.push(map(p));}flush();ctx.restore();
    });
    if(this.marker!==null){const x=this.left+(this.marker-this.window[0])/(this.window[1]-this.window[0])*pw;ctx.strokeStyle='#405267';ctx.setLineDash([3,4]);ctx.beginPath();ctx.moveTo(x,top);ctx.lineTo(x,bottom);ctx.stroke();ctx.setLineDash([]);}
    if(!visible.length||!selected.length){ctx.fillStyle='#64748b';ctx.textAlign='center';ctx.font='12px system-ui';ctx.fillText(selected.length?'该区间暂无数据':'勾选上方指标显示曲线',this.left+pw/2,top+ph/2);}
    this.positionTooltip();
  }
}
