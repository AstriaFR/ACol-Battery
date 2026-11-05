const path=require('node:path'),assert=require('node:assert/strict');
const{pathToFileURL}=require('node:url');
const fs=require('node:fs'),vm=require('node:vm');
const{runCases}=require('./helpers.cjs');

async function liveCursors(){
const source=fs.readFileSync(path.join(__dirname,'../src/main/assets/webroot/live-channel.js'),'utf8').replace(/^import[^\n]+\n/,'');
 const {LiveChannel}=await import('data:text/javascript;base64,'+Buffer.from('const spawn=()=>{},exec=async()=>{};'+source).toString('base64'));
 const channel=new LiveChannel();
 const packet=(id,extra={})=>({ok:true,status:{session:id,settings:{hidePct:30}},...extra});
 channel.accept(packet(107,{record:{id:107},points:[[3600000,1000]]}));
 assert.equal(channel.resumeAfter,3600000);
 channel.setMode(1);channel.accept(packet(107,{batteryInfo:{cutoffMv:3050}}));
 assert.equal(channel.resumeAfter,3600000); // Same-record metadata preserves its cursor.
 channel.accept(packet(108,{batteryInfo:{cutoffMv:3050}}));
 assert.equal(channel.resumeId,108);assert.equal(channel.resumeAfter,0);
 channel.setMode(0);channel.accept(packet(108,{record:{id:108},points:[[2000,999]]}));
 assert.equal(channel.resumeAfter,2000);
 channel.accept(packet(108));assert.equal(channel.resumeAfter,2000);
 channel.setMode(1);channel.accept(packet(109,{batteryInfo:{cutoffMv:3050}}));
 assert.equal(channel.resumeAfter,0);
 channel.accept(packet(108));assert.equal(channel.resumeAfter,2000); // Reuse only this ID's cached cursor.
 channel.accept(packet(-1));assert.equal(channel.resumeId,0);assert.equal(channel.resumeAfter,0);
 channel.accept(packet(-1,{records:[{id:21},{id:20}],total:60,totalRecords:80,listSmall:0}));
 assert.equal(channel.cachedList(false).total,60);assert.equal(channel.cachedList(false).totalRecords,80);assert.equal(channel.cachedList(true),null,'other visibility mode must not preload another page');
 channel.appendList(false,{records:[{id:19},{id:18}],total:60,totalRecords:80});
 channel.accept(packet(-1,{records:[{id:21},{id:20}],total:60,totalRecords:80,listSmall:0}));assert.equal(channel.cachedList(false).records.length,4,'streamed head preserves explicitly loaded pages');
 const normal={id:5,active:1,fast_seconds:0},game={id:4,active:1,fast_seconds:3};
 channel.accept(packet(4,{record:game,points:[[100,1000]],activeRecord:game,parallel:{record:normal,points:[[120000,990]]},records:[game,normal,{id:21},{id:20}],total:6,totalRecords:6,listSmall:0}));
 assert.equal(channel.rows.get(5).points.at(-1)[0],120000,'parallel ordinary data is merged into its own cursor');
 assert.deepEqual(channel.cachedList(false).records.slice(0,2).map(row=>row.id),[4,5]);
 channel.appendList(false,{records:[{id:19},{id:18}],total:6,totalRecords:6});assert.deepEqual(channel.cachedList(false).records.map(row=>row.id),[4,5,21,20,19,18],'pagination keeps game before concurrent ordinary record regardless of ID');
 channel.accept(packet(4,{record:game,parallelPrevious:{record:{...normal,active:0},points:[[121000,989]]},parallel:{record:{id:6,active:1,fast_seconds:0},points:[[0,989]]}}));
 assert.equal(channel.rows.get(5).record.active,0,'power-state changes close only the old ordinary stream');assert.equal(channel.rows.get(4).record.active,1);assert.equal(channel.rows.get(6).record.active,1);
 const stats=new LiveChannel(),statsRecord={id:1,start_wall:1000,app_revision:0};
 const statsPacket=(epoch,version,apps)=>packet(1,{record:{...statsRecord},statsEpoch:epoch,statsVersion:version,apps,points:[]});
 stats.accept(statsPacket('first',0,[]));
 stats.settle(statsRecord,{apps:[{pkg:'ready'}],drainSummary:[],statsVersion:1,statsEpoch:'first',appRevision:1,usagePending:false});
 stats.accept(statsPacket('first',0,[]));assert.equal(stats.rows.get(1).apps[0].pkg,'ready','queued empty watch totals cannot overwrite completed settlement');assert.equal(stats.rows.get(1).record.app_revision,1);
 stats.accept(statsPacket('first',1,[{pkg:'ready'}]));assert.equal(stats.rows.get(1).apps[0].pkg,'ready');
 stats.accept(statsPacket('restarted',0,[{pkg:'new-process'}]));assert.equal(stats.rows.get(1).apps[0].pkg,'new-process','recorder restarts can reset the in-memory version counter');
 stats.settle({...statsRecord,start_wall:2000},{apps:[],statsVersion:5,statsEpoch:'restarted'});assert.equal(stats.rows.get(1).apps[0].pkg,'new-process','another record generation cannot publish totals');
}

