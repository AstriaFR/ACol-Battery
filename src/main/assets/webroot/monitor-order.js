import{longPressFeedback}from'./record-display.js?v=acol-2.2s';

export function installMonitorOrder(list,canReorder,onReorder){
  const scroller=list.closest('.page-scroll');
  const rows=[...list.querySelectorAll('label')];
  const ids=new Map(rows.map(row=>[row,String(Math.log2(Number(row.querySelector('input').dataset.monitorBit))+1)]));
  const byId=new Map(rows.map(row=>[ids.get(row),row]));
  const currentOrder=()=>[...list.querySelectorAll('label')].map(row=>ids.get(row)).join('');
  const midpoint=box=>(box.top+box.bottom)/2;
  const reducedMotion=matchMedia('(prefers-reduced-motion: reduce)');
  const shifts=new Map();
  let press=null,drag=null,hold=0,scrollFrame=0,suppressClickUntil=0;

  function apply(order){
    if(typeof order!=='string'||order.length!==rows.length||new Set(order).size!==rows.length||
        [...order].some(id=>!byId.has(id))||order===currentOrder())return;
    const groups=new Set();
    for(const id of order){
      const row=byId.get(id),group=row.parentElement;
      group.append(row);groups.add(group);
    }
    for(const group of groups)list.append(group);
  }

  function animatePositions(change,except){
    const previous=new Map(rows.filter(row=>row!==except).map(row=>[row,row.getBoundingClientRect().top]));
    change();
    for(const [row,top] of previous){
      shifts.get(row)?.cancel();
      shifts.delete(row);
      const delta=top-row.getBoundingClientRect().top;
      if(Math.abs(delta)<.5||reducedMotion.matches)continue;
      shifts.set(row,row.animate([{transform:`translateY(${delta}px)`},{transform:'translateY(0)'}],
        {duration:160,easing:'cubic-bezier(.2,.65,.3,1)'}));
    }
  }

  function position(y){
    if(!drag)return;
    drag.pointerY=y;
    const listTop=list.getBoundingClientRect().top;
    const{row,slot,parent,scope,others,grab,height,originTop}=drag;
    const top=Math.max(scope.top,Math.min(scope.bottom-height,y-listTop-grab));
    const center=top+height/2;
    const before=others.find(({box})=>center<midpoint(box)||center===midpoint(box)&&top<originTop)?.node??null;
    if(before!==slot.nextElementSibling)animatePositions(()=>parent.insertBefore(slot,before),row);
    const slotTop=row.getBoundingClientRect().top-listTop-drag.offset;
    drag.offset=top-slotTop;
    row.style.setProperty('--monitor-drag-y',`${drag.offset}px`);
  }

  function autoScroll(){
    scrollFrame=0;
    if(!drag)return;
    const box=scroller.getBoundingClientRect(),listTop=list.getBoundingClientRect().top,y=drag.pointerY;
    const edge=Math.min(30,box.height/4);
    const delta=y<box.top+edge&&listTop+drag.scope.top<box.top?-8:
      y>box.bottom-edge&&listTop+drag.scope.bottom>box.bottom?8:0;
    if(!delta)return;
    const previous=scroller.scrollTop;
    scroller.scrollTop+=delta;
    if(previous!==scroller.scrollTop){
      position(y);scrollFrame=requestAnimationFrame(autoScroll);
    }
  }

  function begin(id,x,y,target){
    if(press){finish(false);return;}
    suppressClickUntil=0;
    const row=target.closest('label');
    if(!ids.has(row)||!canReorder())return;
    press={id,x,y};
    hold=setTimeout(()=>{
      if(!canReorder()){finish(false);return;}
      for(const shift of shifts.values())shift.cancel();
      shifts.clear();
      const group=row.parentElement;
      const standalone=group.children.length===1;
      const parent=standalone?list:group,slot=standalone?group:row;
      const top=list.getBoundingClientRect().top;
      const box=node=>{const rect=node.getBoundingClientRect();return {top:rect.top-top,bottom:rect.bottom-top};};
      const others=[...parent.children].filter(node=>node!==slot).map(node=>({node,box:box(node)}));
      const rowBox=box(row);
      drag={row,parent,slot,scope:box(parent),others,original:currentOrder(),originTop:rowBox.top,
        grab:y-top-rowBox.top,height:rowBox.bottom-rowBox.top,offset:0};
      row.dataset.dragged='';row.style.setProperty('--monitor-drag-y','0px');
      list.dataset.reordering='';longPressFeedback();position(y);
    },420);
  }

  function move(id,x,y,event){
    if(press?.id!==id)return;
    if(!drag){
      if(Math.hypot(x-press.x,y-press.y)>8)finish(false);
      return;
    }
    if(event.cancelable)event.preventDefault();
    position(y);
    if(!scrollFrame)scrollFrame=requestAnimationFrame(autoScroll);
  }

  function finish(save,event){
    clearTimeout(hold);press=null;
    if(!drag)return;
    if(event?.cancelable)event.preventDefault();
    if(scrollFrame)cancelAnimationFrame(scrollFrame);
    scrollFrame=0;
    const{row,original}=drag;drag=null;
    suppressClickUntil=performance.now()+650;
    const commit=save&&canReorder();
    animatePositions(()=>{
      row.removeAttribute('data-dragged');row.style.removeProperty('--monitor-drag-y');
      delete list.dataset.reordering;
      if(!commit)apply(original);
    });
    const order=currentOrder();
    if(commit&&order!==original)onReorder(order);
  }

  list.addEventListener('touchstart',event=>{
    if(event.touches.length!==1){finish(false);return;}
    const touch=event.touches[0];begin(touch.identifier,touch.clientX,touch.clientY,event.target);
  },{passive:true});
  list.addEventListener('touchmove',event=>{
    for(const touch of event.changedTouches)move(touch.identifier,touch.clientX,touch.clientY,event);
  },{passive:false});
  for(const type of ['touchend','touchcancel'])list.addEventListener(type,event=>{
    if(Array.from(event.changedTouches).some(touch=>touch.identifier===press?.id))finish(type==='touchend',event);
  },{passive:false});
  list.addEventListener('pointerdown',event=>{
    if(event.pointerType!=='touch'&&event.isPrimary&&event.button===0)begin(event.pointerId,event.clientX,event.clientY,event.target);
  });
  window.addEventListener('pointermove',event=>{
    if(event.pointerType!=='touch')move(event.pointerId,event.clientX,event.clientY,event);
  });
  for(const type of ['pointerup','pointercancel'])window.addEventListener(type,event=>{
    if(event.pointerType!=='touch'&&press?.id===event.pointerId)finish(type==='pointerup',event);
  });
  window.addEventListener('blur',()=>finish(false));
  document.addEventListener('visibilitychange',()=>{if(document.hidden)finish(false);});
  list.addEventListener('click',event=>{
    if(event.detail&&performance.now()<suppressClickUntil){event.preventDefault();event.stopImmediatePropagation();}
  },{capture:true});
  return {apply,cancel:()=>finish(false)};
}
