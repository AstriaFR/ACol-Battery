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
 const limits={...DEFAULT_SETTINGS,voltageMin:0,voltageMax:5,tempMin:-20,tempMax:100};
 assert.deepEqual(encodeSettings(limits).slice(4,8),[0,500,0,120]);
 for(const setting of [{voltageMin:-0.01},{voltageMax:5.01},{voltageMin:5},{tempMin:-21},{tempMax:101},{tempMin:100}]){
  assert.throws(()=>encodeSettings({...limits,...setting}));
 }
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
  const nodes={keypadTitle:{textContent:key==='fastSeconds'?'功耗采样间隔':'性能采样间隔'},keypadApply:{disabled:false},keypadRestore:{disabled:false}};
  globalThis.document={getElementById:id=>nodes[id]};
  const panel=Object.create(SettingsPanel.prototype);
  panel.ready=true;panel.editor={key,draft,field:{value:'10'}};
  panel.values=()=>({...DEFAULT_SETTINGS,[key]:Number(panel.editor.field.value)});
  panel.askChange=async(title,text)=>{hint=text;return accepted};
  panel.api=async(action,...args)=>{
   assert.equal(action,'set_setting');validateApiArgs(action,args);assert.equal(args[0],index);saved=args[1];return{settings:{...panel.values(),[key]:saved}};
  };
  panel.onSaved=()=>{};panel.fill=settings=>{panel.editor.field.value=String(settings[key]);};panel.setStatus=()=>{};
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

async function restoreOneField(){
 const root=path.join(__dirname,'../src/main/assets/webroot/');
 const {SettingsPanel}=await import(pathToFileURL(path.join(root,'settings-panel.js')).href);
 const {DEFAULT_SETTINGS,settingSlots}=await import(pathToFileURL(path.join(root,'config.js')).href);
 const field={value:'1',min:'0',max:'20'},writes=[];
 const panel=Object.assign(Object.create(SettingsPanel.prototype),{
   ready:true,keypad:{open:false},form:{elements:{namedItem:()=>field}},
   flush:async()=>true,openEditor(editor){this.editor=editor;},
   writeSetting:async(...args)=>writes.push(args)
 });
 for(const [index,key] of settingSlots.entries()){
   if(!key||typeof DEFAULT_SETTINGS[key]==='boolean')continue;
   panel.edit(key,false);
   await panel.editor.restore();
   assert.deepEqual(writes.at(-1),['reset_setting',index]);
 }
}

