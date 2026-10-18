const finite=v=>typeof v==='number'&&Number.isFinite(v);
const number=(v,unit='',digits=0)=>finite(v)?`${v.toLocaleString('zh-CN',{maximumFractionDigits:digits})}${unit?' '+unit:''}`:'—';
const capacityRatio=(value,design)=>number(value,'mAh')+(finite(value)&&value>=0&&finite(design)&&design>0?` (${(value/design*100).toFixed(1)}%)`:'');
export class BatteryInfoPanel{
  constructor(){
    this.panel=document.getElementById('batteryInfoPanel');this.active=false;this.loaded=false;this.slowAt=null;
    this.explanation=document.getElementById('batteryEstimateExplanation');
    this.explanationPage=0;this.swipe=null;this.pages=document.getElementById('batteryExplanationPages');
    document.getElementById('previousBatteryExplanation').onclick=()=>this.setExplanationPage(this.explanationPage-1);
    document.getElementById('nextBatteryExplanation').onclick=()=>this.setExplanationPage(this.explanationPage+1);
    this.pages.addEventListener('pointerdown',e=>{if(e.isPrimary&&e.button===0)this.swipe={id:e.pointerId,x:e.clientX,y:e.clientY};});
    this.pages.addEventListener('pointermove',e=>{const s=this.swipe;if(s?.id===e.pointerId&&Math.abs(e.clientX-s.x)>10&&Math.abs(e.clientX-s.x)>Math.abs(e.clientY-s.y)*1.25){if(e.cancelable)e.preventDefault();this.pages.setPointerCapture(e.pointerId);}});
    this.pages.addEventListener('pointerup',e=>{const s=this.swipe;this.swipe=null;if(s?.id!==e.pointerId)return;const dx=e.clientX-s.x,dy=e.clientY-s.y;if(Math.abs(dx)>=40&&Math.abs(dx)>Math.abs(dy)*1.25)this.setExplanationPage(this.explanationPage+(dx<0?1:-1));});
    this.pages.addEventListener('pointercancel',()=>{this.swipe=null;});
    document.getElementById('batteryEstimateInfo').onclick=()=>this.showExplanation();
    document.getElementById('closeBatteryEstimate').onclick=()=>this.hideExplanation();
    this.explanation.addEventListener('cancel',e=>{e.preventDefault();this.hideExplanation();});
    this.explanation.addEventListener('click',e=>{const r=this.explanation.getBoundingClientRect();if(e.target===this.explanation&&(e.clientX<r.left||e.clientX>r.right||e.clientY<r.top||e.clientY>r.bottom))this.hideExplanation();});
  }
  showExplanation(push=true){if(!this.active||this.explanation.open)return;if(push)history.pushState({page:'battery-info',explanation:true,explanationPage:0},'',`${location.pathname}${location.search}#battery-info-explanation`);this.setExplanationPage(push?0:history.state?.explanationPage??0);this.explanation.showModal();}
  setExplanationPage(page){
    this.explanationPage=Math.max(0,Math.min(1,page));this.pages.dataset.page=String(this.explanationPage);
    for(const section of this.pages.querySelectorAll('[data-explanation-page]'))section.hidden=Number(section.dataset.explanationPage)!==this.explanationPage;
    this.pages.scrollTop=0;document.getElementById('batteryEstimateTitle').textContent=this.explanationPage===0?'两个原始值怎么算？':'截止电压与补偿关系';
    document.getElementById('batteryExplanationCounter').textContent=`${this.explanationPage+1} / 2`;
    document.getElementById('previousBatteryExplanation').disabled=this.explanationPage===0;
    document.getElementById('nextBatteryExplanation').disabled=this.explanationPage===1;
    if(history.state?.explanation)history.replaceState({...history.state,explanationPage:this.explanationPage},'');
  }
  renderCoefficients(info){
    const body=document.getElementById('batteryCoefficientRows');body.replaceChildren();
    const rows=(Array.isArray(info?.coefficients)?info.coefficients:[]).filter(row=>row&&['cutoffMv','fccOffset','sohOffset'].every(key=>finite(row[key]))).slice().sort((a,b)=>a.cutoffMv-b.cutoffMv);
    const selected=finite(info?.cutoffMv)&&info.cutoffMv>=3000?rows.filter(row=>row.cutoffMv<=info.cutoffMv).at(-1):null;
    for(const row of rows){const tr=document.createElement('tr');if(row===selected)tr.className='current-coefficient';for(const key of['cutoffMv','fccOffset','sohOffset']){const td=document.createElement('td');td.textContent=number(row[key]);tr.append(td);}body.append(tr);}
    document.getElementById('batteryCoefficientTable').hidden=!rows.length;
    const empty=document.getElementById('batteryCoefficientEmpty');empty.hidden=!!rows.length;empty.textContent=info?'未读到本机的补偿关系表。':'正在读取补偿关系表…';
  }
  hideExplanation(){if(history.state?.explanation)history.back();else this.closeExplanation();}
  closeExplanation(){if(this.explanation.open)this.explanation.close();}
  handleHistory(state){if(state?.explanation)this.showExplanation(false);else this.closeExplanation();}
  field(key,value){this.panel.querySelector(`[data-info="${key}"]`).textContent=value;}
  open(){this.active=true;this.loaded=false;this.slowAt=null;this.panel.hidden=false;this.renderCoefficients(null);for(const node of this.panel.querySelectorAll('[data-info]')){node.textContent='—';node.classList.remove('battery-near-actual');}this.error('');}
  close(){this.closeExplanation();this.active=false;this.panel.hidden=true;}
  error(text){const box=document.getElementById('batteryInfoError');box.textContent=text;box.hidden=!text;}
  update(packet){
    if(!this.active)return;this.error('');const reading=packet.status?.reading??{},info=packet.batteryInfo;
    this.panel.querySelector('[data-info-label=voltage]').textContent=reading.voltageSource==='android'?'电池电压（系统电压）':'电池电压（bcc_parms）';
    const dual=reading.cellCount===2&&finite(reading.cell1)&&finite(reading.cell2);
    this.field('voltage',dual?`${(reading.cell1/1000).toFixed(3)} / ${(reading.cell2/1000).toFixed(3)} V`:finite(reading.voltage)?`${(reading.voltage/1000).toFixed(3)} V`:'—');
    const slowElapsed=reading.slowElapsed??reading.elapsed;
    if(this.slowAt===null||finite(slowElapsed)&&slowElapsed-this.slowAt>=10000){
      this.field('temp',finite(reading.temp)?`${reading.temp.toFixed(1)} ℃`:'—');this.field('rm',number(reading.rm,'mAh'));this.slowAt=slowElapsed??null;
    }
    if(info&&!this.loaded){
      for(const key of['cycles','fcc','rawFcc','soh','rawSoh','qmax','cutoffMv','designCapacity'])this.field(key,number(info[key],['fcc','rawFcc','qmax','designCapacity'].includes(key)?'mAh':['soh','rawSoh'].includes(key)?'%':key==='cutoffMv'?'mV':'',key==='rawSoh'?2:0));
      for(const key of['fcc','rawFcc','qmax'])this.field(key,capacityRatio(info[key],info.designCapacity));
      const cutoffKnown=finite(info.cutoffMv)&&info.cutoffMv>0,noCompensation=cutoffKnown&&info.cutoffMv<3000;
      if(noCompensation)for(const key of['rawFcc','rawSoh'])this.field(key,'——');
      for(const key of['fcc','rawFcc','rawSoh']){
        const nearActual=cutoffKnown&&(noCompensation?key==='fcc':key!=='fcc')&&finite(info[key])&&info[key]>0;
        this.panel.querySelector(`[data-info="${key}"]`).classList.toggle('battery-near-actual',nearActual);
      }
      this.renderCoefficients(info);
      for(const key of['serial','manufactureDate','batteryType'])this.field(key,info[key]||'—');this.loaded=true;
    }
  }
}
