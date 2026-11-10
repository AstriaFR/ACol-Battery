// Fit only data lines that cannot wrap. Names and explanatory text keep their
// normal ellipsis/wrapping rules. Batch reads/writes once per frame, never poll.
const selector='.livegrid strong,.record-date,.summary-metric>strong,.summary-remaining>div>strong,.summary-frequencies strong,.drain-item-values,.appEnergy strong,.home-usage-summary>.screen-time-total,.usage-detail-summary>.screen-time-total,.usage-week-average>strong';
export function installLineFitting(){
  let frame=0;const pending=new Set();
  const recordScroll=document.querySelector('.record-scroll'),observed=new WeakSet(),visible=new WeakSet();
  const records=recordScroll&&typeof IntersectionObserver==='function'?new IntersectionObserver(entries=>{
    for(const {target,isIntersecting}of entries){
      if(!observed.has(target))continue;
      if(isIntersecting){visible.add(target);pending.add(target);}
      else{visible.delete(target);pending.delete(target);}
    }
    if(pending.size)schedule();
  },{root:recordScroll,rootMargin:'72px 0px'}):null;
  const queue=node=>{
    if(records&&node.matches('.record-date')&&recordScroll.contains(node)){
      if(!observed.has(node)){observed.add(node);records.observe(node);}
      if(!visible.has(node))return;
    }
    pending.add(node);
  };
  const collect=root=>{
    const element=root.nodeType===1?root:root.parentElement;
    const line=element?.closest(selector);if(line)queue(line);
    for(const node of root.querySelectorAll?.(selector)??[])queue(node);
  };
  function unobserve(node){
    if(!observed.delete(node))return;
    records.unobserve(node);visible.delete(node);pending.delete(node);
  }
  const fit=()=>{
    frame=0;
    const lines=[...pending].filter(node=>node.isConnected&&node.getClientRects().length&&!node.closest('.expanded'));pending.clear();
    for(const node of lines)node.style.removeProperty('font-size');
    const sizes=lines.map(node=>{
      const width=node.clientWidth,needed=node.scrollWidth,style=getComputedStyle(node);
      const padding=parseFloat(style.paddingLeft)+parseFloat(style.paddingRight);
      return width>padding&&needed>width+1?[node,Math.max(10,parseFloat(style.fontSize)*(width-padding-1)/(needed-padding))]:null;
    });
    for(const item of sizes)if(item)item[0].style.fontSize=`${item[1]}px`;
  };
  const schedule=()=>{if(!frame)frame=requestAnimationFrame(fit);};
  // Child/text mutations cover live values and newly opened pages. Hidden
  // attribute changes cover a cached page becoming visible again.
  const observer=new MutationObserver(records=>{
    for(const record of records){
      if(record.type==='attributes')collect(record.target);
      else{
        const line=(record.target.nodeType===1?record.target:record.target.parentElement)?.closest(selector);if(line)queue(line);
        if(records&&recordScroll.contains(record.target))for(const node of record.removedNodes??[]){
          unobserve(node);for(const date of node.querySelectorAll?.('.record-date')??[])unobserve(date);
        }
        for(const node of record.addedNodes??[])collect(node);
      }
    }
    if(pending.size)schedule();
  });
  observer.observe(document.body,{subtree:true,childList:true,characterData:true,attributes:true,attributeFilter:['hidden']});
  const all=()=>{collect(document);schedule();};
  window.addEventListener('resize',all);document.fonts?.ready.then(all);
  all();
}
