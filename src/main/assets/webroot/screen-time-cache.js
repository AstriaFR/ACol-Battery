import{openDatabase}from'./indexed-db.js?v=acol-2.2s';
// IndexedDB belongs to this App's private WebView profile and survives APK updates.
export function resetScreenEntry(entry,record,revision=record.app_revision??0){
  return Object.assign(entry,{revision,appSchema:2,spans:[],appSpans:[],appLabels:{},appAttempts:{},appFinal:{},attempts:{},settledEnd:record.start_wall});
}
export function recordCacheKeyMatches(key,id,start){
  const prefix=`${id}:${start}`;return typeof key==='string'&&(key===prefix||key.startsWith(prefix+':'));
}
export function normalizeSpans(input){
  const sorted=(input??[]).filter(s=>Array.isArray(s)&&s.length>=3&&Number.isFinite(s[0])&&Number.isFinite(s[1])&&s[1]>s[0]&&(s[2]===0||s[2]===1)).sort((a,b)=>a[0]-b[0]||a[1]-b[1]);
  const out=[];
  for(const source of sorted){
    const last=out.at(-1),start=Math.max(source[0],last?.[1]??source[0]);if(source[1]<=start)continue;
    if(last&&last[1]===start&&last[2]===source[2])last[1]=source[1];else out.push([start,source[1],source[2]]);
  }
  return out;
}
export function missingSpans(spans,start,end){
  const missing=[];let cursor=start;
  for(const [from,to]of spans){if(to<=cursor)continue;if(from>=end)break;if(from>cursor)missing.push([cursor,Math.min(end,from)]);cursor=Math.max(cursor,to);}
  if(cursor<end)missing.push([cursor,end]);return missing;
}
export function normalizeAppSpans(input){
  const sorted=(input??[]).filter(s=>Array.isArray(s)&&s.length>=3&&Number.isFinite(s[0])&&Number.isFinite(s[1])&&s[1]>s[0]&&typeof s[2]==='string'&&s[2]).sort((a,b)=>a[0]-b[0]||a[1]-b[1]),out=[];
  for(const source of sorted){const last=out.at(-1),start=Math.max(source[0],last?.[1]??source[0]);if(source[1]<=start)continue;if(last&&last[1]===start&&last[2]===source[2])last[1]=source[1];else out.push([start,source[1],source[2]]);}return out;
}
export function hourAppRows(spans,start,end,labels={}){
  const totals=new Map();
  for(const [from,to,pkg]of spans){if(pkg==='__screenoff__')continue;const ms=Math.max(0,Math.min(end,to)-Math.max(start,from));if(ms)totals.set(pkg,(totals.get(pkg)||0)+ms);}
  return appRowsFromTotals(totals,labels);
}
export function appRowsFromTotals(totals,labels={}){
  return [...totals].filter(([,ms])=>Number.isFinite(ms)&&ms>=30000).sort((a,b)=>b[1]-a[1]||a[0].localeCompare(b[0])).map(([pkg,ms])=>({pkg,label:labels[pkg]||pkg,minutes:Math.round(ms/60000)}));
}
export function hourlyTotals(spans,start,end){
  const hours=Array(24).fill(0),covered=Array(24).fill(0);let totalMs=0,coveredMs=0;
  for(const [from,to,on]of spans){
    let cursor=Math.max(start,from),stop=Math.min(end,to);
    while(cursor<stop){
      const date=new Date(cursor),hour=date.getHours(),remaining=3600000-date.getMinutes()*60000-date.getSeconds()*1000-date.getMilliseconds(),next=Math.min(stop,cursor+remaining),ms=next-cursor;
      covered[hour]+=ms;coveredMs+=ms;if(on){hours[hour]+=ms;totalMs+=ms;}cursor=next;
    }
  }
  return{hours,covered,totalMs,coveredMs,unknownMs:Math.max(0,end-start-coveredMs),rangeStart:start,rangeEnd:end};
}

