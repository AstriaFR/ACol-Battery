import{Chart,systemTimeLabel,windowPoints}from'./chart.js?v=acol-2.2s';
import{bindExport,exportTime}from'./chart-export.js?v=acol-2.2s';
import{lockedCapacityText,rawFccBoundText}from'./battery-info.js?v=acol-2.2s';
import{longPressFeedback}from'./record-display.js?v=acol-2.2s';
const finite=v=>typeof v==='number'&&Number.isFinite(v);
const positive=v=>finite(v)&&v>0?v:null;
const historyRefreshMs=5000;
const tailRmMetric={key:'tailRm',index:5,name:'尾充剩余电量',legendName:'尾充电量',axis:'尾充剩余电量',unit:'mAh',color:'#9260b5',digits:0};
export const healthMetrics=[
  {key:'cycles',index:1,name:'循环次数',axis:'循环次数',unit:'次',color:'#5479c4',digits:0},
  {key:'qmax',index:2,name:'Qmax',axis:'Qmax',unit:'mAh',color:'#b47b35',digits:0},
  {key:'fcc',index:3,name:'满充容量',axis:'满充容量',unit:'mAh',color:'#26957d',digits:0},
  tailRmMetric
];
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
  preparePoints(points){
    this.tailRmPoints=points.filter(row=>finite(row[tailRmMetric.index]));
    return points;
  }
  signalWindow(signal,visible){
    return signal===tailRmMetric?windowPoints(this.tailRmPoints,...this.window):visible;
  }
  scaleFor(s){return this.healthScales?.get(s.key)??healthScale([]);}
  timeAxis(width){
    const date=x=>{const d=new Date(this.startWall+x);return`${d.getFullYear()}/${d.getMonth()+1}/${d.getDate()}`;};
    const count=Math.max(1,Math.min(4,Math.floor(width/90))),[a,b]=this.window;
    return{label:date,ticks:Array.from({length:count-1},(_,i)=>{const x=a+(b-a)*(i+1)/count;return{x,text:date(x)};})};
  }
  showInspection(row,y){
    this.marker=row[0];this.inspectY=y;
    this.tooltip.textContent=systemTimeLabel(this.startWall+row[0],true,true)+'\n'+this.signals().filter(s=>this.enabled.has(s.key)).map(s=>{
      const value=s.key==='fcc'&&row[4]?rawFccBoundText(row[s.index]):number(row[s.index],s.unit);
      return `${s.name}  ${value}`;
    }).join('\n');
    this.tooltip.hidden=false;this.refreshInspection();
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
  const info=snapshot??{},references=Array.isArray(info.nearActual)?info.nearActual:[],noCompensation=info.noCompensation===true;
  const fccBound=noCompensation?null:rawFccBoundText(info.rawFccAbove);
  const marked=key=>references.includes(key)&&positive(info[key])!==null;
  const referenceKey=fccBound||!noCompensation&&marked('rawFcc')?'rawFcc':marked('fcc')?'fcc':null;
  const fcc=key=>{
    const value=positive(info[key]);
    return key===referenceKey?ratio(value,info.designCapacity):number(value,'mAh');
  };
  const locked=lockedCapacityText(info.qmax,info.lockedCapacity,noCompensation?null:info.rawFccAbove);
  const values={qmax:ratio(positive(info.qmax),info.designCapacity),fcc:fcc('fcc'),rawFcc:noCompensation?'——':fccBound??fcc('rawFcc'),lockedCapacity:locked,soh:number(positive(info.soh),'%'),rawSoh:noCompensation?'——':number(positive(info.rawSoh),'%',2),cutoffMv:number(info.cutoffMv,'mV'),tailRm:number(positive(info.tailRm),'mAh')};
  return{values,referenceKey};
}
export class BatteryHistoryPanel{
  constructor(api,navigate,back,fullScreen){
    this.back=back;this.fullScreen=fullScreen;
    this.api=api;this.navigate=navigate;this.active=false;this.token=0;this.items=new Map();this.snapshots=new Map();this.loaded=false;this.before=0;this.latest=0;this.morePages=false;this.loading=false;this.refreshing=false;this.refreshTimer=null;this.scrollTop=0;
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
    this.trendPanel=document.getElementById('batteryTrendChart');
    this.trendExpand=document.getElementById('batteryTrendFullscreen');this.trendExit=document.getElementById('batteryTrendExit');
    this.trendExpand.onclick=()=>this.setTrendFullscreen(true);
    this.trendExit.onclick=()=>this.backFullscreen();
    window.addEventListener('resize',()=>this.layoutTrendFullscreen());
    window.visualViewport?.addEventListener('resize',()=>this.layoutTrendFullscreen());
    window.visualViewport?.addEventListener('scroll',()=>this.layoutTrendFullscreen());
    document.addEventListener('keydown',e=>{if(e.key==='Escape')this.backFullscreen();});
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
  close(){clearTimeout(this.refreshTimer);this.refreshTimer=null;this.stopPress();this.backFullscreen();this.trendChart?.clearInspect();if(this.trend)this.trend.hidden=true;if(this.deleteDialog.open)this.deleteDialog.close();if(this.recordDialog.open)this.recordDialog.close();if(this.state?.page==='battery-history')this.scrollTop=this.scroller.scrollTop;this.token++;this.loading=false;this.refreshing=false;this.active=false;this.list.hidden=true;this.detail.hidden=true;}
  refreshTheme(){if(this.active&&!this.trend.hidden)this.trendChart.draw();}
  resetList(){
    this.token++;this.loading=false;this.refreshing=false;this.items.clear();this.rows.replaceChildren();
    this.loaded=false;this.listDirty=false;this.before=0;this.latest=0;this.morePages=false;this.scrollTop=0;this.scroller.scrollTop=0;
  }
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
      this.resetList();
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
      this.resetList();
      this.deleteDialog.close();await this.load();
    }catch(error){if(token===this.token)document.getElementById('batteryHistoryDeleteStatus').textContent=error.message;}
    finally{this.deleting=false;this.deleteApply.disabled=this.deleteCancel.disabled=false;}
  }
  async open(state,fresh=false){
    this.backFullscreen();
    if(this.state?.page==='battery-history')this.scrollTop=this.scroller.scrollTop;
    clearTimeout(this.refreshTimer);this.refreshTimer=null;
    this.token++;this.loading=false;this.refreshing=false;this.active=true;this.state={...state};
    this.list.hidden=state.page!=='battery-history';this.detail.hidden=state.page!=='battery-history-detail';
    this.trend.hidden=state.page!=='battery-history-trend';
    if(state.page==='battery-history-trend'){await this.openTrend();return;}
    if(state.page==='battery-history'){
      if(fresh||this.listDirty)this.resetList();
      this.scroller.scrollTop=this.scrollTop;this.more.disabled=false;this.scheduleRefresh();
      if(this.loaded)await this.refreshHead();else await this.load();return;
    }
    const token=this.token;this.detailDelete.disabled=true;document.getElementById('batterySnapshotStatus').textContent='正在读取快照…';
    const rmRow=document.getElementById('batterySnapshotTailRm');rmRow.hidden=true;
    for(const node of this.detail.querySelectorAll('[data-history-field]')){node.textContent='—';node.classList.remove('battery-near-actual');}
    document.getElementById('batterySnapshotDate').textContent='—';document.getElementById('batterySnapshotCycles').textContent='—';
    document.getElementById('batterySnapshotScroll').scrollTop=0;
    try{
      let item=this.snapshots.get(state.id);if(!item){item=(await this.api('battery_history_detail',state.id)).item;this.snapshots.set(state.id,item);while(this.snapshots.size>12)this.snapshots.delete(this.snapshots.keys().next().value);}
      if(token!==this.token||!this.active)return;
      document.getElementById('batterySnapshotDate').textContent=batteryHistoryDate(item.completed_wall);document.getElementById('batterySnapshotCycles').textContent='循环次数：'+number(item.cycles);
      const fields=batteryHistoryFields(item.snapshot);
      rmRow.hidden=!finite(item.snapshot?.tailRm);
      for(const node of this.detail.querySelectorAll('[data-history-field]')){
        const key=node.dataset.historyField;
        node.textContent=fields.values[key];node.classList.toggle('battery-near-actual',key===fields.referenceKey);
      }
      document.getElementById('batterySnapshotStatus').textContent='';
      this.detailDelete.disabled=false;
    }catch(error){if(token===this.token)document.getElementById('batterySnapshotStatus').textContent=error.message;}
  }
  async load(){
    if(!this.active||this.state?.page!=='battery-history'||this.loading||this.loaded&&!this.morePages)return;
    const token=this.token;this.loading=true;this.more.disabled=true;this.status.textContent='正在读取历史记录…';
    try{
      const data=await this.api('battery_history',this.before,0);if(token!==this.token||!this.active)return;
      if(!this.loaded)this.latest=data.items[0]?.id??0;
      this.before=data.items.at(-1)?.id??this.before;this.morePages=data.more;this.loaded=true;
      if(data.items.length)this.rows.append(...this.buildRows(data.items));
      this.status.textContent=this.items.size?'':'暂无电池历史记录';this.more.hidden=!this.morePages;
    }catch(error){if(token===this.token){this.status.textContent=error.message;this.more.hidden=false;}}
    finally{if(token===this.token){this.loading=false;this.more.disabled=false;}}
  }
  scheduleRefresh(){
    if(this.refreshTimer!==null||!this.active||this.state?.page!=='battery-history')return;
    this.refreshTimer=setTimeout(async()=>{
      this.refreshTimer=null;if(!document.hidden)await this.refreshHead();this.scheduleRefresh();
    },historyRefreshMs);
  }
  async refreshHead(){
    if(!this.active||this.state?.page!=='battery-history'||!this.loaded||this.refreshing)return;
    const token=this.token;this.refreshing=true;
    try{
      const {items}=await this.api('battery_history',this.latest,1);
      if(token!==this.token||!items.length)return;
      const wasEmpty=!this.items.size;
      this.latest=items.at(-1).id;
      this.rows.prepend(...this.buildRows(items).reverse());
      if(wasEmpty&&!this.saving)this.status.textContent='';
    }catch{}
    finally{if(token===this.token)this.refreshing=false;}
  }
  buildRows(items){
    const rows=[];
    for(const item of items){
      if(this.items.has(item.id))continue;
      this.items.set(item.id,item);
      const row=document.createElement('button');row.type='button';row.className='battery-history-row';row.dataset.historyId=item.id;
      const text=document.createElement('span'),arrow=document.createElement('span');text.textContent=batteryHistoryDate(item.completed_wall,true)+' · 循环次数：'+number(item.cycles);arrow.textContent='›';arrow.setAttribute('aria-hidden','true');row.append(text,arrow);rows.push(row);
    }
    return rows;
  }
  backFullscreen(){
    if(!this.trendAnchor)return false;
    this.setTrendFullscreen(false);
    return true;
  }
  layoutTrendFullscreen(){
    if(!this.trendAnchor)return;
    const view=window.visualViewport;
    const width=view?.width??window.innerWidth,height=view?.height??window.innerHeight,rotate=height>width;
    const panel=this.trendPanel;
    panel.classList.toggle('rotate',rotate);
    panel.style.setProperty('--full-width',`${rotate?height:width}px`);
    panel.style.setProperty('--full-height',`${rotate?width:height}px`);
    panel.style.setProperty('--full-left',`${(view?.offsetLeft??0)+width/2}px`);
    panel.style.setProperty('--full-top',`${(view?.offsetTop??0)+height/2}px`);
  }
  setTrendFullscreen(enabled){
    if(enabled===!!this.trendAnchor||enabled&&this.trend.hidden)return;
    const panel=this.trendPanel;
    if(enabled){
      this.trendAnchor=document.createComment('battery health chart');
      panel.replaceWith(this.trendAnchor);
      document.body.append(panel);
    }else{
      this.trendAnchor.replaceWith(panel);
      this.trendAnchor=null;
      panel.classList.remove('rotate');
      for(const key of['--full-width','--full-height','--full-left','--full-top'])panel.style.removeProperty(key);
    }
    panel.classList.toggle('expanded',enabled);
    document.body.classList.toggle('chartExpanded',enabled);
    this.trendExpand.hidden=enabled;this.trendExit.hidden=!enabled;
    this.layoutTrendFullscreen();
    try{this.fullScreen(enabled);}catch{}
    this.trendChart.draw();
  }
  async openTrend(){
    const token=this.token,status=document.getElementById('batteryTrendStatus'),button=document.getElementById('batteryTrendExport');
    button.disabled=true;this.trendItems=[];this.trendChart.clearInspect();this.trendChart.set([],1,null,{kind:'health',fast:true});status.textContent='正在读取历史数据…';
    try{
      const items=await loadHealthTrend(this.api,()=>token!==this.token||!this.active);if(!items)return;
      this.trendItems=items;const start=items[0]?.wall??0;
      const points=items.map(i=>{
        const bounded=finite(i.fccAbove)&&i.fccAbove>0;
        return [i.wall-start,i.cycles??null,i.qmax??null,bounded?i.fccAbove:i.fcc??null,bounded,i.tailRm??null];
      });
      this.trendChart.healthScales=new Map(healthMetrics.map(s=>[s.key,healthScale(points.map(p=>p[s.index]),s.key==='cycles'?10:100)]));
      this.trendChart.set(points,Math.max(1,(items.at(-1)?.wall??start)-start),null,{kind:'health',fast:true,startWall:start,cellCount:0});
      const legend=document.getElementById('batteryTrendLegend');legend.replaceChildren();
      const chart=this.trendChart;
      const redraw=()=>{
        for(const label of legend.children)label.classList.toggle('axis-active',label.dataset.series===chart.axisKey);
        chart.clearInspect();chart.draw();
      };
      for(const metric of healthMetrics){
        const label=document.createElement('label');label.style.setProperty('--series',metric.color);label.dataset.series=metric.key;
        label.classList.toggle('axis-active',chart.axisKey===metric.key);
        const input=document.createElement('input');input.type='checkbox';input.checked=chart.enabled.has(metric.key);
        input.onchange=()=>{
          if(input.checked)chart.enabled.add(metric.key);
          else{
            chart.enabled.delete(metric.key);
            if(chart.axisKey===metric.key)chart.axisKey=null;
          }
          redraw();
        };
        let timer,start,suppressClick=false;
        const end=()=>{clearTimeout(timer);start=null;};
        label.addEventListener('pointerdown',e=>{
          if(e.button!==0||!e.isPrimary)return;
          end();suppressClick=false;start=[e.clientX,e.clientY];
          timer=setTimeout(()=>{
            if(token!==this.token||this.trend.hidden||!label.isConnected)return;
            suppressClick=true;
            chart.axisKey=chart.axisKey===metric.key?null:metric.key;
            chart.enabled.add(metric.key);input.checked=true;
            longPressFeedback();redraw();
          },500);
        });
        label.addEventListener('pointermove',e=>{
          if(start&&Math.hypot(e.clientX-start[0],e.clientY-start[1])>8){suppressClick=true;end();}
        });
        for(const event of ['pointerup','pointercancel','pointerleave'])label.addEventListener(event,end);
        label.addEventListener('contextmenu',e=>e.preventDefault());
        label.addEventListener('click',e=>{
          if(suppressClick&&e.detail!==0){e.preventDefault();e.stopImmediatePropagation();}
        },{capture:true});
        const name=document.createElement('span'),range=document.createElement('span');name.textContent=metric.legendName??metric.name;range.className='legend-scale';range.textContent=this.trendChart.scaleFor(metric).label+' '+metric.unit;
        label.append(input,name,range);legend.append(label);
      }
      status.textContent=items.length?'':'暂无电池历史数据';button.disabled=!items.length;
    }catch(error){if(token===this.token)status.textContent=error.message;}
  }
  async *trendCsv(){
    const items=[...this.trendItems];yield['日期','循环次数','Qmax_mAh','满充容量_mAh','尾充剩余电量_mAh'];
    for(const item of items)yield[exportTime(item.wall),item.cycles,item.qmax,finite(item.fccAbove)&&item.fccAbove>0?`＞${item.fccAbove}`:item.fcc,item.tailRm??null];
  }
}