async function recordLists(){
 const root=path.join(__dirname,'../src/main/assets/webroot/');
 const dom=require('./helpers.cjs').recordDom();globalThis.document=dom.document;globalThis.window={};
 globalThis.history={state:{page:'records'},pushState(state){this.state=state},replaceState(state){this.state=state},go(){},back(){}};globalThis.location={href:'https://aco.local/webroot/index.html'};
 const{createRecords}=await import(pathToFileURL(path.join(root,'records.js')).href);
 const{LiveChannel}=await import(pathToFileURL(path.join(root,'live-channel.js')).href);
 const channel=new LiveChannel(),calls=[],deleted=[],opened=[];let database=[],version=1,delayGame=null;
 const ui=createRecords({liveChannel:channel,toast:message=>{if(message!=='记录已删除')throw Error(message)},getSettings:()=>({hidePct:30}),getStatus:()=>({recordingEnabled:true}),getDetail:()=>null,getTab:()=> 'records',
  openRecord:id=>opened.push(id),onKindChange(){},pauseRemaining(){},onDialogClose(){},onDeleted:id=>deleted.push(id),onRefresh(){},
  api:async(action,...args)=>{
   if(action==='delete'){database=database.filter(r=>r.id!==args[0]);return{listVersion:++version};}
   calls.push(args);const[all,offset,year,month,type]=args;if(type===3&&delayGame)return delayGame;
   const filtered=database.filter(r=>(all||r.active||r.fast_seconds>0||r.delta>30)&&(!year||r.year===year&&r.month===month)&&(!type||(type===1?r.kind==='charge':type===2?r.kind==='discharge'&&!r.fast_seconds:r.kind==='discharge'&&r.fast_seconds>0)));
   return{records:filtered.slice(offset,offset+40),total:filtered.length,totalRecords:database.length,listVersion:version};
  }});
 ui.bind();
 const flush=()=>new Promise(setImmediate),record=(id,kind,fast=0,month=10)=>({id,kind,fast_seconds:fast,delta:50,active:0,year:2026,month,start_wall:1000,start_elapsed:0,end_elapsed:120000,pct_start:80,pct_end:40,rm_start:5000,rm_end:2500});
 database=[...Array.from({length:101},(_,i)=>record(200-i,'charge')),record(1,'discharge',10)];
 ui.selectKind('game');await flush();assert.deepEqual(ui.snapshot().records.map(r=>r.id),[1]);assert.equal(ui.snapshot().total,1);assert.equal(ui.snapshot().totalRecords,database.length);assert.equal(calls.at(-1)[4],3);
 ui.selectMonth(2026,10);await flush();database.unshift(record(201,'discharge',10));
 await ui.receive({records:[],activeRecord:null},ui.pollRequest());assert.deepEqual(ui.snapshot().records.map(r=>r.id),[201,1]);assert.equal(ui.snapshot().total,2,'live month queries refresh counts');
 database=database.filter(r=>r.id!==1);version++;await ui.receive({records:[]},ui.pollRequest());assert.deepEqual(ui.snapshot().records.map(r=>r.id),[201],'automatic deletion removes stale filtered rows');
 ui.cancelMonth();await flush();assert.equal(ui.kind,'game');assert.equal(calls.at(-1)[2],0);
 database=Array.from({length:205},(_,i)=>record(500-i,'discharge',10));version++;ui.selectKind('game');await flush();assert.equal(ui.snapshot().records.length,40);
 await ui.more();assert.equal(ui.snapshot().records.length,80);await ui.receive({records:[]},ui.pollRequest());assert.equal(ui.snapshot().records.length,80,'head refresh retains loaded pages');assert.equal(new Set(ui.snapshot().records.map(r=>r.id)).size,80);
 let release;delayGame=new Promise(resolve=>release=resolve);const pending=ui.receive({records:[]},ui.pollRequest());
 database=[record(900,'charge')];ui.selectKind('charge');await flush();release({records:[record(1,'discharge',10)],total:1,listVersion:version});await pending;delayGame=null;
 assert.deepEqual(ui.snapshot().records.map(r=>r.id),[900],'old requests cannot overwrite a new filter');
 const card=dom.get('records').children[0];await card.click();assert.deepEqual(opened,[900]);
 const remove=card.querySelector('.record-delete');await remove.click();assert.equal(dom.get('confirm').open,true);await dom.get('confirmDelete').click();
 assert.deepEqual(deleted,[900]);assert.equal(ui.snapshot().totalRecords,0);assert.equal(dom.get('confirm').open,false);
}

