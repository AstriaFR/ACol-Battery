const finite=v=>typeof v==='number'&&Number.isFinite(v);
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
  close(){this.stopPress();if(this.deleteDialog.open)this.deleteDialog.close();if(this.state?.page==='battery-history')this.scrollTop=this.scroller.scrollTop;this.token++;this.loading=false;this.active=false;this.list.hidden=true;this.detail.hidden=true;}
  askDelete(id){
    if(this.deleting||this.deleteDialog.open||!this.active||!this.items.has(id)&&!(this.state?.page==='battery-history-detail'&&this.state.id===id&&this.snapshots.has(id)))return;
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
      this.status.textContent=this.items.size?'':'暂无尾充完成的历史记录';this.more.hidden=this.offset>=this.total;
    }catch(error){if(token===this.token){this.status.textContent=error.message;this.more.hidden=false;}}
    finally{if(token===this.token){this.loading=false;this.more.disabled=false;}}
  }
}
