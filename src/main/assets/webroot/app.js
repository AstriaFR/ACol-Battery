import{roundedDuration}from'./daily-usage-chart.js?v=acol-2.1';
import{installPageDepth}from'./page-navigation.js?v=acol-2.1';
import{installPageMotion}from'./page-motion.js?v=acol-2.1';
import{exec,fullScreen,cancelTopDialog,readAppIcons}from'./root-bridge.js?v=acol-2.1';
import{LiveChannel,compareRecords}from'./live-channel.js?v=acol-2.1';
import{Chart,seriesFor,valid,duration,setChartSettings}from'./chart.js?v=acol-2.1';
import{ScreenOffOverlay}from'./screen-off-overlay.js?v=acol-2.1';
import{DEFAULT_SETTINGS}from'./config.js?v=acol-2.1';
import{SettingsPanel}from'./settings-panel.js?v=acol-2.1';
import{BatteryInfoPanel}from'./battery-info.js?v=acol-2.1';
import{BatteryHistoryPanel}from'./battery-history.js?v=acol-2.1';
import{PerformancePanel}from'./performance-panel.js?v=acol-2.1-gamepad-temp';
import{remainingEstimate,remainingTime,canEstimateRemaining}from'./estimate.js?v=acol-2.1';
import{loadSelection,saveSelection}from'./preferences.js?v=acol-2.1';
import{capacityChange,capacityFlow,recordChanges,recordIsLive}from'./record-display.js?v=acol-2.1';
import{ThemeController}from'./theme.js?v=acol-2.1';
import{EdgeScrollbar}from'./edge-scrollbar.js?v=acol-2.1';
import{RecordUsagePanel}from'./record-usage.js?v=acol-2.1';
import{HomeUsagePanel}from'./home-usage.js?v=acol-2.1';
import{UsageDetailsPanel}from'./usage-details.js?v=acol-2.1';
import{usagePages,usageState}from'./usage-navigation.js?v=acol-2.1';
const $=id=>document.getElementById(id),command='/data/adb/aco_battery_app/runtime/bin/curve-client';
const edgeScrollbar=new EdgeScrollbar();
const baseUrl=()=>`${location.pathname}${location.search}`;
const pageUrl=tab=>baseUrl()+(tab==='home'?'':'#'+tab);
installPageDepth();
installPageMotion();
const startTab=location.hash==='#settings'?'settings':location.hash==='#records'?'records':'home';
history.replaceState({page:'home'},'',baseUrl());
const tabButtons=[...document.querySelectorAll('[data-tab]')];
let activeTab='home';
for(const type of['selectstart','copy','cut','contextmenu','dragstart'])document.addEventListener(type,e=>e.preventDefault(),{capture:true});
// Cancel native press defaults without blocking touch-action scrolling.
document.addEventListener('pointerdown',e=>{
  if(e.pointerType==='touch'&&!e.target.closest('input[type=number],textarea,[contenteditable=true]'))e.preventDefault();
},{capture:true,passive:false});
function longPressFeedback(){try{navigator.vibrate?.(10);}catch{}}
document.addEventListener('selectionchange',()=>{const selection=getSelection();if(selection?.rangeCount)selection.removeAllRanges();});
document.addEventListener('keydown',e=>{if((e.ctrlKey||e.metaKey)&&['a','c','x'].includes(e.key.toLowerCase()))e.preventDefault();});
const liveChannel=new LiveChannel();
let records=[],kind='all',detail=null,activeId=null,total=0,totalRecords=0,loading=false,request=0,expanded=false,zoomTimer;
let lastStatus=null,pollTimer,liveBusy=false,detailBusy=0,listDue=true,lastListAt=0,queuedPoll=false;
function connectionMessage(fallback){try{return window.AcoNative?.connectionStatus?.()||fallback;}catch{return fallback;}}
window.addEventListener('aco-connection',event=>{if(!$('warning').dataset.startupError)$('warning').textContent=event.detail||'';});
$('warning').textContent=connectionMessage('正在连接…');
let listSelection=0,recordsReady=false,monthFilter=null,filterYear=0,filterMonth=0,filterBusy=false,filterRequest=0;
const filteredLists=new Map();
let followLive=false,followSpan=null,streamId=null,streamAfter=0,streamPoints=[];
let fullscreenAnchor=null;
let revealedRecordId=null,deleteTarget=null,homeActiveRecord=null,usageNavigation=0;
let reversePlot=false;
let remainingTimer,remainingGeneration=0,remainingRecordId=null;
let uiSettings={...DEFAULT_SETTINGS};
let gameFrequencies=null;
const appIcons=new Map(),iconAttempts=new Set();
function remainingEligible(){return !document.hidden&&!$('detail').hidden&&!$('confirm').open&&detail?.record.kind==='discharge'&&activeId!==null&&canEstimateRemaining(detail.record,lastStatus?.reading);}
function clearRemaining(){
  if($('remainingWh'))$('remainingWh').textContent='—';if($('remainingTime'))$('remainingTime').textContent='—';
}
function stopRemaining(){clearTimeout(remainingTimer);remainingTimer=null;remainingRecordId=null;++remainingGeneration;clearRemaining();}
function startRemaining(){
  if(!remainingEligible()){if(remainingRecordId!==null)stopRemaining();else clearRemaining();return;}
  if(remainingRecordId===activeId)return;
  stopRemaining();remainingRecordId=activeId;pollRemaining(remainingGeneration);
}
async function pollRemaining(generation){
  if(generation!==remainingGeneration)return;
  if(!remainingEligible()){stopRemaining();return;}
  const started=performance.now();
  try{
    // status reads root telemetry without appending a sample or writing estimates.
    const status=await api('status');
    if(generation!==remainingGeneration||!remainingEligible())return;
    if(!canEstimateRemaining(detail.record,status.reading)){stopRemaining();return;}
    const estimate=remainingEstimate(status.reading,detail.drainSummary?.[0]?.avgPower);
    if(!estimate){clearRemaining();return;}
    $('remainingWh').textContent=estimate.whText;$('remainingTime').textContent=remainingTime(estimate.minutes,estimate.wh);
  }catch(e){if(generation===remainingGeneration)clearRemaining();}
  finally{
    if(generation===remainingGeneration){
      if(remainingEligible())remainingTimer=setTimeout(()=>pollRemaining(generation),Math.max(100,10000-(performance.now()-started)));
      else stopRemaining();
    }
  }
}
function syncTheme(){
  const flow=detail?.record.kind??(kind==='all'?(lastStatus?.state===0?'discharge':'charge'):kind==='game'?'discharge':kind);
  document.body.dataset.flow=flow;
}
const recordCells=r=>[0,1,2].includes(r?.cell_count)?r.cell_count:2;
const recordFast=r=>Number(r?.fast_seconds)>0;
const fmt=(v,d=0)=>valid(v)?v.toLocaleString('zh-CN',{maximumFractionDigits:d}):'—';
function renderRecordIcon(node,r){
  const type=r?(recordFast(r)?'game':r.kind):'empty';if(node.dataset.type===type)return;
  node.dataset.type=type;node.classList.toggle('game-record-icon',type==='game');
  node.setAttribute('role','img');node.setAttribute('aria-label',type==='game'?'游戏性能统计':type==='charge'?'充电':type==='discharge'?'放电':'暂无记录');
  if(type!=='game'){node.textContent=type==='charge'?'↗':type==='discharge'?'↘':'—';return;}
  const ns='http://www.w3.org/2000/svg',svg=document.createElementNS(ns,'svg');
  svg.setAttribute('viewBox','0 0 24 24');svg.setAttribute('aria-hidden','true');
  const part=(tag,attributes)=>{const shape=document.createElementNS(ns,tag);for(const[key,value]of Object.entries(attributes))shape.setAttribute(key,String(value));svg.append(shape);};
  part('path',{d:'M7.5 6h9c2 0 3.25 1.35 3.72 3.28l1.16 5.11c.57 2.52-2.18 4.16-3.88 2.54l-2.17-2.06H8.67L6.5 16.93c-1.7 1.62-4.45-.02-3.88-2.54l1.16-5.11C4.25 7.35 5.5 6 7.5 6Z',fill:'currentColor','fill-opacity':'.1',stroke:'currentColor','stroke-width':'1.45','stroke-linejoin':'round'});
  part('path',{d:'M7.3 8.8v4.2M5.2 10.9h4.2',fill:'none',stroke:'currentColor','stroke-width':'1.65','stroke-linecap':'round'});
  part('circle',{cx:16.2,cy:9.8,r:1,fill:'currentColor'});
  part('circle',{cx:18.1,cy:11.9,r:1,fill:'currentColor'});
  part('rect',{x:10.6,y:8.1,width:2.8,height:1,rx:.5,fill:'currentColor',opacity:'.5'});
  node.replaceChildren(svg);
}
function updateRecordTitle(title,r){
  const change=recordChanges(r).secondary,delta=el('span',change.text,'record-pct-change');
  delta.dataset.flow=change.flow;
  title.replaceChildren(el('span',`${recordFast(r)?'游戏':r.kind==='charge'?'充电':'放电'} · `),el('span',`${fmt(r.pct_start)}% → ${fmt(r.pct_end)}%`,'record-percentage-range'),delta);
}
function updateRecordMeta(meta,r){
  meta.replaceChildren(el('span',stampList(r.start_wall),'record-start-time'),document.createTextNode(` · ${duration(r.end_elapsed-r.start_elapsed)} · ${reasons[r.reason]??r.reason}`));
}
function updateRecordDelta(delta,r){
  const change=recordChanges(r).primary,value=delta.querySelector('.delta-value');
  value.textContent=change.text;value.dataset.flow=change.flow;
}
const pad2=n=>String(n).padStart(2,'0');
const stamp=t=>{const d=new Date(t);return Number.isNaN(d.getTime())?'—':`${d.getFullYear()}/${pad2(d.getMonth()+1)}/${pad2(d.getDate())} ${pad2(d.getHours())}:${pad2(d.getMinutes())}`;};
const stampList=t=>{const d=new Date(t);return Number.isNaN(d.getTime())?'—':`${pad2(d.getMonth()+1)}/${pad2(d.getDate())} ${pad2(d.getHours())}:${pad2(d.getMinutes())}`;};
const reasons={backup_restored:'恢复备份',recording:'记录中',full_tail_confirmed:'尾充完成',plugged:'接入充电',unplugged:'拔出充电',reboot:'重启中断',collector_restart:'服务中断',framework_restarted:'系统服务重启',module_stopped:'模块停止',recording_stopped:'记录已停止',app_uninstalled:'卸载前结束',service_updated:'后台更新',fast_started:'切换游戏模式',fast_stopped:'游戏记录结束',fast_idle_timeout:'息屏自动结束'};
function el(tag,text,cls){const n=document.createElement(tag);if(text!==undefined)n.textContent=text;if(cls)n.className=cls;return n;}
function remainingInfoButton(){
  const button=el('button','i','remaining-info');button.type='button';
  button.setAttribute('aria-label','查看剩余能量和亮屏时长的估算说明');button.setAttribute('aria-haspopup','dialog');
  button.onclick=()=>{
    if(detail?.record.kind!=='discharge')return;
    history.pushState({page:'record',id:activeId,remainingExplanation:true},'',location.href);
    syncRemainingExplanation();
  };
  return button;
}
function syncRemainingExplanation(){
  const dialog=$('remainingExplanation'),state=history.state;
  const show=state?.page==='record'&&state.id===activeId&&state.remainingExplanation&&detail?.record.kind==='discharge';
  if(show&&!dialog.open)dialog.showModal();else if(!show&&dialog.open)dialog.close();
}
function dismissRemainingExplanation(){
  if(history.state?.remainingExplanation)history.back();else $('remainingExplanation').close();
}
$('closeRemainingExplanation').onclick=dismissRemainingExplanation;
$('remainingExplanation').addEventListener('cancel',e=>{e.preventDefault();dismissRemainingExplanation();});
function toast(text){$('toast').textContent=text;$('toast').hidden=false;clearTimeout(toast.timer);toast.timer=setTimeout(()=>$('toast').hidden=true,4500);}
async function api(action,...args){
  if(action==='daily_usage'){
    if(args.length!==3||args.slice(0,2).some(a=>!/^\d+$/.test(String(a)))||!/^[A-Za-z0-9_-]{1,4096}$/.test(String(args[2])))throw Error('使用情况参数无效');
  }else if(args.some(a=>!/^\d+$/.test(String(a))))throw Error('参数无效');
  if(action==='status')return liveChannel.status();
  if(action==='live'){const packet=await liveChannel.live(args);if(!args[0]||'record'in packet)return packet;}
  const result=await exec(`${command} ${action} ${args.join(' ')}`);
  if(Number(result.errno)!==0)throw Error(result.stderr?.trim()||'操作未能完成，请重试。');
  const data=JSON.parse(result.stdout.trim());if(!data.ok){const error=Error(data.error||'未能取得电池数据，请重新初始化。');throw error;}return data;
}
const chart=new Chart($('plot'),$('tooltip'),()=>scheduleZoom());
const screenOffOverlay=new ScreenOffOverlay(chart,api,()=>!document.hidden&&!$('detail').hidden,message=>toast(message));
chart.onInteract=()=>pauseFollowing();
const settingsPanel=new SettingsPanel(api,value=>{applySettings(value);refresh();});
const batteryPanel=new BatteryInfoPanel();
const batteryHistory=new BatteryHistoryPanel(api,(state,push)=>openBatteryHistory(state,push),()=>window.__acoBack());
const performancePanel=new PerformancePanel(api);
const screenTimePanel=new RecordUsagePanel(api,{formatDuration:roundedDuration,formatDate:wall=>{const date=new Date(wall);return`${pad2(date.getMonth()+1)}/${pad2(date.getDate())}`;},minuteUnit:'分'});
const homeUsage=new HomeUsagePanel(api);
const usageDetails=new UsageDetailsPanel(api,()=>homeUsage.cache,(state,push)=>openUsage(state,push));
homeUsage.onDataChange=day=>usageDetails.refresh(day);
let usageDeleteDay=null,usageDeleteBusy=false;
const askUsageDelete=day=>{if(usageDeleteBusy||$('usageDeleteConfirm').open)return;usageDeleteDay=day;$('usageDeleteConfirm').showModal();$('usageDeleteCancel').focus({preventScroll:true});};
homeUsage.onDeleteDay=askUsageDelete;usageDetails.dayChart.onDeleteDay=askUsageDelete;
$('usageDeleteCancel').onclick=()=>$('usageDeleteConfirm').close();
$('usageDeleteConfirm').addEventListener('cancel',event=>{if(usageDeleteBusy)event.preventDefault();});
$('usageDeleteApply').onclick=async()=>{
  if(usageDeleteBusy||usageDeleteDay===null)return;
  const day=usageDeleteDay,button=$('usageDeleteApply');usageDeleteBusy=true;button.disabled=true;
  homeUsage.pause();
  try{
    await homeUsage.cache.deleteDay(day);usageDetails.hourSelections.delete(day);
    if(homeUsage.day===day)await homeUsage.display(day);
    await usageDetails.refresh(day);$('usageDeleteConfirm').close();toast('当天使用时间数据已删除');
  }catch(error){toast(error.message);}
  finally{usageDeleteBusy=false;button.disabled=false;homeUsage.lastStarted=0;homeUsage.resume(true);}
};

