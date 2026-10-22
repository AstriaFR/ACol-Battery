import{exec,fullScreen,cancelTopDialog}from'./root-bridge.js';
import{LiveChannel}from'./live-channel.js?v=acol-1.3.3';
import{Chart,seriesFor,valid,duration,setChartSettings}from'./chart.js?v=acol-1.3.3';
import{DEFAULT_SETTINGS}from'./config.js?v=acol-1.3.3';
import{SettingsPanel}from'./settings-panel.js?v=acol-1.3.3';
import{BatteryInfoPanel}from'./battery-info.js?v=acol-1.3.3';
import{PerformancePanel}from'./performance-panel.js?v=acol-1.3.3-gamepad-temp';
import{remainingEstimate,remainingTime}from'./estimate.js?v=acol-1.3.3';
import{loadSelection,saveSelection,loadHomePriority}from'./preferences.js?v=acol-1.3.3';
import{capacityChange,recordChanges}from'./record-display.js?v=acol-1.3.3';
import{ThemeController}from'./theme.js?v=acol-1.3.3';
const $=id=>document.getElementById(id),command='/data/adb/aco_battery_app/runtime/bin/curve-client';
for(const type of['selectstart','copy','cut','contextmenu','dragstart'])document.addEventListener(type,e=>e.preventDefault(),{capture:true});
// Cancel native press defaults without blocking touch-action scrolling.
document.addEventListener('pointerdown',e=>{
  if(e.pointerType==='touch'&&!e.target.closest('input[type=number],textarea,[contenteditable=true]'))e.preventDefault();
},{capture:true,passive:false});
function longPressFeedback(){try{navigator.vibrate?.(10);}catch{}}
document.addEventListener('selectionchange',()=>{const selection=getSelection();if(selection?.rangeCount)selection.removeAllRanges();});
document.addEventListener('keydown',e=>{if((e.ctrlKey||e.metaKey)&&['a','c','x'].includes(e.key.toLowerCase()))e.preventDefault();});
const startInSettings=location.hash==='#settings';
history.replaceState({page:'home'},'',location.pathname+location.search);
const liveChannel=new LiveChannel();
let records=[],kind='all',detail=null,activeId=null,total=0,loading=false,request=0,expanded=false,zoomTimer;
let lastStatus=null,pollTimer,liveBusy=false,detailBusy=0,listDue=true,lastListAt=0,queuedPoll=false;
let listSelection=0;
let followLive=false,followSpan=null,streamId=null,streamAfter=0,streamPoints=[];
let fullscreenAnchor=null;
let revealedRecordId=null,deleteTarget=null;
let reverseCharge=false;
const portrait=matchMedia('(orientation: portrait)');
let remainingTimer,remainingGeneration=0,remainingRecordId=null;
let uiSettings={...DEFAULT_SETTINGS};
let homePriority=loadHomePriority();
let gameFrequencies=null;
function remainingEligible(){return !document.hidden&&!$('confirm').open&&detail?.record.kind==='discharge'&&activeId!==null;}
function clearRemaining(){
  if($('remainingWh'))$('remainingWh').textContent='—';if($('remainingTime'))$('remainingTime').textContent='—';
}
function stopRemaining(){clearTimeout(remainingTimer);remainingTimer=null;remainingRecordId=null;++remainingGeneration;clearRemaining();}
function startRemaining(){
  if(!remainingEligible()||remainingRecordId===activeId)return;
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
  const flow=detail?.record.kind??(kind==='all'?(lastStatus?.state===0?'discharge':'charge'):kind);
  document.body.dataset.flow=flow;
}
const recordCells=r=>[0,1,2].includes(r?.cell_count)?r.cell_count:2;
const recordFast=r=>Number(r?.fast_seconds)>0;
const fmt=(v,d=0)=>valid(v)?v.toLocaleString('zh-CN',{maximumFractionDigits:d}):'—';
function appendGameMarker(node,r){
  if(recordFast(r)){
    const marker=el('span',undefined,'fast-record-marker');marker.setAttribute('aria-label','游戏模式记录');
    const ns='http://www.w3.org/2000/svg',svg=document.createElementNS(ns,'svg'),body=document.createElementNS(ns,'path'),dpad=document.createElementNS(ns,'path'),center=document.createElementNS(ns,'path');
    svg.setAttribute('viewBox','0 0 20 16');svg.setAttribute('aria-hidden','true');
    body.setAttribute('d','M5.3 2.4h9.4c2 0 3.7 1.4 4.1 3.4l.8 4.3c.3 1.9-1.8 3-3.2 1.8l-2.5-2h-7l-2.5 2c-1.4 1.2-3.5.1-3.2-1.8L2 5.8c.4-2 2.1-3.4 3.3-3.4Z');
    body.setAttribute('fill','none');body.setAttribute('stroke','currentColor');body.setAttribute('stroke-width','1.5');body.setAttribute('stroke-linejoin','round');
    dpad.setAttribute('d','M5.4 4.7v4M3.4 6.7h4');dpad.setAttribute('fill','none');dpad.setAttribute('stroke','currentColor');dpad.setAttribute('stroke-width','1.35');dpad.setAttribute('stroke-linecap','round');
    center.setAttribute('d','M9.1 4.1h1.8M9.1 5h1.8');center.setAttribute('fill','none');center.setAttribute('stroke','currentColor');center.setAttribute('stroke-width','.65');center.setAttribute('stroke-linecap','round');center.setAttribute('opacity','.75');
    const dot=(cx,cy)=>{const circle=document.createElementNS(ns,'circle');circle.setAttribute('cx',cx);circle.setAttribute('cy',cy);circle.setAttribute('r','.72');circle.setAttribute('fill','currentColor');return circle;};
    svg.append(body,dpad,center,dot(13.1,5.5),dot(15.2,7.1),dot(13.1,8.7),dot(11,7.1));marker.append(svg);node.append(marker);
  }
}
function updateRecordTitle(title,r){
  const change=recordChanges(homePriority,r).secondary,delta=el('span',change.text,'record-pct-change');
  delta.dataset.flow=change.flow;
  appendGameMarker(delta,r);
  title.replaceChildren(el('span',`${r.kind==='charge'?'充电':'放电'} · ${fmt(r.pct_start)}% → ${fmt(r.pct_end)}%`),delta);
}
function updateRecordDelta(delta,r){
  const change=recordChanges(homePriority,r).primary,value=delta.querySelector('.delta-value');
  value.textContent=change.text;value.dataset.flow=change.flow;
  delta.querySelector('small').textContent=change.label;
}
const stamp=t=>new Date(t).toLocaleString('zh-CN',{month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',hour12:false});
const reasons={recording:'记录中',full_tail_confirmed:'尾充完成',plugged:'接入充电',unplugged:'拔出充电',reboot:'重启中断',collector_restart:'服务中断',framework_restarted:'系统服务重启',module_stopped:'模块停止',recording_stopped:'记录已停止',app_uninstalled:'卸载前结束',service_updated:'后台更新',fast_started:'切换游戏模式',fast_stopped:'游戏记录结束',fast_idle_timeout:'息屏自动结束'};
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
  if(args.some(a=>!/^\d+$/.test(String(a))))throw Error('参数无效');
  if(action==='status')return liveChannel.status();
  if(action==='live'){const packet=await liveChannel.live(args);if(!args[0]||'record'in packet)return packet;}
  const result=await exec(`${command} ${action} ${args.join(' ')}`);
  if(Number(result.errno)!==0)throw Error(result.stderr?.trim()||'操作未能完成，请重试。');
  const data=JSON.parse(result.stdout.trim());if(!data.ok){const error=Error(data.error||'未能取得电池数据，请重新初始化。');throw error;}return data;
}
const chart=new Chart($('plot'),$('tooltip'),()=>scheduleZoom());
chart.onInteract=()=>pauseFollowing();
const settingsPanel=new SettingsPanel(api,value=>{applySettings(value);refresh();},value=>{homePriority=value;renderRecords();});
const batteryPanel=new BatteryInfoPanel();
const performancePanel=new PerformancePanel(api);
function updateThemeControl({step,dark}){
  const button=$('themeToggle');if(!button)return;
  button.dataset.mode=step;button.setAttribute('aria-label',step==='auto'?'主题：跟随系统':'主题：手动'+(dark?'暗色':'亮色'));
  button.title=button.getAttribute('aria-label');
}
let themeUiReady=false;
const themeController=new ThemeController(state=>{
  updateThemeControl(state);
  if(!themeUiReady)return;
  try{
    if(activeId!==null&&detail)buildLegend(detail.record.kind);
    chart.draw();performancePanel.refreshTheme();
  }catch(error){console.error('Theme redraw failed',error);}
});
$('themeToggle').onclick=()=>themeController.cycle();
window.__acoBack=()=>{
  if(cancelTopDialog())return'handled';
  if(performancePanel.expanded){performancePanel.fullscreen(false);return'handled';}
  if(expanded){toggleFullscreen(false);return'handled';}
  if(history.state?.page&&history.state.page!=='home'){history.back();return'handled';}
  // Recover a visible child page even if its browser history state was lost.
  if(!$('settingsPanel').hidden||batteryPanel.active||!$('detail').hidden){showHome();return'handled';}
  return document.querySelector('main').hidden?'wait':'home';
};
function applySettings(value){
  const next={...DEFAULT_SETTINGS,...value};
  if(JSON.stringify(next)===JSON.stringify(uiSettings))return;
  uiSettings=next;setChartSettings(next);
  $('showSmallLabel').textContent=`显示 ≤${next.hidePct}%`;
  $('homeNote').textContent=`普通记录 ≤${next.deletePct}% 自动删除；≤${next.hidePct}% 默认隐藏。`;
  if(detail){chart.draw();renderChartInfo();}
}
async function openSettings(){
  performancePanel.close();
  batteryPanel.close();liveChannel.setMode(0);
  ++request;clearTimeout(zoomTimer);activeId=null;detail=null;streamId=null;streamPoints=[];streamAfter=0;followLive=false;
  stopRemaining();$('detail').hidden=true;document.querySelector('main').hidden=true;$('settingsPanel').hidden=false;
  if(history.state?.page!=='settings')history.pushState({page:'settings'},'',`${location.pathname}${location.search}#settings`);
  await settingsPanel.open(lastStatus||liveChannel.latest?uiSettings:null);
}
$('openSettings').onclick=openSettings;
$('settingsBack').onclick=()=>history.back();
$('openCutoff').onclick=async e=>{e.preventDefault();const button=e.currentTarget;if(button.getAttribute('aria-disabled')==='true'){toast('当前设备缺少截止电压调整接口。');return;}const url=button.href;if(await settingsPanel.flush())location.assign(url);};
async function openBatteryInfo(){
  performancePanel.close();
  stopRemaining();settingsPanel.closeEditor();if(expanded)await toggleFullscreen(false);
  ++request;clearTimeout(zoomTimer);activeId=null;detail=null;streamId=null;streamPoints=[];streamAfter=0;followLive=false;
  $('detail').hidden=true;$('settingsPanel').hidden=true;document.querySelector('main').hidden=true;
  batteryPanel.open();liveChannel.setMode(1);
  if(history.state?.page!=='battery-info')history.pushState({page:'battery-info'},'',`${location.pathname}${location.search}#battery-info`);
  refresh();
}
$('openBatteryInfo').onclick=openBatteryInfo;
$('batteryInfoBack').onclick=()=>history.back();
function syncReverseCharge(){
  const available=detail?.record.kind==='charge'&&portrait.matches&&!expanded;
  const enabled=available&&reverseCharge;
  $('reverseCharge').hidden=!available;
  $('reverseCharge').classList.toggle('selected',reverseCharge);
  $('reverseCharge').setAttribute('aria-pressed',String(reverseCharge));
  $('chartPanel').classList.toggle('reverse-charge',enabled);
  chart.reverseCharge=enabled;
  chart.draw();
}
portrait.addEventListener('change',syncReverseCharge);
$('reverseCharge').onclick=()=>{reverseCharge=!reverseCharge;chart.clearInspect();syncReverseCharge();};
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
  chart.set(data.points,Math.max(1,data.record.end_elapsed-data.record.start_elapsed),view,{kind:data.record.kind,cellCount:recordCells(data.record),fast:recordFast(data.record),recordId:data.record.id,ranges:data.ranges??{},live});renderChartInfo();renderFollow();
}
function renderChartInfo(){
  if(!detail)return;const r=detail.record,raw=r.kind==='charge';
  const plan=raw?(r.active?`每 ${uiSettings.chargeSeconds} 秒记录`:'充电原始采样'):recordFast(r)?`游戏模式${r.active?' · 功耗每 '+uiSettings.fastSeconds+' 秒':''} · 实时功耗`:r.active?`亮屏 ${uiSettings.screenOnMinutes} 分钟 / 息屏 ${uiSettings.screenOffMinutes} 分钟`:'按屏幕状态记录';
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
  clearTimeout(pollTimer);if(document.hidden||$('confirm').open)return;
  if(liveBusy){queuedPoll=true;return;}pollTimer=setTimeout(pollLive,delay);
}
function refresh(){listDue=true;schedulePoll(0);}
async function pollLive(){
  if(document.hidden||$('confirm').open)return;
  if(liveBusy||detailBusy||loading){schedulePoll(200);return;}
  liveBusy=true;queuedPoll=false;const started=performance.now(),token=request,infoMode=batteryPanel.active;
  const id=detail?.record.active&&streamId===activeId?activeId:0,after=streamAfter,small=$('showSmall').checked?1:0;
  const wantList=listDue||Date.now()-lastListAt>=15000;let failed=false;
  try{
    let packet=await api('live',id,after,wantList?1:0,small,(lastStatus?.session??-1)+1);
    if(infoMode!==batteryPanel.active)return;
    if(infoMode){batteryPanel.update(packet);return;}
    const initial=packet,points=[...(packet.points??[])];
    while(id&&valid(packet.nextAfter)&&token===request&&id===activeId){
      packet=await api('live',id,packet.nextAfter,0,small,packet.status.session+1);points.push(...(packet.points??[]));
    }
    packet={...initial,...packet,points,records:initial.records,total:initial.total};
    lastStatus=packet.status;renderStatus(lastStatus);
    if(Array.isArray(packet.records)&&small===Number($('showSmall').checked)){records=packet.records;total=packet.total;listDue=false;lastListAt=Date.now();renderRecords();}
    if(packet.activeRecord)updateActiveRecord(packet.activeRecord);
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
      }
    }
    if(token===request&&recordFast(detail?.record))renderGameFrequencies(packet.frequencies??null);
  }catch(e){if(infoMode!==batteryPanel.active)return;failed=true;gameFrequencies=null;renderGameFrequencies(null);if(infoMode)batteryPanel.error(e.message);else{$('warning').textContent=e.message;if(activeId!==null){$('chartInfo').textContent='正在重连…';if(detail)renderSummary(detail.record,true);}}}
  finally{
    liveBusy=false;const period=failed||!batteryPanel.active&&lastStatus?.interactive===false?5000:1000;
    schedulePoll(queuedPoll?0:Math.max(100,period-(performance.now()-started)));
  }
}
function renderStatus(s){
  applySettings(s.settings);settingsPanel.updateRecording(s.recordingEnabled);settingsPanel.updateFast(s.fastEnabled);$('openCutoff').setAttribute('aria-disabled',String(s.cutoffAvailable===false));
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
  const warnings=[];if(s.recordingEnabled===false)warnings.push('记录已停止。');if(!(s.capabilities&1))warnings.push('休眠采样受限。');if(!(s.capabilities&2))warnings.push('电源检测可能延迟。');if(!(s.capabilities&4))warnings.push('唤醒锁不可用。');
  if(s.frameworkHealthy===false)warnings.push('系统服务已断开，后台正在自动恢复。');
  if(s.error)warnings.push('采样异常。');if(r.error)warnings.push('电池数据读取失败。');if(s.usageError&&s.state===0&&s.recordingEnabled!==false)warnings.push('应用统计暂不可用。');
  if(s.screenError)warnings.push('屏幕检测受限，放电每 2 分钟记录。');
  $('warning').textContent=warnings.join('\n');
}
function renderRecords(){
  const list=$('records'),shown=records.filter(r=>kind==='all'||r.kind===kind);
  const existing=new Map([...list.children].filter(row=>row.dataset.recordId).map(row=>[Number(row.dataset.recordId),row]));
  const rows=shown.map(r=>{const row=existing.get(r.id)??createRecordRow(r);updateRecordRow(row,r);return row;});
  if(!rows.length){
    const empty=list.querySelector('.empty')??el('div',undefined,'empty'),text=`暂无记录，可勾选“显示 ≤${uiSettings.hidePct}%”。`;
    if(empty.textContent!==text)empty.textContent=text;rows.push(empty);
  }
  let cursor=list.firstChild;
  for(const row of rows){if(row===cursor)cursor=cursor.nextSibling;else list.insertBefore(row,cursor);}
  while(cursor){const next=cursor.nextSibling;cursor.remove();cursor=next;}
  $('more').hidden=records.length>=total;
}
function updateRecordRow(row,r){
  row.dataset.active=r.active?'1':'0';
  const content=[homePriority,r.kind,r.pct_start,r.pct_end,r.start_wall,r.start_elapsed,r.end_elapsed,r.reason,r.rm_start,r.rm_end,r.fast_seconds].join('|');
  if(row.dataset.content!==content){
    updateRecordTitle(row.querySelector('.info strong'),r);
    row.querySelector('.info small').textContent=`${stamp(r.start_wall)} · ${duration(r.end_elapsed-r.start_elapsed)} · ${reasons[r.reason]??r.reason}`;
    updateRecordDelta(row.querySelector('.delta'),r);row.dataset.content=content;
  }
  const remove=row.querySelector('.record-delete');remove.hidden=revealedRecordId!==r.id;
  remove.parentElement.classList.toggle('delete-revealed',!remove.hidden);
}
function createRecordRow(r){
    const row=el('div',undefined,'record-row record '+r.kind),b=el('button',undefined,'record-open');
    row.dataset.recordId=r.id;b.type='button';b.append(el('span',r.kind==='charge'?'↗':'↘','icon'));
    const info=el('span',undefined,'info');
    const title=el('strong');updateRecordTitle(title,r);info.append(title);
    info.append(el('small',`${stamp(r.start_wall)} · ${duration(r.end_elapsed-r.start_elapsed)} · ${reasons[r.reason]??r.reason}`));b.append(info);
    const action=el('span',undefined,'record-action'),delta=el('span',undefined,'delta');
    delta.append(el('span',undefined,'delta-value'),el('small'));updateRecordDelta(delta,r);
    const remove=el('button','删除','record-delete');remove.type='button';remove.dataset.deleteId=r.id;
    remove.hidden=revealedRecordId!==r.id;action.classList.toggle('delete-revealed',!remove.hidden);
    remove.onclick=e=>{e.stopPropagation();confirmRecordDelete(records.find(item=>item.id===r.id)??r);};
    action.append(delta,remove);row.append(b,action);
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
async function openDetail(id){
  performancePanel.close();
  gameFrequencies=null;
  batteryPanel.close();liveChannel.setMode(0);
  stopRemaining();
  const token=++request;activeId=id;detail=null;streamId=null;followLive=false;reverseCharge=false;syncReverseCharge();detailBusy++;
  try{const data=await readDetail(id,0,9007199254740991,1600,()=>token!==request||id!==activeId);if(data)showDetail(data);}
  catch(e){if(token===request){activeId=null;toast(e.message);}}
  finally{detailBusy--;schedulePoll(0);}
}
function showDetail(data,preserve=false,live=false){
  detail=data;const r=data.record;if($('detailScroll').dataset.recordId!==String(r.id)){$('detailScroll').dataset.recordId=r.id;$('detailScroll').scrollTop=0;}$('detail').hidden=false;document.querySelector('main').hidden=true;
  liveChannel.setMode(recordFast(r)?2:0);
  performancePanel.open(r);
  syncTheme();
  $('detailType').textContent=r.kind==='charge'?'充电记录':recordFast(r)?'游戏模式记录':'放电记录';$('detailTitle').textContent=stamp(r.start_wall);$('chartTitle').textContent=r.kind==='charge'?'充电曲线':'放电曲线';
  renderSummary(r);
  syncRemainingExplanation();
  if(!preserve){streamId=r.id;streamPoints=[...data.points];streamAfter=streamPoints.at(-1)?.[0]??0;followLive=!!r.active;followSpan=null;chart.axisKey=null;chart.enabled=loadSelection(recordFast(r)?'game':r.kind,seriesFor(r.kind,recordCells(r),recordFast(r)).map(s=>s.key));buildLegend(r.kind);}
  const end=Math.max(1,r.end_elapsed-r.start_elapsed),view=live&&followLive?followWindow(end):preserve?chart.window:[0,end];applyChart(data,view,live);
  syncReverseCharge();
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
  const summary=$('summary'),active=!!r.active,live=active&&!failed&&lastStatus?.session===r.id;
  const reading=live?lastStatus.reading??{}:{},flow=valid(reading.power)&&reading.power!==0?(reading.power>0?'charge':'discharge'):r.kind;
  const cells=live?(reading.cellCount??recordCells(r)):recordCells(r);
  const displayCurrent=valid(reading.displayCurrent)?reading.displayCurrent:valid(reading.current)?reading.current*(cells===2?2:1):null;
  const separateCells=cells===2&&(!live||reading.voltageSource!=='android');
  const signed=(v,d=2)=>valid(v)?`${v<0?'−':v>0?'+':''}${Math.abs(v).toFixed(d)}`:'—';
  const number=(v,d)=>valid(v)?v.toFixed(d):'—';
  const elapsed=live&&valid(reading.elapsed)?Math.max(r.end_elapsed,reading.elapsed):r.end_elapsed;
  const change=live&&valid(reading.rm)?capacityChange({...r,rm_end:reading.rm}):capacityChange(r);
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
    ['change',change,'容量变化','',r.kind],
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
  renderDrainSummary(data);
  $('apps').hidden=r.kind==='charge';const appRows=$('appRows');appRows.replaceChildren();
  if(r.kind==='charge')return;
  const integrated=r.stats_basis==='current_integral',metric=integrated?'energyMwh':'mah';
  $('appStatsNote').textContent=integrated?'能量按电压、电流积分，按前台时长分摊；mAh 按电流积分。≤2 分钟归入其他。':r.stats_basis==='rm_minute'?'按 RM 容量变化和使用时段分摊，每分钟及亮灭屏时结算，结束时补结算。≤2 分钟归入其他。':'按使用时段分摊整机耗电，≤2 分钟归入其他。平均功耗仅统计有效数据。';
  const apps=[...data.apps].sort((a,b)=>b[metric]-a[metric]),sum=apps.reduce((a,b)=>a+b[metric],0);
  if(!apps.length)appRows.append(el('p','暂无统计。','small'));
  for(const a of apps){const row=el('div',undefined,'appRow'),line=el('div',undefined,'appLine');line.append(el('span',a.label),el('strong',statValue(a,integrated)));
    row.append(line,el('span',statDescription(a,integrated,sum)+(a.pkg.startsWith('__')?'':' · '+a.pkg),'appMeta'));
    const track=el('div',undefined,'appTrack'),bar=el('div');bar.style.width=`${sum?a[metric]/sum*100:0}%`;track.append(bar);row.append(track);appRows.append(row);}
}
function statValue(a,integrated){
  if(!integrated)return fmt(a.mah,2);
  if(!a.energyMs)return'—';
  return a.energyMwh>=1000?`${fmt(a.energyMwh/1000,2)} Wh`:`${fmt(a.energyMwh,2)} mWh`;
}
function statDescription(a,integrated,sum){
  const metric=integrated?'energyMwh':'mah',watts=valid(a.avgPower)?a.avgPower.toFixed(2):'—';
  return `${duration(a.ms)} · 平均 ${watts} W · ${sum?fmt(a[metric]/sum*100,1):0}%`+
    (integrated?` · ${a.mahMs?fmt(a.mah,2):'—'} mAh`:'')+
    (valid(a.energyMs)&&a.energyMs<a.ms?' · 功耗覆盖 '+duration(a.energyMs):'')+
    (integrated&&valid(a.mahMs)&&a.mahMs<a.ms?' · 电流覆盖 '+duration(a.mahMs):'');
}
function renderDrainSummary(data){
  $('drainSummary').hidden=data.record.kind==='charge';if(data.record.kind==='charge')return;
  const rows=$('drainRows');rows.replaceChildren();
  const integrated=data.record.stats_basis==='current_integral',metric=integrated?'energyMwh':'mah';
  $('drainStatsNote').textContent=integrated?'能量与 mAh 分别由功率、电流积分，占比按能量计算。上方容量变化为 RM 差值，可能不同。':data.record.stats_basis==='rm_minute'?'按 RM 容量变化及电压计算，每分钟及亮灭屏时结算，结束时补结算。':'按已记录的应用时段汇总。';
  const stats=data.drainSummary??[],sum=stats.reduce((total,a)=>total+a[metric],0);
  for(const [i,label]of['亮屏耗电','息屏耗电'].entries()){
    const a=stats[i]??{mah:0,ms:0,energyMwh:0,energyMs:0,mahMs:0,avgPower:null};
    const row=el('div',undefined,'drain-row'),head=el('div',undefined,'appLine');
    head.append(el('span',label),el('strong',statValue(a,integrated)));
    row.append(head,el('span',statDescription(a,integrated,sum),'appMeta'));
    const track=el('div',undefined,'appTrack'),bar=el('div');bar.style.width=`${sum?a[metric]/sum*100:0}%`;track.append(bar);row.append(track);rows.append(row);
  }
}
function renderDetailNote(data){
  const r=data.record;
  $('detailNote').textContent=`${fmt(data.rawCount??data.points.length)} 个采样点 · 缺样 ${r.missed??0} · 异常 ${r.anomalies??0}`;
}
function buildLegend(kind){
  const cells=recordCells(detail?.record);
  const fast=recordFast(detail?.record);
  const remember=()=>{if(!saveSelection(fast?'game':kind,chart.enabled,seriesFor(kind,cells,fast).map(s=>s.key)))toast('勾选设置未能保存。');};
  $('legend').replaceChildren();$('legend').dataset.kind=kind;
  for(const s of seriesFor(kind,cells,fast)){
    const label=el('label');label.dataset.series=s.key;label.style.setProperty('--series',s.color);
    const input=el('input');input.type='checkbox';input.checked=chart.enabled.has(s.key);input.setAttribute('aria-label',s.name);
    input.onchange=()=>{
      input.checked?chart.enabled.add(s.key):chart.enabled.delete(s.key);
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
        chart.clearInspect();chart.draw();renderChartInfo();
      },500);
    });
    label.addEventListener('pointermove',e=>{if(start&&Math.hypot(e.clientX-start[0],e.clientY-start[1])>8){dragged=true;clearTimeout(timer);}});
    const end=()=>{clearTimeout(timer);start=null;if(held||dragged)setTimeout(()=>{held=dragged=false;},400);};
    for(const event of['pointerup','pointercancel','pointerleave'])label.addEventListener(event,end);
    label.addEventListener('click',e=>{if(held||dragged){e.preventDefault();e.stopImmediatePropagation();}},{capture:true});
    label.append(input,el('span',s.name,'legend-name'),el('span',s.axis,'legend-short'),el('span','','legend-scale'));$('legend').append(label);
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
  syncReverseCharge();
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
async function showHome(){performancePanel.close();batteryPanel.close();liveChannel.setMode(0);settingsPanel.closeEditor();if(!$('settingsPanel').hidden&&!await settingsPanel.flush()){history.pushState({page:'settings'},'',`${location.pathname}${location.search}#settings`);return;}$('settingsPanel').hidden=true;stopRemaining();if(expanded)await toggleFullscreen(false);activeId=null;detail=null;streamId=null;streamPoints=[];streamAfter=0;followLive=false;++request;clearTimeout(zoomTimer);$('detail').hidden=true;document.querySelector('main').hidden=false;syncTheme();refresh();}
$('back').onclick=()=>{if(history.state?.page==='record')history.back();else showHome();};
window.addEventListener('popstate',e=>{syncRemainingExplanation();syncDeleteConfirmation();if(e.state?.page==='battery-info'){if(!batteryPanel.active)openBatteryInfo();batteryPanel.handleHistory(e.state);return;}if(e.state?.page==='settings'){if($('settingsPanel').hidden)openSettings();else settingsPanel.handleHistory(e.state);}else{settingsPanel.closeEditor();if(e.state?.page==='record'){if(activeId!==e.state.id)openDetail(e.state.id);}else showHome();}});
$('delete').onclick=()=>confirmRecordDelete(detail?.record);
$('cancelDelete').onclick=dismissRecordDelete;
$('confirm').addEventListener('cancel',e=>{e.preventDefault();dismissRecordDelete();});
$('confirmDelete').onclick=async()=>{
  const target=deleteTarget;if(!target)return;
  $('confirmDelete').disabled=true;
  try{
    const result=await api('delete',target.id);liveChannel.forgetRecord(target,result.listVersion??0);
    records=records.filter(r=>r.id!==target.id);total=Math.max(0,total-1);renderRecords();
    $('confirm').close();revealRecordDelete(null);
    if(history.state?.deleteRecord===target.id)history.go(activeId===target.id?-2:-1);
    else if(activeId===target.id)$('back').click();
    refresh();
    toast('记录已删除');
  }catch(e){toast(e.message);}finally{$('confirmDelete').disabled=false;}
};
$('showSmall').onchange=()=>{
  ++listSelection;revealRecordDelete(null);
  // Both snapshots arrive together; filtering need not wait for the next tick.
  const cached=liveChannel.cachedList($('showSmall').checked);
  if(cached){records=cached.records;total=cached.total;renderRecords();}
  refresh();
};
for(const b of document.querySelectorAll('[data-kind]'))b.onclick=()=>{kind=b.dataset.kind;for(const e of document.querySelectorAll('[data-kind]'))e.classList.toggle('selected',e===b);syncTheme();renderRecords();};
$('more').onclick=async()=>{if(loading)return;loading=true;const selection=listSelection,small=$('showSmall').checked?1:0;try{const data=await api('list',small,records.length);if(selection!==listSelection)return;const list=liveChannel.appendList(small,data);records=list.records;total=list.total;renderRecords();}catch(e){if(selection===listSelection)toast(e.message);}finally{loading=false;}};
document.addEventListener('visibilitychange',()=>{clearTimeout(pollTimer);stopRemaining();if(document.hidden){gameFrequencies=null;renderGameFrequencies(null);settingsPanel.flush();liveChannel.close();}else{refresh();startRemaining();}});
window.addEventListener('pagehide',()=>{clearTimeout(pollTimer);stopRemaining();settingsPanel.flush();liveChannel.close();});
window.addEventListener('pageshow',e=>{if(e.persisted){refresh();startRemaining();}});
document.addEventListener('pointerup',()=>{if(followLive&&detail?.record.active&&!chart.pointers.size)showDetail({...detail,points:streamPoints},true,true);});
$('confirm').addEventListener('close',()=>{deleteTarget=null;schedulePoll(0);startRemaining();});
document.addEventListener('keydown',e=>{if(e.key==='Escape'&&expanded)toggleFullscreen(false);});
themeUiReady=true;
if(startInSettings)openSettings();
refresh();
