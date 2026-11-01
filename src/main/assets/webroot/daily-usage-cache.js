const finite=value=>Number.isFinite(value)&&value>=0;
export const midnight=wall=>{const date=new Date(wall);date.setHours(0,0,0,0);return date.getTime()};
export const nextDate=(day,amount=1)=>{const date=new Date(day);date.setDate(date.getDate()+amount);return date.getTime()};
export const localZone=()=>{try{return Intl.DateTimeFormat().resolvedOptions().timeZone||'local'}catch{return'local'}};
export const canDeleteUsageDay=(day,now=Date.now())=>Number.isFinite(day)&&midnight(day)===day&&day<midnight(now);
export function newDay(day,zone){
  return{key:`${zone}|${day}`,v:1,zone,dayStart:day,dayEnd:nextDate(day),through:day,state:'0',labels:{},hours:Array.from({length:24},()=>({onMs:0,coveredMs:0,apps:[]}))};
}
export function mergeDaily(entry,result){
  if(result.dayStart!==entry.dayStart||result.dayEnd!==entry.dayEnd||result.from!==entry.through||!finite(result.through)||result.through<entry.through||result.through>entry.dayEnd||!Array.isArray(result.hours)||result.hours.length!==24)throw Error('每日使用数据范围不一致');
  const hours=entry.hours.map((old,index)=>{
    const value=result.hours[index];if(!value||!finite(value.onMs)||!finite(value.coveredMs)||value.onMs>value.coveredMs)throw Error('每日使用数据无效');
    const apps=new Map(old.apps);
    for(const row of value.apps??[]){if(!Array.isArray(row)||typeof row[0]!=='string'||!finite(row[1]))throw Error('应用使用数据无效');apps.set(row[0],(apps.get(row[0])||0)+row[1]);}
    return{onMs:old.onMs+value.onMs,coveredMs:old.coveredMs+value.coveredMs,apps:[...apps]};
  });
  return{...entry,hours,through:result.through,state:/^[A-Za-z0-9_-]{1,4096}$/.test(result.state)?result.state:'0',labels:{...entry.labels,...result.labels}};
}
export function dailyView(entry){
  const hours=entry.hours.map(hour=>hour.onMs),covered=entry.hours.map(hour=>hour.coveredMs),totalMs=hours.reduce((a,b)=>a+b,0),coveredMs=covered.reduce((a,b)=>a+b,0);
  return{hours,covered,totalMs,coveredMs,unknownMs:Math.max(0,entry.through-entry.dayStart-coveredMs),rangeStart:entry.dayStart,rangeEnd:entry.through};
}
function validDay(entry,day,zone){
  if(entry?.v!==1||entry.zone!==zone||entry.dayStart!==day||entry.dayEnd!==nextDate(day)||!finite(entry.through)||entry.through<day||entry.through>entry.dayEnd||!Array.isArray(entry.hours)||entry.hours.length!==24)return false;
  return entry.hours.every(hour=>hour&&finite(hour.onMs)&&finite(hour.coveredMs)&&hour.onMs<=hour.coveredMs&&Array.isArray(hour.apps)&&hour.apps.every(row=>Array.isArray(row)&&typeof row[0]==='string'&&finite(row[1])));
}

