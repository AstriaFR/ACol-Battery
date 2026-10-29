// IndexedDB belongs to this App's private WebView profile and survives APK updates.
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
  constructor(){this.entries=new Map();this.pending=new Map();this.deleted=new Set();this.available=true;this.gcKey='acol.screen-time.cache.gc';}
  key(record){return`${record.id}:${record.start_wall}`;}
  open(){
    if(this.ready)return this.ready;
    this.ready=new Promise((resolve,reject)=>{
      let finished=false,request;const timer=setTimeout(()=>finish(Error('缓存打开超时')),10000);
      const finish=(error,db)=>{if(finished){db?.close();return;}finished=true;clearTimeout(timer);error?reject(error):resolve(db)};
      try{
        request=indexedDB.open('acol.screen-time.cache',1);
        request.onupgradeneeded=()=>{const store=request.result.createObjectStore('records',{keyPath:'key'});store.createIndex('recordId','recordId');};
        request.onsuccess=()=>{const db=request.result;db.onversionchange=()=>{db.close();this.ready=null;};finish(null,db);};
        request.onerror=()=>finish(request.error||Error('缓存打开失败'));request.onblocked=()=>finish(Error('缓存正在被占用'));
      }catch(error){finish(error);}
    });
    return this.ready;
  }
  async run(mode,action){
    const db=await this.open();
    return new Promise((resolve,reject)=>{
      let transaction,value,finished=false;const timer=setTimeout(()=>{try{transaction?.abort();}catch{}finish(Error('缓存操作超时'));},10000);
      const finish=error=>{if(finished)return;finished=true;clearTimeout(timer);error?reject(error):resolve(value);};
      try{
        transaction=db.transaction('records',mode);const request=action(transaction.objectStore('records'));
        if(request)request.onsuccess=()=>{value=request.result;};
        transaction.oncomplete=()=>finish();transaction.onabort=transaction.onerror=()=>finish(transaction.error||Error('缓存操作失败'));
      }catch(error){finish(error);}
    });
  }
  async get(record){
    const key=this.key(record);if(this.entries.has(key))return this.entries.get(key);if(this.pending.has(key))return this.pending.get(key);
    const task=(async()=>{
      let saved;try{saved=await this.run('readonly',store=>store.get(key));}catch{this.available=false;}
      const entry={key,recordId:record.id,startWall:record.start_wall,appSchema:2,spans:normalizeSpans(saved?.spans),appSpans:normalizeAppSpans(saved?.appSpans),appLabels:saved?.appLabels&&typeof saved.appLabels==='object'?saved.appLabels:{},appAttempts:saved?.appAttempts&&typeof saved.appAttempts==='object'?saved.appAttempts:{},appFinal:saved?.appSchema===2&&saved?.appFinal&&typeof saved.appFinal==='object'?saved.appFinal:{},attempts:saved?.attempts&&typeof saved.attempts==='object'?saved.attempts:{},settledEnd:Number.isFinite(saved?.settledEnd)?saved.settledEnd:record.start_wall};
      this.entries.set(key,entry);while(this.entries.size>16)this.entries.delete(this.entries.keys().next().value);return entry;
    })();this.pending.set(key,task);
    try{return await task;}finally{this.pending.delete(key);}
  }
  async save(entry){
    if(this.deleted.has(entry.recordId))return;
    try{await this.run('readwrite',store=>store.put(entry));this.available=true;}catch{this.available=false;}
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
      if(!Array.isArray(result.ids)||result.ids.some(id=>!Number.isSafeInteger(id)||id<1))throw Error('缓存记录检查失败');
      const existing=new Set(result.ids);
      for(const id of batch)if(!existing.has(id))await this.remove(id);
      this.gcAfter=batch.at(-1);
      try{localStorage.setItem(this.gcKey,String(this.gcAfter));}catch{}
    }
    if(ids.length<48){this.gcAfter=0;try{localStorage.setItem(this.gcKey,'0');}catch{}}
  }
}
