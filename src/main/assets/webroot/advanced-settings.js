const LOW_VOLTAGE_CONFIRM_MV=2700;
const dischargeVoltageFields=[
  {key:'GAUGE_TERM_VOLTAGE',label:'电量计截止电压',unit:'mV',confirmVoltage:true},
  {key:'GAUGE_SHUTDOWN_VOLTAGE',label:'软件关机电压',unit:'mV',confirmVoltage:true}
];
const floatCorrection={key:'DEC_DELTA',interfaceName:'dec_delta',label:'浮充降压修正',unit:'mV',deviceRange:true,signed:true};
const floatFields=[
  {key:'FV_MAX',label:'浮充电压上限',unit:'mV',deviceRange:true},
  {key:'FV_MIN',label:'浮充电压下限',unit:'mV',deviceRange:true}
];
const gaugeInterval={key:'GAUGE_UPDATE',label:'电流计采样间隔',unit:'s',min:0.5,max:20,scale:1000};
const coolDown={key:'COOL_DOWN',label:'充电降温限流档位',unit:'',deviceRange:true};
const ufcsDisableKey='UFCS_DISABLE';
const wiredCurrentFields=[
  {key:'WIRED_FCC',label:'普通有线电池电流',unit:'mA',max:5000},
  {key:'WIRED_ICL',label:'普通有线输入电流',unit:'mA',max:3000}
];
const fastCurrentFields=[
  {key:'PPS_CURR',label:'PPS 电流',unit:'mA',max:8000},
  {key:'UFCS_CURR',label:'UFCS 电流',unit:'mA',max:15000},
  {key:'VOOC_CURR',label:'VOOC 电流',unit:'mA',max:15000}
];
const chargingModes=[
  {label:'无'},{key:'CHG_DISABLE',label:'暂停'},{key:'WIRED_CHARGE_SUSPEND',label:'禁止'},{key:'PLC',label:'旁路'}
];
const modeReading={label:'限制充电'};
const batteryTypeReading={label:'电池类型'};
const groups=[
  ['advancedDischargeVoltageFields',dischargeVoltageFields],
  ['advancedFloatFields',floatFields],
  ['advancedFloatCorrection',[floatCorrection]],
  ['advancedCoolDown',[coolDown]],
  ['advancedGaugeInterval',[gaugeInterval]],
  ['advancedWiredCurrentFields',wiredCurrentFields],
  ['advancedFastCurrentFields',fastCurrentFields]
];
const fields=groups.flatMap(([,items])=>items);
const capacityReadings=[
  ...dischargeVoltageFields,
  ...floatFields,
  floatCorrection,
  {key:'FV_DEC',unit:'mV'},
  batteryTypeReading,
  {key:'TARGET_TERM_VOLTAGE',unit:'mV'},{key:'TARGET_SHUTDOWN_VOLTAGE',unit:'mV'}
];
const chargingReadings=[
  coolDown,
  modeReading,
  gaugeInterval,
  ...wiredCurrentFields,
  ...fastCurrentFields
];
const $=id=>document.getElementById(id);

