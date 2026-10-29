// A passive viewport-edge indicator: it never reserves width or intercepts swipes.
export function scrollThumb(viewHeight,contentHeight,scrollTop,trackHeight){
  if(![viewHeight,contentHeight,scrollTop,trackHeight].every(Number.isFinite)||viewHeight<=0||trackHeight<=0||contentHeight-viewHeight<=1)return null;
  const height=Math.min(trackHeight,Math.max(18,trackHeight*viewHeight/contentHeight));
  const progress=Math.max(0,Math.min(1,scrollTop/(contentHeight-viewHeight)));
  return{height,offset:(trackHeight-height)*progress};
}

export class EdgeScrollbar{
  constructor(){
    this.scrollers=[document.querySelector('#recordsPage .record-scroll'),document.querySelector('#settingsPanel .page-scroll'),document.getElementById('detailScroll'),document.getElementById('usageDayScroll'),document.getElementById('usageAppScroll'),document.getElementById('usageWeekScroll'),document.getElementById('usageMonthScroll'),document.getElementById('usageAppWeekScroll')].filter(Boolean);
    this.track=document.createElement('div');this.track.className='edge-scrollbar';this.track.hidden=true;this.track.setAttribute('aria-hidden','true');
    this.thumb=document.createElement('div');this.thumb.className='edge-scrollbar-thumb';this.track.append(this.thumb);document.body.append(this.track);
    this.frame=0;this.schedule=()=>{
      if(this.frame||document.hidden)return;
      this.frame=requestAnimationFrame(()=>{this.frame=0;this.render();});
    };
    for(const scroller of this.scrollers)scroller.addEventListener('scroll',this.schedule,{passive:true});
    this.resize=new ResizeObserver(this.schedule);
    for(const scroller of this.scrollers){
      this.resize.observe(scroller);
      // Only direct content boxes are watched. Live text updates which do not
      // change layout cause no observer work and require no polling timer.
      for(const child of scroller.children)this.resize.observe(child);
    }
    this.visibility=new MutationObserver(this.schedule);
    for(const node of [document.body,document.getElementById('appShell'),document.getElementById('recordsPage'),document.getElementById('settingsPanel'),document.getElementById('detail'),document.getElementById('usageDetail'),document.getElementById('usageAppDetail'),document.getElementById('usageWeekDetail'),document.getElementById('usageMonthDetail'),document.getElementById('usageAppWeekDetail')])
      if(node)this.visibility.observe(node,{attributes:true,attributeFilter:['hidden','class']});
    window.addEventListener('resize',this.schedule);window.addEventListener('pageshow',this.schedule);document.addEventListener('visibilitychange',this.schedule);
    this.schedule();
  }
  render(){
    if(document.hidden||document.body.classList.contains('chartExpanded')){this.track.hidden=true;return;}
    const scroller=this.scrollers.find(node=>!node.closest('[hidden]')&&node.getClientRects().length&&node.clientHeight>0);
    if(!scroller){this.track.hidden=true;return;}
    const bounds=scroller.getBoundingClientRect(),top=Math.max(0,bounds.top)+2,bottom=Math.min(window.innerHeight,bounds.bottom)-2;
    const trackHeight=Math.max(0,bottom-top),thumb=scrollThumb(scroller.clientHeight,scroller.scrollHeight,scroller.scrollTop,trackHeight);
    if(!thumb){this.track.hidden=true;return;}
    this.track.style.top=`${top}px`;this.track.style.height=`${trackHeight}px`;
    this.thumb.style.height=`${thumb.height}px`;this.thumb.style.transform=`translateY(${thumb.offset}px)`;this.track.hidden=false;
  }
  destroy(){
    cancelAnimationFrame(this.frame);this.resize.disconnect();this.visibility.disconnect();
    for(const scroller of this.scrollers)scroller.removeEventListener('scroll',this.schedule);
    window.removeEventListener('resize',this.schedule);window.removeEventListener('pageshow',this.schedule);document.removeEventListener('visibilitychange',this.schedule);this.track.remove();
  }
}
