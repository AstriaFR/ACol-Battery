// Navigation-only compositor animations. No cloned pages, canvas copies or data reads.
export function installPageMotion(){
  const ids=['homePage','recordsPage','settingsPanel','detail','batteryInfoPanel','batteryHistoryPanel','batteryHistoryDetail','usageDetail','usageAppDetail','usageWeekDetail','usageMonthDetail','usageAppWeekDetail','cutoffFrame'];
  const nodes=ids.map(id=>document.getElementById(id)).filter(Boolean),tabs=['home','records','settings'];
  const reduced=matchMedia('(prefers-reduced-motion: reduce)'),running=new Map();
  let last=nodes.find(node=>!node.closest('[hidden]')),state=history.state,back=false,tab=false,queued=false,interactive=false;
  for(const event of ['pointerdown','keydown','click'])window.addEventListener(event,()=>{interactive=true;},{capture:true,once:true});
  const stop=node=>{const animation=running.get(node);if(animation){running.delete(node);animation.cancel();}};
  const stopAll=()=>{for(const node of [...running.keys()])stop(node);};
  const animate=(node,frames,duration)=>{
    stop(node);if(!interactive||document.hidden||reduced.matches||typeof node.animate!=='function')return;
    try{
      const animation=node.animate(frames,{duration,easing:'cubic-bezier(.22,.8,.25,1)',fill:'none'});running.set(node,animation);
      const finish=()=>{if(running.get(node)===animation)running.delete(node);};animation.finished.then(finish,finish);
    }catch{ /* Older WebViews keep synchronous navigation. */ }
  };
  const sync=()=>{
    queued=false;
    for(const node of running.keys())if(node.closest('[hidden]'))stop(node);
    const next=nodes.find(node=>!node.closest('[hidden]'));
    if(!next||next===last)return;
    last=next;
    if(document.documentElement.hasAttribute('data-theme-changing'))return;
    animate(next,tab?[{opacity:.65},{opacity:1}]:[{opacity:.65,transform:`translateX(${back?-18:24}px)`},{opacity:1,transform:'translateX(0)'}],tab?170:220);
  };
  const schedule=()=>{if(!queued){queued=true;queueMicrotask(sync);}};
  const direction=next=>{
    const oldPage=state?.page,newPage=next?.page;
    tab=tabs.includes(oldPage)&&tabs.includes(newPage);
    back=(next?.homeDepth??0)<(state?.homeDepth??0);
    state=next;schedule();
  };
  for(const method of ['pushState','replaceState']){
    const original=history[method].bind(history);history[method]=function(...args){const result=original(...args);direction(history.state);return result;};
  }
  window.addEventListener('popstate',event=>direction(event.state));
  const observer=new MutationObserver(schedule);
  for(const node of [...nodes,document.getElementById('appShell')].filter(Boolean))observer.observe(node,{attributes:true,attributeFilter:['hidden']});
  const dialogs=new WeakSet(),documents=new WeakSet();
  const watchDialogs=doc=>{
    if(documents.has(doc))return;documents.add(doc);
    const dialogObserver=new MutationObserver(records=>{
      for(const {target}of records){
        if(!target.open){dialogs.delete(target);stop(target);continue;}
        if(dialogs.has(target))continue;dialogs.add(target);
        animate(target,[{opacity:0},{opacity:1}],170);
      }
    });
    for(const dialog of doc.querySelectorAll('dialog'))dialogObserver.observe(dialog,{attributes:true,attributeFilter:['open']});
  };
  watchDialogs(document);
  const frame=document.getElementById('cutoffFrame');
  frame?.addEventListener('load',()=>{try{watchDialogs(frame.contentDocument);}catch{}});
  window.addEventListener('aco-theme-change',stopAll);
  document.addEventListener('visibilitychange',()=>{if(document.hidden)stopAll();});
  reduced.addEventListener?.('change',()=>{if(reduced.matches)stopAll();});
  window.addEventListener('pagehide',stopAll);
  return{stop:stopAll};
}
