import{Chart,seriesFor,valid,duration,chartRangeGrowth}from'./chart.js?v=acol-2.2s';
import{IntervalAppDetails}from'./interval-app-details.js?v=acol-2.2s';
import{ScreenOffOverlay}from'./screen-off-overlay.js?v=acol-2.2s';
import{PerformancePanel}from'./performance-panel.js?v=acol-2.2s-gamepad-temp';
import{DEFAULT_SETTINGS}from'./config.js?v=acol-2.2s';
import{remainingEstimate,remainingTime,canEstimateRemaining}from'./estimate.js?v=acol-2.2s';
import{loadSelection,saveSelection}from'./preferences.js?v=acol-2.2s';
import{capacityChange,capacityFlow,recordCells,recordFast,recordIsLive,renderRecordHeading,fmt,pad2,reasons,el,longPressFeedback}from'./record-display.js?v=acol-2.2s';
import{createRecordDrain}from'./record-usage.js?v=acol-2.2s';
import{bindExport,recordCsv}from'./chart-export.js?v=acol-2.2s';
import{DETAIL_WATCH_MODE,SUMMARY_WATCH_MODE}from'./live-channel.js?v=acol-2.2s';
import{ChargeIntegralCache}from'./charge-integral.js';
const $=id=>document.getElementById(id);
const fullRecordEnd=Number.MAX_SAFE_INTEGER,previewBudget=1200;
const dischargeRebase=128;

