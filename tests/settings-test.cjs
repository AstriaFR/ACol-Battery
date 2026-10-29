const path=require('node:path'),assert=require('node:assert/strict');
const{pathToFileURL}=require('node:url');
const fs=require('node:fs');
const{runCases}=require('./helpers.cjs');

async function samplingSettings(){
const root=path.join(__dirname,'../src/main/assets/webroot/');
 const {DEFAULT_SETTINGS,encodeSettings,settingsKeys}=await import(pathToFileURL(path.join(root,'config.js')).href);
 const {SettingsPanel}=await import(pathToFileURL(path.join(root,'settings-panel.js')).href);
 assert.equal(encodeSettings(DEFAULT_SETTINGS).length,14);
 assert.equal(DEFAULT_SETTINGS.fastSeconds,10);assert.equal(DEFAULT_SETTINGS.performanceSeconds,2);assert.equal(DEFAULT_SETTINGS.screenOffMinutes,30);assert.equal(DEFAULT_SETTINGS.chargeSeconds,2);
 for(const value of [1,30,60,120])assert.equal(encodeSettings({...DEFAULT_SETTINGS,screenOffMinutes:value})[9],value);
 for(const value of [0,121,1.5])assert.throws(()=>encodeSettings({...DEFAULT_SETTINGS,screenOffMinutes:value}));
 for(const key of ['fastSeconds','performanceSeconds']){
  for(const value of [1,7,60])assert.equal(encodeSettings({...DEFAULT_SETTINGS,[key]:value})[key==='fastSeconds'?10:13],value);
  for(const value of [0,61,1.5,NaN,'2'])assert.throws(()=>encodeSettings({...DEFAULT_SETTINGS,[key]:value}));
 }
 const html=fs.readFileSync(path.join(root,'index.html'),'utf8');
 const names=new Set([...html.matchAll(/<input[^>]*\bname="([^"]+)"/g)].map(m=>m[1]));
 for(const key of settingsKeys)assert.ok(names.has(key),`Missing settings input ${key}`);
 assert.ok(html.includes('功耗采样间隔'));assert.ok(html.includes('性能采样间隔'));
 for(const key of ['fastSeconds','performanceSeconds']){
  let accepted=false,dismissed=false,hint='';const fields={fastSeconds:10,performanceSeconds:10};
  const nodes={keypadTitle:{textContent:key==='fastSeconds'?'功耗采样间隔':'性能采样间隔'},keypadApply:{disabled:false}};
  globalThis.document={getElementById:id=>nodes[id]};
  const panel=Object.create(SettingsPanel.prototype);
  panel.editor={key,draft:'3',field:{value:'10'}};
  panel.values=()=>({...DEFAULT_SETTINGS,...fields,[key]:Number(panel.editor.field.value)});
  panel.askChange=async(title,text)=>{hint=text;return accepted};panel.flush=async()=>true;
  panel.editorError=text=>{throw Error(text)};panel.dismissEditor=()=>{dismissed=true};
  await panel.commitEditor();assert.equal(panel.editor.field.value,'10');assert.ok(!dismissed);assert.ok(hint.includes('3 秒'));
  accepted=true;await panel.commitEditor();assert.equal(panel.editor.field.value,'3');assert.ok(dismissed);assert.ok(hint.includes('继续当前记录'));
 }
}

async function recordingControl(){
const root=path.join(__dirname,'../src/main/assets/webroot/');
const{SettingsPanel}=await import(pathToFileURL(path.join(root,'settings-panel.js')).href);
 const hint={textContent:''};globalThis.document={getElementById:()=>hint};
 const panel=Object.create(SettingsPanel.prototype);panel.recording={};panel.updateFast=()=>{};
 panel.updateRecording(false,'未读到剩余容量');assert.equal(panel.recording.checked,false);assert.ok(hint.textContent.includes('开启时重新检查'));
 panel.recordingValue=false;panel.askRecording=async()=>true;panel.setStatus=message=>panel.lastError=message;panel.onSaved=()=>{};
 let reads=0;panel.api=async()=>{reads++;throw Error('未读到系统电量，记录保持关闭');};
 await panel.toggleRecording(true);assert.equal(reads,1);assert.equal(panel.recording.checked,false);assert.ok(panel.lastError.includes('记录保持关闭'));
 panel.api=async()=>{reads++;return{settings:{},recordingProblem:''};};
 await panel.toggleRecording(true);assert.equal(reads,2);assert.equal(panel.recording.checked,true);assert.equal(panel.recordingProblem,'');
}

async function selectionPreferences(){
const storage=new Map();
globalThis.localStorage={
  getItem:key=>storage.has(key)?storage.get(key):null,
  setItem:(key,value)=>storage.set(key,String(value))
};
globalThis.document={documentElement:{dataset:{theme:'light'}}};
const root=path.join(__dirname,'../src/main/assets/webroot/');
const{loadSelection,saveSelection,loadHomePriority,saveHomePriority}=await import(pathToFileURL(path.join(root,'preferences.js')).href);
storage.clear();
  assert.equal(loadHomePriority(),'percentage','home priority defaults to percentage');
  assert.ok(saveHomePriority('capacity'));
  assert.equal(loadHomePriority(),'capacity','an explicit capacity preference is preserved');

  const available=['rm','pct','voltage','power','temp'];
  const defaults=loadSelection('game',available);
  assert.ok(defaults.has('temp'),'temperature is enabled for new game records by default');

  storage.clear();
  storage.set('battery_curves.legend.game',JSON.stringify(['rm','power']));
  const migrated=loadSelection('game',available);
  assert.ok(migrated.has('temp'),'existing game legend is migrated once to include temperature');
  migrated.delete('temp');
  assert.ok(saveSelection('game',migrated,available));
  assert.equal(loadSelection('game',available).has('temp'),false,'a later user opt-out is preserved');
}

async function resetSettings(){
 const root=path.join(__dirname,'../src/main/assets/webroot/');
 const {SettingsPanel}=await import(pathToFileURL(path.join(root,'settings-panel.js')).href);
 const {DEFAULT_SETTINGS}=await import(pathToFileURL(path.join(root,'config.js')).href);
 const nodes={resetSettings:{disabled:false},themeToggle:{disabled:false}};globalThis.document={getElementById:id=>nodes[id]};
 const store=new Map();globalThis.localStorage={setItem:(k,v)=>store.set(k,v),getItem:k=>store.get(k)??null};
 const panel=Object.create(SettingsPanel.prototype),calls=[],defaults={...DEFAULT_SETTINGS,capacityAh:6.23};
 Object.assign(panel,{ready:true,form:{elements:[{disabled:false}]},fastButton:{},recording:{},powerDisplay:{},priorityButtons:[],fastValue:true,powerDisplayValue:true,recordingValue:false,
   askChange:async()=>true,flush:async()=>true,fill:()=>{},onSaved:()=>{},onHomePriority:()=>{},onResetUi:()=>calls.push(['theme']),setStatus:()=>{},
   updateFast(v){if(typeof v==='boolean')this.fastValue=v},updatePowerDisplay(v){this.powerDisplayValue=v},updateRecording(v){this.recordingValue=v},
   api:async(action,...args)=>{calls.push([action,...args]);if(action==='default_settings')return{settings:defaults};if(action==='set_settings')return{settings:defaults};if(action==='set_fast')return{fastEnabled:false,settings:defaults};if(action==='set_power_display')return{powerDisplayEnabled:false,settings:defaults};if(action==='set_recording')return{recordingEnabled:true,settings:defaults};throw Error(action);}
 });
 assert.equal(await panel.resetDefaults(),true);
 const save=calls.find(c=>c[0]==='set_settings');assert.equal(save[3],623,'uses device design capacity default');assert.equal(save.at(-1),0,'does not prune existing records');
 assert.deepEqual(calls.map(c=>c[0]),['default_settings','set_settings','set_fast','set_power_display','set_recording','theme']);
 assert.equal(panel.homePriority,'percentage');assert.equal(panel.resetBusy,false);assert.equal(panel.resetPromise,null);
 calls.length=0;panel.askChange=async()=>false;assert.equal(await panel.resetDefaults(),false);assert.equal(calls.length,0,'cancelling does not modify settings');
}
runCases([samplingSettings,recordingControl,selectionPreferences,resetSettings]).then(()=>console.log('Settings: intervals, confirmation, recording control, reset and saved selections passed')).catch(error=>{console.error(error);process.exitCode=1;});
