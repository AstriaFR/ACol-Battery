import{Chart,valid,sampleText}from'./chart.js?v=acol-2.0';
import{fullScreen}from'./root-bridge.js?v=acol-2.0';
import{curveColor}from'./curve-colors.js?v=acol-2.0';
import{fpsLimit,cpuLimit,frequencyLimit}from'./performance-scales.js?v=acol-2.0';
const $=id=>document.getElementById(id),selectionKey='acol.performance.selection';
class PerformanceChart extends Chart{
  constructor(...args){super(...args);this.metrics=[];this.maximum=new Map();this.frequencyRanges=new Map();this.cpuMax=0;this.cpuObservedMax=0;this.kind='performance';this.fast=true;this.cellCount=0;}
  signals(){return this.metrics??[];}
  axisMaximum(){return 110;}
  preparePoints(points){return points;}
  scaleFor(s){
    const full=s.key==='temperature'?100:s.unit==='%'?100:s.cpuFrequency?(this.cpuMax||Math.max(1,this.cpuObservedMax)):
      s.key==='fps'?fpsLimit(this.maximum.get(s.key)??0):this.frequencyRanges.get(s.key)??s.limit??1;
    return{base:0,full,min:0,label:`${sampleText(full,1)} ${s.unit}`};
  }
  ingest(points){
    for(const p of points)for(const s of this.metrics){
      const value=p[s.index];if(!valid(value))continue;
      this.maximum.set(s.key,Math.max(this.maximum.get(s.key)??0,value));
      if(s.cpuFrequency)this.cpuObservedMax=Math.max(this.cpuObservedMax,value);
      else if(s.unit==='MHz')this.frequencyRanges.set(s.key,frequencyLimit(this.frequencyRanges.get(s.key)??s.limit??1,value));
    }
  }
}
export class PerformancePanel{
  constructor(api){
    this.api=api;this.id=null;this.token=0;this.points=[];this.cursor=0;this.follow=true;this.busy=false;this.expanded=false;
    this.chart=new PerformanceChart($('performancePlot'),$('performanceTooltip'),()=>{this.follow=false;this.renderFollow();});
    this.chart.onInteract=()=>{this.follow=false;this.renderFollow();};
    $('performanceReset').onclick=()=>{this.follow=true;this.render(true);};
    $('performanceFollow').onclick=()=>{this.follow=!this.follow;this.render(true);};
    $('performanceFullscreen').onclick=()=>this.fullscreen(true);
    $('performanceExit').onclick=()=>this.fullscreen(false);
    $('performanceSources').onclick=()=>{$('performanceMethod').hidden=!$('performanceMethod').hidden;};
    document.addEventListener('visibilitychange',()=>{clearTimeout(this.timer);if(!document.hidden&&this.id)this.poll(this.token);});
    window.addEventListener('pagehide',()=>clearTimeout(this.timer));
    document.addEventListener('fullscreenchange',()=>{if(!document.fullscreenElement&&this.expanded)this.fullscreen(false);});
    document.addEventListener('keydown',e=>{if(e.key==='Escape'&&this.expanded)this.fullscreen(false);});
    for(const target of[window,window.visualViewport].filter(Boolean)){
      target.addEventListener('resize',()=>this.layout());if(target===window.visualViewport)target.addEventListener('scroll',()=>this.layout());
    }
  }
  open(record){
    if(record.kind!=='discharge'||!(record.fast_seconds>0)){this.close();return;}
    this.active=!!record.active;this.interval=Math.max(1000,(record.performance_seconds||record.fast_seconds)*1000);
    if(this.id===record.id)return;
    this.close();this.id=record.id;this.points=[];this.cursor=0;this.follow=true;this.end=1;this.metaText='';
    this.chart.metrics=[];this.chart.maximum.clear();this.chart.frequencyRanges.clear();this.chart.cpuMax=0;this.chart.cpuObservedMax=0;this.chart.axisKey=null;this.chart.enabled.clear();
    this.chart.set([],1,null,{kind:'performance',fast:true,cellCount:0});
    $('performanceLegend').replaceChildren();$('performanceMethod').hidden=true;
    $('performanceHint').textContent='正在读取性能数据…';$('performancePanel').hidden=false;
    this.poll(this.token);
  }
  close(){
    clearTimeout(this.timer);++this.token;this.id=null;
    if(this.expanded)this.fullscreen(false);
    $('performancePanel').hidden=true;
  }
  async poll(token){
    if(token!==this.token||!this.id||document.hidden)return;
    if(this.busy){this.timer=setTimeout(()=>this.poll(token),300);return;}
    this.busy=true;const started=performance.now();let changed=false;
    try{
      let data;
      do{
        data=await this.api('performance',this.id,this.cursor);
        if(token!==this.token)return;
        this.active=!!data.active;this.end=Math.max(this.end,data.end??1);if(data.intervalMs>0)this.interval=Math.max(1000,data.intervalMs);
        if(data.meta&&JSON.stringify(data.meta)!==this.metaText){this.configure(data.meta);changed=true;}
        const fresh=(data.points??[]).filter(p=>!this.points.length||p[0]>this.points.at(-1)[0]);
        if(fresh.length){this.points.push(...fresh);this.chart.ingest(fresh);changed=true;}
        this.cursor=data.cursor;
      }while(data.more);
      if(changed||!this.points.length)this.render();
      $('performanceHint').textContent=this.points.length?
        `每 ${Math.round(this.interval/1000)} 秒采样 · CPU 使用率取相邻区间，GPU 取驱动当前值，温度取 CPU/GPU 最高值 · 长按图例显示实际刻度。`:
        this.active?'等待下一次游戏模式采样；不可用指标留空。':'此记录没有性能采样数据。';
    }catch(e){if(token===this.token)$('performanceHint').textContent=e.message;}
    finally{
      this.busy=false;
      if(token===this.token&&this.id&&!document.hidden&&this.active)this.timer=setTimeout(()=>this.poll(token),Math.max(500,this.interval-(performance.now()-started)));
    }
  }
  configure(meta){
    this.metaText=JSON.stringify(meta);
    this.chart.cpuMax=cpuLimit(meta.clusters);
    const metrics=[
      {key:'fps',index:1,name:'帧率',axis:'帧率',unit:'FPS',color:curveColor(0),digits:1,limit:60},
      {key:'gpuFreq',index:2,name:'GPU 频率',axis:'GPU 频率',unit:'MHz',color:curveColor(1),digits:1,limit:meta.gpuMaxMHz||1},
      {key:'gpuUsage',index:3,name:'GPU 使用率',axis:'GPU 使用率',unit:'%',color:curveColor(2),digits:1,limit:100}
    ];
    (meta.clusters??[]).forEach((c,i)=>{
      metrics.push({key:`cpu${i}Freq`,index:8+i*2,name:`${c.label} 频率`,axis:`${c.label} 频率`,unit:'MHz',color:curveColor(3+i*2),digits:1,cpuFrequency:true,limit:this.chart.cpuMax||1});
      metrics.push({key:`cpu${i}Usage`,index:9+i*2,name:`${c.label} 使用率`,axis:`${c.label} 使用率`,unit:'%',color:curveColor(4+i*2),digits:1,limit:100});
    });
    if(Number.isInteger(meta.temperatureIndex))metrics.push({key:'temperature',index:meta.temperatureIndex,name:'最高温度',axis:'最高温度',unit:'℃',color:curveColor(3+(meta.clusters??[]).length*2),digits:1,limit:100});
    this.chart.metrics=metrics;this.chart.maximum.clear();this.chart.frequencyRanges.clear();this.chart.cpuObservedMax=0;this.chart.ingest(this.points);
    let saved;try{saved=JSON.parse(localStorage.getItem(selectionKey));}catch{}
    this.chart.enabled=new Set(Array.isArray(saved)?saved:metrics.filter(s=>s.key.endsWith('Freq')).map(s=>s.key));
    this.legend();
    const sources=[meta.method,'CPU 频率：'+(meta.clusters??[]).map(c=>c.frequencySource||'不可用').join('；'),
      'GPU 频率：'+(meta.gpuFrequencySource||'不可用'),'GPU 负载：'+(meta.gpuUsageSource||'不可用'),
      'CPU/GPU 温度：'+((meta.temperatureSources??[]).map(s=>`${s.label}（${s.path}）`).join('；')||'未发现可读取的温度节点'),
      '帧率：SurfaceFlinger 前台图层实际呈现时间；静止画面可为 0，不代表屏幕刷新率。'];
    $('performanceMethod').textContent=sources.join('\n');
  }
  refreshTheme(){
    if(!this.id)return;
    this.chart.metrics=this.chart.metrics.map((s,i)=>({...s,color:curveColor(i)}));
    this.legend();this.render();
  }
  remember(){try{localStorage.setItem(selectionKey,JSON.stringify([...this.chart.enabled]));}catch{}}
  legend(){
    const host=$('performanceLegend');host.replaceChildren();
    for(const s of this.chart.metrics){
      const label=document.createElement('label');label.style.setProperty('--series',s.color);label.dataset.series=s.key;
      const input=document.createElement('input');input.type='checkbox';input.checked=this.chart.enabled.has(s.key);
      const name=document.createElement('span');name.className='performance-name';name.textContent=s.name;
      const scale=document.createElement('span');scale.className='legend-scale';
      input.onchange=()=>{
        input.checked?this.chart.enabled.add(s.key):this.chart.enabled.delete(s.key);
        if(!input.checked&&this.chart.axisKey===s.key)this.chart.axisKey=null;
        this.remember();this.chart.clearInspect();this.render();
      };
      let timer,start,held=false,moved=false;
      label.addEventListener('pointerdown',e=>{
        if(e.button!==0||!e.isPrimary)return;held=moved=false;start=[e.clientX,e.clientY];
        timer=setTimeout(()=>{
          if(!this.id||!label.isConnected)return;held=true;this.chart.axisKey=this.chart.axisKey===s.key?null:s.key;
          this.chart.enabled.add(s.key);input.checked=true;this.remember();this.chart.clearInspect();this.render();
          try{navigator.vibrate?.(10);}catch{}
        },500);
      });
      label.addEventListener('pointermove',e=>{if(start&&Math.hypot(e.clientX-start[0],e.clientY-start[1])>8){moved=true;clearTimeout(timer);}});
      const end=()=>{clearTimeout(timer);start=null;if(held||moved)setTimeout(()=>{held=moved=false;},400);};
      for(const event of['pointerup','pointercancel','pointerleave'])label.addEventListener(event,end);
      label.addEventListener('click',e=>{if(held||moved){e.preventDefault();e.stopImmediatePropagation();}},{capture:true});
      label.append(input,name,scale);host.append(label);
    }
  }
  renderFollow(){
    $('performanceFollow').hidden=!this.active;$('performanceFollow').textContent=this.follow?'实时跟随':'回到实时';
    $('performanceFollow').classList.toggle('selected',this.follow);
  }
  render(reset=false){
    const c=this.chart,total=Math.max(1,this.end,this.points.at(-1)?.[0]??0);
    let view=c.window;
    if(reset)view=[0,total];
    else if(this.follow){const span=c.window[1]-c.window[0];view=c.window[0]===0&&c.window[1]>=c.total-1?[0,total]:[Math.max(0,total-span),total];}
    c.set(this.points,total,view,{kind:'performance',fast:true,cellCount:0,live:true});
    for(const s of c.metrics){
      const label=$('performanceLegend').querySelector(`[data-series="${s.key}"]`);if(!label)continue;
      label.classList.toggle('axis-active',c.axisKey===s.key);label.querySelector('.legend-scale').textContent=c.scaleFor(s).label;
    }
    this.renderFollow();
  }
  layout(){
    if(!this.expanded)return;const p=$('performancePanel'),v=window.visualViewport;
    const w=v?.width??innerWidth,h=v?.height??innerHeight,rotate=h>w;
    p.classList.toggle('rotate',rotate);
    p.style.setProperty('--full-width',`${rotate?h:w}px`);p.style.setProperty('--full-height',`${rotate?w:h}px`);
    p.style.setProperty('--full-left',`${(v?.offsetLeft??0)+w/2}px`);p.style.setProperty('--full-top',`${(v?.offsetTop??0)+h/2}px`);
    requestAnimationFrame(()=>this.chart.draw());
  }
  async fullscreen(enable){
    if(this.expanded===enable)return;this.expanded=enable;const panel=$('performancePanel');
    if(enable){this.anchor=document.createComment('performance chart');panel.replaceWith(this.anchor);document.body.append(panel);}
    panel.classList.toggle('expanded',enable);document.body.classList.toggle('chartExpanded',enable);
    $('performanceFullscreen').hidden=enable;$('performanceExit').hidden=!enable;
    try{fullScreen(enable);}catch{}
    if(enable){
      this.layout();try{await panel.requestFullscreen();await screen.orientation.lock('landscape');}catch{}
      if(this.expanded)this.layout();
    }else{
      panel.classList.remove('rotate');try{screen.orientation.unlock();if(document.fullscreenElement)await document.exitFullscreen();}catch{}
      if(this.anchor){this.anchor.replaceWith(panel);this.anchor=null;}
      for(const key of['--full-width','--full-height','--full-left','--full-top'])panel.style.removeProperty(key);
    }
    this.chart.draw();
  }
}