export async function readRecordDetail(api,id,from,to,budget,cancelled=()=>false,preview=false){
  const action=preview?'detail_preview':'detail';
  let data=await api(action,id,from,to,budget);if(cancelled())return null;
  const points=[...data.points],end=Math.min(to,data.record.end_elapsed-data.record.start_elapsed);
  while(valid(data.nextAfter)){
    const page=await api('detail',id,from,end,budget,data.nextAfter);if(cancelled())return null;
    points.push(...page.points);data={...data,nextAfter:page.nextAfter};
  }
  return{...data,points};
}
/** Owns the opened record, chart lifecycle, live cursor and presentation. */
export function createRecordDetail(options){
  const{api,liveChannel,toast,fullScreen,readAppIcons,beforeOpen,onShow,onRefresh,onSettings}=options;
  let detail=null,activeId=null,request=0,detailBusy=0,zoomTimer,expanded=false,fullscreenAnchor=null;
  let followLive=false,followSpan=null,streamId=null,streamAfter=0,streamPoints=[],streamPreview=false,reversePlot=false;
  let followRedrawPending=false;
  let lastStatus=null,uiSettings={...DEFAULT_SETTINGS},bound=false;
  const chargeCache=new ChargeIntegralCache();
  let chargeEntry=null;
  const chart=new Chart($('plot'),$('tooltip'),()=>scheduleZoom());
  const intervalAppDetails=new IntervalAppDetails(chart,api,()=>detail&&!detail.loading?detail.record:null);
  const screenOffOverlay=new ScreenOffOverlay(chart,api,()=>!document.hidden&&!$('detail').hidden,message=>toast(message));
  let performancePanel;
  const drain=createRecordDrain(api,{getData:()=>detail,readAppIcons,onSettled:result=>{
    if(!detail)return;
    liveChannel.settle(detail.record,result);
    detail={...detail,apps:result.apps,drainSummary:result.drainSummary,statsVersion:result.statsVersion,statsEpoch:result.statsEpoch,usagePending:result.usagePending,record:{...detail.record,app_revision:result.appRevision??detail.record.app_revision}};
    renderApps(detail);intervalAppDetails.preload(detail.record,detail.statsVersion);screenOffOverlay.update(detail.record,detail.statsVersion);
  }});
  chart.onInteract=()=>pauseFollowing();
  const readDetail=(...args)=>readRecordDetail(api,...args);
  const schedulePoll=()=>onRefresh();
  const renderApps=data=>drain.render(data,chart.energyScaleWh()),hideAppEnergyTip=()=>drain.hideTip();
  function hasRemainingMetrics(){return !!detail?.record.active&&detail.record.kind==='discharge';}
  function clearRemaining(){
    if($('remainingWh'))$('remainingWh').textContent='—';if($('remainingTime'))$('remainingTime').textContent='—';
  }
  function remainingInfoButton(){
    const button=el('button','i','remaining-info');button.type='button';
    button.setAttribute('aria-label','查看剩余能量和亮屏时长的估算说明');button.setAttribute('aria-haspopup','dialog');
    button.onclick=()=>{
      if(!hasRemainingMetrics())return;
      history.pushState({page:'record',id:activeId,remainingExplanation:true},'',location.href);
      syncRemainingExplanation();
    };
    return button;
  }
  function syncRemainingExplanation(){
    const dialog=$('remainingExplanation'),state=history.state;
    const show=state?.page==='record'&&state.id===activeId&&state.remainingExplanation&&hasRemainingMetrics();
    if(show&&!dialog.open)dialog.showModal();else if(!show&&dialog.open)dialog.close();
  }
  function dismissRemainingExplanation(){
    if(history.state?.remainingExplanation)history.back();else $('remainingExplanation').close();
  }
  function syncReversePlot(redraw=true){
    const available=detail?.record.kind==='charge'||!!(detail?.record&&recordFast(detail.record));
    const enabled=available&&reversePlot;
    $('reversePlot').hidden=!available;
    $('reversePlot').classList.toggle('selected',reversePlot);
    $('reversePlot').setAttribute('aria-pressed',String(reversePlot));
    $('chartPanel').classList.toggle('reverse-plot',enabled);
    chart.reversePlot=enabled;
    if(redraw)chart.draw();
  }
  function pauseFollowing(){followLive=false;renderFollow();}
  function renderFollow(){
    const active=!!detail?.record.active;$('followLatest').hidden=!active;
    $('followLatest').textContent=followLive?'实时跟随':'回到实时';$('followLatest').classList.toggle('selected',followLive);$('followLatest').setAttribute('aria-pressed',String(followLive));
  }
  function followWindow(end){return followSpan===null?[0,end]:[Math.max(0,end-followSpan),end];}
  function scheduleZoom(){if(!detail||detail.loading)return;pauseFollowing();clearTimeout(zoomTimer);zoomTimer=setTimeout(()=>loadWindow(),180);}
  function applyChart(data,view,live=false){
    const record=data.record;
    chart.readRawInspection=data.preview?async(from,to,x)=>(await api('detail_sample',record.id,from,to,x)).sample:null;
    chart.set(data.points,Math.max(1,record.end_elapsed-record.start_elapsed),view,{kind:record.kind,cellCount:recordCells(record),highDesignCapacity:data.highDesignCapacity,fast:recordFast(record),recordId:record.id,startWall:record.start_wall,ranges:data.ranges??{},live,appendOnly:data.points===streamPoints});
    if(!data.loading){intervalAppDetails.preload(record,data.statsVersion);screenOffOverlay.update(record,data.statsVersion);renderApps(data);}
    renderChartInfo();renderFollow();
  }
  function renderChartInfo(){
    if(!detail)return;const r=detail.record,raw=r.kind==='charge';
    const gameSeconds=valid(lastStatus?.gamePowerIntervalMs)?lastStatus.gamePowerIntervalMs/1000:uiSettings.fastSeconds;
    const plan=raw?(r.active?`每 ${uiSettings.chargeSeconds} 秒记录`:'充电原始采样'):recordFast(r)?`游戏性能统计${r.active?' · 功耗每 '+gameSeconds+' 秒':''} · 实时功率`:r.active?`亮屏 ${uiSettings.screenOnMinutes} 分钟 / 息屏 ${uiSettings.screenOffMinutes} 分钟`:'按屏幕状态记录';
    $('chartInfo').textContent=detail.loading?'正在读取曲线…':`${r.active?(followLive?'实时跟随':'已暂停跟随'):'已结束'} · ${plan}${detail.preview?' · 预览':''}`;
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
    try{await loadCurve(id,Math.floor(a),Math.ceil(b),token,data=>{
      if(data.record.active!==detail.record.active){showDetail(data,true);return;}
      detail=data;applyChart(data,[a,b]);
    });}
    catch(e){if(current(token,id))toast(e.message);}
    finally{detailBusy--;schedulePoll(0);}
  }
  async function loadCurve(id,from,to,token,apply){
    const data=await readDetail(id,from,to,previewBudget,()=>!current(token,id),true);
    if(data)apply(data);
  }
  async function close(){
    chargeEntry=null;
    intervalAppDetails.pause();
    screenOffOverlay.close();
    drain.close();
    performancePanel?.close();
    clearRemaining();
    hideAppEnergyTip();
    if(expanded)await toggleFullscreen(false);
    ++request;clearTimeout(zoomTimer);
    activeId=null;detail=null;streamId=null;streamPoints=[];streamAfter=0;streamPreview=false;followLive=false;followRedrawPending=false;chart.clearInspect();
    $('detail').hidden=true;
  }
  async function openDetail(id,record){
    chargeEntry=null;
    intervalAppDetails.pause();
    screenOffOverlay.close();
    beforeOpen();
    drain.close();
    performancePanel?.close();
    clearRemaining();hideAppEnergyTip();
    clearTimeout(zoomTimer);
    const token=++request;activeId=id;detail=null;streamId=null;streamPoints=[];streamAfter=0;streamPreview=false;followLive=false;followRedrawPending=false;reversePlot=false;detailBusy++;
    if(record?.id===id)showDetail({record,points:[],loading:true});
    try{
      await loadCurve(id,0,fullRecordEnd,token,data=>{
        const preserve=detail?.record.id===id;
        streamId=id;streamPoints=[...data.points];streamAfter=streamPoints.at(-1)?.[0]??0;streamPreview=!!data.preview;
        if(preserve&&chart.window[0]===0&&chart.window[1]===chart.total)chart.window=[0,Math.max(1,data.record.end_elapsed-data.record.start_elapsed)];
        showDetail(data,preserve);
        const growth=chartRangeGrowth(chart,uiSettings);
        if(growth.some(Boolean))api('grow_chart_ranges',...growth).then(result=>onSettings?.(result.settings)).catch(()=>{});
        if(data.record.active)liveChannel.seedRecord(id,streamAfter);
      });
    }
    catch(e){
      if(current(token,id)){
        if(!detail){activeId=null;throw e;}
        if(detail.loading)$('chartInfo').textContent='曲线读取失败';
        toast(e.message);
      }
    }
    finally{detailBusy--;schedulePoll(0);}
  }
  function showDetail(data,preserve=false,live=false){
    followRedrawPending=false;
    detail=data;const r=data.record;if($('detailScroll').dataset.recordId!==String(r.id)){$('detailScroll').dataset.recordId=r.id;$('detailScroll').scrollTop=0;}$('detail').hidden=false;$('appShell').hidden=true;
    if(!data.loading)liveChannel.setMode(r.active?DETAIL_WATCH_MODE:SUMMARY_WATCH_MODE);
    if(!data.loading&&recordFast(r)){performancePanel??=new PerformancePanel(api);performancePanel.open(r);}
    if(data.loading)$('apps').hidden=true;
    $('resetZoom').disabled=$('followLatest').disabled=!!data.loading;
    onShow();
    renderRecordHeading(r);
    $('chartTitle').textContent=r.kind==='charge'?'充电曲线':'放电曲线';
    renderSummary(r);
    if(!data.loading)scheduleChargeIntegral(r);
    syncRemainingExplanation();
    if(!preserve){followLive=!!r.active;followSpan=null;chart.axisKey=null;chart.enabled=loadSelection(recordFast(r)?'game':r.kind,legendKeys(r.kind,recordCells(r),recordFast(r)));buildLegend(r.kind);}
    syncReversePlot(false);
    const end=Math.max(1,r.end_elapsed-r.start_elapsed),view=live&&followLive?followWindow(end):preserve?chart.window:[0,end];applyChart(data,view,live);
    renderDetailNote(data);
    if(!preserve&&(history.state?.page!=='record'||history.state.id!==r.id))history.pushState({page:'record',id:r.id},'',`${location.pathname}${location.search}#record-${r.id}`);
  }
  function scheduleChargeIntegral(r){
    if(r.active||r.kind!=='charge'||!valid(r.start_wall)||!valid(r.start_elapsed)||!valid(r.end_elapsed)||r.end_elapsed<r.start_elapsed)return;
    const key=chargeCache.key(r);
    if(chargeEntry?.key===key)return;
    const entry=chargeEntry={key};
    const cancelled=()=>chargeEntry!==entry||activeId!==r.id;
    const load=async()=>{
      try{
        const mah=await chargeCache.read(r,api,cancelled);
        if(cancelled()||mah===undefined)return;
        entry.mah=mah;
        renderChargeIntegral(detail.record);
      }catch{}
    };
    if(typeof requestIdleCallback==='function')requestIdleCallback(load,{timeout:1500});
    else setTimeout(load,100);
  }
  function renderChargeIntegral(r){
    const label=$('summary').querySelector('.summary-current-integral');
    const mah=!r.active&&r.kind==='charge'&&chargeEntry?.key===chargeCache.key(r)?chargeEntry.mah:undefined;
    label.hidden=mah===undefined;
    if(!label.hidden)label.textContent=`电流积分：${mah===null?'—':Math.round(mah)+'mAh'}`;
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
      ['pct',valid(reading.pct??r.pct_end)?`${fmt(reading.pct??r.pct_end)}%`:'—','电量','',null],
      ['rm',fmt(reading.rm??r.rm_end),'剩余容量','',null],
      ['current',r.kind==='discharge'?signed(displayCurrent,0):signed(valid(displayCurrent)?displayCurrent/1000:null),'电流',r.kind==='discharge'?'mA':'A',flow],
      ...(separateCells?[
        ['cell1',number(valid(reading.cell1)?reading.cell1/1000:null,3),'电芯 1','V',null],
        ['cell2',number(valid(reading.cell2)?reading.cell2/1000:null,3),'电芯 2','V',null]
      ]:[['voltage',number(valid(reading.voltage)?reading.voltage/1000:null,3),'电压','V',null]]),
      ['temp',number(reading.temp,1),'温度','°C',null],
      ['power',signed(reading.power),r.kind==='discharge'?'实时功率':'功率','W',flow]
    ];
    if(active&&r.kind==='discharge'){
      const estimate=live&&!document.hidden&&!$('confirm').open&&canEstimateRemaining(r,reading)
        ?remainingEstimate(reading,detail?.drainSummary?.[0]?.avgPower,lastStatus.highDesignCapacity):null;
      primary.push(
        ['remainingWh',estimate?.whText??'—','估算剩余能量','Wh',null],
        ['remainingTime',estimate?remainingTime(estimate.minutes,estimate.wh):'—','估算亮屏时长','',null]
      );
    }
    const values=active?primary:[
      ['pct',`${fmt(r.pct_end)}%`,'结束电量','',null],
      ['change',change,'容量变化','',changeFlow],
      ['duration',duration(r.end_elapsed-r.start_elapsed),'记录时长','',null]
    ];
    const mode=active?'live':'ended',voltageCount=separateCells?'2':'1';summary.dataset.flow=r.kind;
    if(summary.dataset.recordId!==String(r.id)||summary.dataset.mode!==mode||summary.dataset.voltageCount!==voltageCount){
      summary.dataset.recordId=r.id;summary.dataset.mode=mode;summary.dataset.voltageCount=voltageCount;summary.replaceChildren();
      const grid=el('div',undefined,'summary-grid');
      for(const [key,,,unit]of values){
        const item=el('div',undefined,'summary-metric');item.dataset.metric=key;
        const value=el('strong'),text=el('span','','metric-value');
        if(key==='remainingWh'||key==='remainingTime')text.id=key;
        value.append(text,el('span',unit,'metric-unit'));
        if(key==='remainingWh')value.append(remainingInfoButton());
        item.append(value,el('small','','metric-label'));grid.append(item);
      }
      const meta=el('div',undefined,'summary-meta');
      meta.append(el('span','','summary-state'),el('span','','summary-capacity'),el('span','','summary-current-integral'),el('span','','summary-float-max'),el('span','','summary-change'),el('time','','summary-time'));
      summary.append(grid);
      summary.append(meta);
    }
    for(const [key,value,label,,color]of values){
      const item=summary.querySelector(`[data-metric="${key}"]`);
      item.querySelector('.metric-value').textContent=value;
      item.querySelector('.metric-label').textContent=label;
      if(color)item.dataset.flow=color;else delete item.dataset.flow;
    }
    const state=summary.querySelector('.summary-state'),delta=summary.querySelector('.summary-change'),time=summary.querySelector('.summary-time');
    state.textContent=active?`${failed?'连接失败':live?(reading.error?'读取异常':'实时'):'正在读取'} · ${duration(elapsed-r.start_elapsed)}`:
      (reasons[r.reason]??r.reason)+(r.reason==='full_tail_confirmed'?` · ${valid(r.tail_cycles)?fmt(r.tail_cycles):'—'} 次循环`:'');
    const floatMax=summary.querySelector('.summary-float-max');
    floatMax.hidden=active||r.reason!=='full_tail_confirmed';
    floatMax.textContent=floatMax.hidden?'':`浮充电压上限 ${valid(r.tail_float_voltage_max_mv)?fmt(r.tail_float_voltage_max_mv)+' mV':'—'}`;
    renderChargeIntegral(r);
    const endRm=live&&valid(reading.rm)?reading.rm:r.rm_end,capacity=summary.querySelector('.summary-capacity');
    capacity.textContent=`${valid(r.rm_start)?r.rm_start.toFixed(0):'—'} → ${valid(endRm)?endRm.toFixed(0):'—'}`;
    if(recordFast(r)){capacity.dataset.flow=changeFlow;delta.dataset.flow=changeFlow;}else{delete capacity.dataset.flow;delete delta.dataset.flow;}
    capacity.setAttribute('aria-label',`起始容量 ${valid(r.rm_start)?r.rm_start.toFixed(0):'未知'}，结束容量 ${valid(endRm)?endRm.toFixed(0):'未知'}`);
    delta.textContent=active?`容量变化 ${change}`:'';
    delta.hidden=!active;time.hidden=!active;
    time.textContent=live&&valid(reading.wall)?new Date(reading.wall).toLocaleTimeString('zh-CN',{hour12:false,hour:'2-digit',minute:'2-digit',second:'2-digit'}):'—';
    time.dateTime=live&&valid(reading.wall)?new Date(reading.wall).toISOString():'';
    time.setAttribute('aria-label',`读数时间 ${time.textContent}`);
  }
  function renderDetailNote(data){
    const r=data.record;
    $('detailNote').textContent=data.loading?'':`${fmt(data.rawCount??data.points.length)} 个采样点 · 缺样 ${r.missed??0} · 异常 ${r.anomalies??0}`;
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
    if(kind==='discharge'){
      const label=el('label');label.className='screen-off-option';label.style.setProperty('--series','#7c858d');label.title='按已保存的息屏时段显示淡灰投影';
      const input=el('input');input.type='checkbox';input.checked=chart.enabled.has('screenOff');input.setAttribute('aria-label','息屏投影');
      input.onchange=()=>{
        input.checked?chart.enabled.add('screenOff'):chart.enabled.delete('screenOff');remember();
        chart.draw();if(detail)screenOffOverlay.update(detail.record,detail.statsVersion);
      };
      label.append(input,el('span','息屏投影'));$('legend').append(label);
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

  function current(token,id){return token===request&&(id===undefined||id===activeId);}
  function pollRequest(){return{token:request,id:detail?.record.active&&streamId===activeId?activeId:0,after:streamAfter};}
  async function receive(packet,snapshot){
    const{token,id}=snapshot,points=packet.points??[];
    if(id&&current(token,id)){
      if(packet.record===null){toast('此记录已删除');$('back').click();return;}
      if(packet.record){
        const peakChanged=packet.record.kind==='discharge'&&!recordFast(packet.record)&&packet.record.rm_start>detail.record.rm_start;
        const rebasePoint=peakChanged?points.findLast(point=>point[1]===packet.record.rm_start&&(point[6]&dischargeRebase)!==0):null;
        const rebase=rows=>rows.map(row=>{
          if(!rebasePoint||row[0]<=0||row[0]>rebasePoint[0]||(row[6]&dischargeRebase)!==0)return row;
          const next=[...row];next[5]=null;next[6]|=dischargeRebase;return next;
        });
        if(rebasePoint)streamPoints=rebase(streamPoints);
        for(const point of points)if(point[0]>streamAfter){streamPoints.push(point);streamAfter=point[0];}
        const pendingChanged=typeof packet.usagePending==='boolean'&&packet.usagePending!==detail.usagePending;
        const activeChanged=packet.record.active!==detail.record.active;
        const changed=points.length>0||activeChanged||pendingChanged||valid(packet.statsVersion)&&packet.statsVersion!==detail.statsVersion;
        detail={...detail,record:packet.record,ranges:packet.ranges??detail.ranges,rawCount:packet.rawCount??detail.rawCount,statsVersion:packet.statsVersion??detail.statsVersion,statsEpoch:packet.statsEpoch??detail.statsEpoch,usagePending:packet.usagePending??detail.usagePending,apps:packet.apps??detail.apps,drainSummary:packet.drainSummary??detail.drainSummary};
        if(changed){
          if(followLive&&!chart.pointers.size)showDetail({...detail,points:streamPoints,preview:streamPreview},true,true);
          else{
            followRedrawPending=followLive;
            if(activeChanged)renderRecordHeading(detail.record);
            if(rebasePoint){
              detail={...detail,points:rebase(detail.points)};
              applyChart(detail,chart.window,true);
            }else chart.total=Math.max(1,detail.record.end_elapsed-detail.record.start_elapsed);
            if(packet.apps||pendingChanged){renderApps(detail);intervalAppDetails.preload(detail.record,detail.statsVersion);}
            renderDetailNote(detail);renderFollow();renderChartInfo();
          }
        }
        renderSummary(detail.record);screenOffOverlay.update(detail.record,detail.statsVersion);
        if(activeChanged){liveChannel.setMode(detail.record.active?DETAIL_WATCH_MODE:SUMMARY_WATCH_MODE);syncRemainingExplanation();scheduleChargeIntegral(detail.record);}
      }
    }
  }
  function bind(){
    if(bound)return;bound=true;
    bindExport($('chartExport'),()=>`电池曲线-${activeId}.csv`,()=>recordCsv(api,activeId));
    $('closeRemainingExplanation').onclick=dismissRemainingExplanation;
    $('remainingExplanation').addEventListener('cancel',e=>{e.preventDefault();dismissRemainingExplanation();});
    $('reversePlot').onclick=()=>{reversePlot=!reversePlot;chart.clearInspect();syncReversePlot();};
    document.addEventListener('fullscreenchange',()=>{if(!document.fullscreenElement&&expanded)toggleFullscreen(false);});
    window.addEventListener('resize',syncFullscreenLayout);window.visualViewport?.addEventListener('resize',syncFullscreenLayout);window.visualViewport?.addEventListener('scroll',syncFullscreenLayout);
    $('fullscreen').onclick=()=>toggleFullscreen(true);$('closeFullscreen').onclick=()=>toggleFullscreen(false);
    $('followLatest').onclick=()=>{
      if(!detail?.record.active)return;
      if(followLive){pauseFollowing();renderChartInfo();return;}
      ++request;clearTimeout(zoomTimer);const span=chart.window[1]-chart.window[0];
      followSpan=chart.window[0]===0&&span>=chart.total-1?null:span;followLive=true;
      showDetail({...detail,points:streamPoints,preview:streamPreview},true,true);schedulePoll(0);
    };
    $('resetZoom').onclick=()=>{
      if(detail?.record.active&&streamId===activeId){++request;clearTimeout(zoomTimer);followLive=true;followSpan=null;showDetail({...detail,points:streamPoints,preview:streamPreview},true,true);schedulePoll(0);}
      else{chart.setWindow(0,chart.total);scheduleZoom();}
    };

    document.addEventListener('pointerup',()=>{if(followRedrawPending&&followLive&&detail?.record.active&&streamId===activeId&&!detail.loading&&!chart.pointers.size)showDetail({...detail,points:streamPoints,preview:streamPreview},true,true);});
    document.addEventListener('keydown',e=>{if(e.key==='Escape'&&expanded)toggleFullscreen(false);});
  }
  return{bind,open:openDetail,close,pollRequest,current,receive,
    get data(){return detail;},get id(){return activeId;},get busy(){return detailBusy>0;},
    setStatus(value){
      lastStatus=value;
      const highDesignCapacity=value?.highDesignCapacity;
      if(detail&&!detail.loading&&detail.record.kind==='discharge'&&typeof highDesignCapacity==='boolean'&&highDesignCapacity!==detail.highDesignCapacity){
        detail={...detail,highDesignCapacity};applyChart(detail,chart.window,true);
      }
    },
    setSettings(value){
      uiSettings=value;
      if(detail){chart.draw();renderChartInfo();if(!detail.loading)renderApps(detail);}
    },
    theme(){if(activeId!==null&&detail)buildLegend(detail.record.kind);chart.draw();performancePanel?.refreshTheme();},
    syncHistory:syncRemainingExplanation,
    backFullscreen(){if(performancePanel?.expanded){performancePanel.fullscreen(false);return true;}if(expanded){toggleFullscreen(false);return true;}return false;},
    pause(){clearRemaining();drain.pause();intervalAppDetails.pause();},
    resume(){drain.resume();if(detail&&!detail.loading)intervalAppDetails.preload(detail.record,detail.statsVersion);},
    pauseRemaining:clearRemaining,
    prune(){drain.prune();intervalAppDetails.prune();chargeCache.prune(api).catch(()=>{});},
    removeCaches(id){drain.remove(id);intervalAppDetails.remove(id);chargeCache.remove(id);if(activeId===id){drain.close();chargeEntry=null;}},
    failure(){if(activeId!==null){$('chartInfo').textContent='正在重连…';if(detail)renderSummary(detail.record,true);}}
  };
}