export class ScreenTimeCache{
  constructor(databaseName='acol.screen-time.cache'){this.databaseName=databaseName;this.entries=new Map();this.pending=new Map();this.deleted=new Set();this.identities=new Map();this.available=true;this.gcKey='acol.screen-time.cache.gc';}
  key(record){return`${record.id}:${record.start_wall}`;}
  open(){
    return openDatabase(this,this.databaseName,1,db=>{
      const store=db.createObjectStore('records',{keyPath:'key'});store.createIndex('recordId','recordId');
    });
  }
  async run(mode,action){
    const db=await this.open();
    return new Promise((resolve,reject)=>{
      let transaction,value,finished=false;const timer=setTimeout(()=>{try{transaction?.abort();}catch{}finish(Error('缓存操作超时'));},10000);
      const finish=error=>{if(finished)return;finished=true;clearTimeout(timer);error?reject(error):resolve(value);};
      try{
        transaction=db.transaction('records',mode);const request=action(transaction.objectStore('records'));
        if(request)request.onsuccess=()=>{value=request.result;};
        transaction.oncomplete=()=>{this.available=true;finish();};transaction.onabort=transaction.onerror=()=>finish(transaction.error||Error('缓存操作失败'));
      }catch(error){finish(error);}
    });
  }
  async get(record){
    const key=this.key(record),reconcile=entry=>entry.revision===(record.app_revision??0)?entry:resetScreenEntry(entry,record);
    if(this.entries.has(key))return reconcile(this.entries.get(key));if(this.pending.has(key))return reconcile(await this.pending.get(key));
    const task=(async()=>{
      let saved;try{saved=await this.run('readonly',store=>store.get(key));}catch{this.available=false;}
      const entry=reconcile({key,recordId:record.id,startWall:record.start_wall,revision:saved?.revision,appSchema:2,spans:normalizeSpans(saved?.spans),appSpans:normalizeAppSpans(saved?.appSpans),appLabels:saved?.appLabels&&typeof saved.appLabels==='object'?saved.appLabels:{},appAttempts:saved?.appAttempts&&typeof saved.appAttempts==='object'?saved.appAttempts:{},appFinal:saved?.appSchema===2&&saved?.appFinal&&typeof saved.appFinal==='object'?saved.appFinal:{},attempts:saved?.attempts&&typeof saved.attempts==='object'?saved.attempts:{},settledEnd:Number.isFinite(saved?.settledEnd)?saved.settledEnd:record.start_wall});
      this.entries.set(key,entry);while(this.entries.size>16)this.entries.delete(this.entries.keys().next().value);return entry;
    })();this.pending.set(key,task);
    try{return await task;}finally{this.pending.delete(key);}
  }
  async save(entry){
    if(!this.canSave(entry.recordId,entry.key))return;
    try{await this.run('readwrite',store=>this.canSave(entry.recordId,entry.key)?store.put(entry):undefined);this.available=true;}catch{this.available=false;}
  }
  canSave(id,key){return !this.deleted.has(id)&&(!this.identities.has(id)||recordCacheKeyMatches(key,id,this.identities.get(id)));}
  async removeStale(id,start){
    this.identities.set(id,start);
    for(const [key,entry]of this.entries)if(entry.recordId===id&&!recordCacheKeyMatches(key,id,start))this.entries.delete(key);
    await this.run('readwrite',store=>{
      const request=store.index('recordId').openKeyCursor(IDBKeyRange.only(id));
      request.onsuccess=()=>{const cursor=request.result;if(cursor){if(!recordCacheKeyMatches(cursor.primaryKey,id,start))store.delete(cursor.primaryKey);cursor.continue();}};
    });
  }
  async remove(recordId){
    this.deleted.add(recordId);
    for(const [key,entry]of this.entries)if(entry.recordId===recordId)this.entries.delete(key);
    try{
      await this.run('readwrite',store=>{
        const request=store.index('recordId').openCursor(IDBKeyRange.only(recordId));request.onsuccess=()=>{const cursor=request.result;if(cursor){cursor.delete();cursor.continue();}};
      });
    }catch{this.available=false;}
  }
  async prune(api){
    // Scan a bounded batch of keys, never load historical chart values.
    if(this.gcAfter===undefined){try{this.gcAfter=Math.max(0,Number(localStorage.getItem(this.gcKey))||0);}catch{this.gcAfter=0;}}
    const ids=[];
    await this.run('readonly',store=>{
      const request=store.index('recordId').openKeyCursor(IDBKeyRange.lowerBound(this.gcAfter??0,true),'nextunique');
      request.onsuccess=()=>{const cursor=request.result;if(cursor&&ids.length<48){ids.push(cursor.key);cursor.continue();}};
    });
    for(let i=0;i<ids.length;i+=12){
      const batch=ids.slice(i,i+12),result=await api('cache_records',...batch);
      if(!Array.isArray(result.records)||result.records.some(row=>!Array.isArray(row)||!batch.includes(row[0])||!Number.isSafeInteger(row[1])||row[1]<0)||new Set(result.records.map(row=>row[0])).size!==result.records.length)throw Error('缓存记录检查失败');
      const existing=new Map(result.records);
      for(const id of batch)if(!existing.has(id))await this.remove(id);else await this.removeStale(id,existing.get(id));
      this.gcAfter=batch.at(-1);
      try{localStorage.setItem(this.gcKey,String(this.gcAfter));}catch{}
    }
    if(ids.length<48){this.gcAfter=0;try{localStorage.setItem(this.gcKey,'0');}catch{}}
  }
}
