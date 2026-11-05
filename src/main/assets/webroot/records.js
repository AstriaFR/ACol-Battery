import{valid,duration}from'./chart.js?v=acol-2.2s';
import{compareRecords}from'./live-channel.js?v=acol-2.2s';
import{recordFast,fmt,recordChanges,pad2,stampList,reasons,el,longPressFeedback}from'./record-display.js?v=acol-2.2s';
const $=id=>document.getElementById(id);

/** Owns list/filter/delete state and the shared homepage record presentation. */
export function createRecords(options){
  const{api,liveChannel,toast,getSettings,getStatus,getDetail,openRecord,onKindChange,pauseRemaining,onDialogClose,onDeleted,onRefresh}=options;
  let records=[],kind='all',total=0,totalRecords=0,loading=false,recordsReady=false;
  let listDue=true,lastListAt=0,listSelection=0,monthFilter=null,filterYear=0,filterMonth=0,filterBusy=false,filterRequest=0;
  let revealedRecordId=null,deleteTarget=null,homeActiveRecord=null,bound=false;
  const filteredLists=new Map();
  function refresh(){listDue=true;onRefresh();}
  function renderRecordIcon(node,r){
    const type=r?(recordFast(r)?'game':r.kind):'empty';if(node.dataset.type===type)return;
    node.dataset.type=type;node.classList.toggle('game-record-icon',type==='game');
    node.setAttribute('role','img');node.setAttribute('aria-label',type==='game'?'游戏性能统计':type==='charge'?'充电':type==='discharge'?'放电':'暂无记录');
    if(type==='empty'){node.textContent='—';return;}
    const ns='http://www.w3.org/2000/svg',svg=document.createElementNS(ns,'svg');
    svg.setAttribute('viewBox','0 0 24 24');svg.setAttribute('aria-hidden','true');
    const part=(tag,attributes)=>{const shape=document.createElementNS(ns,tag);for(const[key,value]of Object.entries(attributes))shape.setAttribute(key,String(value));svg.append(shape);};
    if(type==='charge'||type==='discharge'){
      part('rect',{x:6.5,y:4.5,width:11,height:16,rx:2.8,fill:'currentColor','fill-opacity':'.035',stroke:'currentColor','stroke-width':1.5});
      part('path',{d:'M10 2h4',fill:'none',stroke:'currentColor','stroke-width':1.8,'stroke-linecap':'round'});
      if(type==='charge')part('path',{d:'m12.9 7.4-3.8 5.4h2.8l-.8 4.6 4-6h-2.8Z',fill:'currentColor'});
      else part('path',{d:'M12 8.4v7.3m-2.6-2.6 2.6 2.6 2.6-2.6',fill:'none',stroke:'currentColor','stroke-width':1.6,'stroke-linecap':'round','stroke-linejoin':'round'});
      node.replaceChildren(svg);return;
    }
    part('path',{d:'M7.5 6h9c2 0 3.25 1.35 3.72 3.28l1.16 5.11c.57 2.52-2.18 4.16-3.88 2.54l-2.17-2.06H8.67L6.5 16.93c-1.7 1.62-4.45-.02-3.88-2.54l1.16-5.11C4.25 7.35 5.5 6 7.5 6Z',fill:'currentColor','fill-opacity':'.1',stroke:'currentColor','stroke-width':'1.45','stroke-linejoin':'round'});
    part('path',{d:'M7.3 8.8v4.2M5.2 10.9h4.2',fill:'none',stroke:'currentColor','stroke-width':'1.65','stroke-linecap':'round'});
    part('circle',{cx:16.2,cy:9.8,r:1,fill:'currentColor'});
    part('circle',{cx:18.1,cy:11.9,r:1,fill:'currentColor'});
    part('rect',{x:10.6,y:8.1,width:2.8,height:1,rx:.5,fill:'currentColor',opacity:'.5'});
    node.replaceChildren(svg);
  }
  function updateRecordTitle(title,r){
    title.replaceChildren(el('span',stampList(r.start_wall),'record-start-time record-date'));
  }
  function updateRecordMeta(meta,r){
    const state=el('span',reasons[r.reason]??(r.active?'记录中':'记录结束'),'record-state');
    state.dataset.active=r.active?'1':'0';
    meta.replaceChildren(el('span',duration(r.end_elapsed-r.start_elapsed),'record-duration'),document.createTextNode('\u00a0\u00a0'),state);
  }
  function renderRecordPrimary(value,change,r){
    value.dataset.flow=change.flow;
    const known=valid(r?.pct_start)&&valid(r?.pct_end),difference=known?r.pct_end-r.pct_start:0;
    const start=valid(r?.pct_start)?`${fmt(r.pct_start)}%`:'—',end=valid(r?.pct_end)?`${fmt(r.pct_end)}%`:'—';
    const svg=document.createElementNS('http://www.w3.org/2000/svg','svg');
    svg.setAttribute('viewBox','0 0 112 44');svg.setAttribute('role','img');
    const capacity=recordChanges(r).secondary;
    svg.setAttribute('aria-label',`起始电量 ${start}，结束电量 ${end}，变化 ${change.text}，容量变化 ${capacity.text}`);
    const part=(tag,attributes,text)=>{const node=document.createElementNS(svg.namespaceURI,tag);for(const[key,v]of Object.entries(attributes))node.setAttribute(key,String(v));if(text!==undefined)node.textContent=text;svg.append(node);return node;};
    const rise=known?Math.sign(difference)*6*Math.sqrt(Math.min(100,Math.abs(difference))/100):0;
    const y1=24+rise,y2=24-rise;
    part('path',{d:`M7 ${y1}C42 ${y1} 70 ${y2} 105 ${y2}`,class:'record-art-track'});
    part('circle',{cx:7,cy:y1,r:2.5,class:'record-art-origin'});
    part('circle',{cx:105,cy:y2,r:3,class:'record-art-destination'});
    const number=part('text',{x:56,y:18,'text-anchor':'middle',class:'record-art-value'},known?fmt(Math.abs(difference)):'—');
    if(known){const unit=document.createElementNS(svg.namespaceURI,'tspan');unit.setAttribute('class','record-art-unit');unit.setAttribute('dx','1');unit.textContent='%';number.append(unit);}
    part('text',{x:2,y:43,class:'record-art-endpoint'},start);
    part('text',{x:110,y:43,'text-anchor':'end',class:'record-art-endpoint'},end);
    part('text',{x:56,y:43,'text-anchor':'middle',class:'record-art-capacity','data-flow':capacity.flow},capacity.text);
    value.replaceChildren(svg);
  }
  function updateRecordDelta(delta,r){
    const change=recordChanges(r).primary,value=delta.querySelector('.delta-value');
    renderRecordPrimary(value,change,r);
  }
  function filteredList(){return !!monthFilter||kind!=='all';}
  function filterKey(){return[kind,monthFilter?.year??0,monthFilter?.month??0,Number($('showSmall').checked),getSettings().hidePct].join(':');}
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
  function renderRecords(){
    const list=$('records'),shown=records;
    const existing=new Map([...list.children].filter(row=>row.dataset.recordId).map(row=>[Number(row.dataset.recordId),row]));
    const rows=shown.map(r=>{const row=existing.get(r.id)??createRecordRow(r);updateRecordRow(row,r);return row;});
    if(!rows.length){
      const empty=list.querySelector('.empty')??el('div',undefined,'empty'),text=`暂无记录，可勾选“显示 ≤${getSettings().hidePct}%”。`;
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
      row.dataset.recordId=r.id;row.dataset.active=r.active?'1':'0';b.type='button';const icon=el('span',undefined,'icon');renderRecordIcon(icon,r);
      const info=el('span',undefined,'info');
      const title=el('strong');updateRecordTitle(title,r);info.append(title);
      const meta=el('small');updateRecordMeta(meta,r);info.append(meta);b.append(info);
      const action=el('span',undefined,'record-action'),delta=el('span',undefined,'delta');
      delta.append(el('span',undefined,'delta-value'));updateRecordDelta(delta,r);
      action.append(delta);row.append(icon,action,b);
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
      row.onclick=e=>{if(held||moved){e.preventDefault();return;}revealRecordDelete(null);openRecord(r.id);};
      return row;
  }
  function revealRecordDelete(id){
    revealedRecordId=id;
    for(const button of $('records').querySelectorAll('[data-delete-id]')){button.hidden=Number(button.dataset.deleteId)!==id;button.parentElement.classList.toggle('delete-revealed',!button.hidden);}
  }
  function confirmRecordDelete(record,push=true){
    if(!record||$('confirm').open||liveChannel.deleted.has(record.id))return;
    pauseRemaining();deleteTarget=record;
    $('confirmText').textContent=record.active?(recordFast(record)?'当前游戏记录将删除，并关闭游戏性能统计。删除后无法恢复。':'当前记录将删除，并立即开始下一段记录。删除后无法恢复。'):'曲线和应用统计将删除，无法恢复。';
    if(push)history.pushState({...history.state,deleteRecord:record.id},'',location.href);
    $('confirm').showModal();
  }
  function syncDeleteConfirmation(){
    const detail=getDetail();
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
    const lastStatus=getStatus();
    homeActiveRecord=r;
    const host=$('homeActive'),button=$('homeActiveOpen');
    const signature=[lastStatus?.recordingEnabled,r?.id,r?.kind,r?.pct_start,r?.pct_end,r?.rm_start,r?.rm_end,r?.end_elapsed,r?.fast_seconds].join('|');
    if(host.dataset.content===signature)return;host.dataset.content=signature;button.disabled=!r;
    const card=$('homeActiveCard');card.className='record-row record'+(r?' '+r.kind+(recordFast(r)?' game-record':''):'');
    if(!r){
      $('homeActiveTitle').textContent='暂无进行中的记录';renderRecordIcon($('homeActiveIcon'),null);
      $('homeActiveMeta').textContent=lastStatus?.recordingEnabled===false?'记录停止':'等待记录';
      $('homeActivePrimary').textContent='—';$('homeActivePrimary').dataset.flow='';return;
    }
    const changes=recordChanges(r);
    renderRecordIcon($('homeActiveIcon'),r);updateRecordTitle($('homeActiveTitle'),r);
    renderRecordPrimary($('homeActivePrimary'),changes.primary,r);
    updateRecordMeta($('homeActiveMeta'),r);
  }
  function renderDateFilter(){
    const year=$('filterYear'),month=$('filterMonth');
    year.textContent=pad2(filterYear%100);month.textContent=pad2(filterMonth);
    year.setAttribute('aria-valuenow',String(filterYear));year.setAttribute('aria-valuetext',`${filterYear}年`);
    month.setAttribute('aria-valuenow',String(filterMonth));month.setAttribute('aria-valuetext',`${filterMonth}月`);
  }
  async function deleteConfirmed(){
    const target=deleteTarget;if(!target)return;
    $('confirmDelete').disabled=true;
    try{
      const result=await api('delete',target.id);liveChannel.forgetRecord(target,result.listVersion??0);onDeleted(target.id);
      ++listSelection;++filterRequest;filterBusy=false;filteredLists.clear();
      if(records.some(r=>r.id===target.id))total=Math.max(0,total-1);
      totalRecords=Math.max(0,totalRecords-1);records=records.filter(r=>r.id!==target.id);renderRecords();
      $('confirm').close();revealRecordDelete(null);
      const activeId=getDetail()?.record.id??null;
      if(history.state?.deleteRecord===target.id)history.go(activeId===target.id?-2:-1);
      else if(activeId===target.id)$('back').click();
      refresh();
      toast('记录已删除');
    }catch(e){toast(e.message);}finally{$('confirmDelete').disabled=false;}
  }

  function pollRequest(){return{selection:listSelection,small:Number($('showSmall').checked),wantList:listDue||Date.now()-lastListAt>=15000};}
  async function receive(packet,snapshot){
    if(snapshot.selection===listSelection){
      if(filteredList()){
        if(options.getTab()==='records'&&!$('appShell').hidden&&(snapshot.wantList||Array.isArray(packet.records)))await loadFiltered(true);
        else if(options.getTab()!=='records'||$('appShell').hidden){listDue=false;lastListAt=Date.now();}
      }else if(Array.isArray(packet.records)&&snapshot.small===Number($('showSmall').checked)){
        records=packet.records;total=packet.total;totalRecords=packet.totalRecords??totalRecords;recordsReady=true;listDue=false;lastListAt=Date.now();renderRecords();
      }
    }
    if(packet.activeRecord&&(!filteredList()||records.some(r=>r.id===packet.activeRecord.id)))updateActiveRecord(packet.activeRecord);
    renderHomeActive(packet.activeRecord&&!liveChannel.deleted.has(packet.activeRecord.id)?packet.activeRecord:null);
  }
  function selectKind(value){
    kind=value;
    for(const button of document.querySelectorAll('[data-kind]')){const selected=button.dataset.kind===kind;button.classList.toggle('selected',selected);button.setAttribute('aria-pressed',String(selected));}
    onKindChange();selectRecords();
  }
  function selectMonth(year,month){
    if(monthFilter&&monthFilter.year===year&&monthFilter.month===month){cancelMonthFilter();return;}
    monthFilter={year,month};$('applyMonth').setAttribute('aria-pressed','true');selectRecords();
  }
  async function more(){
    if(filteredList())return loadFiltered(false);if(loading)return;loading=true;
    const selection=listSelection,small=Number($('showSmall').checked);
    try{const data=await api('list',small,records.length);if(selection!==listSelection)return;const list=liveChannel.appendList(small,data);records=list.records;total=list.total;totalRecords=list.totalRecords??totalRecords;recordsReady=true;renderRecords();}
    catch(error){toast(error.message);}finally{loading=false;}
  }
  function bind(){
    if(bound)return;bound=true;
    document.addEventListener('pointerdown',e=>{if(!e.target.closest('.record-row'))revealRecordDelete(null);});
    $('homeActiveCard').onclick=()=>{if(homeActiveRecord)openRecord(homeActiveRecord.id);};
    $('cancelDelete').onclick=dismissRecordDelete;
    $('confirm').addEventListener('cancel',e=>{e.preventDefault();dismissRecordDelete();});
    $('confirmDelete').onclick=deleteConfirmed;
    $('confirm').addEventListener('close',()=>{deleteTarget=null;onDialogClose();});
    const initialDate=new Date();filterYear=initialDate.getFullYear();filterMonth=initialDate.getMonth()+1;renderDateFilter();
    $('yearUp').onclick=()=>{filterYear=Math.min(2100,filterYear+1);renderDateFilter();};
    $('yearDown').onclick=()=>{filterYear=Math.max(2000,filterYear-1);renderDateFilter();};
    $('monthUp').onclick=()=>{filterMonth=filterMonth%12+1;renderDateFilter();};
    $('monthDown').onclick=()=>{filterMonth=filterMonth===1?12:filterMonth-1;renderDateFilter();};
    $('applyMonth').onclick=()=>selectMonth(filterYear,filterMonth);
    $('showSmall').onchange=selectRecords;
    for(const button of document.querySelectorAll('[data-kind]'))button.onclick=()=>selectKind(button.dataset.kind);
    $('more').onclick=more;
    const scroller=$('recordsPage').querySelector('.record-scroll');
    scroller.addEventListener('scroll',()=>{if(options.getTab()==='records'&&!$('appShell').hidden&&!loading&&!filterBusy&&!$('more').hidden&&scroller.scrollTop>0&&scroller.scrollHeight-scroller.scrollTop-scroller.clientHeight<=60)more();},{passive:true});
  }
  return{bind,refresh,pollRequest,receive,render:renderRecords,selectKind,selectMonth,cancelMonth:cancelMonthFilter,more,
    syncHistory:syncDeleteConfirmation,hideDelete:()=>revealRecordDelete(null),
    get kind(){return kind;},get busy(){return loading;},
    snapshot:()=>({records:[...records],total,totalRecords,ready:recordsReady,filtered:filteredList()})};
}
