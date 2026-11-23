export const valid=value=>typeof value==='number'&&Number.isFinite(value);
export function duration(ms){const s=Math.max(0,Math.round(ms/1000)),h=Math.floor(s/3600),m=Math.floor(s%3600/60);return h?`${h}小时${m}分`:m?`${m}分${s%60}秒`:`${s}秒`;}
const fmt=(value,digits=0)=>valid(value)?value.toLocaleString('zh-CN',{maximumFractionDigits:digits}):'—';
export function recordIsLive(record,status){
  return !!record?.active&&Number.isSafeInteger(record.id)&&[status?.session,status?.normalSession,status?.gameSession].includes(record.id);
}
export function recordEndWall(record){
  if(!record||record.active)return null;
  if(Number.isSafeInteger(record.end_wall)&&record.end_wall>0&&valid(new Date(record.end_wall).getTime()))return record.end_wall;
  if(![record.start_wall,record.start_elapsed,record.end_elapsed].every(valid)||record.end_elapsed<record.start_elapsed)return null;
  const wall=record.start_wall+(record.end_elapsed-record.start_elapsed);
  return Number.isSafeInteger(wall)&&wall>=0?wall:null;
}

export function capacityChange(record){
  if(!valid(record.rm_start)||!valid(record.rm_end))return'—';
  const change=record.rm_end-record.rm_start;
  return`${change>0?'+':change<0?'−':record.kind==='charge'||Number(record.fast_seconds)>0?'+':'−'}${fmt(Math.abs(change))}`;
}
export function percentChange(record){
  const change=valid(record.pct_start)&&valid(record.pct_end)?record.pct_end-record.pct_start:null;
  return change===null?'—':`${change>0?'+':change<0?'−':record.kind==='charge'||Number(record.fast_seconds)>0?'+':'−'}${fmt(Math.abs(change))}%`;
}
export function percentFlow(record){
  return valid(record.pct_start)&&valid(record.pct_end)&&record.pct_start!==record.pct_end
    ?record.pct_end>record.pct_start?'charge':'discharge'
    :'';
}
export function capacityFlow(record){
  return valid(record.rm_start)&&valid(record.rm_end)&&record.rm_start!==record.rm_end
    ?record.rm_end>record.rm_start?'charge':'discharge'
    :'';
}
export function recordChanges(record){
  return{
    primary:{text:percentChange(record),flow:percentFlow(record)},
    secondary:{text:capacityChange(record),flow:capacityFlow(record)}
  };
}

export{fmt};
export const recordCells=r=>[0,1,2].includes(r?.cell_count)?r.cell_count:2;
export const recordFast=r=>Number(r?.fast_seconds)>0;
export const pad2=n=>String(n).padStart(2,'0');
export const stamp=t=>{const d=new Date(t);return Number.isNaN(d.getTime())?'—':`${d.getFullYear()}/${pad2(d.getMonth()+1)}/${pad2(d.getDate())} ${pad2(d.getHours())}:${pad2(d.getMinutes())}`;};
export const stampList=t=>{const d=new Date(t);return Number.isNaN(d.getTime())?'—':`${pad2(d.getMonth()+1)}/${pad2(d.getDate())} ${pad2(d.getHours())}:${pad2(d.getMinutes())}`;};
export const reasons={backup_restored:'恢复备份',recording:'记录中',full_tail_confirmed:'尾充完成',plugged:'接入充电',unplugged:'拔出充电',reboot:'重启中断',collector_restart:'服务中断',framework_restarted:'服务重启',module_stopped:'模块停止',recording_stopped:'记录停止',app_uninstalled:'卸载结束',service_updated:'后台更新',fast_started:'切换游戏',fast_stopped:'记录结束',fast_idle_timeout:'息屏结束',cell_count_changed:'电芯数量变更'};
export function el(tag,text,cls){const n=document.createElement(tag);if(text!==undefined)n.textContent=text;if(cls)n.className=cls;return n;}
export function longPressFeedback(){try{navigator.vibrate?.(10);}catch{}}

export function renderRecordHeading(r){
  const node=id=>document.getElementById(id);
  const heading=node('detailScroll').querySelector('.record-detail-head');
  heading.dataset.kind=recordFast(r)?'game':r?.kind??'';heading.dataset.active=r?.active?'1':'0';
  node('detailType').textContent=!r?'记录':recordFast(r)?'游戏记录':r.kind==='charge'?'充电记录':'放电记录';
  node('detailState').textContent=!r?'':r.active?'记录中':reasons[r.reason]??'记录结束';
  const endWall=recordEndWall(r);
  for(const [time,wall] of [[node('detailTitle'),r?.start_wall],[node('detailEnd'),endWall]]){
    const known=valid(wall)&&Number.isFinite(new Date(wall).getTime());
    time.textContent=known?stamp(wall):'—';
    if(known)time.dateTime=new Date(wall).toISOString();else time.removeAttribute('datetime');
  }
  if(r?.active)node('detailEnd').textContent='记录中';
}
