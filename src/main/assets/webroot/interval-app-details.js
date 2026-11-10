import{IntervalAppCache}from'./interval-app-cache.js?v=acol-2.2s';
/** Preload saved interval application names; inspecting never starts an IPC request. */
export class IntervalAppDetails{
  constructor(chart,api,getRecord,cache=new IntervalAppCache()){this.chart=chart;this.api=api;this.getRecord=getRecord;this.cache=cache;this.records=new Map();this.deleted=new Set();chart.onInspect=row=>this.inspect(row);}
  eligible(record){return record?.kind==='discharge'&&!(record.fast_seconds>0);}
  preload(record,statsVersion){
    if(!this.eligible(record)||this.deleted.has(record.id))return Promise.resolve();
    record={...record}; // Bind saved results to the revision that started this read.
    const key=this.key(record);
    let state=this.records.get(key);
    if(!state){state={names:new Map(),cursor:0,loadedEnd:-1,complete:false,retryCursor:null};this.records.set(key,state);while(this.records.size>3)this.records.delete(this.records.keys().next().value);}
    if(Number.isFinite(statsVersion)&&Number.isFinite(state.statsVersion)&&statsVersion!==state.statsVersion&&!state.complete)state.retryAt=0;
    if(Number.isFinite(statsVersion))state.statsVersion=statsVersion;
    const end=record.end_elapsed-record.start_elapsed;
    if(state.busy)return state.busy;
    if(state.loadedEnd>=end&&state.complete||Date.now()<(state.retryAt??0))return Promise.resolve();
    const startedVersion=state.statsVersion;
    state.busy=(async()=>{
      try{
        if(!state.restored){const saved=await this.cache.read(record);state.restored=true;if(this.deleted.has(record.id))return;if(saved){state.names=new Map(saved.names);state.cursor=saved.cursor;state.loadedEnd=saved.end;state.complete=saved.complete;state.retryCursor=saved.retryCursor;state.retryAt=Math.min(Number(saved.retryAt)||0,Date.now()+60000);this.render();}}
        if(state.loadedEnd>=end&&state.complete||Date.now()<(state.retryAt??0))return;
        if(state.retryCursor!==null)state.cursor=Math.min(state.cursor,state.retryCursor);
        state.retryCursor=null;let data,complete=true;
        do{
          data=await this.api('interval_apps',record.id,state.cursor);
          if(this.deleted.has(record.id))return;
          if(!Number.isSafeInteger(data.cursor)||data.cursor<state.cursor||data.more&&data.cursor===state.cursor)throw Error('区间应用读取进度无效');
          if(data.complete===false)complete=false;
          if(Number.isSafeInteger(data.retryCursor)&&data.retryCursor>=0)state.retryCursor=state.retryCursor===null?data.retryCursor:Math.min(state.retryCursor,data.retryCursor);
          for(const[x,pkg,status]of data.apps??[])state.names.set(x,pkg?(data.labels?.[pkg]??pkg):status==='none'?'暂无记录区间':status==='off'?'无亮屏应用':'暂无应用数据');
          state.cursor=data.cursor;this.render();
        }while(data.more);
        state.loadedEnd=Math.min(end,data.through??end);state.complete=complete;state.retryAt=complete?0:Date.now()+60000;await this.cache.save(record,state);
      }catch{state.retryAt=Date.now()+5000;}
      finally{
        state.busy=null;this.render();const latest=this.getRecord();
        const advanced=startedVersion!==state.statsVersion&&!state.complete;
        if(advanced)state.retryAt=0;
        if(latest?.id===record.id&&latest.start_wall===record.start_wall
          &&((latest.app_revision??0)!==(record.app_revision??0)||advanced||!state.retryAt&&latest.end_elapsed-latest.start_elapsed>state.loadedEnd))this.preload(latest);
        else this.scheduleRetry(record,state);
      }
    })();return state.busy;
  }
  scheduleRetry(record,state){
    clearTimeout(this.retryTimer);
    if(!state.retryAt||!this.visible()||this.getRecord()?.id!==record.id||this.chart.recordId!==record.id)return;
    this.retryTimer=setTimeout(()=>{const latest=this.getRecord();if(this.visible()&&latest?.id===record.id&&latest.start_wall===record.start_wall)this.preload(latest);},Math.max(1000,state.retryAt-Date.now()));
  }
  visible(){return typeof document!=='undefined'&&!document.hidden&&!this.chart.canvas?.closest('[hidden]');}
  inspect(row){this.inspection=row?{row,base:this.chart.tooltip.textContent}:null;this.render();}
  key(record){return`${record.id}:${record.start_wall}:${record.app_revision??0}`;}
  async remove(id){this.deleted.add(id);clearTimeout(this.retryTimer);for(const[key]of this.records)if(key.startsWith(id+':'))this.records.delete(key);await this.cache.remove(id);}
  prune(){if(this.pruning||Date.now()<(this.pruneAt??0))return;this.pruneAt=Date.now()+60000;this.pruning=this.cache.prune(this.api).catch(()=>{}).finally(()=>this.pruning=null);}
  render(){
    const record=this.getRecord(),chart=this.chart,item=this.inspection;
    if(!item||!this.eligible(record)||document.hidden||chart.tooltip.hidden||chart.recordId!==record.id||chart.marker!==item.row[0])return;
    const text=this.records.get(this.key(record))?.names.get(item.row[0]);
    chart.tooltip.textContent=item.base+(text?'\n'+text:'');chart.positionTooltip();
  }
}
