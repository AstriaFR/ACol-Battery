import{ScreenTimeCache}from'./screen-time-cache.js?v=acol-2.0';

export const roundedWh=value=>Math.round((Math.max(0,Number(value)||0)+Number.EPSILON)*100)/100;
export const formatWh=value=>`${roundedWh(value).toFixed(2)} Wh`;
export function energyRows(values,labels={}){
  return(values??[]).map(([pkg,wh])=>({pkg,label:labels[pkg]||pkg,wh:Number(wh)||0}))
    .filter(row=>roundedWh(row.wh)>.01).sort((a,b)=>b.wh-a.wh||a.pkg.localeCompare(b.pkg));
}
export function mergeEnergy(entry,result){
  if(result.rangeStart!==entry.through||!Number.isFinite(result.through)||result.through<entry.through)throw Error('耗电缓存区间不连续');
  for(let h=0;h<24;h++){
    entry.hours[h]+=Math.max(0,Number(result.hours?.[h])||0);
    entry.covered[h]+=Math.max(0,Number(result.covered?.[h])||0);
    const apps=new Map(entry.apps[h]);
    for(const [pkg,wh]of result.apps?.[h]??[])if(typeof pkg==='string'&&Number.isFinite(wh)&&wh>=0)apps.set(pkg,(apps.get(pkg)||0)+wh);
    entry.apps[h]=[...apps];
  }
  Object.assign(entry.labels,result.labels??{});entry.through=result.through;
  return entry;
}
function resetEnergy(entry,start,revision){
  Object.assign(entry,{schema:2,through:start,revision,partial:false,retryAt:0,hours:Array(24).fill(0),covered:Array(24).fill(0),apps:Array.from({length:24},()=>[]),labels:{}});
  return entry;
}

/** One day per entry: opening a record never reads its other days or other records. */
export class RecordEnergyCache extends ScreenTimeCache{
  constructor(){super();this.reads=new Map();this.gcKey='acol.record-energy.cache.gc';}
  key(record,day){return`${record.id}:${record.start_wall}:${day}:${Intl.DateTimeFormat().resolvedOptions().timeZone}:${new Date(day).getTimezoneOffset()}`;}
  open(){
    if(this.ready)return this.ready;
    this.ready=new Promise((resolve,reject)=>{
      let finished=false,request;const timer=setTimeout(()=>finish(Error('缓存打开超时')),10000);
      const finish=(error,db)=>{if(finished){db?.close();return;}finished=true;clearTimeout(timer);error?reject(error):resolve(db);};
      try{
        request=indexedDB.open('acol.record-energy.cache',1);
        request.onupgradeneeded=()=>{const store=request.result.createObjectStore('records',{keyPath:'key'});store.createIndex('recordId','recordId');};
        request.onsuccess=()=>{const db=request.result;db.onversionchange=()=>{db.close();this.ready=null;};finish(null,db);};
        request.onerror=()=>finish(request.error||Error('缓存打开失败'));request.onblocked=()=>finish(Error('缓存正在被占用'));
      }catch(error){finish(error);}
    });return this.ready;
  }
  async get(record,day){
    const key=this.key(record,day);if(this.entries.has(key))return this.entries.get(key);if(this.pending.has(key))return this.pending.get(key);
    const task=(async()=>{
      let saved;try{saved=await this.run('readonly',store=>store.get(key));}catch{this.available=false;}
      const start=Math.max(day,record.start_wall);
      const valid=saved?.schema===2&&Number.isFinite(saved.through)&&saved.through>=start&&saved.hours?.length===24&&saved.covered?.length===24&&saved.apps?.length===24;
      const entry=valid?saved:resetEnergy({key,recordId:record.id},start,Number(record.app_revision)||0);
      this.entries.set(key,entry);while(this.entries.size>16)this.entries.delete(this.entries.keys().next().value);return entry;
    })();this.pending.set(key,task);
    try{return await task;}finally{this.pending.delete(key);}
  }
  async read(record,day,end,api){
    const key=this.key(record,day);
    if(this.reads.has(key))await this.reads.get(key);
    const entry=await this.get(record,day),start=Math.max(day,record.start_wall);
    // Recheck after the await: another view may already be updating this day.
    if(this.reads.has(key)){await this.reads.get(key);return this.read(record,day,end,api);}
    if(Number.isFinite(record.app_revision)&&entry.revision!==record.app_revision||entry.partial&&Date.now()>=entry.retryAt)resetEnergy(entry,start,Number(record.app_revision)||0);
    if(entry.through>=end)return entry;
    const task=(async()=>{
      let result=await api('hour_energy',record.id,day,entry.through);
      const revision=Number(result.appRevision)||0;
      // A backfill can repair earlier hours while this request reads the tail.
      if(revision!==entry.revision&&entry.through>start){resetEnergy(entry,start,revision);result=await api('hour_energy',record.id,day,start);}
      const from=entry.through,wasPartial=entry.partial;
      mergeEnergy(entry,result);entry.revision=Number(result.appRevision)||0;record.app_revision=entry.revision;
      entry.partial=from>start&&wasPartial||result.attributionComplete===false;
      if(entry.partial&&!entry.retryAt)entry.retryAt=Date.now()+60000;
      await this.save(entry);return entry;
    })();
    this.reads.set(key,task);
    try{return await task;}finally{if(this.reads.get(key)===task)this.reads.delete(key);}
  }
}
