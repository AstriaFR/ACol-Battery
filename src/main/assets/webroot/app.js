import{valid,setChartSettings}from'./chart.js?v=acol-2.2s';
import{fmt}from'./record-display.js?v=acol-2.2s';
import{createRecords}from'./records.js?v=acol-2.2s';
import{installPageDepth}from'./page-navigation.js?v=acol-2.2s';
import{installPageMotion}from'./page-motion.js?v=acol-2.2s';
import{installLineFitting}from'./fit-lines.js?v=acol-2.2s';
import{exec,fullScreen,cancelTopDialog,readAppIcons}from'./root-bridge.js?v=acol-2.2s';
import{LiveChannel}from'./live-channel.js?v=acol-2.2s';
import{DEFAULT_SETTINGS}from'./config.js?v=acol-2.2s';
import{SettingsPanel}from'./settings-panel.js?v=acol-2.2s';
import{BatteryInfoPanel}from'./battery-info.js?v=acol-2.2s';
import{ThemeController}from'./theme.js?v=acol-2.2s';
import{EdgeScrollbar}from'./edge-scrollbar.js?v=acol-2.2s';
import{HomeUsagePanel}from'./home-usage.js?v=acol-2.2s';
import{usagePages,usageRoots,usageState}from'./usage-navigation.js?v=acol-2.2s';
const $=id=>document.getElementById(id),command='/data/adb/aco_battery_app/runtime/bin/curve-client';
const edgeScrollbar=new EdgeScrollbar();
const baseUrl=()=>`${location.pathname}${location.search}`;
const pageUrl=tab=>baseUrl()+(tab==='home'?'':'#'+tab);
installPageDepth();
installPageMotion();
installLineFitting();
const startTab=location.hash==='#settings'?'settings':location.hash==='#records'?'records':'home';
history.replaceState({page:'home'},'',baseUrl());
const tabButtons=[...document.querySelectorAll('[data-tab]')];
let activeTab='home';
for(const type of['selectstart','copy','cut','contextmenu','dragstart'])document.addEventListener(type,e=>e.preventDefault(),{capture:true});
// Cancel native press defaults without blocking touch-action scrolling.
document.addEventListener('pointerdown',e=>{
  if(e.pointerType==='touch'&&!e.target.closest('input[type=number],textarea,[contenteditable=true]'))e.preventDefault();
},{capture:true,passive:false});
document.addEventListener('selectionchange',()=>{const selection=getSelection();if(selection?.rangeCount)selection.removeAllRanges();});
document.addEventListener('keydown',e=>{if((e.ctrlKey||e.metaKey)&&['a','c','x'].includes(e.key.toLowerCase()))e.preventDefault();});
const liveChannel=new LiveChannel();
let lastStatus=null,pollTimer,liveBusy=false,queuedPoll=false,usageNavigation=0;
let detailPage,batteryHistory,usageDetails,pendingPanel;
const batteryHistoryRoots={'battery-history':'batteryHistoryPanel','battery-history-detail':'batteryHistoryDetail','battery-history-trend':'batteryTrendPanel'};
function connectionMessage(fallback){try{return window.AcoNative?.connectionStatus?.()||fallback;}catch{return fallback;}}
window.addEventListener('aco-connection',event=>{if(!$('warning').dataset.startupError)$('warning').textContent=event.detail||'';});
$('warning').textContent=connectionMessage('正在连接…');
let uiSettings={...DEFAULT_SETTINGS};
function syncTheme(){
  const kind=recordsView.kind,flow=detailPage?.data?.record.kind??(kind==='all'?(lastStatus?.state===0?'discharge':'charge'):kind==='game'?'discharge':kind);
  document.body.dataset.flow=flow;
}
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
function lazyPanel(load){
  let pending;
  return()=>pending??=load().catch(error=>{pending=null;throw error;});
}
const importDetail=()=>import('./record-detail.js?v=acol-2.2s');
const importBatteryHistory=()=>import('./battery-history.js?v=acol-2.2s');
const importUsageDetails=()=>import('./usage-details.js?v=acol-2.2s');
const warmModules=[importDetail,importUsageDetails,importBatteryHistory];
let warming=false;
function warmPanelCode(){
  if(warming||!warmModules.length||document.hidden||activeTab!=='home'||$('appShell').hidden)return;
  warming=true;
  const load=async()=>{
    try{
      if(!document.hidden&&activeTab==='home'&&!$('appShell').hidden)await warmModules.shift()();
    }catch{}finally{warming=false;}
  };
  if(typeof requestIdleCallback==='function')requestIdleCallback(load);else setTimeout(load,200);
}
function closePendingPanel(hide=true){
  if(!pendingPanel)return;
  const{root,status}=pendingPanel;pendingPanel=null;
  delete root.dataset.panelLoading;status.remove();if(hide)root.hidden=true;
}
function showPendingPanel(id){
  closePendingPanel();const root=$(id),status=document.createElement('p');
  status.className='small panel-loading-status';status.setAttribute('role','status');status.textContent='正在加载…';
  root.dataset.panelLoading='true';root.append(status);root.hidden=false;pendingPanel={root,status};
}
const loadDetail=lazyPanel(async()=>{
  const{createRecordDetail}=await importDetail();
  const panel=createRecordDetail({api,liveChannel,toast,fullScreen,readAppIcons,
    beforeOpen(){closePendingPanel();usageDetails?.close();batteryHistory?.close();batteryPanel.close();},
    onShow:syncTheme,onRefresh:()=>schedulePoll(0)});
  panel.setStatus(lastStatus);panel.setSettings(uiSettings);panel.bind();return detailPage=panel;
});
async function openRecord(id,record){
  const navigation=++usageNavigation;
  try{const panel=await loadDetail();if(navigation===usageNavigation)await panel.open(id,record);}
  catch(error){if(navigation===usageNavigation)toast(error.message);}
}
const recordsView=createRecords({api,liveChannel,toast,getSettings:()=>uiSettings,getStatus:()=>lastStatus,getDetail:()=>detailPage?.data,
  getTab:()=>activeTab,openRecord,onKindChange:syncTheme,
  pauseRemaining:()=>detailPage?.pauseRemaining(),onDialogClose(){schedulePoll(0);detailPage?.resumeRemaining();},
  onDeleted:id=>detailPage?.removeCaches(id),onRefresh:()=>schedulePoll(0)});
