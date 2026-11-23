const finite=v=>typeof v==='number'&&Number.isFinite(v);
const number=(v,unit='',digits=0)=>finite(v)?`${v.toLocaleString('zh-CN',{maximumFractionDigits:digits})}${unit?' '+unit:''}`:'—';
function currentText(value){
  if(!finite(value))return '—';
  return Math.abs(value)>=1000?`${(value/1000).toFixed(2)} A`:number(Math.trunc(value),'mA');
}
const watts=(millivolts,milliamps)=>finite(millivolts)&&millivolts>0&&finite(milliamps)?millivolts*milliamps/1_000_000:null;
function wattsText(value){
  if(!finite(value))return '—';
  return `${Math.abs(value)<1?value.toFixed(2):value.toPrecision(3)}W`;
}
const capacityRatio=(value,design)=>number(value,'mAh')+(finite(value)&&value>=0&&finite(design)&&design>0?` (${(value/design*100).toFixed(1)}%)`:'');
export function rawFccBoundText(limit){
  return finite(limit)&&limit>0?`＞${number(limit,'mAh')}`:null;
}
export function validCoefficientRows(coefficients){
  return (Array.isArray(coefficients)?coefficients:[])
    .filter(row=>row&&['cutoffMv','fccOffset','sohOffset'].every(key=>finite(row[key])))
    .slice().sort((a,b)=>a.cutoffMv-b.cutoffMv);
}
export function selectCoefficientRow(rows,cutoffMv){
  return finite(cutoffMv)&&cutoffMv>0?(rows.filter(row=>row.cutoffMv>0&&row.cutoffMv<=cutoffMv).at(-1)??null):null;
}
export function isBatteryUncompensated(info){
  const cutoff=info?.cutoffMv;
  if(!finite(cutoff)||cutoff<=0)return false;
  if(typeof info?.noCompensation==='boolean')return info.noCompensation;
  const rows=validCoefficientRows(info?.coefficients);
  if(!rows.length)return false;
  const selected=selectCoefficientRow(rows,cutoff);
  return !selected||(selected.fccOffset===0&&selected.sohOffset===0);
}
export function lockedCapacityText(qmax,capacity,fccAbove){
  if(!finite(qmax)||qmax<=0)return '—';
  if(finite(fccAbove)&&fccAbove>0){
    const limit=qmax-fccAbove;
    if(limit>0)return `＜${number(limit,'mAh')}`;
    capacity=0;
  }
  if(!finite(capacity))return '—';
  capacity=Math.max(0,capacity);
  const percent=(capacity/qmax*100).toFixed(1);
  return `${number(capacity,'mAh')} (${percent==='0.0'?'':'-'}${percent}%)`;
}
export class BatteryInfoPanel{
  constructor(){
    this.panel=document.getElementById('batteryInfoPanel');this.active=false;this.loaded=false;
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
  showExplanation(push=true){
    if(!this.active||this.explanation.open)return;
    const scroller=this.panel.querySelector('.page-scroll'),scrollTop=scroller?.scrollTop??0;
    if(push)history.pushState({page:'battery-info',explanation:true,explanationPage:0},'',`${location.pathname}${location.search}#battery-info-explanation`);
    this.setExplanationPage(push?0:history.state?.explanationPage??0);
    this.explanation.showModal();
    this.explanation.focus({preventScroll:true});
    const restoreScroll=()=>{if(scroller)scroller.scrollTop=scrollTop;};
    restoreScroll();requestAnimationFrame(restoreScroll);
  }
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
    const rows=validCoefficientRows(info?.coefficients),selected=selectCoefficientRow(rows,info?.cutoffMv);
    for(const row of rows){const tr=document.createElement('tr');if(row===selected)tr.className='current-coefficient';for(const key of['cutoffMv','fccOffset','sohOffset']){const td=document.createElement('td');td.textContent=number(row[key]);tr.append(td);}body.append(tr);}
    document.getElementById('batteryCoefficientTable').hidden=!rows.length;
    const empty=document.getElementById('batteryCoefficientEmpty');empty.hidden=!!rows.length;empty.textContent=info?'未读到本机的补偿关系表。':'正在读取补偿关系表…';
  }
  hideExplanation(){if(history.state?.explanation)history.back();else this.closeExplanation();}
  closeExplanation(){if(this.explanation.open)this.explanation.close();}
  handleHistory(state){if(state?.explanation)this.showExplanation(false);else this.closeExplanation();}
  field(key,value){this.panel.querySelector(`[data-info="${key}"]`).textContent=value;}
  open(){this.active=true;this.loaded=false;this.panel.hidden=false;this.renderCoefficients(null);for(const node of this.panel.querySelectorAll('[data-info]')){node.textContent='—';node.classList.remove('battery-near-actual');}this.error('');}
  close(){this.closeExplanation();this.active=false;this.panel.hidden=true;}
  error(text){const box=document.getElementById('batteryInfoError');box.textContent=text;box.hidden=!text;}
  update(packet){
    if(!this.active)return;this.error('');const reading=packet.status?.reading??{},info=packet.batteryInfo;
    const capacitySource=reading.rmSource==='android'?'CHARGE_COUNTER':reading.rmSource==='charge_now'||reading.rmSource==='battery_rm'?reading.rmSource:'';
    this.panel.querySelector('[data-info-label=rm]').textContent=capacitySource?`当前剩余电量（${capacitySource}）`:'当前剩余电量';
    this.panel.querySelector('[data-info-label=voltage]').textContent=reading.voltageSource==='android'?'电池电压（系统电压）':'电池电压（bcc_parms）';
    const dual=reading.cellCount===2&&finite(reading.cell1)&&finite(reading.cell2);
    if(dual){
      const totalVoltage=(reading.cell1+reading.cell2)/1000;
      this.field('voltage',`${(reading.cell1/1000).toFixed(3)} / ${(reading.cell2/1000).toFixed(3)} V\u2003${totalVoltage.toFixed(3)} V`);
    }else this.field('voltage',finite(reading.voltage)?`${(reading.voltage/1000).toFixed(3)} V`:'—');
    const current=reading.current;
    const currentSource=reading.currentSource==='current_now'?'current_now':reading.currentSource==='android'?'CURRENT_NOW':'bcc_parms';
    this.panel.querySelector('[data-info-label=batteryCurrent]').textContent=`电池电流（${currentSource}）`;
    this.field('batteryCurrent',currentText(current));
    const batteryPower=finite(reading.power)?reading.power:watts(reading.voltage,current);
    this.field('chargingPower',wattsText(batteryPower));
    this.field('chargingProtocol',reading.chargingProtocol||'—');
    this.field('usbInputVoltage',finite(reading.usbInputVoltage)?`${(reading.usbInputVoltage/1000).toFixed(3)} V`:'—');
    this.field('targetInputCurrent',currentText(reading.targetInputCurrent));
    const tempSource=reading.tempSource==='android'?'系统温度':reading.tempSource==='temp'?'temp':'bcc_parms';
    this.panel.querySelector('[data-info-label=temperature]').textContent=`电池温度（${tempSource}）`;
    this.field('temperature',finite(reading.temp)?`${reading.temp.toFixed(1)} ℃`:'—');
    this.field('rm',number(reading.rm,'mAh'));
    if(info&&!this.loaded){
      for(const key of['cycles','fcc','rawFcc','soh','rawSoh','qmax','cutoffMv','designCapacity'])this.field(key,number(info[key],['fcc','rawFcc','qmax','designCapacity'].includes(key)?'mAh':['soh','rawSoh'].includes(key)?'%':key==='cutoffMv'?'mV':'',key==='rawSoh'?2:0));
      this.field('qmax',capacityRatio(info.qmax,info.designCapacity));
      const cutoffKnown=finite(info.cutoffMv)&&info.cutoffMv>0,noCompensation=isBatteryUncompensated(info);
      if(noCompensation)for(const key of['rawFcc','rawSoh'])this.field(key,'——');
      const fccBound=cutoffKnown&&!noCompensation?rawFccBoundText(info.rawFccAbove):null;
      if(fccBound)this.field('rawFcc',fccBound);
      const referenceFcc=noCompensation?info.fcc:info.rawFcc;
      const lockedCapacity=finite(info.qmax)&&finite(referenceFcc)&&referenceFcc>0?info.qmax-referenceFcc:null;
      this.field('lockedCapacity',lockedCapacityText(info.qmax,lockedCapacity,fccBound?info.rawFccAbove:null));
      for(const key of['fcc','rawFcc']){
        const bounded=key==='rawFcc'&&fccBound!==null;
        const nearActual=cutoffKnown&&key===(noCompensation?'fcc':'rawFcc')&&(bounded||finite(info[key])&&info[key]>0);
        this.panel.querySelector(`[data-info="${key}"]`).classList.toggle('battery-near-actual',nearActual);
        if(nearActual&&!bounded)this.field(key,capacityRatio(info[key],info.designCapacity));
      }
      this.panel.querySelector('[data-info="qmax"]').classList.toggle('battery-near-actual',finite(info.qmax)&&info.qmax>0);
      this.renderCoefficients(info);
      for(const key of['serial','manufactureDate','batteryType'])this.field(key,info[key]||'—');this.loaded=true;
    }
  }
}
