const valid=value=>typeof value==='number'&&Number.isFinite(value);
const fmt=(value,digits=0)=>valid(value)?value.toLocaleString('zh-CN',{maximumFractionDigits:digits}):'—';
export function recordIsLive(record,status){
  return !!record?.active&&Number.isSafeInteger(record.id)&&[status?.session,status?.normalSession,status?.gameSession].includes(record.id);
}
export function recordEndWall(record){
  if(!record||record.active||![record.start_wall,record.start_elapsed,record.end_elapsed].every(valid)||record.end_elapsed<record.start_elapsed)return null;
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
export const reasons={backup_restored:'恢复备份',recording:'记录中',full_tail_confirmed:'尾充完成',plugged:'接入充电',unplugged:'拔出充电',reboot:'重启中断',collector_restart:'服务中断',framework_restarted:'服务重启',module_stopped:'模块停止',recording_stopped:'记录停止',app_uninstalled:'卸载结束',service_updated:'后台更新',fast_started:'切换游戏',fast_stopped:'记录结束',fast_idle_timeout:'息屏结束'};
export function el(tag,text,cls){const n=document.createElement(tag);if(text!==undefined)n.textContent=text;if(cls)n.className=cls;return n;}
export function longPressFeedback(){try{navigator.vibrate?.(10);}catch{}}