const settingsPanel=new SettingsPanel(api,(value,result)=>{if(result?.removed>0)recordsView.invalidate();applySettings(value);refresh();});
const batteryPanel=new BatteryInfoPanel();
const homeUsage=new HomeUsagePanel(api);
const loadBatteryHistory=lazyPanel(async()=>{
  const{BatteryHistoryPanel}=await importBatteryHistory();
  return batteryHistory=new BatteryHistoryPanel(api,(state,push)=>openBatteryHistory(state,push),()=>window.__acoBack());
});
const loadUsageDetails=lazyPanel(async()=>{
  const{UsageDetailsPanel}=await importUsageDetails();
  const panel=new UsageDetailsPanel(api,()=>homeUsage.cache,(state,push)=>openUsage(state,push));
  panel.dayChart.onDeleteDay=askUsageDelete;
  return usageDetails=panel;
});
homeUsage.onDataChange=day=>usageDetails?.refresh(day);
let usageDeleteDay=null,usageDeleteBusy=false;
const askUsageDelete=day=>{if(usageDeleteBusy||$('usageDeleteConfirm').open)return;usageDeleteDay=day;$('usageDeleteConfirm').showModal();$('usageDeleteCancel').focus({preventScroll:true});};
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
    detailPage?.theme();
    batteryHistory?.refreshTheme();
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
async function activateTab(tab){
  usageNavigation++;closePendingPanel();usageDetails?.close();
  batteryHistory?.close();batteryPanel.close();
  settingsPanel.closeEditor();
  liveChannel.setMode(3);
  await detailPage?.close();
  $('appShell').hidden=false;
  showTab(tab);
  if(tab==='home')homeUsage.prioritize(null);
  syncTheme();
  if(tab==='records')recordsView.open();
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
  if(usageDetails&&(usageDetails.dayChart.tip&&!usageDetails.dayChart.tipHost||usageDetails.appChart.tip)){usageDetails.hideTips();return'handled';}
  if(homeUsage.tip){homeUsage.hideTip();return'handled';}
  if(cancelTopDialog())return'handled';
  if(detailPage?.backFullscreen())return'handled';
  if(history.state?.page&&history.state.page!=='home'){history.back();return'handled';}
  // Recover a visible child page even if its browser history state was lost.
  if(activeTab!=='home'||batteryPanel.active||batteryHistory?.active||!$('detail').hidden||usageDetails?.state){showHome();return'handled';}
  return $('appShell').hidden?'wait':'home';
};
function applySettings(value){
  const next={...DEFAULT_SETTINGS,...value};
  if(JSON.stringify(next)===JSON.stringify(uiSettings))return;
  uiSettings=next;setChartSettings(next);
  $('showSmallLabel').textContent=`显示 ≤${next.hidePct}%`;
  detailPage?.setSettings(next);
}
$('openCutoff').onclick=async e=>{e.preventDefault();const button=e.currentTarget;if(button.getAttribute('aria-disabled')==='true'){toast('当前设备缺少截止电压调整接口。');return;}if(!await settingsPanel.flush()){toast('设置未能保存，请重试。');return;}history.pushState({page:'cutoff'},'',pageUrl('cutoff'));showCutoff(history.state);};
async function openBatteryInfo(){
  closePendingPanel();batteryHistory?.close();const navigation=++usageNavigation;usageDetails?.close();settingsPanel.closeEditor();
  await detailPage?.close();if(navigation!==usageNavigation)return;
  $('appShell').hidden=true;batteryPanel.open();liveChannel.setMode(1);
  if(history.state?.page!=='battery-info')history.pushState({page:'battery-info'},'',`${location.pathname}${location.search}#battery-info`);
  refresh();
}
$('openBatteryInfo').onclick=openBatteryInfo;
$('batteryInfoBack').onclick=()=>window.__acoBack();
$('openBatteryHistory').onclick=()=>openBatteryHistory({page:'battery-history'},true);
for(const id of ['batteryHistoryBack','batterySnapshotBack','batteryTrendBack'])$(id).onclick=()=>window.__acoBack();
async function openBatteryHistory(state,push=false){
  const navigation=++usageNavigation;
  closePendingPanel();
  usageDetails?.close();batteryPanel.close();settingsPanel.closeEditor();
  await detailPage?.close();if(navigation!==usageNavigation)return;
  clearTimeout(pollTimer);liveChannel.setMode(3);liveChannel.close();$('appShell').hidden=true;
  if(push)history.pushState(state,'',pageUrl(state.page));
  document.title=(state.page==='battery-history'?'电池历史记录':state.page==='battery-history-trend'?'电池健康趋势':'电池快照')+' · ACol 电池';
  if(!batteryHistory){
    showPendingPanel(batteryHistoryRoots[state.page]);
    try{await loadBatteryHistory();}catch(error){if(navigation===usageNavigation&&pendingPanel)pendingPanel.status.textContent=error.message;return;}
    if(navigation!==usageNavigation)return;
    closePendingPanel(false);
  }
  await batteryHistory.open(state,push&&state.page==='battery-history');
}

