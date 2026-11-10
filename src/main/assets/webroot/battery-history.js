import{Chart}from'./chart.js?v=acol-2.2s';
import{bindExport,exportTime}from'./chart-export.js?v=acol-2.2s';
const finite=v=>typeof v==='number'&&Number.isFinite(v);
export const healthMetrics=[{key:'cycles',index:1,name:'循环次数',axis:'循环次数',unit:'次',color:'#5479c4',digits:0},{key:'qmax',index:2,name:'Qmax',axis:'Qmax',unit:'mAh',color:'#b47b35',digits:0},{key:'fcc',index:3,name:'满充容量',axis:'满充容量',unit:'mAh',color:'#26957d',digits:0}];
export function healthScale(values,step=100){
  const known=values.filter(finite);if(!known.length)return{base:0,full:1,min:0,label:'暂无数据'};
  let low=Infinity,high=-Infinity;for(const v of known){low=Math.min(low,v);high=Math.max(high,v);}
  let base=Math.max(0,Math.floor(low/step)*step),full=Math.ceil(high/step)*step;
  if(full===base){base=Math.max(0,base-step);full+=step;}
  return{base,full,min:base,label:`${base.toLocaleString()}–${full.toLocaleString()}`};
}
export async function loadHealthTrend(api,cancelled=()=>false){
  const items=[];let after=0,through=0;
  do{const data=await api('battery_history_trend',after,through);if(cancelled())return null;items.push(...data.items);through=data.through;
    if(!finite(data.nextAfter))break;if(data.nextAfter<=after)throw Error('历史记录分页未前进');after=data.nextAfter;
  }while(true);
  return items.filter(i=>finite(i.wall)).sort((a,b)=>a.wall-b.wall||a.id-b.id);
}
class HealthChart extends Chart{
  signals(){return healthMetrics;}
  axisMaximum(){return 100;}
  preparePoints(points){return points;}
  scaleFor(s){return this.healthScales?.get(s.key)??healthScale([]);}
  timeAxis(width){
    const date=x=>{const d=new Date(this.startWall+x);return`${d.getFullYear()}/${d.getMonth()+1}/${d.getDate()}`;};
    const count=Math.max(1,Math.min(4,Math.floor(width/90))),[a,b]=this.window;
    return{label:date,ticks:Array.from({length:count-1},(_,i)=>{const x=a+(b-a)*(i+1)/count;return{x,text:date(x)};})};
  }
  inspect(...args){
    super.inspect(...args);if(this.marker===null||this.tooltip.hidden)return;
    const row=this.points.find(p=>p[0]===this.marker);if(!row)return;
    this.tooltip.textContent=batteryHistoryDate(this.startWall+row[0])+ '\n'+this.signals().filter(s=>this.enabled.has(s.key)).map(s=>`${s.name}  ${number(row[s.index],s.unit)}`).join('\n');this.positionTooltip();
  }
}
const number=(v,unit='',digits=0)=>finite(v)?v.toLocaleString('zh-CN',{maximumFractionDigits:digits})+(unit?' '+unit:''):'—';
const ratio=(value,design)=>number(value,'mAh')+(finite(value)&&value>=0&&finite(design)&&design>0?` (${(value/design*100).toFixed(1)}%)`:'');
export function batteryHistoryDate(wall,dateOnly=false){
  if(!finite(wall))return'—';const d=new Date(wall),pad=v=>String(v).padStart(2,'0');
  const date=`${d.getFullYear()}/${pad(d.getMonth()+1)}/${pad(d.getDate())}`;
  return dateOnly?date:`${date} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
}
export function batteryHistoryFields(snapshot){
  const info=snapshot??{},green=new Set(Array.isArray(info.nearActual)?info.nearActual:[]),noCompensation=info.noCompensation===true;
  const locked=finite(info.lockedCapacity)&&finite(info.qmax)&&info.qmax>0?`${number(info.lockedCapacity,'mAh')} (${(-Math.abs(info.lockedCapacity)/info.qmax*100).toFixed(1)}%)`:'—';
  const values={qmax:ratio(info.qmax,info.designCapacity),fcc:ratio(info.fcc,info.designCapacity),rawFcc:noCompensation?'——':ratio(info.rawFcc,info.designCapacity),lockedCapacity:locked,soh:number(info.soh,'%'),rawSoh:noCompensation?'——':number(info.rawSoh,'%',2),cutoffMv:number(info.cutoffMv,'mV')};
  return{values,green};
}
export class BatteryHistoryPanel{
  constructor(api,navigate,back){
    this.back=back;
    this.api=api;this.navigate=navigate;this.active=false;this.token=0;this.items=new Map();this.snapshots=new Map();this.loaded=false;this.offset=0;this.total=0;this.loading=false;this.scrollTop=0;
    this.list=document.getElementById('batteryHistoryPanel');this.detail=document.getElementById('batteryHistoryDetail');this.rows=document.getElementById('batteryHistoryRows');this.scroller=document.getElementById('batteryHistoryScroll');this.more=document.getElementById('batteryHistoryMore');this.status=document.getElementById('batteryHistoryStatus');
    this.deleteDialog=document.getElementById('batteryHistoryDelete');this.deleteApply=document.getElementById('batteryHistoryDeleteApply');this.deleteCancel=document.getElementById('batteryHistoryDeleteCancel');
    this.deleteCancel.onclick=()=>this.deleteDialog.close();this.deleteApply.onclick=()=>this.remove();
    this.detailDelete=document.getElementById('batterySnapshotDelete');this.detailDelete.onclick=()=>this.askDelete(this.state?.id);
    this.recordButton=document.getElementById('batteryHistoryRecord');this.recordButton.onclick=()=>this.askRecord();
    this.trend=document.getElementById('batteryTrendPanel');this.trendItems=[];
    document.getElementById('batteryHistoryTrend').onclick=()=>this.navigate({page:'battery-history-trend'},true);
    document.getElementById('batteryTrendBack').onclick=()=>this.back();
    this.trendChart=new HealthChart(document.getElementById('batteryTrendPlot'),document.getElementById('batteryTrendTooltip'));
    this.trendChart.enabled=new Set(healthMetrics.map(s=>s.key));this.trendChart.kind='health';this.trendChart.fast=true;
    document.getElementById('batteryTrendReset').onclick=()=>this.trendChart.setWindow(0,this.trendChart.total);
    bindExport(document.getElementById('batteryTrendExport'),'电池健康趋势.csv',()=>this.trendCsv());
    this.recordDialog=document.getElementById('batteryHistoryRecordConfirm');this.recordCancel=document.getElementById('batteryHistoryRecordCancel');
    this.recordCancel.onclick=()=>this.recordDialog.close();
    document.getElementById('batteryHistoryRecordApply').onclick=()=>{if(!this.recordDialog.open)return;this.recordDialog.close();this.record();};
    this.deleteDialog.addEventListener('cancel',event=>{if(this.deleting)event.preventDefault();});
    let timer,start,held=false,moved=false;
    const stop=()=>{clearTimeout(timer);start=null;};this.stopPress=stop;
    this.rows.addEventListener('pointerdown',event=>{
      stop();held=moved=false;const row=event.target.closest('[data-history-id]');if(!row||!event.isPrimary||event.button!==0)return;
      start=[event.clientX,event.clientY];timer=setTimeout(()=>{if(!row.isConnected||moved)return;held=true;this.askDelete(Number(row.dataset.historyId));},500);
    });
    this.rows.addEventListener('pointermove',event=>{if(start&&Math.hypot(event.clientX-start[0],event.clientY-start[1])>8){moved=true;stop();}});
    for(const type of ['pointerup','pointercancel','pointerleave'])this.rows.addEventListener(type,stop);
    this.rows.onclick=event=>{if(held||moved){event.preventDefault();return;}const row=event.target.closest('[data-history-id]');if(row)this.navigate({page:'battery-history-detail',id:Number(row.dataset.historyId)},true);};
    this.rows.addEventListener('keydown',event=>{const row=event.target.closest('[data-history-id]');if(row&&(event.key==='Delete'||event.key==='F10'&&event.shiftKey)){event.preventDefault();this.askDelete(Number(row.dataset.historyId));}});
    this.more.onclick=()=>this.load();
    this.scroller.addEventListener('scroll',()=>{if(this.active&&this.state?.page==='battery-history'&&this.scroller.scrollTop>0&&this.scroller.scrollHeight-this.scroller.scrollTop-this.scroller.clientHeight<60)this.load();},{passive:true});
  }
  close(){this.stopPress();this.trendChart?.clearInspect();if(this.trend)this.trend.hidden=true;if(this.deleteDialog.open)this.deleteDialog.close();if(this.recordDialog.open)this.recordDialog.close();if(this.state?.page==='battery-history')this.scrollTop=this.scroller.scrollTop;this.token++;this.loading=false;this.active=false;this.list.hidden=true;this.detail.hidden=true;}
  refreshTheme(){if(this.active&&!this.trend.hidden)this.trendChart.draw();}
  askRecord(){
    if(this.saving||this.deleting||!this.active||this.state?.page!=='battery-history'||this.recordDialog.open)return;
    this.recordDialog.showModal();this.recordCancel.focus({preventScroll:true});
  }
  async record(){
    if(this.saving||this.deleting||!this.active||this.state?.page!=='battery-history')return;
    this.saving=true;this.recordButton.disabled=true;this.recordButton.textContent='记录中';this.status.textContent='正在记录当前电池信息…';
    const token=this.token;
    try{
      await this.api('battery_history_record');this.listDirty=true;
      if(!this.active||this.state?.page!=='battery-history')return;
      // Invalidate any earlier page load so its offset cannot skip the new row.
      this.token++;this.loading=false;this.items.clear();this.rows.replaceChildren();this.loaded=false;this.listDirty=false;this.offset=0;this.total=0;this.scrollTop=0;this.scroller.scrollTop=0;
      const refreshToken=this.token;await this.load();
      if(refreshToken===this.token&&this.active&&this.state?.page==='battery-history'&&!this.status.textContent)this.status.textContent='已记录当前电池信息';
    }catch(error){if(token===this.token&&this.active)this.status.textContent='记录失败：'+error.message;}
    finally{this.saving=false;this.recordButton.disabled=false;this.recordButton.textContent='记录';}
  }
  askDelete(id){
    if(this.saving||this.deleting||this.deleteDialog.open||!this.active||!this.items.has(id)&&!(this.state?.page==='battery-history-detail'&&this.state.id===id&&this.snapshots.has(id)))return;
    this.deleteId=id;document.getElementById('batteryHistoryDeleteStatus').textContent='';this.deleteDialog.showModal();this.deleteCancel.focus({preventScroll:true});
  }
  async remove(){
    if(this.deleting||!this.deleteId)return;
    const id=this.deleteId,token=this.token;this.deleting=true;this.deleteApply.disabled=this.deleteCancel.disabled=true;
    try{
      await this.api('battery_history_delete',id);this.snapshots.delete(id);this.listDirty=true;
      if(token!==this.token||!this.active)return;
      if(this.state?.page==='battery-history-detail'){
        this.detailDelete.disabled=true;this.deleteDialog.close();this.back();return;
      }
      // Reload from the first page so deleting a row cannot shift later offsets past an entry.
      this.token++;this.loading=false;this.items.clear();this.rows.replaceChildren();this.loaded=false;this.listDirty=false;this.offset=0;this.total=0;this.scrollTop=0;this.scroller.scrollTop=0;
      this.deleteDialog.close();await this.load();
    }catch(error){if(token===this.token)document.getElementById('batteryHistoryDeleteStatus').textContent=error.message;}
    finally{this.deleting=false;this.deleteApply.disabled=this.deleteCancel.disabled=false;}
  }
  async open(state,fresh=false){
    if(this.state?.page==='battery-history')this.scrollTop=this.scroller.scrollTop;
    this.token++;this.loading=false;this.active=true;this.state={...state};
    this.list.hidden=state.page!=='battery-history';this.detail.hidden=state.page!=='battery-history-detail';
    this.trend.hidden=state.page!=='battery-history-trend';
    if(state.page==='battery-history-trend'){await this.openTrend();return;}
    if(state.page==='battery-history'){
      if(fresh||this.listDirty){this.items.clear();this.rows.replaceChildren();this.loaded=false;this.listDirty=false;this.offset=0;this.total=0;this.scrollTop=0;}
      this.scroller.scrollTop=this.scrollTop;if(!this.loaded)await this.load();return;
    }
    const token=this.token;this.detailDelete.disabled=true;document.getElementById('batterySnapshotStatus').textContent='正在读取快照…';
    for(const node of this.detail.querySelectorAll('[data-history-field]')){node.textContent='—';node.classList.remove('battery-near-actual');}
    document.getElementById('batterySnapshotDate').textContent='—';document.getElementById('batterySnapshotCycles').textContent='—';
    document.getElementById('batterySnapshotScroll').scrollTop=0;
    try{
      let item=this.snapshots.get(state.id);if(!item){item=(await this.api('battery_history_detail',state.id)).item;this.snapshots.set(state.id,item);while(this.snapshots.size>12)this.snapshots.delete(this.snapshots.keys().next().value);}
      if(token!==this.token||!this.active)return;
      document.getElementById('batterySnapshotDate').textContent=batteryHistoryDate(item.completed_wall);document.getElementById('batterySnapshotCycles').textContent='循环次数：'+number(item.cycles);
      const fields=batteryHistoryFields(item.snapshot);
      for(const node of this.detail.querySelectorAll('[data-history-field]')){const key=node.dataset.historyField;node.textContent=fields.values[key];node.classList.toggle('battery-near-actual',fields.green.has(key));}
      document.getElementById('batterySnapshotStatus').textContent='';
      this.detailDelete.disabled=false;
    }catch(error){if(token===this.token)document.getElementById('batterySnapshotStatus').textContent=error.message;}
  }
  async load(){
    if(!this.active||this.state?.page!=='battery-history'||this.loading||this.loaded&&this.offset>=this.total)return;
    const token=this.token;this.loading=true;this.more.disabled=true;this.status.textContent='正在读取历史记录…';
    try{
      const data=await this.api('battery_history',this.offset);if(token!==this.token||!this.active)return;
      this.total=data.total;this.offset+=data.items.length;this.loaded=true;
      for(const item of data.items){if(this.items.has(item.id))continue;this.items.set(item.id,item);
        const row=document.createElement('button');row.type='button';row.className='battery-history-row';row.dataset.historyId=item.id;
        const text=document.createElement('span'),arrow=document.createElement('span');text.textContent=batteryHistoryDate(item.completed_wall,true)+' · 循环次数：'+number(item.cycles);arrow.textContent='›';arrow.setAttribute('aria-hidden','true');row.append(text,arrow);this.rows.append(row);
      }
      this.status.textContent=this.items.size?'':'暂无电池历史记录';this.more.hidden=this.offset>=this.total;
    }catch(error){if(token===this.token){this.status.textContent=error.message;this.more.hidden=false;}}
    finally{if(token===this.token){this.loading=false;this.more.disabled=false;}}
  }
  async openTrend(){
    const token=this.token,status=document.getElementById('batteryTrendStatus'),button=document.getElementById('batteryTrendExport');
    button.disabled=true;this.trendItems=[];this.trendChart.clearInspect();this.trendChart.set([],1,null,{kind:'health',fast:true});status.textContent='正在读取历史数据…';
    try{
      const items=await loadHealthTrend(this.api,()=>token!==this.token||!this.active);if(!items)return;
      this.trendItems=items;const start=items[0]?.wall??0;
      const points=items.map(i=>[i.wall-start,i.cycles??null,i.qmax??null,i.fcc??null]);
      this.trendChart.healthScales=new Map(healthMetrics.map(s=>[s.key,healthScale(points.map(p=>p[s.index]),s.key==='cycles'?10:100)]));
      this.trendChart.set(points,Math.max(1,(items.at(-1)?.wall??start)-start),null,{kind:'health',fast:true,startWall:start,cellCount:0});
      const legend=document.getElementById('batteryTrendLegend');legend.replaceChildren();
      for(const metric of healthMetrics){
        const label=document.createElement('label');label.style.setProperty('--series',metric.color);
        const input=document.createElement('input');input.type='checkbox';input.checked=this.trendChart.enabled.has(metric.key);
        input.onchange=()=>{input.checked?this.trendChart.enabled.add(metric.key):this.trendChart.enabled.delete(metric.key);this.trendChart.clearInspect();this.trendChart.draw();};
        label.onclick=e=>{if(e.target===input)return;this.trendChart.axisKey=metric.key;this.trendChart.draw();};
        const name=document.createElement('span'),range=document.createElement('span');name.textContent=metric.name;range.className='legend-scale';range.textContent=this.trendChart.scaleFor(metric).label+' '+metric.unit;
        label.append(input,name,range);legend.append(label);
      }
      status.textContent=items.length?'':'暂无电池历史数据';button.disabled=!items.length;
    }catch(error){if(token===this.token)status.textContent=error.message;}
  }
  async *trendCsv(){
    const items=[...this.trendItems];yield['日期','循环次数','Qmax_mAh','满充容量_mAh'];
    for(const item of items)yield[exportTime(item.wall),item.cycles,item.qmax,item.fcc];
  }
}
