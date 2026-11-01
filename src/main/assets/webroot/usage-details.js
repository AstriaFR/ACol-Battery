import{DailyUsageChart,roundedDuration}from'./daily-usage-chart.js?v=acol-2.1';
import{UsageWeekPanel}from'./usage-week.js?v=acol-2.1';
import{isoWeek}from'./usage-calendar.js?v=acol-2.1';
import{readAppIcons}from'./root-bridge.js?v=acol-2.1';
const $=id=>document.getElementById(id);
const roots={'usage-day':'usageDetail','usage-week':'usageWeekDetail','usage-month':'usageMonthDetail','usage-app':'usageAppDetail','usage-app-week':'usageAppWeekDetail'};
const scrolls={'usage-day':'usageDayScroll','usage-week':'usageWeekScroll','usage-month':'usageMonthScroll','usage-app':'usageAppScroll','usage-app-week':'usageAppWeekScroll'};
const lists={day:{page:'usage-day',rows:'usageAppRows',status:'usageAppListStatus',scroll:'usageDayScroll'},week:{page:'usage-week',rows:'usageWeekAppRows',status:'usageWeekAppListStatus',scroll:'usageWeekScroll'}};
export class UsageDetailsPanel{
  constructor(api,getCache,navigate){
    this.getCache=getCache;this.navigate=navigate;this.token=0;this.icons=new Map();this.iconQueue=new Map();this.viewScroll=new Map();this.hourSelections=new Map();this.lists=new Map();this.state=null;
    this.dayChart=new DailyUsageChart(api,{prefix:'usageDay',getCache,tipHost:$('usageDayHourDetails'),onDate:day=>navigate({...this.state,page:'usage-day',day},false)});
    this.appChart=new DailyUsageChart(api,{prefix:'usageApp',getCache,inspect:false,onDate:day=>navigate({...this.state,day},false)});
    this.weekChart=new UsageWeekPanel(getCache,navigate);
    this.monthChart=new UsageWeekPanel(getCache,navigate,{prefix:'usageMonth',month:true});
    this.appWeekChart=new UsageWeekPanel(getCache,navigate,{prefix:'usageAppWeek',app:true});
    $('openUsageWeek').onclick=()=>navigate({page:'usage-week',day:this.state.day},true);
    $('openUsageMonthFromWeek').onclick=()=>navigate({page:'usage-month',day:this.state.day},true);
    this.observers=new Map();
    for(const [kind,info]of Object.entries(lists)){
      this.observers.set(kind,new IntersectionObserver(entries=>{for(const entry of entries)if(entry.isIntersecting){this.observers.get(kind).unobserve(entry.target);this.loadIcon(entry.target,entry.target.dataset.pkg)}},{root:$(info.scroll),rootMargin:'80px'}));
      $(info.rows).addEventListener('click',event=>{
        if(this.state?.page!==info.page)return;const row=event.target.closest('[data-usage-pkg]'),value=row&&this.lists.get(kind)?.rows.get(row.dataset.usagePkg);if(!value)return;
        navigate({page:kind==='week'?'usage-app-week':'usage-app',day:kind==='week'?this.weekChart.week.start:this.state.day,pkg:value.pkg,label:value.label},true);
      });
    }
    document.addEventListener('visibilitychange',()=>{if(document.hidden)this.hideTips();});
  }
  viewKey(state){const week=state.page.endsWith('week');return`${state.page}:${week?isoWeek(state.day).start:state.day}:${state.pkg||''}`;}
  hideTips(){this.dayChart.hideTip();this.appChart.hideTip();}
  rememberView(){
    if(!this.state)return;const key=this.viewKey(this.state);this.viewScroll.delete(key);this.viewScroll.set(key,{page:this.state.page,day:this.state.day,top:$(scrolls[this.state.page]).scrollTop});
    if(this.state.page==='usage-day'){
      if(this.dayChart.tip===this.dayChart.tipHost&&!this.dayChart.tipHost.hidden)this.hourSelections.set(this.state.day,this.dayChart.selected);else this.hourSelections.delete(this.state.day);
    }
    while(this.viewScroll.size>12){const oldest=this.viewScroll.keys().next().value,value=this.viewScroll.get(oldest);this.viewScroll.delete(oldest);if(value.page==='usage-day')this.hourSelections.delete(value.day);}
  }
  stopIcons(){for(const observer of this.observers.values())observer.disconnect();this.iconQueue.clear();clearTimeout(this.iconTimer);this.iconTimer=null;}
  close(){
    this.rememberView();this.token++;this.state=null;this.dayChart.close();this.appChart.close();this.weekChart.close();this.monthChart.close();this.appWeekChart.close();this.stopIcons();
    for(const id of Object.values(roots))$(id).hidden=true;
  }
  appHeader(state,prefix){
    $(prefix+'Name').textContent=state.label||state.pkg;$(prefix+'Package').textContent=state.pkg.startsWith('__')?'':state.pkg;$(prefix+'Package').hidden=state.pkg.startsWith('__');this.paintIcon($(prefix+'Icon'),state.pkg,state.label||state.pkg);
  }
  async open(state){
    this.rememberView();const changed=!this.state||this.viewKey(this.state)!==this.viewKey(state);this.token++;this.state={...state};const current=this.state;this.hideTips();this.stopIcons();
    for(const [page,id]of Object.entries(roots))$(id).hidden=state.page!==page;
    if(state.page!=='usage-day')this.dayChart.close();if(state.page!=='usage-app')this.appChart.close();if(state.page!=='usage-week')this.weekChart.close();if(state.page!=='usage-month')this.monthChart.close();if(state.page!=='usage-app-week')this.appWeekChart.close();
    if(state.page==='usage-week')await this.renderWeek();
    else if(state.page==='usage-month')await this.monthChart.display(state.day);
    else if(state.page==='usage-app-week'){
      this.appHeader(state,'usageAppWeek');await this.appWeekChart.display(state.day,state.pkg,state.label||state.pkg);if(this.state!==current)return;this.loadIcon($('usageAppWeekIcon'),state.pkg);
    }else if(state.page==='usage-app'){
      this.appHeader(state,'usageApp');await this.appChart.display(state.day,state.pkg,state.label||state.pkg);if(this.state!==current)return;this.loadIcon($('usageAppIcon'),state.pkg);
    }else await this.renderDay();
    if(this.state!==current)return;
    if(state.page==='usage-day'&&this.hourSelections.has(state.day))this.dayChart.requestHourDetails(this.hourSelections.get(state.day));
    if(changed)$(scrolls[state.page]).scrollTop=this.viewScroll.get(this.viewKey(state))?.top||0;
  }
  async refresh(day){
    if(!this.state)return;
    if(this.state.page==='usage-week'){if(day>=this.weekChart.week?.start&&day<this.weekChart.week.end)await this.renderWeek();return;}
    if(this.state.page==='usage-month'){await this.monthChart.refresh(day);return;}
    if(this.state.page==='usage-app-week'){await this.appWeekChart.refresh(day);return;}
    if(this.state.day!==day)return;
    if(this.state.page==='usage-day')await this.renderDay();else await this.appChart.display(day,this.state.pkg,this.state.label||this.state.pkg);
  }
  prepareList(kind,key){
    let context=this.lists.get(kind);if(context?.key===key)return context;
    context={key,nodes:new Map(),rows:new Map(),fresh:true};this.lists.set(kind,context);$(lists[kind].rows).replaceChildren();$(lists[kind].status).textContent=kind==='week'?'正在读取本周应用汇总…':'正在读取当天应用汇总…';return context;
  }
  async renderDay(){
    const token=++this.token,key=this.viewKey(this.state),day=this.state.day;this.prepareList('day',key);
    try{
      const [data]=await Promise.all([this.getCache().getDailyApps(day),this.dayChart.display(day)]);
      if(token!==this.token||this.state?.page!=='usage-day'||this.viewKey(this.state)!==key)return;
      await this.renderRows(data.apps,'day',key,token);
    }catch{if(token===this.token)$('usageAppListStatus').textContent='当天应用汇总暂不可用';}
  }
  async renderWeek(){
    const token=++this.token,key=this.viewKey(this.state);this.prepareList('week',key);
    try{
      const data=await this.weekChart.display(this.state.day);
      if(token!==this.token||this.state?.page!=='usage-week'||this.viewKey(this.state)!==key)return;
      if(!data)throw Error('本周汇总暂不可用');await this.renderRows(data.apps??[],'week',key,token);
    }catch{if(token===this.token)$('usageWeekAppListStatus').textContent='本周应用汇总暂不可用';}
  }
  async renderRows(input,kind,key,token){
    const info=lists[kind],list=$(info.rows),context=this.lists.get(kind),observer=this.observers.get(kind),top=$(info.scroll).scrollTop;
    const rows=input.filter(row=>row.ms>=30000).sort((a,b)=>b.ms-a.ms||a.pkg.localeCompare(b.pkg));context.rows=new Map(rows.map(row=>[row.pkg,row]));observer.disconnect();
    $(info.status).textContent=rows.length?'':kind==='week'?'暂无已缓存的本周应用使用时长':'暂无已缓存的应用使用时长';
    const nodes=[];let cursor=0;
    await new Promise(resolve=>{
      const append=()=>{
        if(token!==this.token||this.state?.page!==info.page||this.viewKey(this.state)!==key){resolve();return;}
        const fragment=document.createDocumentFragment(),images=[];
        for(const item of rows.slice(cursor,cursor+20)){
          let row=context.nodes.get(item.pkg);
          if(!row){
            row=document.createElement('button');row.type='button';row.className='usage-app-row';row.dataset.usagePkg=item.pkg;
            const icon=document.createElement('span');icon.className='app-icon usage-app-icon';icon.dataset.pkg=item.pkg;this.paintIcon(icon,item.pkg,item.label);
            const text=document.createElement('span');text.className='usage-app-text';text.append(document.createElement('strong'),document.createElement('small'));
            const arrow=document.createElement('span');arrow.className='usage-app-arrow';arrow.textContent='›';arrow.setAttribute('aria-hidden','true');row.append(icon,text,arrow);context.nodes.set(item.pkg,row);
          }
          row.querySelector('strong').textContent=item.label;row.querySelector('small').textContent=roundedDuration(item.ms);nodes.push(row);images.push(row.querySelector('.usage-app-icon'));if(context.fresh)fragment.append(row);
        }
        if(context.fresh)list.append(fragment);for(const icon of images)observer.observe(icon);cursor+=20;
        if(cursor<rows.length){requestAnimationFrame(append);return;}
        let anchor=list.firstChild;for(const row of nodes){if(row===anchor)anchor=anchor.nextSibling;else list.insertBefore(row,anchor);}
        while(anchor){const next=anchor.nextSibling;anchor.remove();anchor=next;}
        for(const pkg of context.nodes.keys())if(!context.rows.has(pkg))context.nodes.delete(pkg);
        if(!context.fresh)$(info.scroll).scrollTop=top;context.fresh=false;resolve();
      };append();
    });
  }
  paintIcon(node,pkg,label){
    node.replaceChildren();node.dataset.pkg=pkg;const source=this.icons.get(pkg);
    if(source){const image=document.createElement('img');image.src=source;image.alt='';image.decoding='async';image.draggable=false;node.append(image);}
    else{const fallback=document.createElement('span');fallback.className='app-icon-fallback';fallback.textContent=Array.from(label||'?')[0];node.append(fallback);}
  }
  loadIcon(node,pkg){
    if(!pkg||pkg.startsWith('__'))return;if(this.icons.has(pkg)){this.paintIcon(node,pkg);return;}
    if(!this.iconQueue.has(pkg))this.iconQueue.set(pkg,new Set());this.iconQueue.get(pkg).add(node);if(!this.iconTimer)this.iconTimer=setTimeout(()=>this.flushIcons(),40);
  }
  flushIcons(){
    this.iconTimer=null;if(!this.state||document.hidden)return;
    const packages=[...this.iconQueue.keys()].slice(0,6),images=readAppIcons(packages);
    for(const pkg of packages){const nodes=this.iconQueue.get(pkg);this.iconQueue.delete(pkg);const source=images[pkg];if(typeof source==='string'&&source.startsWith('data:image/')){this.icons.set(pkg,source);while(this.icons.size>40)this.icons.delete(this.icons.keys().next().value);for(const node of nodes)if(node.isConnected&&node.dataset.pkg===pkg)this.paintIcon(node,pkg);}}
    if(this.iconQueue.size)this.iconTimer=setTimeout(()=>this.flushIcons(),40);
  }
}
