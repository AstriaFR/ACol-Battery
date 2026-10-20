const storageKey='acol.theme.preference';
function readPreference(){
  try{
    const value=JSON.parse(localStorage.getItem(storageKey));
    if(value?.step==='reverse'||value?.step==='same')return{step:value.step,dark:!!value.dark};
  }catch{}
  return{step:'auto',dark:false};
}
export class ThemeController{
  constructor(onChange=()=>{}){
    this.onChange=onChange;this.preference=readPreference();this.media=matchMedia('(prefers-color-scheme: dark)');
    this.media.addEventListener?.('change',()=>this.apply());this.apply();
  }
  get dark(){return this.preference.step==='auto'?this.media.matches:this.preference.dark;}
  apply(){
    const dark=this.dark,root=document.documentElement;root.dataset.theme=dark?'dark':'light';root.style.colorScheme=dark?'dark':'light';
    const meta=document.querySelector('meta[name=theme-color]');if(meta)meta.content=dark?'#000000':'#f6f8fb';
    try{window.AcoNative?.setDarkTheme?.(dark);}catch{}
    this.onChange({step:this.preference.step,dark});
  }
  cycle(){
    const systemDark=this.media.matches;
    if(this.preference.step==='auto')this.preference={step:'reverse',dark:!systemDark};
    else if(this.preference.step==='reverse')this.preference={step:'same',dark:systemDark};
    else this.preference={step:'auto',dark:false};
    try{localStorage.setItem(storageKey,JSON.stringify(this.preference));}catch{}
    this.apply();
  }
  static applyStored(){return new ThemeController();}
}