export function createAdvancedPage(api,keypad){
  const root=$('advancedPage'),status=$('advancedStatus');
  const confirmation=$('advancedConfirm');
  const reset=$('resetAdvancedSettings');
  const voltageAdvice=$('advancedDischargeVoltageAdvice'),voltageAdviceValue=$('advancedDischargeVoltageAdviceValue');
  const autoRestore=$('advancedAutoRestore'),cellButtons=[$('singleCell'),$('dualCell')];
  const ufcsToggle=$('advancedDisableUfcs'),ufcsUnavailable=$('advancedUfcsUnavailable');
  const modeGroup=$('advancedChargingMode'),modeUnavailable=$('advancedChargingModeUnavailable');
  const capacityList=$('advancedCapacityReadings'),chargingList=$('advancedChargingReadings');
  const rows=new Map(),controls=new Map();
  const limitedModes=chargingModes.slice(1);
  let timer,revision=0,reading=false,refreshPending=false,applying=false,state,resetError='';

  for(const [list,readings] of [[capacityList,capacityReadings],[chargingList,chargingReadings]])for(const field of readings){
    const row=document.createElement('div');
    const name=document.createElement('dt'),value=document.createElement('dd');
    name.textContent=field.key??field.label;
    row.append(name,value);
    row.hidden=field!==modeReading;
    if(field===batteryTypeReading)row.className='advanced-battery-type';
    list.append(row);
    rows.set(field,{row,value});
  }
  const modeValue=rows.get(modeReading).value;
  const modeButtons=chargingModes.map((mode,index)=>{
    const button=document.createElement('button');
    const missingLabel=document.createElement('small');
    button.type='button';
    button.textContent=mode.label;
    missingLabel.className='advanced-field-node';
    missingLabel.textContent='接口缺失';
    missingLabel.hidden=true;
    button.append(missingLabel);
    button.disabled=true;
    button.onclick=()=>setChargingMode(index);
    modeGroup.append(button);
    return {button,missingLabel};
  });
  for(const [container,items] of groups)for(const field of items){
    const row=document.createElement('div');
    row.className='advanced-field';
    const label=document.createElement('span');
    const nodeName=document.createElement('small'),button=document.createElement('button');
    const unavailable=document.createElement('span');
    label.className='advanced-field-label';
    label.textContent=field.label;
    nodeName.className='advanced-field-node';
    nodeName.textContent=field.interfaceName??field.key;
    label.append(nodeName);
    button.type='button';
    button.textContent='—';
    button.disabled=true;
    button.setAttribute('aria-label',field.label);
    button.setAttribute('aria-haspopup','dialog');
    button.onclick=()=>openEditor(field,true);
    unavailable.textContent='接口缺失';
    unavailable.hidden=true;
    row.append(label,button,unavailable);
    $(container).append(row);
    controls.set(field.key,{button,unavailable});
  }

  const visible=()=>!root.hidden&&!document.hidden;
  const displayValue=(field,value)=>String(value/(field.scale??1));
  function inputRange(field){
    const node=state?.nodes?.[field.key];
    return {min:node?.min??field.min??0,max:node?.max??field.max};
  }
  function rangeLabel(field){
    const {min,max}=inputRange(field);
    return Number.isFinite(max)?`${min}–${max}${field.unit?' '+field.unit:''}\n${field.scale?'步进 0.1 s':'整数'}`:'';
  }
  function render(data,error=''){
    state=data;
    voltageAdvice.hidden=typeof data?.highDesignCapacity!=='boolean';
    if(!voltageAdvice.hidden){
      const value=data.highDesignCapacity?'2600':'2850';
      if(voltageAdviceValue.textContent!==value)voltageAdviceValue.textContent=value;
    }
    const snapshot=data?.nodes,nodes=snapshot??{};
    const isMissing=key=>!!snapshot&&(!nodes[key]||nodes[key].missing===true);
    for(const [field,{row,value}] of rows){
      if(field===modeReading)continue;
      if(field===batteryTypeReading){
        const type=typeof data?.batteryType==='string'?data.batteryType.trim():'';
        row.hidden=!type;
        if(value.textContent!==type)value.textContent=type;
        continue;
      }
      const effective=nodes[field.key]?.effective;
      row.hidden=!snapshot;
      if(row.hidden)continue;
      const text=isMissing(field.key)?'接口缺失':Number.isFinite(effective)?
        [displayValue(field,effective),field.unit].filter(Boolean).join(' '):'—';
      if(value.textContent!==text)value.textContent=text;
    }
    capacityList.hidden=!capacityReadings.some(field=>!rows.get(field).row.hidden);
    for(const field of fields){
      const node=nodes[field.key],{button,unavailable}=controls.get(field.key);
      const missing=isMissing(field.key)||node?.controlMissing===true;
      const canEdit=!missing&&typeof node?.forced==='boolean'&&(!field.deviceRange||Number.isFinite(node.max)||node.forced);
      const text=canEdit?
        (node.forced?[displayValue(field,node.forceValue),field.unit].filter(Boolean).join(' '):'厂商策略'):'—';
      if(button.textContent!==text)button.textContent=text;
      button.hidden=missing;
      button.disabled=!canEdit||applying;
      unavailable.hidden=!missing;
    }
    const editing=keypad.keypad.open&&fields.find(field=>field.key===keypad.editor?.key);
    if(editing)$('keypadRange').textContent=rangeLabel(editing);
    const available=limitedModes.some(mode=>typeof nodes[mode.key]?.forced==='boolean');
    const missing=limitedModes.every(mode=>isMissing(mode.key));
    const known=limitedModes.every(mode=>isMissing(mode.key)||typeof nodes[mode.key]?.forced==='boolean');
    const active=limitedModes.filter(mode=>nodes[mode.key]?.forced===true);
    const selected=active.length===1?active[0]:known&&available&&active.length===0?chargingModes[0]:null;
    modeUnavailable.hidden=!missing;
    modeGroup.hidden=missing;
    const modeText=missing?'接口缺失':selected?.label??'—';
    if(modeValue.textContent!==modeText)modeValue.textContent=modeText;
    modeButtons.forEach(({button,missingLabel},index)=>{
      const mode=chargingModes[index];
      const modeMissing=index!==0&&isMissing(mode.key);
      const pressed=String(mode===selected);
      if(button.getAttribute('aria-pressed')!==pressed)button.setAttribute('aria-pressed',pressed);
      missingLabel.hidden=!modeMissing;
      button.disabled=applying||!available||(index!==0&&(!known||modeMissing));
    });
    const ufcs=nodes[ufcsDisableKey],ufcsMissing=isMissing(ufcsDisableKey)||ufcs?.controlMissing===true;
    ufcsToggle.checked=ufcs?.forced===true&&ufcs.forceValue>0;
    ufcsToggle.disabled=applying||ufcsMissing||typeof ufcs?.forced!=='boolean';
    ufcsToggle.hidden=ufcsMissing;
    ufcsUnavailable.hidden=!ufcsMissing;
    autoRestore.checked=data?.advancedAutoRestore===true;
    autoRestore.disabled=applying||!data;
    cellButtons.forEach((button,index)=>{
      const pressed=String(!!data&&(data.cellCount||1)===index+1);
      if(button.getAttribute('aria-pressed')!==pressed)button.setAttribute('aria-pressed',pressed);
      button.disabled=applying||!data;
    });
    reset.disabled=!data||applying||!data.hasAdvancedTargets&&!ufcs?.forced&&!fields.some(field=>nodes[field.key]?.forced)&&!active.length;
    const message=error||resetError||(!data?'正在读取…':Object.keys(nodes).length?'':'未读取到投票节点。');
    if(status.textContent!==message)status.textContent=message;
    status.hidden=!message;
  }
  async function refresh(restoreKeypad=false){
    if(!visible()||applying)return;
    if(reading){refreshPending=true;return;}
    reading=true;
    const requested=revision;
    try{
      const data=await api('oplus_votes');
      if(visible()&&requested===revision){
        render(data);
        if(restoreKeypad)syncKeypad(history.state);
      }
    }catch(error){
      if(visible()&&requested===revision)render(null,error.message);
    }finally{
      reading=false;
      if(refreshPending){refreshPending=false;refresh();}
    }
  }
  function pause(){
    clearInterval(timer);
    timer=undefined;
  }
  function resume(restoreKeypad=false){
    if(!visible()||timer!==undefined)return;
    refresh(restoreKeypad);
    timer=setInterval(()=>refresh(),2000);
  }

  function askChange(title,description,label='确定'){
    $('advancedConfirmTitle').textContent=title;
    $('advancedConfirmText').textContent=description;
    $('advancedConfirmApply').textContent=label;
    confirmation.returnValue='cancel';
    confirmation.showModal();
    return new Promise(resolve=>{
      confirmation.addEventListener('close',()=>resolve(confirmation.returnValue==='apply'),{once:true});
    });
  }
  $('advancedConfirmCancel').onclick=()=>confirmation.close('cancel');
  $('advancedConfirmApply').onclick=()=>confirmation.close('apply');
  async function updateOption(action,value,confirm){
    if(applying||!state)return;
    applying=true;
    revision++;
    resetError='';
    render(state);
    if(action==='set_advanced_auto_restore')autoRestore.checked=value===1;
    try{
      if(confirm&&(!await confirm()||root.hidden))return;
      const updated=await api(action,value);
      state={...state,...updated};
    }catch(error){resetError=error.message;}
    finally{
      applying=false;
      if(visible()){render(state);refresh();}
    }
  }
  autoRestore.onchange=()=>updateOption('set_advanced_auto_restore',autoRestore.checked?1:0);
  cellButtons.forEach((button,index)=>{
    const count=index+1;
    button.onclick=()=>{
      if(!state||state.cellCount===count)return;
      const hint=count!==(state.cellCount||1)?
        '请确认与设备规格一致。进行中的记录会结束，后续记录将使用新的电芯数量。':
        '请确认与设备规格一致。后台将按此计算电流和功率。';
      return updateOption('set_cell_count',count,()=>askChange(`设为${button.textContent}？`,hint));
    };
  });
  ufcsToggle.onchange=()=>{
    if(!ufcsToggle.disabled)return applyChargingChange('set_oplus_vote',ufcsDisableKey,ufcsToggle.checked?1:'stock');
  };
  function setChargingMode(option){
    const {button}=modeButtons[option];
    if(applying||!state||button.disabled)return;
    // Selecting no restriction also clears the saved mode, even if the driver already shows it.
    if(option!==0&&button.getAttribute('aria-pressed')==='true')return;
    return applyChargingChange('set_charging_mode',option);
  }
  async function applyChargingChange(action,...args){
    if(applying||!state)return;
    resetError='';
    applying=true;
    revision++;
    render(state);
    try{await api(action,...args);}
    catch(error){resetError=error.message;}
    finally{
      applying=false;
      refresh();
    }
  }
  async function confirmVoltage(field,value){
    if(value===null)return await askChange('确定恢复厂商截止电压？',
      `取消「${field.label}」的手动覆盖，交回厂商动态策略。恢复值由驱动决定。`)&&!root.hidden;
    if(value<LOW_VOLTAGE_CONFIRM_MV&&state?.highDesignCapacity!==true){
      return await askChange('低电压风险确认',
        `${field.label}：${value} mV\n\n设置低于 ${LOW_VOLTAGE_CONFIRM_MV} mV 的电压可能会对电池造成损伤。是否仍要应用？`,'确认应用')&&!root.hidden;
    }
    return !root.hidden;
  }
  async function apply(field,value){
    let raw=null;
    if(value!==null){
      const {min,max}=inputRange(field);
      const tenths=Math.round(value*10);
      if(!Number.isFinite(max))throw Error('暂时无法读取可填范围。');
      if(value<min||value>max||!(field.scale?value===tenths/10:Number.isInteger(value)))
        throw Error(`请输入 ${min}–${max}${field.unit?' '+field.unit:''}${field.scale?'，步进 0.1 s':' 的整数'}。`);
      raw=field.scale?tenths*100:value;
    }
    applying=true;
    revision++;
    reset.disabled=true;
    try{
      if(field.confirmVoltage&&!await confirmVoltage(field,raw))return false;
      await api('set_oplus_vote',field.key,raw??'stock');
      return true;
    }finally{
      applying=false;
      refresh();
    }
  }
  reset.onclick=async()=>{
    if(applying||!state)return;
    resetError='';
    applying=true;
    revision++;
    reset.disabled=true;
    try{
      if(!await askChange('恢复默认？','将参数设置中的手动调整恢复为默认。')||root.hidden)return;
      try{
        const {failed}=await api('reset_oplus_votes');
        if(failed)resetError=`部分参数恢复失败。${failed}`;
      }catch(error){resetError=`部分参数恢复失败。${error.message}`;}
    }finally{
      applying=false;
      refresh();
    }
  };
  function openEditor(field,push){
    if(root.hidden||applying||controls.get(field.key).button.disabled)return;
    const node=state.nodes[field.key];
    const current=node.forced?node.forceValue:node.effective;
    keypad.openEditor({
      key:field.key,draft:Number.isFinite(current)?displayValue(field,current):'',decimal:!!field.scale,signed:!!field.signed,digits:1,
      commit:value=>apply(field,value),restore:()=>apply(field,null)
    },field.label,field.unit,rangeLabel(field),
    push?{page:'advanced',keypad:true,key:field.key}:null);
  }
  function syncKeypad(entry){
    if(entry?.page!=='advanced')return;
    if(entry.keypad){
      const field=fields.find(item=>item.key===entry.key);
      if(field&&!keypad.keypad.open)openEditor(field,false);
    }else if(keypad.editor?.commit)keypad.closeEditor();
  }
  return {
    get active(){return !root.hidden;},
    open(){
      if(!root.hidden)return;
      root.hidden=false;
      resetError='';
      render(null);
      resume(true);
    },
    handleHistory:syncKeypad,
    close(){
      if(root.hidden)return;
      root.hidden=true;
      if(confirmation.open)confirmation.close('cancel');
      if(keypad.editor?.commit)keypad.closeEditor();
      revision++;
      pause();
    },
    pause,resume
  };
}
