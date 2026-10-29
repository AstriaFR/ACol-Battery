const storageKey='acol.theme.preference';
function readPreference(){
  try{
    const value=JSON.parse(localStorage.getItem(storageKey));
    if(value?.step==='reverse'||value?.step==='same')return{step:value.step,dark:!!value.dark};
  }catch{}
  return{step:'auto',dark:false};
}
export class ThemeController{
  constructor(onChange=()=>{},{followParent=false}={}){
    this.onChange=onChange;this.preference=readPreference();this.media=matchMedia('(prefers-color-scheme: dark)');
    this.generation=0;
    if(followParent)try{if(window.parent!==window&&window.parent.document)this.parent=window.parent;}catch{}
    this.parent?.addEventListener('aco-theme-change',()=>this.apply());
    window.addEventListener('storage',event=>{if(event.key===storageKey){this.preference=readPreference();this.apply();}});
    this.media.addEventListener?.('change',()=>this.apply());
    window.addEventListener('aco-system-theme',()=>this.apply());this.apply();
  }
  get systemDark(){
    try{const dark=window.AcoNative?.isSystemDark?.();if(typeof dark==='boolean')return dark;}catch{}
    return this.media.matches;
  }
  get dark(){return this.preference.step==='auto'?this.systemDark:this.preference.dark;}
  apply(){
    const inherited=this.parent?.__acoThemeState;
    const dark=this.parent?(inherited?.dark??(this.parent.document.documentElement.dataset.theme==='dark')):this.dark;
    const state={step:inherited?.step??this.preference.step,dark},signature=`${state.step}:${dark}`;
    // Display and media callbacks may report the same change more than once.
    if(this.requested===signature)return;
    this.requested=signature;const generation=++this.generation;
    this.transition?.skipTransition();
    const synchronizeBars=()=>{
      // An embedded frame never owns native bars. Ignore superseded presentations.
      if(this.parent||generation!==this.generation)return;
      try{window.AcoNative?.setDarkTheme?.(dark);}catch{}
    };
    const commit=()=>{
      if(generation!==this.generation)return;
      const darkChanged=this.state?.dark!==dark,root=document.documentElement;
      root.dataset.themeChanging='true';root.dataset.theme=dark?'dark':'light';root.style.colorScheme=dark?'dark':'light';
      const meta=document.querySelector('meta[name=theme-color]');if(meta)meta.content=dark?'#000000':'#f6f8fb';
      this.state=state;window.__acoThemeState=state;
      window.dispatchEvent?.(new CustomEvent('aco-theme-change',{detail:state}));
      this.onChange({...state,darkChanged});
      const finish=()=>{if(generation===this.generation)delete root.dataset.themeChanging;};
      if(typeof requestAnimationFrame==='function')requestAnimationFrame(()=>requestAnimationFrame(finish));else finish();
    };
    // Swap a complete viewport snapshot, without a crossfade or progressive tile repaint.
    // Initial render, embedded frames and older WebViews update synchronously.
    if(this.state&&this.state.dark!==dark&&!this.parent&&!document.hidden&&typeof document.startViewTransition==='function'){
      try{
        const transition=document.startViewTransition(commit);this.transition=transition;
        // Updating DOM does not mean the old snapshot has left the screen yet.
        // On a skipped/unsupported transition, the native visual-state fence still applies.
        transition.ready.then(synchronizeBars,synchronizeBars);
        transition.finished.catch(()=>{});
      }catch{commit();synchronizeBars();}
    }else{commit();synchronizeBars();}
  }
  cycle(){
    const systemDark=this.systemDark;
    if(this.preference.step==='auto')this.preference={step:'reverse',dark:!systemDark};
    else if(this.preference.step==='reverse')this.preference={step:'same',dark:systemDark};
    else this.preference={step:'auto',dark:false};
    try{localStorage.setItem(storageKey,JSON.stringify(this.preference));}catch{}
    this.apply();
  }
  reset(){
    this.preference={step:'auto',dark:false};
    localStorage.removeItem(storageKey);
    this.apply();
  }
  static applyStored(options){return new ThemeController(()=>{},options);}
}
