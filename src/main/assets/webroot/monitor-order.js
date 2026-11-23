import{longPressFeedback}from'./record-display.js?v=acol-2.2s';

export function installMonitorOrder(list,canReorder,onReorder){
  const scroller=list.closest('.page-scroll');
  const rows=[...list.querySelectorAll('label')];
  const ids=new Map(rows.map(row=>[row,String(Math.log2(Number(row.querySelector('input').dataset.monitorBit))+1)]));
  const byId=new Map(rows.map(row=>[ids.get(row),row]));
  const currentOrder=()=>[...list.querySelectorAll('label')].map(row=>ids.get(row)).join('');
  const midpoint=box=>(box.top+box.bottom)/2;
  let press=null,drag=null,hold=0,scrollFrame=0,suppressClickUntil=0;

  function apply(order){
    if(typeof order!=='string'||!/^[1-7]{7}$/.test(order)||order===currentOrder())return;
    const groups=new Set();
    for(const id of order){
      const row=byId.get(id),group=row.parentElement;
      group.append(row);groups.add(group);
    }
    for(const group of groups)list.append(group);
  }

  function position(y){
    if(!drag)return;
    drag.pointerY=y;
    const bounds=list.getBoundingClientRect();
    const localY=y-bounds.top;
    const{group,groupBox,row,rowBox,peerBox,startY,others}=drag;
    const inside=localY>=groupBox.top&&localY<=groupBox.bottom;
    const up=peerBox&&peerBox.top<rowBox.top;
    const withinPair=inside&&peerBox&&(up?localY<midpoint(peerBox):localY>midpoint(peerBox));
    const moving=withinPair?row:group;
    if(drag.moving!==moving){
      drag.moving?.removeAttribute('data-dragged');
      drag.moving?.style.removeProperty('--monitor-drag-y');
      drag.moving=moving;moving.dataset.dragged='';
    }
    const box=withinPair?rowBox:groupBox;
    const offset=Math.max(-box.top,Math.min(bounds.height-box.bottom,localY-startY));
    moving.style.setProperty('--monitor-drag-y',`${offset}px`);

    let target=null;
    if(withinPair)target={parent:group,before:up?group.firstElementChild:null,y:up?groupBox.top:groupBox.bottom};
    else if(!inside){
      const next=others.find(item=>localY<midpoint(item.box));
      const before=next?.node??null;
      if(before!==group.nextElementSibling)target={parent:list,before,y:next?.box.top??bounds.height};
    }
    drag.target=target;
    list.toggleAttribute('data-drop',!!target);
    if(target)list.style.setProperty('--monitor-drop-y',`${target.y-1}px`);
  }

  function autoScroll(){
    scrollFrame=0;
    if(!drag)return;
    const box=scroller.getBoundingClientRect(),listBox=list.getBoundingClientRect(),y=drag.pointerY;
    const edge=Math.min(30,box.height/4);
    const delta=y<box.top+edge&&listBox.top<box.top?-8:y>box.bottom-edge&&listBox.bottom>box.bottom?8:0;
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
      const group=row.parentElement,peer=row.nextElementSibling??row.previousElementSibling;
      const top=list.getBoundingClientRect().top;
      const box=node=>{const rect=node.getBoundingClientRect();return {top:rect.top-top,bottom:rect.bottom-top};};
      const others=[...list.children].filter(node=>node!==group).map(node=>({node,box:box(node)}));
      drag={row,group,groupBox:box(group),rowBox:box(row),peerBox:peer?box(peer):null,others,startY:y-top};
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
    const{moving,target}=drag;drag=null;
    suppressClickUntil=performance.now()+650;
    moving.removeAttribute('data-dragged');moving.style.removeProperty('--monitor-drag-y');
    delete list.dataset.reordering;delete list.dataset.drop;list.style.removeProperty('--monitor-drop-y');
    if(save&&target&&canReorder()){
      target.parent.insertBefore(moving,target.before);
      onReorder(currentOrder());
    }
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
