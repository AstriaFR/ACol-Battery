const path=require('node:path'),assert=require('node:assert/strict');
const{pathToFileURL}=require('node:url');
const fs=require('node:fs');
const{runCases}=require('./helpers.cjs');

async function samplingSettings(){
const root=path.join(__dirname,'../src/main/assets/webroot/');
 const {DEFAULT_SETTINGS,encodeSettings,settingsKeys}=await import(pathToFileURL(path.join(root,'config.js')).href);
 const {SettingsPanel}=await import(pathToFileURL(path.join(root,'settings-panel.js')).href);
 globalThis.window={};
 const {validateApiArgs}=await import(pathToFileURL(path.join(root,'root-bridge.js')).href);
 assert.equal(encodeSettings(DEFAULT_SETTINGS).length,16);
 assert.equal(encodeSettings({...DEFAULT_SETTINGS,energyWh:null})[14],10001);
 for(const value of [0,0.01,25.34,100])assert.equal(encodeSettings({...DEFAULT_SETTINGS,energyWh:value})[14],Math.round(value*100));
 for(const value of [-0.01,100.01,1.001])assert.throws(()=>encodeSettings({...DEFAULT_SETTINGS,energyWh:value}));
 assert.equal(encodeSettings({...DEFAULT_SETTINGS,gaugeIntervalOverride:true})[15],1);
 assert.equal(encodeSettings({...DEFAULT_SETTINGS,gaugeIntervalOverride:false})[15],0);
 assert.throws(()=>encodeSettings({...DEFAULT_SETTINGS,gaugeIntervalOverride:1}));
 for(const value of [1,30,60,120])assert.equal(encodeSettings({...DEFAULT_SETTINGS,screenOffMinutes:value})[9],value);
 for(const value of [0,121,1.5])assert.throws(()=>encodeSettings({...DEFAULT_SETTINGS,screenOffMinutes:value}));
 for(const [key,index] of [['fastSeconds',10],['performanceSeconds',13]]){
  for(const value of [0.5,0.6,1.1,2,19.9,20])assert.equal(encodeSettings({...DEFAULT_SETTINGS,[key]:value})[index],value);
  for(const value of [0,0.4,20.1,60,1.05,NaN,'2'])assert.throws(()=>encodeSettings({...DEFAULT_SETTINGS,[key]:value}));
 }
 const request=[...encodeSettings({...DEFAULT_SETTINGS,fastSeconds:0.6,performanceSeconds:0.7}),0];
 assert.doesNotThrow(()=>validateApiArgs('set_settings',request));
 for(const [index,value] of [[10,0.65],[12,3.5],[13,0.75],[13,'0.7;id']]){
  assert.throws(()=>validateApiArgs('set_settings',request.with(index,value)),/参数无效/);
 }
 assert.throws(()=>validateApiArgs('set_recording',[0.7]),/参数无效/);
 const html=fs.readFileSync(path.join(root,'index.html'),'utf8');
 const names=new Set([...html.matchAll(/<input[^>]*\bname="([^"]+)"/g)].map(m=>m[1]));
 for(const key of settingsKeys)assert.ok(names.has(key),`Missing settings input ${key}`);
 assert.ok(html.includes('功耗采样间隔'));assert.ok(html.includes('性能采样间隔'));
 assert.ok(html.indexOf('name="fastSeconds"')<html.indexOf('name="gaugeIntervalOverride"'));
 assert.ok(html.indexOf('name="gaugeIntervalOverride"')<html.indexOf('name="performanceSeconds"'));
 for(const key of ['fastSeconds','performanceSeconds']){
  let accepted=false,dismissed=false,hint='',saved;
  const draft=key==='performanceSeconds'?'0.7':'0.6',index=key==='performanceSeconds'?13:10;
  const nodes={keypadTitle:{textContent:key==='fastSeconds'?'功耗采样间隔':'性能采样间隔'},keypadApply:{disabled:false}};
  globalThis.document={getElementById:id=>nodes[id]};
  const panel=Object.create(SettingsPanel.prototype);
  panel.ready=true;panel.editor={key,draft,field:{value:'10'}};
  panel.values=()=>({...DEFAULT_SETTINGS,[key]:Number(panel.editor.field.value)});
  panel.askChange=async(title,text)=>{hint=text;return accepted};
  panel.api=async(action,...args)=>{
   assert.equal(action,'set_settings');validateApiArgs(action,args);saved=args[index];return{settings:panel.values()};
  };
  panel.onSaved=()=>{};panel.fill=()=>{};panel.setStatus=()=>{};
  panel.editorError=text=>{throw Error(text)};panel.dismissEditor=()=>{dismissed=true};
  await panel.commitEditor();assert.equal(panel.editor.field.value,'10');assert.equal(saved,undefined);assert.ok(!dismissed);assert.ok(hint.includes(`${draft} 秒`));
  accepted=true;await panel.commitEditor();assert.equal(panel.editor.field.value,draft);assert.equal(saved,Number(draft));assert.ok(dismissed);assert.ok(hint.includes('继续当前记录'));
 }
 const fields=Object.fromEntries(settingsKeys.map(key=>[key,{
  type:typeof DEFAULT_SETTINGS[key]==='boolean'?'checkbox':'number',value:'',checked:false,
  setAttribute(){},removeAttribute(){},checkValidity:()=>true,get valueAsNumber(){return Number(this.value);}
 }]));
 const panel=Object.create(SettingsPanel.prototype);panel.form={elements:{namedItem:key=>fields[key]}};
 panel.fill({...DEFAULT_SETTINGS,gaugeIntervalOverride:true});assert.equal(fields.gaugeIntervalOverride.checked,true);
 fields.gaugeIntervalOverride.checked=false;assert.equal(encodeSettings(panel.values())[15],0);
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
const{loadSelection,saveSelection}=await import(pathToFileURL(path.join(root,'preferences.js')).href);
  const available=['rm','pct','voltage','power','temp','energy'];
  for(const kind of ['game','discharge']){
    const selected=new Set(['rm','energy']);
    assert.ok(saveSelection(kind,selected,available));
    selected.delete('energy');assert.ok(saveSelection(kind,selected,available));
    assert.ok(!loadSelection(kind,available).has('energy'),'explicit legend choices are preserved');
  }
  storage.clear();
  storage.set('battery_curves.legend.game',JSON.stringify(['rm','power']));
  const migrated=loadSelection('game',available);
  assert.ok(migrated.has('rm')&&migrated.has('power'),'migration preserves saved legend choices');
  migrated.delete('temp');
  assert.ok(saveSelection('game',migrated,available));
  assert.equal(loadSelection('game',available).has('temp'),false,'a later user opt-out is preserved');
}

async function resetSettings(){
 const root=path.join(__dirname,'../src/main/assets/webroot/');
 const {SettingsPanel}=await import(pathToFileURL(path.join(root,'settings-panel.js')).href);
 const {DEFAULT_SETTINGS,encodeSettings}=await import(pathToFileURL(path.join(root,'config.js')).href);
 const nodes={resetSettings:{disabled:false},themeToggle:{disabled:false}};globalThis.document={getElementById:id=>nodes[id]};
 const store=new Map();globalThis.localStorage={setItem:(k,v)=>store.set(k,v),getItem:k=>store.get(k)??null};
 const panel=Object.create(SettingsPanel.prototype),calls=[],defaults={...DEFAULT_SETTINGS,capacityAh:6.23};
 Object.assign(panel,{ready:true,form:{elements:[{disabled:false}]},fastButton:{},recording:{},powerDisplay:{},fastValue:true,powerDisplayValue:true,recordingValue:false,
   askChange:async()=>true,flush:async()=>true,fill:()=>{},onSaved:()=>{},onResetUi:()=>calls.push(['theme']),setStatus:()=>{},
   updateFast(v){if(typeof v==='boolean')this.fastValue=v},updatePowerDisplay(v){this.powerDisplayValue=v},updateRecording(v){this.recordingValue=v},
   api:async(action,...args)=>{calls.push([action,...args]);if(action==='reset_settings')return{settings:defaults};if(action==='set_fast')return{fastEnabled:false,settings:defaults};if(action==='set_power_display')return{powerDisplayEnabled:false,settings:defaults};if(action==='set_recording')return{recordingEnabled:true,settings:defaults};throw Error(action);}
 });
 assert.equal(await panel.resetDefaults(),true);
 assert.deepEqual(JSON.parse(panel.lastSaved),encodeSettings(defaults),'uses the defaults supplied by the device');
 assert.deepEqual(calls.map(([action])=>action),['reset_settings','set_fast','set_power_display','set_recording','theme']);
 assert.equal(panel.resetBusy,false);assert.equal(panel.resetPromise,null);
 calls.length=0;panel.askChange=async()=>false;assert.equal(await panel.resetDefaults(),false);assert.equal(calls.length,0,'cancelling does not modify settings');
}
async function gameToggle(){
 const {SettingsPanel}=await import(pathToFileURL(path.join(__dirname,'../src/main/assets/webroot/settings-panel.js')).href);
 const panel=Object.create(SettingsPanel.prototype),calls=[];
 Object.assign(panel,{ready:true,recordingValue:false,fastValue:false,fastBusy:false,fastButton:{},panel:{querySelector:()=>({classList:{toggle(){}}})},setStatus(message){if(message)throw Error(message)},flush:async()=>true,onSaved(){},askChange(){throw Error('Game toggle must not request confirmation')},api:async(action,value)=>{calls.push([action,value]);return{fastEnabled:!!value,settings:{}}}});
 panel.updateFast();assert.equal(panel.fastButton.disabled,false,'game toggle remains available while ordinary recording is off');
 await panel.toggleFast();assert.equal(panel.fastValue,true);await panel.toggleFast();assert.equal(panel.fastValue,false);assert.deepEqual(calls,[['set_fast',1],['set_fast',0]]);assert.equal(panel.fastBusy,false);
}
runCases([samplingSettings,recordingControl,selectionPreferences,resetSettings,gameToggle]).then(()=>console.log('Settings: intervals, confirmation, recording control, reset and saved selections passed')).catch(error=>{console.error(error);process.exitCode=1;});