// Small stores are read independently; no startup scan of historical values/keys.
const stores=['summaries','hours','day_apps','app_hours','hour_apps','progress','app_totals','app_total_days'];
const emptyHours=()=>Array.from({length:24},()=>({onMs:0,coveredMs:0}));
const keyOf=(zone,day)=>[zone,day];
export function summarizeDay(entry){
  return{zone:entry.zone,dayStart:entry.dayStart,dayEnd:entry.dayEnd,through:entry.through,onMs:entry.hours.reduce((n,h)=>n+h.onMs,0),coveredMs:entry.hours.reduce((n,h)=>n+h.coveredMs,0)};
}
export function neighboringDates(day,cached,today=midnight(Date.now())){
  const first=nextDate(today,-9),previous=[cached.previous,day>first&&day<=today?nextDate(day,-1):null].filter(v=>Number.isFinite(v)&&v<day),next=[cached.next,day<first?first:day<today?nextDate(day):null].filter(v=>Number.isFinite(v)&&v>day&&v<=today);
  return{previous:previous.length?Math.max(...previous):null,next:next.length?Math.min(...next):null};
}
export class DailyUsageCache{
  constructor(){this.zone=localZone();this.memory=new Map();this.migrations=new Map();this.totalIndexing=new Map();this.available=true;}
  remember(store,key,value){const id=store+JSON.stringify(key);this.memory.delete(id);this.memory.set(id,value);while(this.memory.size>64)this.memory.delete(this.memory.keys().next().value);return value;}
  open(){
    if(this.ready)return this.ready;
    this.ready=new Promise((resolve,reject)=>{
      let finished=false;const finish=(error,db)=>{if(finished){db?.close();return;}finished=true;clearTimeout(timer);error?reject(error):resolve(db)},timer=setTimeout(()=>finish(Error('缓存打开超时')),10000);
      try{
        const request=indexedDB.open('acol.daily-usage.cache',4);
        request.onupgradeneeded=()=>{
          const db=request.result;
          for(const name of stores)if(!db.objectStoreNames.contains(name))db.createObjectStore(name,{keyPath:name==='app_totals'?['zone','pkg','dayStart']:name==='app_hours'?['zone','dayStart','pkg']:name==='hour_apps'?['zone','dayStart','hour']:['zone','dayStart']});
          const totals=request.transaction.objectStore('app_totals');if(!totals.indexNames.contains('zoneDay'))totals.createIndex('zoneDay',['zone','dayStart']);
          // Legacy days are converted only when that date is requested.
          if(db.objectStoreNames.contains('days')){const old=request.transaction.objectStore('days');if(!old.indexNames.contains('zoneDay'))old.createIndex('zoneDay',['zone','dayStart']);}
        };
        request.onsuccess=()=>{const db=request.result;db.onversionchange=()=>{db.close();this.ready=null;};finish(null,db);};
        request.onerror=()=>finish(request.error||Error('缓存打开失败'));request.onblocked=()=>finish(Error('缓存正在被占用'));
      }catch(error){finish(error);}
    });return this.ready;
  }
  async transaction(names,mode,work){
    const db=await this.open();
    try{return await new Promise((resolve,reject)=>{
      let transaction,value,problem,finished=false;
      const finish=error=>{if(finished)return;finished=true;clearTimeout(timer);error?reject(error):resolve(value)},timer=setTimeout(()=>{try{transaction?.abort();}catch{}finish(Error('缓存操作超时'));},10000);
      const abort=error=>{problem=error;try{transaction.abort();}catch{finish(error)}};
      try{
        transaction=db.transaction(names,mode);const tables=Object.fromEntries(names.map(name=>[name,transaction.objectStore(name)]));
        const read=(store,key,fn)=>{const request=store.get(key);request.onsuccess=()=>{try{fn(request.result)}catch(error){abort(error)}};};
        transaction.oncomplete=()=>{this.available=true;finish()};transaction.onabort=transaction.onerror=()=>finish(problem||transaction.error||Error('缓存操作失败'));
        work(tables,result=>{value=result},abort,read);
      }catch(error){try{transaction?.abort()}catch{}finish(error);}
    })}catch(error){this.available=false;throw error;}
  }
  async read(store,key){
    const id=store+JSON.stringify(key);if(this.memory.has(id))return this.memory.get(id);
    const value=await this.transaction([store],'readonly',(s,set,fail,read)=>read(s[store],key,set));return this.remember(store,key,value);
  }
  async summary(day){
    const key=keyOf(this.zone,day);let value=await this.read('summaries',key);if(value)return value;
    await this.migrate(day);return this.read('summaries',key);
  }
  async migrate(day){
    if(this.migrations.has(day))return this.migrations.get(day);
    const task=(async()=>{
      const db=await this.open();if(!db.objectStoreNames.contains('days'))return;
      const old=await this.transaction(['days'],'readonly',(s,set,fail,read)=>read(s.days,`${this.zone}|${day}`,set));
      if(!validDay(old,day,this.zone))return;
      const result={dayStart:day,dayEnd:old.dayEnd,from:day,through:old.through,hours:old.hours,labels:old.labels??{},state:old.state||'0'};
      await this.apply(result,false,true);
      await this.transaction(['days'],'readwrite',(s)=>s.days.delete(old.key));
    })();this.migrations.set(day,task);try{return await task;}finally{this.migrations.delete(day);}
  }
  async layer(store,day,part){
    await this.summary(day);return this.read(store,part===undefined?keyOf(this.zone,day):[this.zone,day,part]);
  }
  async getHours(day){return await this.layer('hours',day)||{zone:this.zone,dayStart:day,dayEnd:nextDate(day),through:day,hours:emptyHours()};}
  async getProgress(day){return await this.layer('progress',day)||{through:day,state:'0'};}
  async getDailyApps(day){return await this.layer('day_apps',day)||{dayStart:day,apps:[]};}
  async getHourApps(day,hour){return await this.layer('hour_apps',day,hour)||{apps:[]};}
  async getAllHourApps(day){
    await this.summary(day);const key=keyOf(this.zone,day),id='hour_day'+JSON.stringify(key);
    if(this.memory.has(id))return this.memory.get(id);
    const rows=await this.transaction(['hour_apps'],'readonly',(s,set)=>{const request=s.hour_apps.getAll(IDBKeyRange.bound([this.zone,day,0],[this.zone,day,23]),24);request.onsuccess=()=>set(request.result);});
    const hours=Array.from({length:24},()=>({apps:[]}));for(const row of rows)hours[row.hour]=row;
    return this.remember('hour_day',key,hours);
  }
  async getAppHours(day,pkg){return await this.layer('app_hours',day,pkg)||{pkg,ms:Array(24).fill(0)};}
  async neighbors(day){
    const db=await this.open(),names=['summaries',...(db.objectStoreNames.contains('days')?['days']:[])];
    return this.transaction(names,'readonly',(s,set)=>{
      const candidates={previous:[],next:[]};let pending=names.length*2;
      for(const name of names)for(const direction of ['previous','next']){
        const source=name==='days'?s.days.index('zoneDay'):s.summaries;
        const range=direction==='previous'?IDBKeyRange.bound([this.zone,0],[this.zone,day],false,true):IDBKeyRange.bound([this.zone,day],[this.zone,Number.MAX_SAFE_INTEGER],true,false);
        const request=source.openKeyCursor(range,direction==='previous'?'prev':'next');
        request.onsuccess=()=>{if(request.result)candidates[direction].push(request.result.key[1]);if(--pending===0)set({previous:candidates.previous.length?Math.max(...candidates.previous):null,next:candidates.next.length?Math.min(...candidates.next):null});};
      }
    });
  }
  // Week/month views can read only bounded daily totals, without hour/app data.
  async summaries(from,to,limit=31){
    if(!Number.isFinite(from)||!Number.isFinite(to)||to<=from||!Number.isInteger(limit)||limit<1||limit>366)throw Error('日期范围无效');
    await this.prepareDays(from,to,limit);
    return this.transaction(['summaries'],'readonly',(s,set)=>{const request=s.summaries.getAll(IDBKeyRange.bound([this.zone,from],[this.zone,to],false,true),limit);request.onsuccess=()=>set(request.result);});
  }
  weekRange(from,to){
    if(!Number.isFinite(from)||!Number.isFinite(to)||from<0||to<=from||to-from>8*86400000)throw Error('周范围无效');
  }
  async prepareWeek(from,to){
    this.weekRange(from,to);return this.prepareDays(from,to,7);
  }
  async prepareDays(from,to,limit){
    const db=await this.open();if(!db.objectStoreNames.contains('days'))return;
    const dates=await this.transaction(['days'],'readonly',(s,set)=>{
      const values=[],request=s.days.index('zoneDay').openKeyCursor(IDBKeyRange.bound([this.zone,from],[this.zone,to],false,true));
      request.onsuccess=()=>{const cursor=request.result;if(cursor&&values.length<limit){values.push(cursor.key[1]);cursor.continue();}else set(values);};
    });
    for(const day of dates)await this.summary(day);
  }
  async weeklyOverview(from,to){
    await this.prepareWeek(from,to);
    const data=await this.transaction(['summaries','day_apps'],'readonly',(s,set)=>{
      const result={};let pending=2;
      for(const name of ['summaries','day_apps']){const request=s[name].getAll(IDBKeyRange.bound([this.zone,from],[this.zone,to],false,true),7);request.onsuccess=()=>{result[name]=request.result;if(--pending===0)set(result);};}
    });
    const today=midnight(Date.now()),known=new Set(data.summaries.filter(row=>row.dayStart<=today&&row.coveredMs>0).map(row=>row.dayStart)),totals=new Map();
    for(const day of data.day_apps){this.remember('day_apps',[this.zone,day.dayStart],day);if(!known.has(day.dayStart))continue;for(const row of day.apps){const old=totals.get(row.pkg);totals.set(row.pkg,{pkg:row.pkg,label:row.label||old?.label||row.pkg,ms:(old?.ms??0)+row.ms});}}
    return{summaries:data.summaries,apps:[...totals.values()].sort((a,b)=>b.ms-a.ms||a.pkg.localeCompare(b.pkg))};
  }
  async ensureAppTotals(from,to){
    const id=`${this.zone}:${from}:${to}`;if(this.totalIndexing.has(id))return this.totalIndexing.get(id);
    const task=(async()=>{
      await this.prepareWeek(from,to);
      const data=await this.transaction(['summaries','app_total_days'],'readonly',(s,set)=>{
        const result={};let pending=2;
        for(const name of ['summaries','app_total_days']){const request=s[name].getAll(IDBKeyRange.bound([this.zone,from],[this.zone,to],false,true),7);request.onsuccess=()=>{result[name]=request.result;if(--pending===0)set(result);};}
      });
      const markers=new Map(data.app_total_days.map(row=>[row.dayStart,row.through]));
      for(const summary of data.summaries){
        if(markers.get(summary.dayStart)===summary.through)continue;
        await this.transaction(['day_apps','app_totals','app_total_days'],'readwrite',(s,set,fail,read)=>{
          const key=[this.zone,summary.dayStart];let day,marker,pending=2;
          const finish=()=>{if(--pending)return;if(!day||marker?.through===day.through)return;for(const row of day.apps)s.app_totals.put({zone:this.zone,dayStart:day.dayStart,pkg:row.pkg,label:row.label,ms:row.ms,through:day.through});s.app_total_days.put({zone:this.zone,dayStart:day.dayStart,through:day.through});};
          read(s.day_apps,key,value=>{day=value;finish()});read(s.app_total_days,key,value=>{marker=value;finish()});
        });
      }
    })();this.totalIndexing.set(id,task);try{return await task;}finally{this.totalIndexing.delete(id);}
  }
  async weeklyApp(from,to,pkg){
    await this.ensureAppTotals(from,to);
    return this.transaction(['summaries','app_totals'],'readonly',(s,set)=>{
      const result={};let pending=2;
      const summary=s.summaries.getAll(IDBKeyRange.bound([this.zone,from],[this.zone,to],false,true),7),apps=s.app_totals.getAll(IDBKeyRange.bound([this.zone,pkg,from],[this.zone,pkg,to],false,true),7);
      summary.onsuccess=()=>{result.summaries=summary.result;if(--pending===0)set(result)};apps.onsuccess=()=>{result.appTotals=apps.result;if(--pending===0)set(result)};
    });
  }
  forgetDay(day){
    const prefix=JSON.stringify([this.zone,day]);
    for(const id of this.memory.keys())if(id.includes(prefix)||id.includes(prefix.slice(0,-1)+','))this.memory.delete(id);
  }
  async deleteDay(day){
    if(!Number.isFinite(day)||midnight(day)!==day)throw Error('日期无效');
    const db=await this.open(),names=[...stores,...(db.objectStoreNames.contains('days')?['days']:[])],zone=this.zone;
    await this.transaction(names,'readwrite',(s)=>{
      for(const name of ['hours','day_apps','progress','app_total_days'])s[name].delete([zone,day]);
      for(const name of ['app_hours','hour_apps','app_totals']){
        const source=name==='app_totals'?s[name].index('zoneDay'):s[name];
        const range=name==='app_totals'?IDBKeyRange.only([zone,day]):IDBKeyRange.bound([zone,day],[zone,day,[]]);
        const request=source.openCursor(range);request.onsuccess=()=>{const cursor=request.result;if(cursor){cursor.delete();cursor.continue();}};
      }
      if(s.days)s.days.delete(`${zone}|${day}`);
      s.summaries.put({zone,dayStart:day,dayEnd:nextDate(day),through:nextDate(day),onMs:0,coveredMs:0,deleted:true});
    });
    this.forgetDay(day);
  }
  /** Replace one incomplete hour, updating all derived totals in one transaction. */
  async repairHour(result,hour){
    const day=result.dayStart,zone=this.zone,key=keyOf(zone,day),a=new Date(day),b=new Date(day);
    a.setHours(hour,0,0,0);b.setHours(hour+1,0,0,0);
    const value=result.hours?.[hour];
    if(!Number.isInteger(hour)||hour<0||hour>23||result.dayEnd!==nextDate(day)||result.from!==a.getTime()||!finite(result.through)||result.through<=result.from||result.through>b.getTime()||!value||!finite(value.onMs)||!finite(value.coveredMs)||value.onMs>value.coveredMs||value.coveredMs>result.through-result.from)throw Error('补读小时范围无效');
    const incoming=new Map();
    for(const row of value.apps??[]){if(typeof row?.[0]!=='string'||!finite(row[1]))throw Error('补读应用数据无效');incoming.set(row[0],(incoming.get(row[0])??0)+row[1]);}
    const summary=await this.transaction(stores,'readwrite',(s,set,fail,read)=>{
      let base,oldHour,dayApps,deleted=false,pending=4;const appHours=new Map();
      const prepare=()=>{
        if(--pending)return;
        // Do not replace a newer tail with an older in-flight request, or trade
        // confirmed time for another unknown result.
        if(deleted||!base||Math.min(b.getTime(),base.through)!==result.through||value.coveredMs<=base.hours[hour].coveredMs)return;
        const old=new Map((oldHour?.apps??[]).map(row=>[row.pkg,row.ms])),packages=new Set([...old.keys(),...incoming.keys()]);
        const finish=()=>{
          try{
            const meta={zone,dayStart:day,dayEnd:base.dayEnd,through:base.through},totals=new Map((dayApps?.apps??[]).map(row=>[row.pkg,{...row}]));
            for(const pkg of packages){
              const previous=totals.get(pkg),ms=Math.max(0,(previous?.ms??0)-(old.get(pkg)??0)+(incoming.get(pkg)??0)),label=result.labels?.[pkg]??previous?.label??pkg;
              if(ms>0){
                totals.set(pkg,{pkg,ms,label});
                const values=[...(appHours.get(pkg)?.ms??Array(24).fill(0))];values[hour]=incoming.get(pkg)??0;
                s.app_hours.put({...meta,pkg,label,ms:values});s.app_totals.put({...meta,pkg,label,ms});
              }else{totals.delete(pkg);s.app_hours.delete([zone,day,pkg]);s.app_totals.delete([zone,pkg,day]);}
            }
            const hours=base.hours.map((item,index)=>index===hour?{onMs:value.onMs,coveredMs:value.coveredMs}:item),overview={...meta,hours},summary=summarizeDay(overview);
            s.hours.put(overview);s.summaries.put(summary);
            s.hour_apps.put({...meta,hour,apps:[...incoming].map(([pkg,ms])=>({pkg,ms,label:totals.get(pkg)?.label??result.labels?.[pkg]??pkg}))});
            s.day_apps.put({...meta,apps:[...totals.values()].sort((a,b)=>b.ms-a.ms)});set(summary);
            // The checkpoint describes the end of the day/tail, not this hour.
            // Keep it intact so the next normal read remains incremental.
          }catch(error){fail(error);}
        };
        pending=packages.size;if(!pending){finish();return;}
        for(const pkg of packages)read(s.app_hours,[zone,day,pkg],value=>{appHours.set(pkg,value);if(--pending===0)finish();});
      };
      read(s.summaries,key,value=>{deleted=!!value?.deleted;prepare();});
      read(s.hours,key,value=>{base=value;prepare();});read(s.hour_apps,[zone,day,hour],value=>{oldHour=value;prepare();});read(s.day_apps,key,value=>{dayApps=value;prepare();});
    });
    if(!summary)return false;this.forgetDay(day);this.remember('summaries',key,summary);return true;
  }
  async apply(result,migrate=true,replace=false){
    const day=result.dayStart;if(migrate)await this.summary(day);
    const zone=this.zone,key=keyOf(zone,day),changedHours=[],deltas=new Map();
    if(result.dayEnd!==nextDate(day)||!finite(result.from)||!finite(result.through)||result.through<result.from||result.through>result.dayEnd||!Array.isArray(result.hours)||result.hours.length!==24)throw Error('每日使用数据无效');
    result.hours.forEach((hour,index)=>{
      if(!finite(hour.onMs)||!finite(hour.coveredMs)||hour.onMs>hour.coveredMs)throw Error('每日使用数据无效');
      if(hour.coveredMs||hour.onMs||hour.apps?.length)changedHours.push(index);
      for(const row of hour.apps??[]){if(typeof row?.[0]!=='string'||!finite(row[1]))throw Error('应用使用数据无效');if(!deltas.has(row[0]))deltas.set(row[0],Array(24).fill(0));deltas.get(row[0])[index]+=row[1];}
    });
    const written=await this.transaction(stores,'readwrite',(s,set,fail,read)=>{
      let base,dayApps,indexMarker,deleted,pending=4;const oldHours=new Map(),oldApps=new Map();
      const finish=()=>{
        try{
          const meta={zone,dayStart:day,dayEnd:result.dayEnd,through:result.through};
          const hours=base.hours.map((h,i)=>({onMs:h.onMs+result.hours[i].onMs,coveredMs:h.coveredMs+result.hours[i].coveredMs}));
          const totals=new Map((dayApps?.apps??[]).map(row=>[row.pkg,{...row}]));
          for(const [pkg,ms]of deltas){
            const count=ms.reduce((a,b)=>a+b,0),old=totals.get(pkg);totals.set(pkg,{pkg,ms:(old?.ms??0)+count,label:result.labels?.[pkg]??old?.label??pkg});
            const previous=oldApps.get(pkg)?.ms??Array(24).fill(0);s.app_hours.put({...meta,pkg,label:totals.get(pkg).label,ms:previous.map((v,i)=>v+ms[i])});
          }
          for(const hour of changedHours){
            const apps=new Map((oldHours.get(hour)?.apps??[]).map(row=>[row.pkg,{...row}]));
            for(const [pkg,ms]of result.hours[hour].apps??[]){const old=apps.get(pkg);apps.set(pkg,{pkg,ms:(old?.ms??0)+ms,label:result.labels?.[pkg]??old?.label??pkg});}
            s.hour_apps.put({...meta,hour,apps:[...apps.values()]});
          }
          const overview={...meta,hours},summary=summarizeDay(overview);
          s.hours.put(overview);s.summaries.put(summary);s.day_apps.put({...meta,apps:[...totals.values()].sort((a,b)=>b.ms-a.ms)});
          const indexRows=indexMarker?.through===base.through&&!replace?[...deltas.keys()].map(pkg=>totals.get(pkg)):[...totals.values()];
          for(const row of indexRows)s.app_totals.put({...meta,pkg:row.pkg,label:row.label,ms:row.ms});
          s.app_total_days.put({zone,dayStart:day,through:result.through});
          s.progress.put({...meta,state:/^[A-Za-z0-9_-]{1,4096}$/.test(result.state)?result.state:'0'});set(summary);
        }catch(error){fail(error);}
      };
      const prepare=()=>{
        if(--pending)return;
        if(deleted){set(deleted);return;}
        if(!base||replace)base={through:day,hours:emptyHours()};
        if(base.through!==result.from){fail(Error('使用统计已更新，请重新读取'));return;}
        pending=changedHours.length+deltas.size;if(!pending){finish();return;}
        for(const hour of changedHours)read(s.hour_apps,[zone,day,hour],value=>{oldHours.set(hour,replace?undefined:value);if(--pending===0)finish();});
        for(const pkg of deltas.keys())read(s.app_hours,[zone,day,pkg],value=>{oldApps.set(pkg,replace?undefined:value);if(--pending===0)finish();});
      };
      read(s.summaries,key,value=>{deleted=value?.deleted?value:null;prepare()});
      read(s.hours,key,value=>{base=value;prepare()});read(s.day_apps,key,value=>{dayApps=replace?undefined:value;prepare()});read(s.app_total_days,key,value=>{indexMarker=value;prepare()});
    });
    // Clear only this date's small in-memory layers. Durable history is retained.
    this.forgetDay(day);
    this.remember('summaries',key,written);return written;
  }
}
