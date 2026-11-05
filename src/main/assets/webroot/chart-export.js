import{remainingEstimate}from'./estimate.js?v=acol-2.2s';
const finite=v=>typeof v==='number'&&Number.isFinite(v);
export function csvCell(value){
  if(value===null||value===undefined||typeof value==='number'&&!finite(value))return'';
  let text=String(value);if(typeof value==='string'&&/^[=+\-@\t\r]/.test(text))text="'"+text;
  return /[",\r\n]/.test(text)?'"'+text.replaceAll('"','""')+'"':text;
}
export const csvRow=row=>row.map(csvCell).join(',')+'\r\n';
export function exportTime(wall){
  if(!finite(wall))return'';const d=new Date(wall),pad=v=>String(v).padStart(2,'0');
  return`${d.getFullYear()}-${pad(d.getMonth()+1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;
}
let exporting=false;
export async function saveCsv(name,rows){
  if(exporting)throw Error('已有导出任务，请稍候。');
  const native=globalThis.window?.AcoNative;if(!native?.beginChartExport)throw Error('请在更新后的 App 内导出。');
  exporting=true;let token='',finished=false;
  try{
    token=native.beginChartExport(name);if(!token)throw Error('无法开始导出，请完成或取消上一次保存。');
    let buffer='\ufeff',count=0;
    const flush=()=>{if(buffer&&!native.appendChartExport(token,buffer))throw Error('导出暂存失败或数据超出支持大小。');buffer='';};
    for await(const row of rows){
      const text=csvRow(row);if(text.length>65536)throw Error('单行数据过长，无法导出。');
      if(buffer.length+text.length>32768)flush();buffer+=text;
      if(++count%200===0){flush();await new Promise(resolve=>setTimeout(resolve,0));}
    }
    flush();if(!native.finishChartExport(token))throw Error('无法打开保存位置。');finished=true;
  }finally{if(token&&!finished)native.cancelChartExport(token);exporting=false;}
}
export function bindExport(button,name,rows){
  button.onclick=async()=>{
    if(button.disabled)return;button.disabled=true;const text=button.textContent;button.textContent='准备中';
    try{await saveCsv(typeof name==='function'?name():name,rows());}
    catch(error){const toast=document.getElementById('toast');if(toast){toast.textContent=error.message;toast.hidden=false;setTimeout(()=>{toast.hidden=true;},4500);}}
    finally{button.disabled=false;button.textContent=text;}
  };
}
export async function* recordCsv(api,id){
  if(!id)throw Error('请先打开记录。');
  yield['系统时间','计时_ms','剩余容量_mAh','系统电量_%','电流_mA_补偿后','电芯均压_V','图表功率_W','电芯1_V','电芯2_V','电池温度_℃','剩余能量估算_Wh','连接充电','内存已用_GB','内存总量_GB','采样间隔_ms','质量标记'];
  let cursor=0,end=0;
  do{
    const data=await api('export_points',id,cursor,end);end=data.end+1;
    const record=data.record,fast=record.fast_seconds>0,cells=record.cell_count===2?2:1;
    for(const p of data.points){
      const estimate=record.kind==='discharge'&&!(fast&&p[12]===1)?remainingEstimate({rm:p[1],voltage:p[4]},null):null;
      yield[exportTime(finite(p[13])?p[13]:record.start_wall+p[0]),p[0],p[1],p[2],finite(p[3])?p[3]*cells:null,finite(p[4])?p[4]/1000:null,p[5],finite(p[8])?p[8]/1000:null,finite(p[9])?p[9]/1000:null,p[10],estimate?Number(estimate.whText):null,p[12],p[14],p[15],p[7],p[6]];
    }
    if(!finite(data.nextAfter))break;
    const next=data.nextAfter+1;if(next<=cursor)throw Error('采样分页未前进，已停止导出。');cursor=next;
  }while(true);
}
export async function* performanceCsv(api,id){
  if(!id)throw Error('请先打开游戏记录。');let cursor=0,end=null,metrics=null;
  do{
    const data=await api('performance',id,cursor);
    if(end===null){end=data.end;metrics=[['帧率_FPS',1],['GPU频率_MHz',2],['GPU使用率_%',3]];
      for(const[cIndex,c]of(data.meta?.clusters??[]).entries())metrics.push([`${c.label}频率_MHz`,8+cIndex*2],[`${c.label}使用率_%`,9+cIndex*2]);
      if(Number.isInteger(data.meta?.temperatureIndex))metrics.push(['CPU_GPU最高温度_℃',data.meta.temperatureIndex]);
      if(Number.isInteger(data.meta?.memoryIndex))metrics.push(['内存已用_GB',data.meta.memoryIndex]);
      yield['系统时间','计时_ms',...metrics.map(s=>s[0])];
    }
    const walls=new Map(data.wallTimes??[]);
    for(const p of data.points??[])if(p[0]<=end)yield[exportTime(walls.get(p[0])),p[0],...metrics.map(([,index])=>p[index])];
    if(!data.more||data.cursor>end)break;
    if(data.cursor<=cursor)throw Error('性能分页未前进，已停止导出。');cursor=data.cursor;
  }while(true);
}