async function recordDisplay(){
const root=path.join(__dirname,'../src/main/assets/webroot/');
const{recordChanges,recordIsLive,recordEndWall}=await import(pathToFileURL(path.join(root,'record-display.js')).href);
const{canEstimateRemaining}=await import(pathToFileURL(path.join(root,'estimate.js')).href);
const status={session:20,normalSession:10,gameSession:20};
const started=Date.UTC(2026,11,31,23,50),ended={active:0,start_wall:started,start_elapsed:1000,end_elapsed:1801000};
assert.equal(recordEndWall(ended),Date.UTC(2027,0,1,0,20));
assert.equal(recordEndWall({...ended,active:1}),null);
assert.equal(recordEndWall({...ended,end_elapsed:999}),null);
assert.equal(recordEndWall({...ended,start_wall:null}),null);
assert.equal(recordEndWall({...ended,end_elapsed:1000}),started);
assert.ok(recordIsLive({id:10,active:1},status));assert.ok(recordIsLive({id:20,active:1},status));assert.ok(!recordIsLive({id:20,active:0},status));assert.ok(!recordIsLive({id:30,active:1},status));
assert.equal(canEstimateRemaining({fast_seconds:3},{online:true,onlineKnown:true}),false);
assert.equal(canEstimateRemaining({fast_seconds:3},{online:null,onlineKnown:false}),false);
assert.equal(canEstimateRemaining({fast_seconds:3},{online:false,onlineKnown:true}),true);
assert.equal(canEstimateRemaining({fast_seconds:0},{online:true,onlineKnown:true}),true);
const record={kind:'discharge',pct_start:81,pct_end:77,rm_start:5100,rm_end:4800};
  const changes=recordChanges(record);
  assert.deepEqual(changes.primary,{text:'−4%',flow:'discharge'});
  assert.deepEqual(changes.secondary,{text:'−300',flow:'discharge'});
  const charge=recordChanges({...record,kind:'charge',pct_start:40,pct_end:80,rm_start:2500,rm_end:5000});
  assert.deepEqual(charge.primary,{text:'+40%',flow:'charge'});
  assert.deepEqual(charge.secondary,{text:'+2,500',flow:'charge'});
  const game={...record,fast_seconds:3,pct_start:40,pct_end:60,rm_start:2500,rm_end:3500};
  assert.equal(recordChanges(game).primary.flow,'charge');assert.equal(recordChanges(game).secondary.flow,'charge');
  assert.equal(recordChanges({...game,pct_end:30,rm_end:2000}).primary.flow,'discharge');assert.equal(recordChanges({...game,rm_end:2000}).secondary.flow,'discharge');
  const unchanged=recordChanges({...game,pct_end:40,rm_end:2500});assert.deepEqual(unchanged.primary,{text:'+0%',flow:''});assert.deepEqual(unchanged.secondary,{text:'+0',flow:''});
}

async function detailPaging(){
 globalThis.window={};
 const{readRecordDetail}=await import(pathToFileURL(path.join(__dirname,'../src/main/assets/webroot/record-detail.js')).href);
 const calls=[],record={id:9,start_elapsed:10,end_elapsed:310};
 const api=async(...args)=>{calls.push(args);return calls.length===1?{record,points:[[0]],nextAfter:100,apps:[]}:{record,points:[[200]],nextAfter:null};};
 const data=await readRecordDetail(api,9,0,1000,1600);assert.deepEqual(data.points,[[0],[200]]);assert.equal(calls[1][3],300,'later pages stay within the opened record endpoint');
 let canceled=false,count=0;
 const stale=await readRecordDetail(async()=>{count++;canceled=true;return{record,points:[[0]],nextAfter:100}},9,0,1000,1600,()=>canceled);
 assert.equal(stale,null);assert.equal(count,1,'leaving the page prevents additional detail pages');
}

