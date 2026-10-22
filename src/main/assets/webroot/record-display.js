const valid=value=>typeof value==='number'&&Number.isFinite(value);
const fmt=(value,digits=0)=>valid(value)?value.toLocaleString('zh-CN',{maximumFractionDigits:digits}):'—';

export function capacityChange(record){
  if(!valid(record.rm_start)||!valid(record.rm_end))return'—';
  const change=record.rm_end-record.rm_start;
  return`${change>0?'+':change<0?'−':record.kind==='charge'?'+':'−'}${fmt(Math.abs(change))}`;
}
export function percentChange(record){
  const change=valid(record.pct_start)&&valid(record.pct_end)?record.pct_end-record.pct_start:null;
  return change===null?'—':`${change>0?'+':change<0?'−':record.kind==='charge'?'+':'−'}${fmt(Math.abs(change))}%`;
}
export function percentFlow(record){
  return valid(record.pct_start)&&valid(record.pct_end)&&record.pct_start!==record.pct_end
    ?record.pct_end>record.pct_start?'charge':'discharge'
    :record.kind;
}
export function recordChanges(priority,record){
  const percentage={text:percentChange(record),flow:percentFlow(record),label:'百分比变化'};
  const capacity={text:capacityChange(record),flow:record.kind,label:'容量变化'};
  return priority==='percentage'
    ?{primary:percentage,secondary:capacity}
    :{primary:capacity,secondary:percentage};
}
