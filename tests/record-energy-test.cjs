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
  const day=new Date(2026,9,20).getTime(),end=day+3600000,entry={spans:[[day,end,1]],appSpans:[],appLabels:{},attempts:{},appAttempts:{},appFinal:{},settledEnd:end};
  const view=Object.create(RecordUsagePanel.prototype);let appCalls=0;
  Object.assign(view,{mode:'time',record:{id:9,start_wall:day,start_elapsed:0,end_elapsed:3600000,active:0,kind:'discharge'},day,token:0,busyKey:null,key:null,force:false,cache:{get:async()=>entry,save:async()=>{}},node:()=>({}),drawEmpty(){},draw(){},api:async()=>{appCalls++;return {segments:appCalls===1?[]:[[day,end,'repaired']],labels:{},complete:appCalls!==1,appRevision:appCalls-1};}});
  await view.load();assert.equal(entry.appFinal[day],undefined);assert.ok(Number.isFinite(view.refreshAt),'incomplete ended records must remain retryable');
  view.key=null;view.force=true;await view.load();assert.equal(appCalls,2);assert.equal(entry.appFinal[day],end);
  const scale=p.chartScale([.08]);assert.ok(scale.maximum>=.08);assert.equal(scale.divisor,1);
  let switched=0,scrubbed=0;p.onSwipe=()=>switched++;p.cancelPress=()=>p.press=null;
  p.press={x:100,y:10,active:false};assert.equal(p.movePress(45,12),true);assert.equal(switched,1);
  p.hourAt=()=>8;p.requestHourDetails=()=>scrubbed++;p.press={x:100,y:10,active:true,hour:2};
  p.movePress(45,12);assert.equal(switched,1);assert.equal(scrubbed,1,'held drag scrubs instead of switching view');
  console.log('Record usage: partial retry, attribution repair/revision, incremental/concurrent cache and gestures passed');
})().catch(error=>{console.error(error);process.exitCode=1;});