async function advancedOptions(){
 const root=path.join(__dirname,'../src/main/assets/webroot/');
 const {createAdvancedPage}=await import(pathToFileURL(path.join(root,'advanced-settings.js')).href);
 const nodes=new Map(),writes=[];
 function element(){
   const attributes=new Map();
   return{
     hidden:true,children:[],append(...items){this.children.push(...items);},getAttribute:key=>attributes.get(key),setAttribute:(key,value)=>attributes.set(key,value),
     showModal(){this.open=true;},addEventListener(name,callback){this.onclose=callback;},
     close(value){this.open=false;this.returnValue=value;this.onclose?.();}
   };
 }
 const get=id=>{
   if(!nodes.has(id))nodes.set(id,element());
   return nodes.get(id);
 };
 globalThis.document={getElementById:get,createElement:element,hidden:false};
 globalThis.history={state:null};
 get('singleCell').textContent='单电芯';
 get('dualCell').textContent='双电芯';
 let cellCount=2,advancedAutoRestore=false,rejectToggle=false,batteryType='example-type',ufcs,rejectUfcs=false;
 const page=createAdvancedPage(async(action,...args)=>{
   const [value,setting]=args;
   if(action==='oplus_votes')return{nodes:ufcs?{UFCS_DISABLE:ufcs}:{},cellCount,advancedAutoRestore,batteryType};
   writes.push([action,...args]);
   if(action==='set_cell_count')return{cellCount:cellCount=value};
   if(action==='set_oplus_vote'){
     assert.equal(value,'UFCS_DISABLE');
     if(rejectUfcs)throw Error('写入读回不一致');
     ufcs={forced:setting===1,forceValue:1};return{};
   }
   if(action==='reset_oplus_votes'){ufcs={forced:false,forceValue:1};return{failed:''};}
   if(rejectToggle)throw Error('后台暂不可用');
   return{advancedAutoRestore:advancedAutoRestore=value===1};
 },{keypad:{}});
 const flush=()=>new Promise(resolve=>setImmediate(resolve));
 try{
   page.open();
   await flush();
   const capacityReadings=get('advancedCapacityReadings').children;
   const typeRow=capacityReadings.at(-3);
   assert.deepEqual(capacityReadings.slice(2,4).map(row=>row.children[0].textContent),['FV_MAX','FV_MIN']);
   assert.deepEqual(capacityReadings.slice(-2).map(row=>row.children[0].textContent),['TARGET_TERM_VOLTAGE','TARGET_SHUTDOWN_VOLTAGE']);
   assert.equal(typeRow.children[1].textContent,'example-type');
   assert.equal(typeRow.hidden,false);
   batteryType=undefined;
   assert.equal(get('dualCell').getAttribute('aria-pressed'),'true');
   assert.equal(get('singleCell').disabled,false);
   const cancelled=get('singleCell').onclick();
   get('advancedConfirmCancel').onclick();
   await cancelled;
   assert.deepEqual(writes,[]);
   const confirmed=get('singleCell').onclick();
   get('advancedConfirmApply').onclick();
   await confirmed;
   assert.deepEqual(writes,[['set_cell_count',1]]);
   assert.equal(typeRow.hidden,true);
   assert.equal(get('singleCell').getAttribute('aria-pressed'),'true');
   const toggle=get('advancedAutoRestore');
   assert.equal(toggle.checked,false);
   toggle.checked=true;
   await toggle.onchange();
   assert.equal(toggle.checked,true);
   rejectToggle=true;
   toggle.checked=false;
   await toggle.onchange();
   assert.equal(toggle.checked,true);
   assert.deepEqual(writes.slice(1),[['set_advanced_auto_restore',1],['set_advanced_auto_restore',0]]);
   const ufcsToggle=get('advancedDisableUfcs'),reset=get('resetAdvancedSettings');
   assert.equal(ufcsToggle.disabled,true);
   assert.equal(get('advancedUfcsUnavailable').hidden,false);
   ufcs={forced:false,forceValue:0};page.pause();page.resume();await flush();
   assert.equal(ufcsToggle.disabled,false);
   assert.equal(get('advancedChargingReadings').children.some(row=>row.children[0].textContent==='UFCS_DISABLE'),false);
   ufcsToggle.checked=true;await ufcsToggle.onchange();await flush();
   assert.deepEqual(writes.at(-1),['set_oplus_vote','UFCS_DISABLE',1]);
   assert.equal(ufcsToggle.checked,true);
   assert.equal(reset.disabled,false);
   rejectUfcs=true;ufcsToggle.checked=false;await ufcsToggle.onchange();await flush();
   assert.equal(ufcsToggle.checked,true);
   assert.match(get('advancedStatus').textContent,/写入读回不一致/);
   rejectUfcs=false;ufcsToggle.checked=false;await ufcsToggle.onchange();await flush();
   assert.deepEqual(writes.at(-1),['set_oplus_vote','UFCS_DISABLE','stock']);
   assert.equal(ufcsToggle.checked,false);
   ufcsToggle.checked=true;await ufcsToggle.onchange();await flush();
   const resetting=reset.onclick();get('advancedConfirmApply').onclick();await resetting;await flush();
   assert.deepEqual(writes.at(-1),['reset_oplus_votes']);
   assert.equal(ufcsToggle.checked,false);
 }finally{page.close();}
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
 const {DEFAULT_SETTINGS}=await import(pathToFileURL(path.join(root,'config.js')).href);
 const nodes={resetSettings:{disabled:false},themeToggle:{disabled:false}};globalThis.document={getElementById:id=>nodes[id]};
 const store=new Map();globalThis.localStorage={setItem:(k,v)=>store.set(k,v),getItem:k=>store.get(k)??null};
 const panel=Object.create(SettingsPanel.prototype),calls=[],defaults={...DEFAULT_SETTINGS,capacityAh:6.23};
 let filled;
 Object.assign(panel,{ready:true,form:{elements:[{disabled:false}]},fastButton:{},recording:{},powerDisplay:{},fastValue:true,powerDisplayValue:true,recordingValue:false,
   askChange:async()=>true,flush:async()=>true,fill:value=>{filled=value;},onSaved:()=>{},onResetUi:()=>calls.push(['theme']),setStatus:()=>{},
   updateFast(v){if(typeof v==='boolean')this.fastValue=v},updatePowerDisplay(v){this.powerDisplayValue=v},updateRecording(v){this.recordingValue=v},
   api:async(action,...args)=>{calls.push([action,...args]);if(action==='reset_settings')return{settings:defaults};if(action==='set_fast')return{fastEnabled:false,settings:defaults};if(action==='set_power_display')return{powerDisplayEnabled:false,settings:defaults};if(action==='set_recording')return{recordingEnabled:true,settings:defaults};throw Error(action);}
 });
 assert.equal(await panel.resetDefaults(),true);
 assert.deepEqual(filled,defaults,'uses the defaults supplied by the device');
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
async function gameMetrics(){
 const{createGameMetricsPage,gameMetricSelection}=await import(pathToFileURL(path.join(__dirname,'../src/main/assets/webroot/game-metrics.js')).href);
 let state={battery:2,performance:33,taskFpsCallback:true};
 const calls=[],buttons=[0,1].map(value=>({value,setAttribute(name,v){this[name]=v;}}));
 const input={dataset:{gameMetric:'performance'},value:32};
 const nodes={gameMetricsPage:{hidden:true},gameMetricsStatus:{},gameMetricsControls:{
   querySelectorAll:selector=>selector==='[data-game-metric]'?[input]:buttons
 }};
 globalThis.document={getElementById:id=>nodes[id]};
 const panel=createGameMetricsPage(async(action,...args)=>{
   calls.push([action,...args]);
   if(action==='set_game_metrics')state={battery:args[0],performance:args[1],taskFpsCallback:args.length===3?!!args[2]:state.taskFpsCallback};
   return state;
 });
 const settled=()=>new Promise(resolve=>setImmediate(resolve));
 panel.open();await settled();
 assert.ok(input.checked&&buttons[1].disabled);
 input.checked=false;input.onchange();await settled();
 assert.ok(!buttons[1].disabled&&buttons[1]['aria-pressed']==='true');
 buttons[0].onclick();await settled();
 assert.deepEqual(calls,[['game_metrics'],['set_game_metrics',2,1],['set_game_metrics',2,1,0]]);
 const withEstimate=gameMetricSelection('battery',2,64,true);
 assert.equal(withEstimate&73,73);
 assert.equal(gameMetricSelection('battery',withEstimate,8,false)&64,0);
 panel.close();assert.ok(!panel.active);
}
async function keypadKeyboard(){
 const {SettingsPanel}=await import(pathToFileURL(path.join(__dirname,'../src/main/assets/webroot/settings-panel.js')).href);
 const digit={dataset:{key:'3'}},calls=[],nodes=new Map();
 let keydown;
 const keypad={querySelectorAll:()=>[digit],addEventListener(type,handler){if(type==='keydown')keydown=handler;}};
 const control={type:'checkbox',addEventListener(){}};
 nodes.set('settingsKeypad',keypad);
 nodes.set('settingsForm',{elements:{namedItem:()=>control},addEventListener(){}});
 globalThis.document={getElementById(id){
  if(!nodes.has(id))nodes.set(id,{id,querySelector:()=>({}),addEventListener(){}});
  return nodes.get(id);
 }};
 const panel=new SettingsPanel();
 panel.dismissEditor=()=>calls.push('cancel');panel.commitEditor=()=>calls.push('apply');panel.press=key=>calls.push(key);
 const press=(key,target)=>{
  let prevented=false;keydown({key,target,preventDefault(){prevented=true;}});
  if(key==='Enter'&&!prevented)target.onclick?.();
 };
 press('Enter',nodes.get('keypadCancel'));press('Enter',digit);press('7',digit);press('Enter',nodes.get('keypadApply'));
 assert.deepEqual(calls,['cancel','3','7','apply']);
}
runCases([samplingSettings,recordingControl,restoreOneField,advancedOptions,selectionPreferences,resetSettings,gameToggle,gameMetrics,keypadKeyboard]).then(()=>console.log('Settings: intervals, confirmation, recording control, cell count, reset, saved selections and keypad passed')).catch(error=>{console.error(error);process.exitCode=1;});