async function usageCompletion(){
 const{RecordUsageRefresh}=await import(pathToFileURL(path.join(__dirname,'../src/main/assets/webroot/record-usage-refresh.js')).href);
 let now=0,serial=0,shown=true,record={id:7,start_wall:1000,kind:'discharge',active:0,app_revision:0},calls=0,read;
 const timers=new Map(),updates=[];
 const options={now:()=>now,setTimer:(fn,delay)=>{const id=++serial;timers.set(id,{fn,at:now+delay});return id},clearTimer:id=>timers.delete(id)};
 let refresh;
 const pending=()=>({record:{...record},apps:[],statsVersion:0,usagePending:true});
 const complete=()=>({apps:[{pkg:'app',energyMwh:50}],drainSummary:[],statsVersion:1,appRevision:0,usagePending:false});
 const apply=(result,id)=>{updates.push({id,...result});refresh.update({record:{...record},...result});};
 refresh=new RecordUsageRefresh(async(action,id)=>{assert.equal(action,'settle_usage');calls++;return read(id);},()=>record,()=>shown,apply,options);
 const advance=async ms=>{now+=ms;for(const[id,task]of [...timers])if(task.at<=now){timers.delete(id);task.fn();}for(let i=0;i<12;i++)await Promise.resolve();};
 read=async()=>calls===1?{...complete(),apps:[],statsVersion:0,usagePending:true}:complete();
 refresh.update(pending());refresh.update(pending());assert.equal(timers.size,1,'re-rendering does not duplicate or delay settlement');
 await advance(500);assert.equal(calls,1);assert.equal(updates[0].usagePending,true);
 await advance(2999);assert.equal(calls,1);await advance(1);assert.equal(calls,2);assert.equal(updates.at(-1).apps[0].pkg,'app');
 await advance(60000);assert.equal(calls,2,'ended record completes without any new curve points and stops retrying');
 refresh.close();timers.clear();calls=0;updates.length=0;read=async()=>{if(calls===1)throw Error('temporary event read failure');return complete();};
 refresh.update(pending());await advance(500);assert.equal(calls,1);assert.equal(updates.length,0);
 shown=false;refresh.pause();await advance(60000);assert.equal(calls,1,'background pages do not settle');
 shown=true;refresh.resume();await advance(0);assert.equal(calls,2);assert.equal(updates.length,1,'resume retries after transient failure');
 refresh.close();timers.clear();calls=0;updates.length=0;let release;
 read=async()=>new Promise(resolve=>release=resolve);refresh.update(pending());await advance(500);assert.equal(calls,1);
 record={...record,id:8,start_wall:2000};refresh.update(pending());release(complete());await advance(0);assert.equal(updates.length,0,'previous record completion cannot overwrite another record');
 read=async()=>complete();await advance(500);assert.equal(updates.at(-1).id,8);
 refresh.close();timers.clear();calls=0;updates.length=0;
 read=async()=>new Promise(resolve=>release=resolve);refresh.update(pending());await advance(500);
 refresh.update({record,apps:[{pkg:'newer'}],statsVersion:2,usagePending:false});release(complete());await advance(0);
 assert.equal(updates.length,0,'in-flight settlement cannot replace a newer displayed result');
 refresh.close();refresh.update({record,apps:[],statsVersion:2,usagePending:false});await advance(60000);assert.equal(calls,1,'confirmed empty results do not repeatedly recalculate');
 refresh.close();timers.clear();calls=0;updates.length=0;read=async()=>new Promise(resolve=>release=resolve);
 refresh.update(pending());await advance(500);shown=false;refresh.pause();release(complete());await advance(0);assert.equal(updates.length,0,'completion while hidden does not repaint or keep polling');assert.equal(timers.size,0);
 shown=true;read=async()=>complete();refresh.resume();await advance(3000);assert.equal(updates.length,1,'pending result resumes automatically on returning to the page');
 refresh.close();
 // Browser timer APIs reject an arbitrary object receiver. Exercise defaults,
 // rather than only the injected clock used by the retry timing cases above.
 const originalSet=globalThis.setTimeout,originalClear=globalThis.clearTimeout,pendingTimers=new Map();let handle=0,defaultApplied=false;
 try{
  globalThis.setTimeout=function(callback){if(this!==undefined&&this!==globalThis)throw TypeError('Illegal invocation');pendingTimers.set(++handle,callback);return handle;};
  globalThis.clearTimeout=function(id){if(this!==undefined&&this!==globalThis)throw TypeError('Illegal invocation');pendingTimers.delete(id);};
  const native=new RecordUsageRefresh(async()=>complete(),()=>record,()=>true,result=>{defaultApplied=true;native.update({record,...result});});
  native.update(pending());native.pause();assert.equal(pendingTimers.size,0);native.resume();assert.equal(pendingTimers.size,1);
  [...pendingTimers.values()][0]();for(let i=0;i<8;i++)await Promise.resolve();assert.equal(defaultApplied,true,'default timers support the browser receiver contract');native.close();
 }finally{globalThis.setTimeout=originalSet;globalThis.clearTimeout=originalClear;}
}

runCases([liveCursors,recordLists,recordDisplay,detailPaging,usageCompletion]).then(()=>console.log('Records: list/detail pagination, filters, deletion, live cursors, races, display and deferred usage completion passed')).catch(error=>{console.error(error);process.exitCode=1;});