function updateThemeControl({step,dark}){
  const button=$('themeToggle');if(!button)return;
  button.dataset.mode=step;button.setAttribute('aria-label',step==='auto'?'主题：跟随系统':'主题：手动'+(dark?'暗色':'亮色'));
  button.title=button.getAttribute('aria-label');
}
let themeUiReady=false;
const themeController=new ThemeController(state=>{
  updateThemeControl(state);
  if(!themeUiReady||!state.darkChanged)return;
  try{
    if(activeId!==null&&detail)buildLegend(detail.record.kind);
    chart.draw();performancePanel.refreshTheme();
  }catch(error){console.error('Theme redraw failed',error);}
});
$('themeToggle').onclick=()=>themeController.cycle();
settingsPanel.onResetUi=()=>themeController.reset();
$('resetSettings').onclick=async()=>{if(await settingsPanel.resetDefaults())toast('已恢复默认设置');};
function showTab(tab){
  activeTab=tab;document.body.dataset.tab=tab;
  $('homePage').hidden=tab!=='home';
  $('recordsPage').hidden=tab!=='records';
  $('settingsPanel').hidden=tab!=='settings';
  for(const button of tabButtons){
    const selected=button.dataset.tab===tab;
    button.classList.toggle('selected',selected);
    if(selected)button.setAttribute('aria-current','page');else button.removeAttribute('aria-current');
  }
  document.title=tab==='home'?'ACol 电池':tab==='records'?'记录 · ACol 电池':'设置 · ACol 电池';
}
async function leaveDetail(){
  screenOffOverlay.close();
  screenTimePanel.close();
  performancePanel.close();
  stopRemaining();
  hideAppEnergyTip();
  if(expanded)await toggleFullscreen(false);
  ++request;clearTimeout(zoomTimer);
  activeId=null;detail=null;streamId=null;streamPoints=[];streamAfter=0;followLive=false;
  $('detail').hidden=true;
}
async function activateTab(tab){
  usageNavigation++;usageDetails.close();
  batteryHistory.close();batteryPanel.close();
  settingsPanel.closeEditor();
  liveChannel.setMode(0);
  await leaveDetail();
  $('appShell').hidden=false;
  showTab(tab);
  syncTheme();
  if(tab==='records')renderRecords();
  if(tab==='settings'){prepareCutoff();await settingsPanel.open(lastStatus||liveChannel.latest?uiSettings:null);}
  else refresh();
}
async function requestTab(tab){
  if(tab===activeTab&&!$('appShell').hidden){
    const scroller=tab==='records'?$('recordsPage').querySelector('.record-scroll'):tab==='settings'?$('settingsPanel').querySelector('.page-scroll'):null;
    scroller?.scrollTo({top:0,behavior:'smooth'});
    return;
  }
  if(activeTab==='settings'&&tab!=='settings'&&!await settingsPanel.flush()){toast('设置未能保存，请重试。');return;}
  if(history.state?.page!==tab)history.pushState({page:tab},'',pageUrl(tab));
  await activateTab(tab);
}
for(const button of tabButtons)button.onclick=()=>requestTab(button.dataset.tab);
window.__acoOpenSettings=()=>requestTab('settings');
const cutoffFrame=$('cutoffFrame');let cutoffReady=false;
window.__acoCutoffExec=exec;
window.__acoCutoffBack=()=>history.back();
window.__acoCutoffKeypad=key=>history.pushState({...history.state,page:'cutoff',keypad:true,key},'',`${pageUrl('cutoff')}-keypad`);
window.__acoCutoffHome=()=>returnHome();
window.__acoCutoffReady=()=>{cutoffReady=true;if(!cutoffFrame.hidden){cutoffFrame.contentWindow.__acoCutoffOpen();cutoffFrame.contentWindow.__acoCutoffSync(history.state);}};
function hideCutoff(){if(cutoffFrame.hidden)return;cutoffFrame.contentWindow?.__acoCutoffClose?.();cutoffFrame.hidden=true;}
function prepareCutoff(){if(!cutoffFrame.getAttribute('src'))cutoffFrame.src='cutoff/index.html?from=settings&embedded=1';}
function showCutoff(state){
  cutoffFrame.hidden=false;$('appShell').hidden=true;
  if(!cutoffFrame.getAttribute('src'))prepareCutoff();
  else if(cutoffReady){cutoffFrame.contentWindow.__acoCutoffOpen();cutoffFrame.contentWindow.__acoCutoffSync(state);}
}
window.__acoBack=()=>{
  if(!cutoffFrame.hidden){if(cutoffReady)return cutoffFrame.contentWindow.__acoBack();history.back();return'handled';}
  if(usageDetails.dayChart.tip&&!usageDetails.dayChart.tipHost||usageDetails.appChart.tip){usageDetails.hideTips();return'handled';}
  if(homeUsage.tip){homeUsage.hideTip();return'handled';}
  if(cancelTopDialog())return'handled';
  if(performancePanel.expanded){performancePanel.fullscreen(false);return'handled';}
  if(expanded){toggleFullscreen(false);return'handled';}
  if(history.state?.page&&history.state.page!=='home'){history.back();return'handled';}
  // Recover a visible child page even if its browser history state was lost.
  if(activeTab!=='home'||batteryPanel.active||batteryHistory.active||!$('detail').hidden||usageDetails.state){showHome();return'handled';}
  return $('appShell').hidden?'wait':'home';
};
function applySettings(value){
  const next={...DEFAULT_SETTINGS,...value};
  if(JSON.stringify(next)===JSON.stringify(uiSettings))return;
  uiSettings=next;setChartSettings(next);
  $('showSmallLabel').textContent=`显示 ≤${next.hidePct}%`;
  if(detail){chart.draw();renderChartInfo();}
}
$('openCutoff').onclick=async e=>{e.preventDefault();const button=e.currentTarget;if(button.getAttribute('aria-disabled')==='true'){toast('当前设备缺少截止电压调整接口。');return;}if(!await settingsPanel.flush()){toast('设置未能保存，请重试。');return;}history.pushState({page:'cutoff'},'',pageUrl('cutoff'));showCutoff(history.state);};
async function openBatteryInfo(){
  batteryHistory.close();
  usageNavigation++;usageDetails.close();
  screenTimePanel.close();
  performancePanel.close();
  stopRemaining();settingsPanel.closeEditor();if(expanded)await toggleFullscreen(false);
  ++request;clearTimeout(zoomTimer);activeId=null;detail=null;streamId=null;streamPoints=[];streamAfter=0;followLive=false;
  $('detail').hidden=true;$('appShell').hidden=true;
  batteryPanel.open();liveChannel.setMode(1);
  if(history.state?.page!=='battery-info')history.pushState({page:'battery-info'},'',`${location.pathname}${location.search}#battery-info`);
  refresh();
}
$('openBatteryInfo').onclick=openBatteryInfo;
$('batteryInfoBack').onclick=()=>window.__acoBack();
$('openBatteryHistory').onclick=()=>openBatteryHistory({page:'battery-history'},true);
for(const id of ['batteryHistoryBack','batterySnapshotBack'])$(id).onclick=()=>window.__acoBack();
async function openBatteryHistory(state,push=false){
  const navigation=++usageNavigation;usageDetails.close();batteryPanel.close();settingsPanel.closeEditor();
  await leaveDetail();if(navigation!==usageNavigation)return;
  clearTimeout(pollTimer);liveChannel.setMode(0);liveChannel.close();$('appShell').hidden=true;
  if(push)history.pushState(state,'',pageUrl(state.page));
  document.title=(state.page==='battery-history'?'电池历史记录':'电池快照')+' · ACol 电池';
  await batteryHistory.open(state,push&&state.page==='battery-history');
}

