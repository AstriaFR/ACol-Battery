const path=require('node:path'),assert=require('node:assert/strict');const{pathToFileURL}=require('node:url');
(async()=>{
 const{batteryHistoryFields,batteryHistoryDate,BatteryHistoryPanel}=await import(pathToFileURL(path.join(__dirname,'../src/main/assets/webroot/battery-history.js')).href);
 const saved={qmax:6200,designCapacity:6500,fcc:6100,rawFcc:5800,lockedCapacity:400,soh:99,rawSoh:94.5,cutoffMv:3150,nearActual:['qmax','rawFcc','rawSoh'],noCompensation:false};
 const a=batteryHistoryFields(saved);assert.equal(a.values.qmax,'6,200 mAh (95.4%)');assert.equal(a.values.lockedCapacity,'400 mAh (-6.5%)');assert.ok(a.green.has('rawFcc')&&!a.green.has('fcc'));assert.equal(a.values.cutoffMv,'3,150 mV');
 const b=batteryHistoryFields({...saved,noCompensation:true,nearActual:['fcc','qmax']});assert.equal(b.values.rawFcc,'——');assert.equal(b.values.rawSoh,'——');assert.ok(b.green.has('fcc')&&!b.green.has('rawFcc'));
 assert.equal(batteryHistoryFields(null).values.fcc,'—');assert.equal(batteryHistoryFields(null).green.size,0);
 assert.ok(batteryHistoryDate(new Date(2026,10,1,12,34).getTime()).startsWith('2026/11/01 12:34'));
 assert.equal(batteryHistoryDate(new Date(2026,10,1,12,34).getTime(),true),'2026/11/01');
 let closed=false,reloaded=false;
 const panel=Object.assign(Object.create(BatteryHistoryPanel.prototype),{deleteId:12,token:1,active:true,deleting:false,items:new Map([[12,{}]]),snapshots:new Map([[12,saved]]),offset:20,total:21,loaded:true,deleteApply:{},deleteCancel:{},deleteDialog:{close(){closed=true}},rows:{replaceChildren(){}},scroller:{scrollTop:50},api:async(action,id)=>{assert.equal(action,'battery_history_delete');assert.equal(id,12)},load:async function(){assert.equal(this.offset,0);assert.equal(this.items.size,0);reloaded=true}});
 await panel.remove();assert.ok(closed&&reloaded);assert.equal(panel.snapshots.has(12),false);assert.equal(panel.deleting,false);assert.equal(panel.deleteApply.disabled,false);
 // A delete completing after navigation invalidates the cached list without touching the new page.
 panel.deleteId=13;panel.snapshots.set(13,saved);panel.api=async()=>{panel.token++};reloaded=false;await panel.remove();assert.equal(panel.listDirty,true);assert.equal(reloaded,false);assert.equal(panel.snapshots.has(13),false);
 let returned=false;panel.state={page:'battery-history-detail',id:14};panel.deleteId=14;panel.detailDelete={disabled:false};panel.api=async()=>{};panel.back=()=>{returned=true};await panel.remove();assert.ok(returned);assert.equal(panel.detailDelete.disabled,true);assert.equal(reloaded,false);assert.equal(panel.listDirty,true);
 let finish,calls=0;
 Object.assign(panel,{state:{page:'battery-history'},active:true,deleting:false,recordButton:{},status:{textContent:''},api:async action=>{assert.equal(action,'battery_history_record');calls++;await new Promise(resolve=>{finish=resolve})},load:async function(){assert.equal(this.offset,0);assert.equal(this.items.size,0);this.status.textContent='';reloaded=true}});
 let prompts=0;panel.recordDialog={open:false,showModal(){this.open=true;prompts++},close(){this.open=false}};panel.recordCancel={focus(){}};
 panel.askRecord();panel.askRecord();assert.equal(prompts,1);assert.equal(calls,0,'opening confirmation never records data');
 panel.recordDialog.close();assert.equal(calls,0,'cancel leaves data unchanged');
 panel.state={page:'battery-history-detail'};panel.askRecord();assert.equal(prompts,1,'cannot prompt from a stale history page');panel.state={page:'battery-history'};
 reloaded=false;const saving=panel.record();await panel.record();assert.equal(calls,1);assert.equal(panel.recordButton.disabled,true);finish();await saving;
 assert.ok(reloaded);assert.equal(panel.status.textContent,'已记录当前电池信息');assert.equal(panel.recordButton.disabled,false);assert.equal(panel.recordButton.textContent,'记录');
 panel.api=async()=>{throw Error('读取失败')};await panel.record();assert.equal(panel.status.textContent,'记录失败：读取失败');assert.equal(panel.saving,false);assert.equal(panel.recordButton.disabled,false);
 panel.api=async()=>{panel.active=false;panel.token++};reloaded=false;await panel.record();assert.equal(reloaded,false);assert.equal(panel.listDirty,true,'saving after navigation invalidates the cached list');
 console.log('Battery history UI: stored values and trust markers, ratios, low-cutoff fields and dated entries passed');
})().catch(e=>{console.error(e);process.exitCode=1});
