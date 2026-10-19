const assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path');
const {pathToFileURL}=require('node:url');
(async()=>{
 const root=path.join(__dirname,'../src/main/assets/webroot/');
 const {DEFAULT_SETTINGS,encodeSettings,settingsKeys}=await import(pathToFileURL(path.join(root,'config.js')).href);
 const {SettingsPanel}=await import(pathToFileURL(path.join(root,'settings-panel.js')).href);
 assert.equal(encodeSettings(DEFAULT_SETTINGS).length,14);
 assert.equal(DEFAULT_SETTINGS.fastSeconds,10);assert.equal(DEFAULT_SETTINGS.performanceSeconds,3);assert.equal(DEFAULT_SETTINGS.screenOffMinutes,20);assert.equal(DEFAULT_SETTINGS.chargeSeconds,3);
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
 console.log('Game settings: independent values, ranges, fields and cancel/confirm behavior passed');
})().catch(e=>{console.error(e);process.exitCode=1});
