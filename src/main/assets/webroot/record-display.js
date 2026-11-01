const valid=value=>typeof value==='number'&&Number.isFinite(value);
const fmt=(value,digits=0)=>valid(value)?value.toLocaleString('zh-CN',{maximumFractionDigits:digits}):'—';
export function recordIsLive(record,status){
  return !!record?.active&&Number.isSafeInteger(record.id)&&[status?.session,status?.normalSession,status?.gameSession].includes(record.id);
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
