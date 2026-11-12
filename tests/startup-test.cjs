const path=require('node:path'),assert=require('node:assert/strict');
const{pathToFileURL}=require('node:url');
const fs=require('node:fs'),vm=require('node:vm');
const{runCases}=require('./helpers.cjs');

async function startupProgress(){
const project=path.join(__dirname,'..'),root=path.join(project,'src/main/assets/webroot');
const source=fs.readFileSync(path.join(root,'app.js'),'utf8'),html=fs.readFileSync(path.join(root,'index.html'),'utf8');
const events=new Map(),warning={dataset:{},textContent:'正在连接…'};let status='等待 root 授权…';
const window={AcoNative:{connectionStatus:()=>status},addEventListener:(event,handler)=>events.set(event,handler)};
const context={window,$:()=>warning};vm.createContext(context);
vm.runInContext(source.slice(source.indexOf('function connectionMessage('),source.indexOf('let uiSettings=')),context);
assert.equal(warning.textContent,status);status='正在启动后台…';events.get('aco-connection')({detail:status});assert.equal(warning.textContent,status);
assert.equal(context.connectionMessage('连接超时'),status,'native progress must remain visible during authorization/startup');
warning.dataset.startupError='1';warning.textContent='页面启动失败';events.get('aco-connection')({detail:''});assert.equal(warning.textContent,'页面启动失败');
delete warning.dataset.startupError;status='';assert.equal(context.connectionMessage('连接超时'),'连接超时');
const inline=html.match(/<script>([\s\S]*?)<\/script>/)[1];
vm.runInNewContext(inline,{window,localStorage:{getItem:()=>null},matchMedia:()=>({matches:false}),document:{documentElement:{dataset:{}},getElementById:()=>warning}});
warning.textContent='等待 root 授权…';events.get('error')({});assert.equal(warning.textContent,'页面启动失败，请重新打开 App。');assert.equal(warning.dataset.startupError,'1');
const warningBlock=source.slice(source.indexOf('const warnings=[]'),source.indexOf("$('warning').textContent=warnings.join"));
const warningsFor=s=>Array.from(vm.runInNewContext(`${warningBlock}\nwarnings;`,{s,r:{},recording:true}));
const broadcast={capabilities:31,screenFallback:true,screenEventApi:'screen_broadcast',screenStateAvailable:true};
assert.deepEqual(warningsFor(broadcast),[]);
assert.ok(warningsFor({...broadcast,screenStateAvailable:false}).some(text=>text.includes('屏幕状态暂不可用')));
assert.ok(warningsFor({...broadcast,screenEventApi:'state_poll'}).some(text=>text.includes('亮息屏切换可能延迟')));
}

async function requestTimeout(){
const root=path.join(__dirname,'../src/main/assets/webroot/');
// Exercise the request timeout without waiting ten seconds in real time.
 const timers=[];globalThis.window={};globalThis.AcoNative={post(){}};
 const bridgeSource=fs.readFileSync(path.join(root,'root-bridge.js'),'utf8');
 const bridge=await import('data:text/javascript;base64,'+Buffer.from('const setTimeout=(fn,ms)=>{globalThis.__timers.push({fn,ms});return 1;},clearTimeout=()=>{};'+bridgeSource).toString('base64'));
 globalThis.__timers=timers;const pending=bridge.exec('test');assert.equal(timers[0].ms,10000);timers[0].fn();await assert.rejects(pending,/超时/);
}

runCases([startupProgress,requestTimeout]).then(()=>console.log('Startup UI: progress, errors, screen status and request timeout passed')).catch(error=>{console.error(error);process.exitCode=1;});
