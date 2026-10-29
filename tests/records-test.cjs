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
}

async function recordLists(){
const root=path.join(__dirname,'../src/main/assets/webroot/');
const liveSource=fs.readFileSync(path.join(root,'live-channel.js'),'utf8').replace(/^import[^\n]+\n/,'');
 const{LiveChannel}=await import('data:text/javascript;base64,'+Buffer.from('const spawn=()=>{},exec=async()=>{};'+liveSource).toString('base64'));
 const source=fs.readFileSync(path.join(root,'app.js'),'utf8');
 const functions=source.slice(source.indexOf('function filteredList('),source.indexOf('function renderStatus('));
 const small={checked:false},shell={hidden:false},confirmation={open:false};
 const calls=[],channel=new LiveChannel();let database=[],version=1,delayGame=null;
 const context={kind:'game',monthFilter:null,listSelection:0,filterRequest:0,filterBusy:false,filteredLists:new Map(),
  records:[],total:0,recordsReady:false,uiSettings:{hidePct:30},liveChannel:channel,listDue:true,lastListAt:0,
  document:{hidden:false},performance,request:0,detail:null,activeId:null,streamId:null,streamAfter:0,lastStatus:null,
  liveBusy:false,detailBusy:0,loading:false,queuedPoll:false,activeTab:'records',batteryPanel:{active:false},
  $:id=>id==='showSmall'?small:id==='appShell'?shell:id==='confirm'?confirmation:{setAttribute(){}},
  renderRecords(){},revealRecordDelete(){},refresh(){this.listDue=true;},toast:message=>{throw Error(message);},
  renderStatus(){},renderHomeActive(){},recordFast:()=>false,schedulePoll(){},
  homeUsage:{connected(){}},screenTimePanel:{prune(){}},renderGameFrequencies(){},gameFrequencies:null,
  api:async(action,...args)=>{
   if(action==='live')return{status:{session:-1,interactive:true},records:[],total:0};
   calls.push(args);const[all,offset,year,month,type]=args;
   if(type===3&&delayGame)return delayGame;
   const filtered=database.filter(r=>(all||r.active||r.fast_seconds>0||r.delta>30)
    &&(!year||r.year===year&&r.month===month)
    &&(!type||(type===1?r.kind==='charge':type===2?r.kind==='discharge'&&!r.fast_seconds:r.kind==='discharge'&&r.fast_seconds>0)));
   return{records:filtered.slice(offset,offset+100),total:filtered.length,listVersion:version};
  }
 };
 vm.createContext(context);vm.runInContext(functions,context);
 const record=(id,kind,fast=0,month=10)=>({id,kind,fast_seconds:fast,delta:50,active:false,year:2026,month});
 database=[...Array.from({length:101},(_,i)=>record(200-i,'charge')),record(1,'discharge',10)];
 await context.loadFiltered(true);assert.deepEqual(Array.from(context.records,r=>r.id),[1]);assert.equal(context.total,1);assert.equal(calls.at(-1)[4],3);
 context.monthFilter={year:2026,month:10};context.selectRecords();await new Promise(setImmediate);
 database.unshift(record(201,'discharge',10));
 await context.pollLive();assert.deepEqual(Array.from(context.records,r=>r.id),[201,1]);assert.equal(context.total,2,'live snapshots refresh the month query');
 database=database.filter(r=>r.id!==1);version++;
 await context.pollLive();assert.deepEqual(Array.from(context.records,r=>r.id),[201]);assert.equal(context.total,1,'automatic deletion removes stale month rows');
 context.cancelMonthFilter();await new Promise(setImmediate);assert.equal(context.kind,'game');assert.equal(calls.at(-1)[4],3);assert.equal(calls.at(-1)[2],0);
 database=Array.from({length:205},(_,i)=>record(500-i,'discharge',10));version++;
 await context.loadFiltered(true);await context.loadFiltered(false);assert.equal(context.records.length,200);
 await context.pollLive();assert.equal(context.records.length,200,'head refresh retains loaded later pages');assert.equal(new Set(context.records.map(r=>r.id)).size,200);
 let resolveOld;delayGame=new Promise(resolve=>resolveOld=resolve);
 const pending=context.loadFiltered(true);
 database=[record(900,'charge')];context.kind='charge';context.selectRecords();await new Promise(setImmediate);
 resolveOld({records:[record(1,'discharge',10)],total:1,listVersion:version});await pending;delayGame=null;
 assert.deepEqual(Array.from(context.records,r=>r.id),[900],'old filter responses cannot overwrite a new selection');
 assert.equal(context.filterBusy,false);assert.equal(context.total,1);
}

async function recordDisplay(){
const root=path.join(__dirname,'../src/main/assets/webroot/');
const{recordChanges}=await import(pathToFileURL(path.join(root,'record-display.js')).href);
const record={kind:'discharge',pct_start:81,pct_end:77,rm_start:5100,rm_end:4800};
  const percentage=recordChanges('percentage',record);
  assert.deepEqual(percentage.primary,{text:'−4%',flow:'discharge',label:'百分比变化'});
  assert.deepEqual(percentage.secondary,{text:'−300',flow:'discharge',label:'容量变化'});
  const capacity=recordChanges('capacity',record);
  assert.deepEqual(capacity.primary,{text:'−300',flow:'discharge',label:'容量变化'});
  assert.deepEqual(capacity.secondary,{text:'−4%',flow:'discharge',label:'百分比变化'});
}

runCases([liveCursors,recordLists,recordDisplay]).then(()=>console.log('Records: live cursors, filters, pagination, races and capacity display passed')).catch(error=>{console.error(error);process.exitCode=1;});
