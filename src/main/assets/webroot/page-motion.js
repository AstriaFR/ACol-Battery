// History identifies the destination. Visibility only signals that the route is ready to paint.
const pages={
  home:'homePage',records:'recordsPage',settings:'settingsPanel',record:'detail',
  'monitor-appearance':'monitorAppearancePage','game-metrics':'gameMetricsPage',
  advanced:'advancedPage','advanced-info':'advancedInfoPage','cool-down-details':'coolDownDetailsPage',
  'battery-info':'batteryInfoPanel','battery-history':'batteryHistoryPanel',
  'battery-history-detail':'batteryHistoryDetail','battery-history-trend':'batteryTrendPanel',
  'usage-day':'usageDetail','usage-week':'usageWeekDetail','usage-month':'usageMonthDetail',
  'usage-app':'usageAppDetail','usage-app-week':'usageAppWeekDetail'
};
const tabs=new Set(['home','records','settings']);
const ease='cubic-bezier(.2,.75,.25,1)';
const slide=x=>({transform:`translateX(${x}px)`});

export function installPageMotion(){
  const root=page=>document.getElementById(pages[page]);
  const shell=document.getElementById('appShell');
  const reduced=matchMedia('(prefers-reduced-motion: reduce)');
  const running=new Map();
  let shown=history.state??{page:'home'},target=shown,method='',queued=false,interactive=false;
  for(const name of ['pointerdown','keydown','click'])window.addEventListener(name,()=>{interactive=true;},{capture:true,once:true});
  const enabled=()=>interactive&&!document.hidden&&!reduced.matches&&!document.documentElement.hasAttribute('data-theme-changing');
  function stop(node){
    const animation=running.get(node);
    if(!animation)return;
    running.delete(node);animation.cancel();
  }
  function stopAll(){for(const node of [...running.keys()])stop(node);}
  function animate(node,frames,duration){
    if(!enabled()||!node.animate)return;
    try{
      const animation=node.animate(frames,{duration,easing:ease});
      running.set(node,animation);
      const finish=()=>{if(running.get(node)===animation)running.delete(node);};
      animation.finished.then(finish,finish);
    }catch{ /* Older WebViews switch pages immediately. */ }
  }
  function sync(){
    queued=false;
    const next=root(target.page);
    if(!next||next.closest('[hidden]'))return;
    const previous=shown;shown=target;
    if(previous.page===target.page)return;
    for(const node of [...running.keys()])if(!opened.has(node))stop(node);
    // Keep the old route hidden so glyphs and chart lines never crossfade with the destination.
    if(tabs.has(previous.page)&&tabs.has(target.page)||target.page==='home'&&method==='replaceState'){
      animate(next,[{opacity:.94},{opacity:1}],95);
    }else{
      const back=(target.homeDepth??0)<(previous.homeDepth??0);
      animate(next,[slide(back?-6:8),slide(0)],back?110:135);
    }
  }
  function schedule(){if(!queued){queued=true;queueMicrotask(sync);}}
  function navigate(state,operation){target=state??{page:'home'};method=operation;schedule();}
  for(const name of ['pushState','replaceState']){
    const original=history[name].bind(history);
    history[name]=function(...args){const result=original(...args);navigate(history.state,name);return result;};
  }
  window.addEventListener('popstate',event=>navigate(event.state,'popstate'));
  const observer=new MutationObserver(schedule);
  for(const node of [shell,...Object.keys(pages).map(root)])if(node)observer.observe(node,{attributes:true,attributeFilter:['hidden']});
  const opened=new WeakSet();
  const dialogs=new MutationObserver(records=>{
    for(const {target:dialog} of records){
      if(!dialog.open){opened.delete(dialog);stop(dialog);continue;}
      if(opened.has(dialog))continue;
      opened.add(dialog);
      const sheet=dialog.classList?.contains('numeric-keypad');
      animate(dialog,[{opacity:.15,transform:sheet?'translateY(30px)':'scale(.965)'},{opacity:1,transform:'none'}],sheet?205:155);
    }
  });
  for(const dialog of document.querySelectorAll('dialog'))dialogs.observe(dialog,{attributes:true,attributeFilter:['open']});
  window.addEventListener('aco-theme-change',stopAll);
  document.addEventListener('visibilitychange',()=>{if(document.hidden)stopAll();});
  reduced.addEventListener?.('change',()=>{if(reduced.matches)stopAll();});
  window.addEventListener('pagehide',stopAll);
  return {stop:stopAll};
}
