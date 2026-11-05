const fs=require('node:fs'),path=require('node:path'),vm=require('node:vm'),assert=require('node:assert/strict');
const root=path.resolve(__dirname,'../src/main/assets/webroot'),context=vm.createContext({console,setTimeout,clearTimeout,Intl,Date}),modules=new Map();
function load(file){
  file=file.split('?')[0];if(modules.has(file))return modules.get(file);
  const mod=new vm.SourceTextModule(fs.readFileSync(file,'utf8'),{context,identifier:file});modules.set(file,mod);return mod;
}
(async()=>{
  const mod=load(path.join(root,'record-energy-cache.js'));
  await mod.link((specifier,parent)=>load(path.resolve(path.dirname(parent.identifier),specifier)));await mod.evaluate();
  const {roundedWh,formatWh,energyRows,mergeEnergy,RecordEnergyCache}=mod.namespace;
  assert.equal(roundedWh(.015),.02);assert.equal(formatWh(1.425),'1.43 Wh');
  assert.equal(energyRows([['tiny',.0149],['one',.01],['keep',.015]])[0].pkg,'keep');
  assert.equal(energyRows([['tiny',.0149],['one',.01],['keep',.015]]).length,1);
  const cache=new RecordEnergyCache(),record={id:7,start_wall:1000};cache.run=async()=>undefined;cache.save=async()=>{};
  let release,calls=0;const gate=new Promise(resolve=>release=resolve);
  const api=async(action,id,day,from)=>{
    calls++;assert.equal(action,'hour_energy');assert.equal(from,1000);await gate;
    return{rangeStart:1000,through:2000,hours:[.03,...Array(23).fill(0)],covered:[1000,...Array(23).fill(0)],apps:[[['app',.03]],...Array.from({length:23},()=>[])],labels:{app:'应用'}};
  };
  const a=cache.read(record,0,2000,api),b=cache.read(record,0,2000,api);release();
  const [one,two]=await Promise.all([a,b]);assert.equal(calls,1);assert.equal(one,two);assert.equal(one.hours[0],.03);
  await cache.read(record,0,2000,api);assert.equal(calls,1,'confirmed intervals are never re-read');
  assert.throws(()=>mergeEnergy(one,{rangeStart:1000,through:2000}),/不连续/);
  const repair=new RecordEnergyCache(),repairRecord={id:8,start_wall:1000,app_revision:0};repair.run=async()=>undefined;repair.save=async()=>{};
  let repairCalls=0;
  const repairApi=async(action,id,day,from)=>{
    repairCalls++;assert.equal(from,1000);
    return{rangeStart:1000,through:2000,hours:[.03,...Array(23).fill(0)],covered:[1000,...Array(23).fill(0)],apps:[[[repairCalls===1?'__other__':'repaired',.03]],...Array.from({length:23},()=>[])],labels:{},appRevision:repairCalls===1?0:1,attributionComplete:repairCalls!==1};
  };
  const partial=await repair.read(repairRecord,0,2000,repairApi);assert.equal(partial.partial,true);
  partial.retryAt=0;const repaired=await repair.read(repairRecord,0,2000,repairApi);
  assert.equal(repairCalls,2);assert.equal(repaired.apps[0][0][0],'repaired');assert.equal(repaired.hours[0],.03,'repair must replace, not add to the old day');
  await repair.read(repairRecord,0,2000,repairApi);assert.equal(repairCalls,2);
  repairRecord.app_revision=2;await repair.read(repairRecord,0,2000,repairApi);assert.equal(repairCalls,3,'a later attribution revision invalidates completed cache');
  const panel=load(path.join(root,'record-usage.js'));
  await panel.link((specifier,parent)=>load(path.resolve(path.dirname(parent.identifier),specifier)));await panel.evaluate();
  const {RecordUsagePanel}=panel.namespace,p=Object.create(RecordUsagePanel.prototype);p.mode='energy';
  const day=new Date(2026,9,20).getTime(),end=day+3600000,entry={revision:0,spans:[[day,end,1]],appSpans:[],appLabels:{},attempts:{},appAttempts:{},appFinal:{},settledEnd:end};
  const view=Object.create(RecordUsagePanel.prototype);let appCalls=0;
  Object.assign(view,{mode:'time',record:{id:9,start_wall:day,start_elapsed:0,end_elapsed:3600000,active:0,kind:'discharge'},day,token:0,busyKey:null,key:null,force:false,cache:{get:async()=>entry,save:async()=>{}},node:()=>({}),drawEmpty(){},draw(){},scheduleRetry(){},api:async()=>{appCalls++;return {segments:appCalls===1?[]:[[day,end,'repaired']],labels:{},complete:appCalls!==1,appRevision:0};}});
  await view.load();assert.equal(entry.appFinal[day],undefined);assert.ok(Number.isFinite(view.refreshAt),'incomplete ended records must remain retryable');
  view.key=null;view.force=true;await view.load();assert.equal(appCalls,2);assert.equal(entry.appFinal[day],end);
  const scale=p.chartScale([.08]);assert.ok(scale.maximum>=.08);assert.equal(scale.divisor,1);
  let switched=0,scrubbed=0;p.onSwipe=()=>switched++;p.cancelPress=()=>p.press=null;
  p.press={x:100,y:10,active:false};assert.equal(p.movePress(45,12),true);assert.equal(switched,1);
  p.hourAt=()=>8;p.requestHourDetails=()=>scrubbed++;p.press={x:100,y:10,active:true,hour:2};
  p.movePress(45,12);assert.equal(switched,1);assert.equal(scrubbed,1,'held drag scrubs instead of switching view');
  // Fully covered time/app results must still be replaced after a repair.
  const {ScreenTimeCache,recordCacheKeyMatches}=modules.get(path.join(root,'screen-time-cache.js')).namespace;
  const screenCache=new ScreenTimeCache(),screenRecord={...view.record,id:10,app_revision:0};
  screenCache.run=async()=>({revision:0,appSchema:2,spans:[[day,end,1]],appSpans:[[day,end,'old']],appLabels:{},appFinal:{[day]:end}});screenCache.save=async()=>{};
  await screenCache.get(screenRecord);screenRecord.app_revision=1;let timeCalls=0,drawn;
  Object.assign(view,{record:screenRecord,cache:screenCache,key:null,busyKey:null,force:true,api:async action=>{
    timeCalls++;return action==='screen_usage'?{segments:[[day,day+1800000,1],[day+1800000,end,0]],settledEnd:end,appRevision:1}:{segments:[[day,day+1800000,'new'],[day+1800000,end,'__screenoff__']],labels:{},complete:true,appRevision:1};
  },draw(value){drawn=value;}});
  await view.load();assert.equal(timeCalls,2);assert.equal(drawn.totalMs,1800000);assert.equal(view.hourDetails[0].rows[0].pkg,'new');
  const replaced=await screenCache.get(screenRecord);assert.equal(replaced.revision,1);assert.equal(replaced.appSpans.some(s=>s[2]==='old'),false);
  await screenCache.get(screenRecord);assert.equal(timeCalls,2,'unchanged revisions keep confirmed data');
  assert.equal(await view.adoptRevision(replaced,screenRecord,2),true);assert.equal(replaced.spans.length,0);assert.equal(replaced.appSpans.length,0);assert.equal(view.dirty,true);

  // All persistent cache types share retryable opening, including synchronous
  // errors, blocked upgrades, timeouts and late success from a failed attempt.
  const daily=load(path.join(root,'daily-usage-cache.js'));if(daily.status==='unlinked')await daily.link((specifier,parent)=>load(path.resolve(path.dirname(parent.identifier),specifier)));if(daily.status==='linked')await daily.evaluate();
  for(const Cache of [ScreenTimeCache,RecordEnergyCache,daily.namespace.DailyUsageCache])for(const failure of ['error','blocked','throw','timeout']){
    let attempts=0,timeout,mode=failure;const requests=[];
    context.setTimeout=fn=>{timeout=fn;return 1};context.clearTimeout=()=>{};
    context.indexedDB={open(){attempts++;if(mode==='throw')throw Error('temporary');const request={};requests.push(request);return request;}};
    const opening=new Cache(),first=opening.open(),failed=assert.rejects(first);
    if(failure==='error'){requests[0].error=Error('temporary');requests[0].onerror();}
    else if(failure==='blocked')requests[0].onblocked();else if(failure==='timeout')timeout();
    await failed;assert.equal(opening.ready,null);
    mode='success';const retry=opening.open();assert.equal(opening.open(),retry,'share concurrent open');
    const db={closed:false,close(){this.closed=true}};requests.at(-1).result=db;requests.at(-1).onsuccess();assert.equal(await retry,db);assert.equal(attempts,2);
    if(failure!=='throw'){const late={closed:false,close(){this.closed=true}};requests[0].result=late;requests[0].onsuccess();assert(late.closed);assert.equal(opening.ready,retry);}
    db.onversionchange();assert.equal(opening.ready,null);
  }
  context.setTimeout=setTimeout;context.clearTimeout=clearTimeout;

  // GC checks each primary key without reading large historical chart values.
  context.IDBKeyRange={only:value=>value,lowerBound:value=>value};context.localStorage={getItem:()=>0,setItem(){}};
  const gc=new ScreenTimeCache(),keys=new Set(['1:100','1:100:day','1:200','1:200:day','2:300']);let removedId;
  gc.gcAfter=0;gc.entries.set('1:100',{recordId:1});gc.entries.set('1:200',{recordId:1});
  gc.remove=async id=>{removedId=id;for(const key of keys)if(key.startsWith(id+':'))keys.delete(key);};
  gc.run=async(mode,action)=>{
    const pending=[];
    const cursorFor=rows=>{
      const request={};let i=0;
      const advance=()=>Promise.resolve().then(()=>{request.result=i<rows.length?{key:rows[i][0],primaryKey:rows[i++][1],continue:advance}:null;request.onsuccess();});pending.push(advance());return request;
    };
    action({delete:key=>keys.delete(key),index:()=>({openKeyCursor:(range,direction)=>direction==='nextunique'?cursorFor([[1,'1:100'],[2,'2:300']]):cursorFor([...keys].filter(key=>key.startsWith(range+':')).map(key=>[range,key]))})});
    await Promise.all(pending);for(let n=0;n<10;n++)await Promise.resolve();
  };
  await gc.prune(async()=>({ids:[1],records:[[1,200]]}));assert.equal(removedId,2);assert.deepEqual([...keys],['1:200','1:200:day']);assert.equal(gc.entries.has('1:100'),false);assert(gc.entries.has('1:200'));
  assert.equal(gc.canSave(1,'1:100'),false,'late stale writes cannot recreate old generations');assert(gc.canSave(1,'1:200'));
  assert.equal(recordCacheKeyMatches('1:2000',1,200),false,'identity matches exact timestamp boundaries');

  const interval=load(path.join(root,'interval-app-details.js'));await interval.link((specifier,parent)=>load(path.resolve(path.dirname(parent.identifier),specifier)));await interval.evaluate();
  const {IntervalAppDetails}=interval.namespace,{IntervalAppCache}=modules.get(path.join(root,'interval-app-cache.js')).namespace;
  const intervalRecord={...screenRecord,id:20,app_revision:0},stored={};let intervalCalls=0;
  const intervalCache=new IntervalAppCache();intervalCache.run=async(mode,work)=>work({get:()=>stored.value,put:value=>{stored.value=JSON.parse(JSON.stringify(value));}});
  let details=new IntervalAppDetails({},async(action,id,cursor)=>{
    intervalCalls++;if(intervalCalls>1)assert.equal(cursor,3600000,'retry begins at first unconfirmed interval');
    return{apps:[[3600000,intervalCalls===1?null:'recovered','missing']],labels:{},cursor:3600001,more:false,through:3600000,complete:intervalCalls>1,retryCursor:intervalCalls===1?3600000:null};
  },()=>intervalRecord,{read:async()=>stored.value,save:async(record,state)=>intervalCache.save(record,state)});
  await details.preload(intervalRecord);assert.equal(stored.value.complete,false);assert.equal(stored.value.schema,3);
  stored.value.retryAt=0;
  details=new IntervalAppDetails({},details.api,()=>intervalRecord,{read:async()=>intervalCache.read(intervalRecord),save:async(record,state)=>intervalCache.save(record,state)});
  await details.preload(intervalRecord);assert.equal(intervalCalls,2);assert.equal(stored.value.complete,true);assert.equal(stored.value.names[0][1],'recovered');
  await details.preload(intervalRecord);assert.equal(intervalCalls,2,'confirmed completed results remain cached');
  stored.value.schema=2;assert.equal(await intervalCache.read(intervalRecord),null,'old potentially finalized missing results are revalidated');
  let pageCalls=0;const pagedRecord={...intervalRecord,end_elapsed:2000};
  const paged=new IntervalAppDetails({},async(action,id,cursor)=>{
    pageCalls++;if(pageCalls===2)throw Error('second page unavailable');
    if(pageCalls===3)assert.equal(cursor,1000,'a later page error cannot discard an earlier unconfirmed cursor');
    return cursor<=1000?{apps:[[1000,pageCalls===1?null:'fixed','app']],labels:{},cursor:1001,more:true,through:2000,complete:pageCalls!==1,retryCursor:pageCalls===1?1000:null}:{apps:[[2000,'tail','app']],labels:{},cursor:2001,more:false,through:2000,complete:true,retryCursor:null};
  },()=>pagedRecord,{read:async()=>null,save:async()=>{}});
  await paged.preload(pagedRecord);const pagedState=paged.records.get(paged.key(pagedRecord));pagedState.retryAt=0;
  await paged.preload(pagedRecord);assert.equal(pagedState.complete,true);assert.equal(pagedState.names.get(1000),'fixed');assert.equal(pageCalls,4);
  let releaseRevision,revisionCalls=0;const mutableRecord={...pagedRecord,app_revision:0},writes=[];
  const changing=new IntervalAppDetails({},async()=>{
    if(++revisionCalls===1)await new Promise(resolve=>releaseRevision=resolve);
    return{apps:[[2000,revisionCalls===1?'old':'new','app']],labels:{},cursor:2001,more:false,through:2000,complete:true,retryCursor:null};
  },()=>mutableRecord,{read:async()=>null,save:async(record,state)=>writes.push([record.app_revision,[...state.names][0][1]])});
  const oldRead=changing.preload(mutableRecord);while(!releaseRevision)await Promise.resolve();mutableRecord.app_revision=1;releaseRevision();await oldRead;
  for(let n=0;n<12;n++)await Promise.resolve();assert.deepEqual(writes,[[0,'old'],[1,'new']],'in-flight old data is never saved under a newer revision');
  console.log('Record caches: revision replacement, retryable opens, generation GC, persistent partial interval retry, incremental/concurrent energy and gestures passed');
})().catch(error=>{console.error(error);process.exitCode=1;});