function syncReversePlot(){
  const available=['charge','discharge'].includes(detail?.record.kind);
  const enabled=available&&reversePlot;
  $('reversePlot').hidden=!available;
  $('reversePlot').classList.toggle('selected',reversePlot);
  $('reversePlot').setAttribute('aria-pressed',String(reversePlot));
  $('chartPanel').classList.toggle('reverse-plot',enabled);
  chart.reversePlot=enabled;
  chart.draw();
}
$('reversePlot').onclick=()=>{reversePlot=!reversePlot;chart.clearInspect();syncReversePlot();};
function pauseFollowing(){followLive=false;renderFollow();}
function renderFollow(){
  const active=!!detail?.record.active;$('followLatest').hidden=!active;
  $('followLatest').textContent=followLive?'实时跟随':'回到实时';$('followLatest').classList.toggle('selected',followLive);$('followLatest').setAttribute('aria-pressed',String(followLive));
}
function followWindow(end){return followSpan===null?[0,end]:[Math.max(0,end-followSpan),end];}
async function readDetail(id,from,to,budget,cancelled=()=>false){
  let data=await api('detail',id,from,to,budget);if(cancelled())return null;
  const points=[...data.points],end=Math.min(to,data.record.end_elapsed-data.record.start_elapsed);
  while(valid(data.nextAfter)){
    const page=await api('detail',id,from,end,budget,data.nextAfter);if(cancelled())return null;
    points.push(...page.points);data={...data,nextAfter:page.nextAfter};
  }
  return{...data,points};
}
function scheduleZoom(){pauseFollowing();clearTimeout(zoomTimer);zoomTimer=setTimeout(()=>loadWindow(),180);}
function applyChart(data,view,live=false){
  chart.set(data.points,Math.max(1,data.record.end_elapsed-data.record.start_elapsed),view,{kind:data.record.kind,cellCount:recordCells(data.record),fast:recordFast(data.record),recordId:data.record.id,ranges:data.ranges??{},live});screenOffOverlay.update(data.record,data.statsVersion);renderChartInfo();renderFollow();
}
function renderChartInfo(){
  if(!detail)return;const r=detail.record,raw=r.kind==='charge';
  const gameSeconds=valid(lastStatus?.gamePowerIntervalMs)?lastStatus.gamePowerIntervalMs/1000:uiSettings.fastSeconds;
  const plan=raw?(r.active?`每 ${uiSettings.chargeSeconds} 秒记录`:'充电原始采样'):recordFast(r)?`游戏性能统计${r.active?' · 功耗每 '+gameSeconds+' 秒':''} · 实时功率`:r.active?`亮屏 ${uiSettings.screenOnMinutes} 分钟 / 息屏 ${uiSettings.screenOffMinutes} 分钟`:'按屏幕状态记录';
  $('chartInfo').textContent=`${r.active?(followLive?'实时跟随':'已暂停跟随'):'已结束'} · ${plan}`;
  for(const s of seriesFor(r.kind,recordCells(r),recordFast(r))){
    const label=$('legend').querySelector(`[data-series="${s.key}"]`);if(!label)continue;
    const scale=chart.scaleFor(s);label.querySelector('.legend-scale').textContent=scale.label;
    label.classList.toggle('axis-active',chart.axisKey===s.key);
    label.querySelector('.legend-name').textContent=s.name;label.querySelector('input').setAttribute('aria-label',s.name);
    label.title=s.key==='voltage'?`电压范围 ${scale.label}`:`${s.name}：100% = ${scale.label}`;
  }
}
async function loadWindow(){
  if(activeId===null)return;const token=++request,id=activeId,[a,b]=chart.window;detailBusy++;
  try{const data=await readDetail(id,Math.floor(a),Math.ceil(b),1600,()=>token!==request||id!==activeId);if(!data)return;detail=data;applyChart(data,[a,b]);}
  catch(e){toast(e.message);}
  finally{detailBusy--;schedulePoll(0);}
}
function schedulePoll(delay=0){
  clearTimeout(pollTimer);if(document.hidden||$('confirm').open||batteryHistory.active)return;
  if(liveBusy){queuedPoll=true;return;}pollTimer=setTimeout(pollLive,delay);
}
function refresh(){listDue=true;schedulePoll(0);}
function filteredList(){return !!monthFilter||kind!=='all';}
function filterKey(){return[kind,monthFilter?.year??0,monthFilter?.month??0,Number($('showSmall').checked),uiSettings.hidePct].join(':');}
async function loadFiltered(reset){
  if(!filteredList()||filterBusy)return false;
  filterBusy=true;
  const id=++filterRequest,selection=listSelection,key=filterKey(),small=Number($('showSmall').checked),offset=reset?0:records.length;
  const year=monthFilter?.year??0,month=monthFilter?.month??0,type={all:0,charge:1,discharge:2,game:3}[kind];
  try{
    const data=await api('list',small,offset,year,month,type);
    if(id!==filterRequest||selection!==listSelection||key!==filterKey()||(data.listVersion??0)<liveChannel.minimumListVersion)return false;
    const rows=data.records.filter(row=>!liveChannel.deleted.has(row.id)),cached=filteredLists.get(key);
    records=reset?liveChannel.mergeHead(rows,data.total,cached?.records,cached?.total,data.listVersion===cached?.listVersion)
      :[...new Map([...records,...rows].map(row=>[row.id,row])).values()].sort(compareRecords);
    filteredLists.delete(key);filteredLists.set(key,{...data,records});
    while(filteredLists.size>12)filteredLists.delete(filteredLists.keys().next().value);
    total=data.total;totalRecords=data.totalRecords??totalRecords;recordsReady=true;renderRecords();
    listDue=false;lastListAt=Date.now();return true;
  }catch(e){if(id===filterRequest)toast(e.message);return false;}
  finally{if(id===filterRequest)filterBusy=false;}
}
function selectRecords(){
  ++listSelection;++filterRequest;filterBusy=false;revealRecordDelete(null);
  liveChannel.selectSmall($('showSmall').checked);
  $('recordsPage').querySelector('.record-scroll').scrollTop=0;
  const cached=filteredList()?filteredLists.get(filterKey()):liveChannel.cachedList($('showSmall').checked);
  records=cached?.records??[];total=cached?.total??0;totalRecords=cached?.totalRecords??liveChannel.lists?.totalAll??totalRecords;recordsReady=!!cached;renderRecords();
  if(filteredList())loadFiltered(true);
  refresh();
}
function cancelMonthFilter(){
  monthFilter=null;
  $('applyMonth').setAttribute('aria-pressed','false');
  selectRecords();
}
async function pollLive(){
  if(document.hidden||$('confirm').open||batteryHistory.active)return;
  if(liveBusy||detailBusy||loading){schedulePoll(200);return;}
  liveBusy=true;queuedPoll=false;const started=performance.now(),token=request,selection=listSelection,infoMode=batteryPanel.active;
  const id=detail?.record.active&&streamId===activeId?activeId:0,after=streamAfter,small=$('showSmall').checked?1:0;
  const wantList=listDue||Date.now()-lastListAt>=15000;let failed=false;
  try{
    let packet=await api('live',id,after,wantList?1:0,small,(lastStatus?.session??-1)+1);
    if(infoMode!==batteryPanel.active||batteryHistory.active)return;
    if(infoMode){batteryPanel.update(packet);return;}
    const initial=packet,points=[...(packet.points??[])];
    while(id&&valid(packet.nextAfter)&&token===request&&id===activeId){
      packet=await api('live',id,packet.nextAfter,0,small,packet.status.session+1);points.push(...(packet.points??[]));
    }
    packet={...initial,...packet,points,records:initial.records,total:initial.total,totalRecords:initial.totalRecords};
    lastStatus=packet.status;renderStatus(lastStatus);homeUsage.connected();screenTimePanel.prune();
    if(selection===listSelection){
      if(filteredList()){
        if(activeTab==='records'&&!$('appShell').hidden&&(wantList||Array.isArray(packet.records)))await loadFiltered(true);
        else if(activeTab!=='records'||$('appShell').hidden){listDue=false;lastListAt=Date.now();}
      }else if(Array.isArray(packet.records)&&small===Number($('showSmall').checked)){
        records=packet.records;total=packet.total;totalRecords=packet.totalRecords??totalRecords;recordsReady=true;listDue=false;lastListAt=Date.now();renderRecords();
      }
    }
    if(packet.activeRecord&&(!filteredList()||records.some(r=>r.id===packet.activeRecord.id)))updateActiveRecord(packet.activeRecord);
    renderHomeActive(packet.activeRecord&&!liveChannel.deleted.has(packet.activeRecord.id)?packet.activeRecord:null);
    if(id&&id===activeId&&token===request){
      if(packet.record===null){toast('此记录已删除');$('back').click();return;}
      if(packet.record){
        for(const point of points)if(point[0]>streamAfter){streamPoints.push(point);streamAfter=point[0];}
        const changed=points.length>0||packet.record.active!==detail.record.active||valid(packet.statsVersion)&&packet.statsVersion!==detail.statsVersion;
        detail={...detail,record:packet.record,ranges:packet.ranges??detail.ranges,rawCount:packet.rawCount??detail.rawCount,statsVersion:packet.statsVersion??detail.statsVersion,apps:packet.apps??detail.apps,drainSummary:packet.drainSummary??detail.drainSummary};
        if(changed){
          if(followLive&&!chart.pointers.size)showDetail({...detail,points:streamPoints},true,true);
          else{chart.total=Math.max(1,detail.record.end_elapsed-detail.record.start_elapsed);if(packet.apps)renderApps(detail);renderDetailNote(detail);renderFollow();renderChartInfo();}
        }
        renderSummary(detail.record);
        screenOffOverlay.update(detail.record,detail.statsVersion);
      }
    }
    if(token===request&&recordFast(detail?.record))renderGameFrequencies(packet.frequencies??null);
    if(token===request)startRemaining();
  }catch(e){if(infoMode!==batteryPanel.active)return;failed=true;gameFrequencies=null;renderGameFrequencies(null);if(infoMode)batteryPanel.error(e.message);else{$('warning').textContent=connectionMessage(e.message);if(activeId!==null){$('chartInfo').textContent='正在重连…';if(detail)renderSummary(detail.record,true);}}}
  finally{
    liveBusy=false;const period=failed||!batteryPanel.active&&lastStatus?.interactive===false?5000:1000;
    schedulePoll(queuedPoll?0:Math.max(100,period-(performance.now()-started)));
  }
}
function renderStatus(s){
  applySettings(s.settings);settingsPanel.updateRecording(s.recordingEnabled,s.recordingProblem);settingsPanel.updateFast(s.fastEnabled);settingsPanel.updatePowerDisplay(s.powerDisplayEnabled,s.powerDisplayError);$('openCutoff').setAttribute('aria-disabled',String(s.cutoffAvailable===false));
  const r=s.reading??{};
  syncTheme();
  document.querySelector('.live').dataset.flow=s.state===0?'discharge':'charge';
  $('livePct').textContent=valid(r.pct)?fmt(r.pct):'—';$('liveRm').textContent=fmt(r.rm);
  const cells=r.cellCount??s.cellCount;
  const current=valid(r.displayCurrent)?r.displayCurrent:valid(r.current)?r.current*(cells===2?2:1):null;
  const amps=valid(current)&&Math.abs(current)>=1000,currentUnit=amps?'A':'mA';
  $('liveCurrent').textContent=valid(current)?`${current<0?'−':current>0?'+':''}${(Math.abs(current)/(amps?1000:1)).toFixed(amps?2:0)}`:'—';
  $('liveCurrentUnit').textContent=currentUnit;
  $('liveCurrent').parentElement.dataset.flow=valid(current)&&current!==0?(current>0?'charge':'discharge'):'';
  const voltage=cells===2?(valid(r.cell1)&&valid(r.cell2)?(r.cell1+r.cell2)/2:r.voltage):valid(r.cell1)?r.cell1:r.voltage;
  $('liveVoltage').textContent=valid(voltage)?(voltage/1000).toFixed(2):'—';
  $('liveVoltage').setAttribute('aria-label',`${cells===2?'电芯平均':'电池'}电压 ${valid(voltage)?(voltage/1000).toFixed(2):'未知'} V`);
  $('liveTemp').textContent=valid(r.temp)?r.temp.toFixed(1):'—';
  const flow=valid(r.power)&&r.power!==0?(r.power>0?'charge':'discharge'):s.state===0?'discharge':'charge';
  $('livePower').dataset.flow=flow;
  $('livePower').parentElement.dataset.flow=valid(r.power)&&r.power!==0?flow:'';
  $('livePower').textContent=valid(r.power)?`${r.power>0?'+':r.power<0?'−':''}${Math.abs(r.power).toFixed(2)}`:'—';
  $('livePower').setAttribute('aria-label',`${flow==='charge'?'充电':'放电'}功率 ${valid(r.power)?fmt(Math.abs(r.power),2):'—'} W`);
  const warnings=[];if(s.recordingEnabled===false)warnings.push(s.recordingProblem?`${s.recordingProblem}，记录已关闭，可在设置中重新开启。`:'记录已停止。');if(!(s.capabilities&1))warnings.push('休眠采样受限，不能保证深度休眠期间准时采样。');if(!(s.capabilities&2))warnings.push('电源检测可能延迟。');if(!(s.capabilities&4))warnings.push('唤醒锁不可用。');
  if(s.frameworkHealthy===false)warnings.push('系统服务已断开，后台正在自动恢复。');
  if(s.powerStateAvailable===false&&s.recordingEnabled!==false||r.onlineKnown===false)warnings.push('充电连接状态待确认，采样暂停并自动重试。');
  else{if(s.error)warnings.push('采样异常。');if(r.error)warnings.push('电池数据读取失败。');}if(s.usageError&&s.state===0&&s.recordingEnabled!==false)warnings.push('应用统计暂不可用。');
  if(s.screenFallback)warnings.push('屏幕监听不可用，已启用状态回退检测，亮息屏切换可能延迟。');
  else if(s.screenError)warnings.push('屏幕事件通知受限，亮息屏切换可能延迟。');
  if(s.screenStateKnown===false)warnings.push('屏幕状态暂不可用，等待状态恢复。');
  if(s.state===1&&r.tailAvailable===false)warnings.push('尾充识别暂不可用，充电采样继续，拔线或停止记录时结束。');
  $('warning').textContent=warnings.join('\n');
}
function renderRecords(){
  const list=$('records'),shown=records;
  const existing=new Map([...list.children].filter(row=>row.dataset.recordId).map(row=>[Number(row.dataset.recordId),row]));
  const rows=shown.map(r=>{const row=existing.get(r.id)??createRecordRow(r);updateRecordRow(row,r);return row;});
  if(!rows.length){
    const empty=list.querySelector('.empty')??el('div',undefined,'empty'),text=`暂无记录，可勾选“显示 ≤${uiSettings.hidePct}%”。`;
    if(empty.textContent!==text)empty.textContent=text;rows.push(empty);
  }
  let cursor=list.firstChild;
  for(const row of rows){if(row===cursor)cursor=cursor.nextSibling;else list.insertBefore(row,cursor);}
  while(cursor){const next=cursor.nextSibling;cursor.remove();cursor=next;}
  $('recordCount').textContent=recordsReady?(filteredList()?`${total}/共${totalRecords}条`:`共${totalRecords}条`):'';
  $('more').hidden=records.length>=total;
}
function updateRecordRow(row,r){
  row.dataset.active=r.active?'1':'0';row.classList.toggle('game-record',recordFast(r));
  const content=[r.kind,r.pct_start,r.pct_end,r.start_wall,r.start_elapsed,r.end_elapsed,r.reason,r.rm_start,r.rm_end,r.fast_seconds].join('|');
  if(row.dataset.content!==content){
    renderRecordIcon(row.querySelector('.icon'),r);updateRecordTitle(row.querySelector('.info strong'),r);
    updateRecordMeta(row.querySelector('.info small'),r);
    updateRecordDelta(row.querySelector('.delta'),r);row.dataset.content=content;
  }
  const remove=row.querySelector('.record-delete');
  if(remove){remove.hidden=revealedRecordId!==r.id;remove.parentElement.classList.toggle('delete-revealed',!remove.hidden);}
}
function createRecordRow(r){
    const row=el('div',undefined,'record-row record '+r.kind+(recordFast(r)?' game-record':'')),b=el('button',undefined,'record-open');
    row.dataset.recordId=r.id;row.dataset.active=r.active?'1':'0';b.type='button';const icon=el('span',undefined,'icon');renderRecordIcon(icon,r);b.append(icon);
    const info=el('span',undefined,'info');
    const title=el('strong');updateRecordTitle(title,r);info.append(title);
    const meta=el('small');updateRecordMeta(meta,r);info.append(meta);b.append(info);
    const action=el('span',undefined,'record-action'),delta=el('span',undefined,'delta');
    delta.append(el('span',undefined,'delta-value'));updateRecordDelta(delta,r);
    action.append(delta);row.append(b,action);
    const remove=el('button','删除','record-delete');remove.type='button';remove.dataset.deleteId=r.id;
    remove.hidden=revealedRecordId!==r.id;action.classList.toggle('delete-revealed',!remove.hidden);
    remove.onclick=e=>{e.stopPropagation();confirmRecordDelete(records.find(item=>item.id===r.id)??r);};
    action.append(remove);
    let timer,start,held=false,moved=false;
    row.addEventListener('pointerdown',e=>{
      if(e.button!==0||!e.isPrimary||e.target.closest('.record-delete'))return;
      clearTimeout(timer);held=moved=false;start=[e.clientX,e.clientY];
      timer=setTimeout(()=>{if(row.isConnected&&!moved){held=true;revealRecordDelete(r.id);longPressFeedback();}},500);
    });
    row.addEventListener('pointermove',e=>{if(start&&Math.hypot(e.clientX-start[0],e.clientY-start[1])>8){moved=true;clearTimeout(timer);}});
    const end=()=>{clearTimeout(timer);start=null;if(held||moved)setTimeout(()=>{held=moved=false;},400);};
    for(const type of['pointerup','pointercancel','pointerleave'])row.addEventListener(type,end);
    row.onclick=e=>{if(held||moved){e.preventDefault();return;}revealRecordDelete(null);openDetail(r.id);};
    return row;
}
function revealRecordDelete(id){
  revealedRecordId=id;
  for(const button of $('records').querySelectorAll('[data-delete-id]')){button.hidden=Number(button.dataset.deleteId)!==id;button.parentElement.classList.toggle('delete-revealed',!button.hidden);}
}
document.addEventListener('pointerdown',e=>{if(!e.target.closest('.record-row'))revealRecordDelete(null);});
function confirmRecordDelete(record,push=true){
  if(!record||$('confirm').open||liveChannel.deleted.has(record.id))return;
  stopRemaining();deleteTarget=record;
  $('confirmText').textContent=record.active?'当前记录将删除，并立即开始下一段记录。删除后无法恢复。':'曲线和应用统计将删除，无法恢复。';
  if(push)history.pushState({...history.state,deleteRecord:record.id},'',location.href);
  $('confirm').showModal();
}
function syncDeleteConfirmation(){
  const id=history.state?.deleteRecord;
  if(!id){if($('confirm').open)$('confirm').close();return;}
  const record=detail?.record.id===id?detail.record:records.find(row=>row.id===id);
  if(record&&!liveChannel.deleted.has(id))confirmRecordDelete(record,false);
  else{const state={...history.state};delete state.deleteRecord;history.replaceState(state,'');if($('confirm').open)$('confirm').close();}
}
function dismissRecordDelete(){if(history.state?.deleteRecord)history.back();else $('confirm').close();}
function updateActiveRecord(row){
  const index=records.findIndex(r=>r.id===row.id);if(index<0){listDue=true;return;}
  if(records[index].end_elapsed===row.end_elapsed&&records[index].active===row.active)return;
  records[index]=row;const b=$('records').querySelector(`[data-record-id="${row.id}"]`);if(!b)return;
  updateRecordRow(b,row);
}
function renderHomeActive(r){
  homeActiveRecord=r;
  const host=$('homeActive'),button=$('homeActiveOpen');
  const signature=[lastStatus?.recordingEnabled,r?.id,r?.kind,r?.pct_start,r?.pct_end,r?.rm_start,r?.rm_end,r?.end_elapsed,r?.fast_seconds].join('|');
  if(host.dataset.content===signature)return;host.dataset.content=signature;button.disabled=!r;
  const card=$('homeActiveCard');card.className='record-row record'+(r?' '+r.kind+(recordFast(r)?' game-record':''):'');
  if(!r){
    $('homeActiveTitle').textContent='暂无进行中的记录';renderRecordIcon($('homeActiveIcon'),null);
    $('homeActiveMeta').textContent=lastStatus?.recordingEnabled===false?'记录已停止':'等待下一次记录';
    $('homeActivePrimary').textContent='—';$('homeActivePrimary').dataset.flow='';return;
  }
  const changes=recordChanges(r);
  renderRecordIcon($('homeActiveIcon'),r);updateRecordTitle($('homeActiveTitle'),r);
  $('homeActivePrimary').textContent=changes.primary.text;$('homeActivePrimary').dataset.flow=changes.primary.flow;
  updateRecordMeta($('homeActiveMeta'),r);
}
$('homeActiveCard').onclick=()=>{if(homeActiveRecord)openDetail(homeActiveRecord.id);};
async function openDetail(id){
  screenOffOverlay.close();
  usageNavigation++;usageDetails.close();
  screenTimePanel.close();
  performancePanel.close();
  gameFrequencies=null;
  batteryHistory.close();batteryPanel.close();liveChannel.setMode(0);
  stopRemaining();hideAppEnergyTip();
  const token=++request;activeId=id;detail=null;streamId=null;followLive=false;reversePlot=false;syncReversePlot();detailBusy++;
  try{const data=await readDetail(id,0,9007199254740991,1600,()=>token!==request||id!==activeId);if(data)showDetail(data);}
  catch(e){if(token===request){activeId=null;toast(e.message);}}
  finally{detailBusy--;schedulePoll(0);}
}
function showDetail(data,preserve=false,live=false){
  detail=data;const r=data.record;if($('detailScroll').dataset.recordId!==String(r.id)){$('detailScroll').dataset.recordId=r.id;$('detailScroll').scrollTop=0;}$('detail').hidden=false;$('appShell').hidden=true;
  liveChannel.setMode(recordFast(r)?2:0);
  performancePanel.open(r);
  syncTheme();
  $('detailScroll').querySelector('.pagehead').classList.toggle('game-detail-header',recordFast(r));
  $('detailType').textContent=r.kind==='charge'?'充电记录':recordFast(r)?'游戏模式记录':'放电记录';$('detailTitle').textContent=stamp(r.start_wall);$('chartTitle').textContent=r.kind==='charge'?'充电曲线':'放电曲线';
  renderSummary(r);
  syncRemainingExplanation();
  if(!preserve){streamId=r.id;streamPoints=[...data.points];streamAfter=streamPoints.at(-1)?.[0]??0;followLive=!!r.active;followSpan=null;chart.axisKey=null;chart.enabled=loadSelection(recordFast(r)?'game':r.kind,legendKeys(r.kind,recordCells(r),recordFast(r)));buildLegend(r.kind);}
  const end=Math.max(1,r.end_elapsed-r.start_elapsed),view=live&&followLive?followWindow(end):preserve?chart.window:[0,end];applyChart(data,view,live);
  syncReversePlot();
  if(!preserve&&chart.enabled.has('energy')&&!(uiSettings.energyWh>0))toast(uiSettings.energyWh===0?'默认能量量程为 0，量程按记录最大值显示。':'未设置默认能量量程，量程按记录最大值显示。');
  renderApps(data);renderDetailNote(data);
  if(!preserve){if(history.state?.page!=='record'||history.state.id!==r.id)history.pushState({page:'record',id:r.id},'',`${location.pathname}${location.search}#record-${r.id}`);startRemaining();}
}
function frequencyCell(label,kind){
  const cell=el('div',undefined,kind),value=el('strong');
  value.append(el('span','—','frequency-value'),el('span','MHz','metric-unit'));
  cell.append(value,el('small',label));return cell;
}
function renderGameFrequencies(data=gameFrequencies){
  const row=$('summary').querySelector('.summary-frequencies');if(!row)return;
  gameFrequencies=data;
  const clusters=Array.isArray(data?.clusters)&&data.clusters.length?data.clusters:[{label:'CPU',mhz:null}];
  const profile=JSON.stringify(clusters.map(c=>c.label)),cpu=row.querySelector('.summary-cpu-frequencies');
  if(row.dataset.profile!==profile){
    row.dataset.profile=profile;cpu.replaceChildren(...clusters.map(c=>frequencyCell(c.label,'summary-frequency-cpu')));
    row.style.gridTemplateColumns=`minmax(0,${clusters.length}fr) minmax(0,1fr)`;
    cpu.style.gridTemplateColumns=`repeat(${clusters.length},minmax(0,1fr))`;
    row.classList.toggle('many-clusters',clusters.length>3);
  }
  for(let i=0;i<clusters.length;i++)cpu.children[i].querySelector('.frequency-value').textContent=fmt(clusters[i].mhz);
  row.querySelector('.summary-frequency-gpu .frequency-value').textContent=fmt(data?.gpuMHz);
}
function renderSummary(r,failed=false){
  const summary=$('summary'),active=!!r.active,live=!failed&&recordIsLive(r,lastStatus);
  const reading=live?lastStatus.reading??{}:{},flow=valid(reading.power)&&reading.power!==0?(reading.power>0?'charge':'discharge'):r.kind;
  const cells=live?(reading.cellCount??recordCells(r)):recordCells(r);
  const displayCurrent=valid(reading.displayCurrent)?reading.displayCurrent:valid(reading.current)?reading.current*(cells===2?2:1):null;
  const separateCells=cells===2&&(!live||reading.voltageSource!=='android');
  const signed=(v,d=2)=>valid(v)?`${v<0?'−':v>0?'+':''}${Math.abs(v).toFixed(d)}`:'—';
  const number=(v,d)=>valid(v)?v.toFixed(d):'—';
  const elapsed=live&&valid(reading.elapsed)?Math.max(r.end_elapsed,reading.elapsed):r.end_elapsed;
  const currentRecord=live&&valid(reading.rm)?{...r,rm_end:reading.rm}:r;
  const change=capacityChange(currentRecord),changeFlow=recordFast(r)?capacityFlow(currentRecord):r.kind;
  const primary=[
    ['pct',valid(reading.pct)?`${fmt(reading.pct)}%`:'—','电量','',null],
    ['rm',fmt(reading.rm),'剩余容量','',null],
    ['current',r.kind==='discharge'?signed(displayCurrent,0):signed(valid(displayCurrent)?displayCurrent/1000:null),'电流',r.kind==='discharge'?'mA':'A',flow],
    ...(separateCells?[
      ['cell1',number(valid(reading.cell1)?reading.cell1/1000:null,3),'电芯 1','V',null],
      ['cell2',number(valid(reading.cell2)?reading.cell2/1000:null,3),'电芯 2','V',null]
    ]:[['voltage',number(valid(reading.voltage)?reading.voltage/1000:null,3),'电压','V',null]]),
    ['temp',number(reading.temp,1),'温度','°C',null],
    ['power',signed(reading.power),r.kind==='discharge'?'实时功率':'功率','W',flow]
  ];
  if(r.kind==='discharge')primary.push(
    ['remainingWh',null,'估算剩余能量','Wh',null],
    ['remainingTime',null,'估算亮屏时长','',null]
  );
  const values=active?primary:[
    ['pct',`${fmt(r.pct_end)}%`,'结束电量','',null],
    ['change',change,'容量变化','',changeFlow],
    ['duration',duration(r.end_elapsed-r.start_elapsed),'记录时长','',null]
  ];
  const mode=active?'live':'ended',voltageCount=separateCells?'2':'1';summary.dataset.flow=r.kind;
  if(summary.dataset.recordId!==String(r.id)||summary.dataset.mode!==mode||summary.dataset.voltageCount!==voltageCount){
    const sameRecord=summary.dataset.recordId===String(r.id),wh=sameRecord?$('remainingWh')?.textContent:'—',timeLeft=sameRecord?$('remainingTime')?.textContent:'—';
    summary.dataset.recordId=r.id;summary.dataset.mode=mode;summary.dataset.voltageCount=voltageCount;summary.replaceChildren();
    const grid=el('div',undefined,'summary-grid');
    for(const [key,,,unit]of values){
      const item=el('div',undefined,'summary-metric');item.dataset.metric=key;
      const value=el('strong'),text=el('span','','metric-value');
      if(key==='remainingWh'||key==='remainingTime'){text.id=key;text.textContent=(key==='remainingWh'?wh:timeLeft)??'—';}
      value.append(text,el('span',unit,'metric-unit'));
      if(key==='remainingWh')value.append(remainingInfoButton());
      item.append(value,el('small','','metric-label'));grid.append(item);
    }
    const meta=el('div',undefined,'summary-meta');
    meta.append(el('span','','summary-state'),el('span','','summary-capacity'),el('span','','summary-change'),el('time','','summary-time'));
    summary.append(grid);
    if(r.kind==='discharge'&&!active){
      const remaining=el('div',undefined,'summary-remaining');
      const energy=el('div'),energyValue=el('strong'),energyText=el('span',wh??'—');energyText.id='remainingWh';
      energyValue.append(energyText,el('span','Wh','metric-unit'),remainingInfoButton());energy.append(energyValue,el('small','估算剩余能量'));
      const runtime=el('div'),runtimeValue=el('strong',timeLeft??'—');runtimeValue.id='remainingTime';runtime.append(runtimeValue,el('small','估算亮屏时长'));
      remaining.append(energy,runtime);summary.append(remaining);
    }
    if(recordFast(r)){
      const frequencies=el('div',undefined,'summary-frequencies');frequencies.setAttribute('aria-label','当前 CPU 与 GPU 频率');
      frequencies.append(el('div',undefined,'summary-cpu-frequencies'),frequencyCell('GPU','summary-frequency-gpu'));summary.append(frequencies);
    }
    summary.append(meta);
  }
  for(const [key,value,label,,color]of values){
    const item=summary.querySelector(`[data-metric="${key}"]`);
    if(key!=='remainingWh'&&key!=='remainingTime')item.querySelector('.metric-value').textContent=value;
    item.querySelector('.metric-label').textContent=label;
    if(color)item.dataset.flow=color;else delete item.dataset.flow;
  }
  const state=summary.querySelector('.summary-state'),delta=summary.querySelector('.summary-change'),time=summary.querySelector('.summary-time');
  state.textContent=active?`${failed?'连接失败':live?(reading.error?'读取异常':'实时'):'正在读取'} · ${duration(elapsed-r.start_elapsed)}`:
    (reasons[r.reason]??r.reason)+(r.reason==='full_tail_confirmed'?` · ${valid(r.tail_cycles)?fmt(r.tail_cycles):'—'} 次循环`:'');
  const endRm=live&&valid(reading.rm)?reading.rm:r.rm_end,capacity=summary.querySelector('.summary-capacity');
  capacity.textContent=`${valid(r.rm_start)?r.rm_start.toFixed(0):'—'} → ${valid(endRm)?endRm.toFixed(0):'—'}`;
  if(recordFast(r)){capacity.dataset.flow=changeFlow;delta.dataset.flow=changeFlow;}else{delete capacity.dataset.flow;delete delta.dataset.flow;}
  capacity.setAttribute('aria-label',`起始容量 ${valid(r.rm_start)?r.rm_start.toFixed(0):'未知'}，结束容量 ${valid(endRm)?endRm.toFixed(0):'未知'}`);
  delta.textContent=active?`容量变化 ${change}`:'';
  delta.hidden=!active;time.hidden=!active;
  time.textContent=live&&valid(reading.wall)?new Date(reading.wall).toLocaleTimeString('zh-CN',{hour12:false,hour:'2-digit',minute:'2-digit',second:'2-digit'}):'—';
  time.dateTime=live&&valid(reading.wall)?new Date(reading.wall).toISOString():'';
  time.setAttribute('aria-label',`读数时间 ${time.textContent}`);
  if(recordFast(r))renderGameFrequencies();
}
function renderApps(data){
  const r=data.record;
  screenTimePanel.update(r,data.statsVersion??(recordFast(r)?Math.floor(r.end_elapsed/60000):r.end_elapsed));
  renderDrainSummary(data);
  $('apps').hidden=r.kind==='charge';const appRows=$('appRows');appRows.replaceChildren();
  if(r.kind==='charge')return;
  const basis=r.stats_basis;
  $('appStatsNote').textContent=basis==='current_integral'?'能量按电压、电流积分，按前台时长分摊；占比按 Wh 计算。≤1 分钟归入其他。':basis==='rm_minute'?'仅统计放电区间，按 RM 变化和平均电压计算 Wh，每分钟结算并按使用时段分摊。≤1 分钟归入其他。':'按放电能量与使用时段分摊；占比按 Wh 计算。≤1 分钟归入其他。平均功耗仅统计有效数据。';
  const apps=[...(data.apps??[])],sum=apps.reduce((total,a)=>total+appEnergy(a),0);
  apps.sort((a,b)=>sum>0?appEnergy(b)-appEnergy(a):(b.mah??0)-(a.mah??0));
  if(!apps.length)appRows.append(el('p','暂无统计。','small'));
  for(const a of apps)appRows.append(appRow(a,sum));
  loadAppIcons(apps);
}
function appEnergy(a){return valid(a.energyMwh)&&a.energyMs>0?a.energyMwh:0;}
function appWhText(a){
  if(!valid(a.energyMwh)||!(a.energyMs>0))return'—';
  return (a.energyMwh/1000).toFixed(2);
}
function appRow(a,sum){
  const row=el('div',undefined,'appRow');
  row.append(appIconNode(a));
  const info=el('div',undefined,'appInfo'),share=sum>0?`${fmt(appEnergy(a)/sum*100,1)}%`:'—';
  const name=el('div',undefined,'appNameLine');
  name.append(el('span',a.label,'appName'),el('span',share,'app-share'));
  info.append(name);
  const meta=[roundedDuration(a.ms)];
  if(valid(a.avgPower))meta.push(`平均${a.avgPower.toFixed(2)}W`);
  info.append(el('span',meta.join(' · '),'appMeta'));
  const energy=el('div',undefined,'appEnergy'),value=el('strong');
  value.append(el('span',appWhText(a),'app-energy-value'),el('span','Wh','app-energy-unit'));
  energy.append(value);
  energy.setAttribute('aria-label',`${appWhText(a)} Wh${sum>0?`，占 ${share}`:''}，长按查看 mAh 统计`);
  bindAppEnergyTip(energy,a);
  row.append(info,energy);
  return row;
}
let energyTipTimer;
function bindLongPressTip(node,text){
  let timer,start;
  const cancel=()=>{clearTimeout(timer);start=null;};
  node.addEventListener('pointerdown',e=>{
    if(e.button!==0||!e.isPrimary)return;
    clearTimeout(timer);start=[e.clientX,e.clientY];
    timer=setTimeout(()=>{
      showEnergyTip(typeof text==='function'?text():text,start[0],start[1]);longPressFeedback();
    },500);
  });
  node.addEventListener('pointermove',e=>{if(start&&Math.hypot(e.clientX-start[0],e.clientY-start[1])>8)cancel();});
  for(const type of['pointerup','pointercancel','pointerleave'])node.addEventListener(type,cancel);
}
function bindAppEnergyTip(node,a){
  bindLongPressTip(node,()=>{
    if(!(valid(a.mah)&&a.mahMs>0))return'暂无 mAh 统计';
    return`${fmt(Math.round(a.mah))} mAh\n平均 ${fmt(Math.round(a.mah*3600000/a.mahMs))} mA`;
  });
}
function showEnergyTip(text,x,y){
  const tip=$('appEnergyTip');
  tip.textContent=text;tip.hidden=false;
  const w=tip.offsetWidth,h=tip.offsetHeight;
  tip.style.left=`${Math.max(8,Math.min(innerWidth-w-8,x-w-12))}px`;
  tip.style.top=`${Math.max(8,Math.min(innerHeight-h-8,y-h-12))}px`;
  clearTimeout(energyTipTimer);energyTipTimer=setTimeout(()=>tip.hidden=true,3500);
}
function hideAppEnergyTip(){clearTimeout(energyTipTimer);$('appEnergyTip').hidden=true;}
document.addEventListener('pointerdown',e=>{if(!e.target.closest('.appEnergy,.drain-item,.drain-chart'))hideAppEnergyTip();});
function appIconNode(a){
  const box=el('span',undefined,'app-icon');
  if(a.pkg==='__screenoff__'){box.append(appGlyph('moon'));return box;}
  if(a.pkg==='__other__'){box.append(appGlyph('grid'));return box;}
  const icon=appIcons.get(a.pkg);
  if(icon){const img=el('img');img.src=icon;img.alt='';img.decoding='async';img.draggable=false;box.append(img);}
  else box.append(el('span',Array.from(a.label||'?')[0]||'?','app-icon-fallback'));
  return box;
}
function appGlyph(kind){
  const ns='http://www.w3.org/2000/svg',svg=document.createElementNS(ns,'svg');
  svg.setAttribute('viewBox','0 0 24 24');svg.setAttribute('aria-hidden','true');svg.setAttribute('class','app-glyph');
  const paths=kind==='moon'?['M20.3 15.1A8.6 8.6 0 0 1 8.9 3.7 8.7 8.7 0 1 0 20.3 15.1Z']:['M5.5 5.5h4v4h-4zM14.5 5.5h4v4h-4zM5.5 14.5h4v4h-4zM14.5 14.5h4v4h-4z'];
  for(const d of paths){const path=document.createElementNS(ns,'path');path.setAttribute('d',d);svg.append(path);}
  return svg;
}
function loadAppIcons(apps){
  const missing=[...new Set(apps.map(a=>a.pkg).filter(pkg=>pkg&&!pkg.startsWith('__')&&!appIcons.has(pkg)))].sort();
  if(!missing.length)return;
  const key=missing.join('|');
  if(iconAttempts.has(key))return;
  iconAttempts.add(key);
  setTimeout(()=>{
    const icons=readAppIcons(missing);
    for(const [pkg,icon] of Object.entries(icons))if(typeof icon==='string'&&icon.startsWith('data:image/'))appIcons.set(pkg,icon);
    if(detail)renderApps(detail);
  },0);
}
function renderDrainSummary(data){
  $('drainSummary').hidden=data.record.kind==='charge';if(data.record.kind==='charge')return;
  const host=$('drainRows');host.replaceChildren();
  const stats=data.drainSummary??[],energy=a=>valid(a?.energyMwh)&&a.energyMs>0?Math.max(0,a.energyMwh):0;
  const total=stats.reduce((sum,a)=>sum+energy(a),0);
  const layout=el('div',undefined,'drain-layout'),chart=el('div',undefined,'drain-chart');
  const ns='http://www.w3.org/2000/svg',ring=document.createElementNS(ns,'svg');
  ring.setAttribute('viewBox','0 0 100 100');ring.setAttribute('class','drain-ring');ring.setAttribute('aria-hidden','true');
  const circumference=2*Math.PI*40,onLength=total?Math.min(circumference,energy(stats[0])/total*circumference):0,offLength=total?Math.max(0,circumference-onLength):0;
  const segment=(cls,dash,offset)=>{
    const circle=document.createElementNS(ns,'circle');
    circle.setAttribute('cx','50');circle.setAttribute('cy','50');circle.setAttribute('r','40');
    circle.setAttribute('class',`drain-ring-seg ${cls}`);circle.setAttribute('stroke-dasharray',`${dash} ${circumference}`);
    if(offset)circle.setAttribute('stroke-dashoffset',`${-offset}`);
    circle.setAttribute('transform','rotate(-90 50 50)');return circle;
  };
  ring.append(segment('drain-ring-track',circumference,0),segment('drain-ring-on',onLength,0),segment('drain-ring-off',offLength,onLength));
  const center=el('div',undefined,'drain-center'),totalText=el('div');
  totalText.append(el('strong',total?fmt(total/1000,2):'—'),el('span','Wh','drain-center-unit'));
  center.append(totalText,el('small','合计'));chart.append(ring,center);
  const totalMs=stats.reduce((sum,a)=>sum+(valid(a?.ms)?Math.max(0,a.ms):0),0);
  const totalEnergyMs=stats.reduce((sum,a)=>sum+(valid(a?.energyMs)?Math.max(0,a.energyMs):0),0);
  const totalPower=totalEnergyMs>0?total*3600/totalEnergyMs:null;
  bindLongPressTip(chart,()=>totalMs>0||totalPower!==null?`总时长  ${roundedDuration(totalMs)}\n总平均功耗  ${totalPower!==null?totalPower.toFixed(2)+' W':'—'}`:'暂无统计');
  const items=el('div',undefined,'drain-items');
  for(const [i,label]of['亮屏耗电','息屏耗电'].entries()){
    const a=stats[i]??{ms:0,energyMwh:0,energyMs:0,avgPower:null},value=energy(a),share=total?value/total*100:null;
    const item=el('div',undefined,'drain-item '+(i?'off':'on')),head=el('div',undefined,'drain-item-head'),name=el('div',undefined,'drain-item-name');
    name.append(el('span',undefined,'drain-dot'),el('span',label,'drain-item-label'));
    head.append(name,el('strong',share===null?'—':`${fmt(share,1)}%`,'drain-item-share'));
    const values=el('div',undefined,'drain-item-values'),wh=el('span'),avg=el('span');
    wh.append(el('b',value?fmt(value/1000,2):'—'),document.createTextNode(' Wh'));
    avg.append(el('b',valid(a.avgPower)?a.avgPower.toFixed(2):'—'),document.createTextNode(' W'));
    values.append(wh,el('span',roundedDuration(a.ms),'drain-duration'),avg);
    bindAppEnergyTip(item,a);
    item.append(head,values);items.append(item);
  }
  layout.append(items,chart);host.append(layout);
}
function renderDetailNote(data){
  const r=data.record;
  $('detailNote').textContent=`${fmt(data.rawCount??data.points.length)} 个采样点 · 缺样 ${r.missed??0} · 异常 ${r.anomalies??0}`;
}
function legendKeys(kind,cells,fast){return [...seriesFor(kind,cells,fast).map(s=>s.key),...(kind==='discharge'?['screenOff']:[])];}
function buildLegend(kind){
  const cells=recordCells(detail?.record);
  const fast=recordFast(detail?.record);
  const remember=()=>{if(!saveSelection(fast?'game':kind,chart.enabled,legendKeys(kind,cells,fast)))toast('勾选设置未能保存。');};
  $('legend').replaceChildren();$('legend').dataset.kind=kind;
  for(const s of seriesFor(kind,cells,fast)){
    const label=el('label');label.dataset.series=s.key;label.style.setProperty('--series',s.color);
    const input=el('input');input.type='checkbox';input.checked=chart.enabled.has(s.key);input.setAttribute('aria-label',s.name);
    input.onchange=()=>{
      input.checked?chart.enabled.add(s.key):chart.enabled.delete(s.key);
      if(input.checked&&s.key==='energy'&&!(uiSettings.energyWh>0))toast(uiSettings.energyWh===0?'默认能量量程为 0，量程按记录最大值显示。':'未设置默认能量量程，量程按记录最大值显示。');
      if(!input.checked&&chart.axisKey===s.key)chart.axisKey=null;
      remember();
      chart.clearInspect();chart.draw();renderChartInfo();
    };
    let timer,start,held=false,dragged=false;
    label.addEventListener('pointerdown',e=>{
      if(e.button!==0||!e.isPrimary)return;
      clearTimeout(timer);held=dragged=false;start=[e.clientX,e.clientY];
      timer=setTimeout(()=>{
        if(!label.isConnected||detail?.record.kind!==kind)return;
        held=true;longPressFeedback();chart.axisKey=chart.axisKey===s.key?null:s.key;
        chart.enabled.add(s.key);input.checked=true;remember();
        if(s.key==='energy'&&!(uiSettings.energyWh>0))toast(uiSettings.energyWh===0?'默认能量量程为 0，量程按记录最大值显示。':'未设置默认能量量程，量程按记录最大值显示。');
        chart.clearInspect();chart.draw();renderChartInfo();
      },500);
    });
    label.addEventListener('pointermove',e=>{if(start&&Math.hypot(e.clientX-start[0],e.clientY-start[1])>8){dragged=true;clearTimeout(timer);}});
    const end=()=>{clearTimeout(timer);start=null;if(held||dragged)setTimeout(()=>{held=dragged=false;},400);};
    for(const event of['pointerup','pointercancel','pointerleave'])label.addEventListener(event,end);
    label.addEventListener('click',e=>{if(held||dragged){e.preventDefault();e.stopImmediatePropagation();}},{capture:true});
    label.append(input,el('span',s.name,'legend-name'),el('span',s.axis,'legend-short'),el('span','','legend-scale'));$('legend').append(label);
  }
  if(kind==='discharge'){
    const label=el('label');label.className='screen-off-option';label.style.setProperty('--series','#7c858d');label.title='按已保存的息屏时段显示淡灰投影';
    const input=el('input');input.type='checkbox';input.checked=chart.enabled.has('screenOff');input.setAttribute('aria-label','息屏投影');
    input.onchange=()=>{
      input.checked?chart.enabled.add('screenOff'):chart.enabled.delete('screenOff');remember();
      chart.draw();if(detail)screenOffOverlay.update(detail.record,detail.statsVersion);
    };
    label.append(input,el('span','息屏投影'));$('legend').insertBefore(label,$('legend').querySelector('[data-series="energy"]'));
  }
}
function syncFullscreenLayout(){
  if(!expanded)return;const panel=$('chartPanel'),v=window.visualViewport;
  const w=v?.width??innerWidth,h=v?.height??innerHeight,rotate=h>w;
  panel.classList.toggle('rotate',rotate);
  panel.style.setProperty('--full-width',`${rotate?h:w}px`);panel.style.setProperty('--full-height',`${rotate?w:h}px`);
  panel.style.setProperty('--full-left',`${(v?.offsetLeft??0)+w/2}px`);panel.style.setProperty('--full-top',`${(v?.offsetTop??0)+h/2}px`);
  requestAnimationFrame(()=>chart.draw());
}
async function toggleFullscreen(enable){
  expanded=enable;const panel=$('chartPanel');
  if(enable&&!fullscreenAnchor){fullscreenAnchor=document.createComment('chart position');panel.replaceWith(fullscreenAnchor);document.body.append(panel);}
  panel.classList.toggle('expanded',enable);document.body.classList.toggle('chartExpanded',enable);
  syncReversePlot();
  $('closeFullscreen').hidden=!enable;$('fullscreen').hidden=enable;
  try{fullScreen(enable);}catch{}
  if(enable){
    syncFullscreenLayout();
    try{await panel.requestFullscreen();await screen.orientation.lock('landscape');}catch{}
    syncFullscreenLayout();
  }else{
    panel.classList.remove('rotate');try{screen.orientation.unlock();if(document.fullscreenElement)await document.exitFullscreen();}catch{}
    if(fullscreenAnchor){fullscreenAnchor.replaceWith(panel);fullscreenAnchor=null;}
    for(const key of['--full-width','--full-height','--full-left','--full-top'])panel.style.removeProperty(key);
  }
  chart.draw();
}
document.addEventListener('fullscreenchange',()=>{if(!document.fullscreenElement&&expanded)toggleFullscreen(false);});
window.addEventListener('resize',syncFullscreenLayout);window.visualViewport?.addEventListener('resize',syncFullscreenLayout);window.visualViewport?.addEventListener('scroll',syncFullscreenLayout);
$('fullscreen').onclick=()=>toggleFullscreen(true);$('closeFullscreen').onclick=()=>toggleFullscreen(false);
$('followLatest').onclick=()=>{
  if(!detail?.record.active)return;
  if(followLive){pauseFollowing();renderChartInfo();return;}
  ++request;clearTimeout(zoomTimer);const span=chart.window[1]-chart.window[0];
  followSpan=chart.window[0]===0&&span>=chart.total-1?null:span;followLive=true;
  showDetail({...detail,points:streamPoints},true,true);schedulePoll(0);
};
$('resetZoom').onclick=()=>{
  if(detail?.record.active&&streamId===activeId){++request;clearTimeout(zoomTimer);followLive=true;followSpan=null;showDetail({...detail,points:streamPoints},true,true);schedulePoll(0);}
  else{chart.setWindow(0,chart.total);scheduleZoom();}
};
async function openUsage(state,push=null){
  const navigation=++usageNavigation,day=Number(state.day),isApp=state.page==='usage-app'||state.page==='usage-app-week';
  if(!Number.isFinite(day)||day<0||day> Date.now()||!usagePages.has(state.page)||isApp&&(typeof state.pkg!=='string'||!state.pkg||state.pkg.length>256))return;
  if(activeTab==='settings'&&!await settingsPanel.flush()){toast('设置未能保存，请重试。');return;}
  batteryHistory.close();batteryPanel.close();settingsPanel.closeEditor();liveChannel.setMode(0);await leaveDetail();
  if(navigation!==usageNavigation)return;
  $('appShell').hidden=true;
  const pageState=usageState({...state,day});
  if(push===true)history.pushState(pageState,'',pageUrl(state.page));else if(push===false)history.replaceState(pageState,'',pageUrl(state.page));
  document.title=state.page==='usage-app-week'?'应用周使用时间 · ACol 电池':isApp?'应用使用时间 · ACol 电池':state.page==='usage-month'?'月使用时间 · ACol 电池':state.page==='usage-week'?'周使用时间 · ACol 电池':'使用时间 · ACol 电池';
  await usageDetails.open(pageState);if(navigation===usageNavigation)refresh();
}
$('openUsageDetails').onclick=()=>openUsage({page:'usage-day',day:homeUsage.day},true);
for(const id of ['usageBack','usageAppBack','usageWeekBack','usageMonthBack','usageAppWeekBack'])$(id).onclick=()=>window.__acoBack();
for(const button of document.querySelectorAll('.page-home'))button.onclick=()=>returnHome();
window.__acoGoHome=returnHome;
for(const [id,method]of [['openErrorLogs','openErrorLogs'],['clearAllData','clearAllData'],['backupAllData','backupAllData'],['restoreAllData','restoreAllData']])$(id).onclick=async()=>{
  if(!await settingsPanel.flush()){toast('设置未能保存，请重试。');return;}
  const bridge=window.AcoNative;if(typeof bridge?.[method]!=='function'){toast('当前应用入口不可用，请重新打开应用。');return;}
  bridge[method]();
};
async function returnHome(){
  if(activeTab==='settings'&&!await settingsPanel.flush()){toast('设置未能保存，请重试。');return;}
  hideCutoff();
  history.replaceState({page:'home'},'',baseUrl());
  await showHome();
}
async function showHome(){
  if(activeTab==='settings'&&!await settingsPanel.flush()){toast('设置未能保存，请重试。');return false;}
  await activateTab('home');return true;
}
async function applyHistory(state){
  syncRemainingExplanation();syncDeleteConfirmation();
  const page=state?.page??'home';
  if(page==='cutoff'){if(cutoffFrame.hidden)showCutoff(state);else if(cutoffReady)cutoffFrame.contentWindow.__acoCutoffSync(state);return;}
  if(!cutoffFrame.hidden){hideCutoff();$('appShell').hidden=false;}
  if(usagePages.has(page)){await openUsage(state);return;}
  if(page==='battery-history'||page==='battery-history-detail'){await openBatteryHistory(state);return;}
  if(page==='battery-info'){if(!batteryPanel.active)openBatteryInfo();batteryPanel.handleHistory(state);return;}
  if(page==='record'){settingsPanel.closeEditor();if(activeId!==state.id)openDetail(state.id);return;}
  if(page==='settings'){if(activeTab!=='settings')await activateTab('settings');settingsPanel.handleHistory(state);return;}
  if(activeTab==='settings'&&!await settingsPanel.flush()){
    history.pushState({page:'settings'},'',pageUrl('settings'));$('appShell').hidden=false;showTab('settings');toast('设置未能保存，请重试。');return;
  }
  await activateTab(page==='records'?'records':'home');
}
$('back').onclick=()=>window.__acoBack();
window.addEventListener('popstate',event=>applyHistory(event.state));
$('delete').onclick=()=>confirmRecordDelete(detail?.record);
$('cancelDelete').onclick=dismissRecordDelete;
$('confirm').addEventListener('cancel',e=>{e.preventDefault();dismissRecordDelete();});
$('confirmDelete').onclick=async()=>{
  const target=deleteTarget;if(!target)return;
  $('confirmDelete').disabled=true;
  try{
    const result=await api('delete',target.id);liveChannel.forgetRecord(target,result.listVersion??0);screenTimePanel.remove(target.id);
    ++listSelection;++filterRequest;filterBusy=false;filteredLists.clear();
    if(records.some(r=>r.id===target.id))total=Math.max(0,total-1);
    totalRecords=Math.max(0,totalRecords-1);records=records.filter(r=>r.id!==target.id);renderRecords();
    $('confirm').close();revealRecordDelete(null);
    if(history.state?.deleteRecord===target.id)history.go(activeId===target.id?-2:-1);
    else if(activeId===target.id)$('back').click();
    refresh();
    toast('记录已删除');
  }catch(e){toast(e.message);}finally{$('confirmDelete').disabled=false;}
};
function renderDateFilter(){
  const year=$('filterYear'),month=$('filterMonth');
  year.textContent=pad2(filterYear%100);month.textContent=pad2(filterMonth);
  year.setAttribute('aria-valuenow',String(filterYear));year.setAttribute('aria-valuetext',`${filterYear}年`);
  month.setAttribute('aria-valuenow',String(filterMonth));month.setAttribute('aria-valuetext',`${filterMonth}月`);
}
const initialDate=new Date();filterYear=initialDate.getFullYear();filterMonth=initialDate.getMonth()+1;renderDateFilter();
$('yearUp').onclick=()=>{filterYear=Math.min(2100,filterYear+1);renderDateFilter();};
$('yearDown').onclick=()=>{filterYear=Math.max(2000,filterYear-1);renderDateFilter();};
$('monthUp').onclick=()=>{filterMonth=filterMonth%12+1;renderDateFilter();};
$('monthDown').onclick=()=>{filterMonth=filterMonth===1?12:filterMonth-1;renderDateFilter();};
$('applyMonth').onclick=()=>{
  if(monthFilter&&monthFilter.year===filterYear&&monthFilter.month===filterMonth){cancelMonthFilter();return;}
  monthFilter={year:filterYear,month:filterMonth};
  $('applyMonth').setAttribute('aria-pressed','true');
  selectRecords();
};
$('showSmall').onchange=()=>{
  selectRecords();
};
for(const b of document.querySelectorAll('[data-kind]'))b.onclick=()=>{kind=b.dataset.kind;for(const e of document.querySelectorAll('[data-kind]')){const selected=e===b;e.classList.toggle('selected',selected);e.setAttribute('aria-pressed',String(selected));}syncTheme();selectRecords();};
$('more').onclick=async()=>{if(filteredList()){loadFiltered(false);return;}if(loading)return;loading=true;const selection=listSelection,small=$('showSmall').checked?1:0;try{const data=await api('list',small,records.length);if(selection!==listSelection)return;const list=liveChannel.appendList(small,data);records=list.records;total=list.total;totalRecords=list.totalRecords??totalRecords;recordsReady=true;renderRecords();}catch(e){if(selection===listSelection)toast(e.message);}finally{loading=false;}};
const recordScroller=$('recordsPage').querySelector('.record-scroll');
recordScroller.addEventListener('scroll',()=>{
  if(activeTab==='records'&&!$('appShell').hidden&&!loading&&!filterBusy&&!$('more').hidden&&recordScroller.scrollTop>0&&recordScroller.scrollHeight-recordScroller.scrollTop-recordScroller.clientHeight<=60)$('more').click();
},{passive:true});

document.addEventListener('visibilitychange',()=>{clearTimeout(pollTimer);stopRemaining();if(document.hidden){gameFrequencies=null;renderGameFrequencies(null);settingsPanel.flush();liveChannel.close();}else{refresh();startRemaining();}});
window.addEventListener('pagehide',()=>{clearTimeout(pollTimer);stopRemaining();settingsPanel.flush();liveChannel.close();});
window.addEventListener('pageshow',e=>{if(e.persisted){refresh();startRemaining();}});
document.addEventListener('pointerup',()=>{if(followLive&&detail?.record.active&&!chart.pointers.size)showDetail({...detail,points:streamPoints},true,true);});
$('confirm').addEventListener('close',()=>{deleteTarget=null;schedulePoll(0);startRemaining();});
document.addEventListener('keydown',e=>{if(e.key==='Escape'&&expanded)toggleFullscreen(false);});
themeUiReady=true;
window.__acoPageReady=true;
if(startTab!=='home'){
  history.pushState({page:startTab},'',pageUrl(startTab));
  activateTab(startTab).catch(()=>{});
}
refresh();