function schedulePoll(delay=0){
  clearTimeout(pollTimer);if(document.hidden||$('confirm').open||Object.hasOwn(batteryHistoryRoots,history.state?.page))return;
  if(liveBusy){queuedPoll=true;return;}pollTimer=setTimeout(pollLive,delay);
}
function refresh(){recordsView.refresh();}
async function pollLive(){
  if(document.hidden||$('confirm').open||Object.hasOwn(batteryHistoryRoots,history.state?.page))return;
  if(liveBusy||detailPage?.busy||recordsView.busy){schedulePoll(200);return;}
  liveBusy=true;queuedPoll=false;
  const started=performance.now(),snapshot=detailPage?.pollRequest()??{id:0,after:0},listSnapshot=recordsView.pollRequest(),infoMode=batteryPanel.active;
  const{id,after,token}=snapshot,{small}=listSnapshot;let failed=false;
  try{
    let packet=await api('live',id,after,0,small,(lastStatus?.session??-1)+1,0);
    if(infoMode!==batteryPanel.active||batteryHistory?.active)return;
    if(infoMode){batteryPanel.update(packet);return;}
    const initial=packet,points=[...(packet.points??[])];
    while(id&&valid(packet.nextAfter)&&detailPage.current(token,id)){
      packet=await api('live',id,packet.nextAfter,0,small,packet.status.session+1,0);points.push(...(packet.points??[]));
    }
    packet={...initial,...packet,points,records:initial.records,total:initial.total,totalRecords:initial.totalRecords};
    lastStatus=packet.status;detailPage?.setStatus(lastStatus);renderStatus(lastStatus);homeUsage.connected();detailPage?.prune();warmPanelCode();
    await recordsView.receive(packet,listSnapshot);
    await detailPage?.receive(packet,snapshot);
  }catch(error){
    if(infoMode!==batteryPanel.active)return;failed=true;
    if(infoMode)batteryPanel.error(error.message);else{$('warning').textContent=connectionMessage(error.message);detailPage?.failure();}
  }finally{
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
  $('liveCurrent').textContent=valid(current)?(Math.abs(current)/(amps?1000:1)).toFixed(amps?2:0):'—';
  $('liveCurrentUnit').textContent=currentUnit;
  $('liveCurrent').parentElement.dataset.flow=valid(current)&&current!==0?(current>0?'charge':'discharge'):'';
  const voltage=cells===2?(valid(r.cell1)&&valid(r.cell2)?(r.cell1+r.cell2)/2:r.voltage):valid(r.cell1)?r.cell1:r.voltage;
  $('liveVoltage').textContent=valid(voltage)?(voltage/1000).toFixed(2):'—';
  $('liveVoltage').setAttribute('aria-label',`${cells===2?'电芯平均':'电池'}电压 ${valid(voltage)?(voltage/1000).toFixed(2):'未知'} V`);
  $('liveTemp').textContent=valid(r.temp)?r.temp.toFixed(1):'—';
  const flow=valid(r.power)&&r.power!==0?(r.power>0?'charge':'discharge'):s.state===0?'discharge':'charge';
  $('livePower').dataset.flow=flow;
  $('livePower').parentElement.dataset.flow=valid(r.power)&&r.power!==0?flow:'';
  $('livePower').textContent=valid(r.power)?Math.abs(r.power).toFixed(2):'—';
  $('livePower').setAttribute('aria-label',`${flow==='charge'?'充电':'放电'}功率 ${valid(r.power)?fmt(Math.abs(r.power),2):'—'} W`);
  const recording=s.recordingEnabled!==false||s.fastEnabled;
  const warnings=[];if(!recording)warnings.push(s.recordingProblem?`${s.recordingProblem}，记录已关闭，可在设置中重新开启。`:'记录已停止。');if(!(s.capabilities&1))warnings.push('休眠采样受限，不能保证深度休眠期间准时采样。');if(!(s.capabilities&2))warnings.push('电源检测可能延迟。');if(!(s.capabilities&4))warnings.push('唤醒锁不可用。');
  if(s.frameworkHealthy===false)warnings.push('系统服务已断开，后台正在自动恢复。');
  if(recording&&s.readingRecoveryProblem)warnings.push(`${s.readingRecoveryProblem}，后台正在重试。`);
  if(s.powerStateAvailable===false&&recording||r.onlineKnown===false)warnings.push('充电连接状态待确认，采样暂停并自动重试。');
  else{if(s.error)warnings.push('采样异常。');if(r.error)warnings.push('电池数据读取失败。');}if(s.usageError&&s.state===0&&recording)warnings.push('应用统计暂不可用。');
  if(s.screenFallback)warnings.push('屏幕监听不可用，已启用状态回退检测，亮息屏切换可能延迟。');
  else if(s.screenError)warnings.push('屏幕事件通知受限，亮息屏切换可能延迟。');
  if(s.screenStateKnown===false)warnings.push('屏幕状态暂不可用，等待状态恢复。');
  $('warning').textContent=warnings.join('\n');
}
async function openUsage(state,push=null){
  const navigation=++usageNavigation,day=Number(state.day),isApp=state.page==='usage-app'||state.page==='usage-app-week';
  if(!Number.isFinite(day)||day<0||day> Date.now()||!usagePages.has(state.page)||isApp&&(typeof state.pkg!=='string'||!state.pkg||state.pkg.length>256))return;
  if(activeTab==='settings'&&!await settingsPanel.flush()){toast('设置未能保存，请重试。');return;}
  if(navigation!==usageNavigation)return;
  closePendingPanel();
  batteryHistory?.close();batteryPanel.close();settingsPanel.closeEditor();liveChannel.setMode(3);await detailPage?.close();
  if(navigation!==usageNavigation)return;
  $('appShell').hidden=true;
  const pageState=usageState({...state,day});
  homeUsage.prioritize(pageState.day);
  if(push===true)history.pushState(pageState,'',pageUrl(state.page));else if(push===false)history.replaceState(pageState,'',pageUrl(state.page));
  document.title=state.page==='usage-app-week'?'应用周使用时间 · ACol 电池':isApp?'应用使用时间 · ACol 电池':state.page==='usage-month'?'月使用时间 · ACol 电池':state.page==='usage-week'?'周使用时间 · ACol 电池':'使用时间 · ACol 电池';
  if(!usageDetails){
    showPendingPanel(usageRoots[state.page]);
    try{await loadUsageDetails();}catch(error){if(navigation===usageNavigation&&pendingPanel)pendingPanel.status.textContent=error.message;return;}
    if(navigation!==usageNavigation)return;
    closePendingPanel(false);
  }
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
  detailPage?.syncHistory();recordsView.syncHistory();
  const page=state?.page??'home';
  if(page==='cutoff'){if(cutoffFrame.hidden)showCutoff(state);else if(cutoffReady)cutoffFrame.contentWindow.__acoCutoffSync(state);return;}
  if(!cutoffFrame.hidden){hideCutoff();$('appShell').hidden=false;}
  if(usagePages.has(page)){await openUsage(state);return;}
  if(page==='battery-history'||page==='battery-history-detail'||page==='battery-history-trend'){await openBatteryHistory(state);return;}
  if(page==='battery-info'){if(!batteryPanel.active)openBatteryInfo();batteryPanel.handleHistory(state);return;}
  if(page==='record'){settingsPanel.closeEditor();if(detailPage?.id!==state.id)openRecord(state.id,recordsView.find(state.id));return;}
  if(page==='settings'){if(activeTab!=='settings')await activateTab('settings');settingsPanel.handleHistory(state);return;}
  if(activeTab==='settings'&&!await settingsPanel.flush()){
    history.pushState({page:'settings'},'',pageUrl('settings'));$('appShell').hidden=false;showTab('settings');toast('设置未能保存，请重试。');return;
  }
  await activateTab(page==='records'?'records':'home');
}
$('back').onclick=()=>window.__acoBack();
window.addEventListener('popstate',event=>applyHistory(event.state));
document.addEventListener('visibilitychange',()=>{clearTimeout(pollTimer);if(document.hidden){detailPage?.pause();settingsPanel.flush();liveChannel.close();}else{refresh();detailPage?.resume();}});
window.addEventListener('pagehide',()=>{clearTimeout(pollTimer);detailPage?.pause();settingsPanel.flush();liveChannel.close();});
window.addEventListener('pageshow',e=>{if(e.persisted){refresh();detailPage?.resume();}});
recordsView.bind();
themeUiReady=true;
window.__acoPageReady=true;
if(startTab!=='home'){
  history.pushState({page:startTab},'',pageUrl(startTab));
  activateTab(startTab).catch(()=>{});
}
refresh();
