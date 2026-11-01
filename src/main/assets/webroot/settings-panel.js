import{DEFAULT_SETTINGS,settingsKeys,encodeSettings}from'./config.js?v=acol-2.1';
export class SettingsPanel{
  constructor(api,onSaved){
    this.api=api;this.onSaved=onSaved;this.panel=document.getElementById('settingsPanel');this.form=document.getElementById('settingsForm');this.status=document.getElementById('settingsStatus');
    this.ready=false;this.dirty=false;this.running=null;this.timer=null;this.lastSaved='';this.editor=null;
    this.fastButton=document.getElementById('toggleFast');this.fastBusy=false;this.fastValue=false;this.editorBusy=false;
    this.fastButton.onclick=()=>this.toggleFast();
    this.gameInfo=document.getElementById('gameModeExplanation');
    document.getElementById('gameModeInfo').onclick=()=>{history.pushState({...history.state,gameInfo:true},'',location.href);this.gameInfo.showModal();};
    const dismissInfo=()=>{if(history.state?.gameInfo)history.back();else this.gameInfo.close();};
    document.getElementById('closeGameModeInfo').onclick=dismissInfo;
    this.gameInfo.addEventListener('cancel',e=>{e.preventDefault();dismissInfo();});
    this.changeConfirm=document.getElementById('settingsChangeConfirm');this.changeResolve=null;this.changeAccepted=false;
    document.getElementById('settingsChangeCancel').onclick=()=>this.dismissChange(false);
    document.getElementById('settingsChangeApply').onclick=()=>this.dismissChange(true);
    this.changeConfirm.addEventListener('cancel',e=>{e.preventDefault();this.dismissChange(false);});
    this.changeConfirm.addEventListener('click',e=>{const r=this.changeConfirm.getBoundingClientRect();if(e.target===this.changeConfirm&&(e.clientX<r.left||e.clientX>r.right||e.clientY<r.top||e.clientY>r.bottom))this.dismissChange(false);});
    this.recording=document.getElementById('recordingEnabled');this.recordingBusy=false;
    this.recording.onchange=()=>this.toggleRecording(this.recording.checked);
    this.powerDisplay=document.getElementById('powerDisplayEnabled');this.powerDisplayBusy=false;
    this.powerDisplay.onchange=()=>this.togglePowerDisplay(this.powerDisplay.checked);
    this.recordingConfirm=document.getElementById('recordingConfirm');this.recordingResolve=null;
    document.getElementById('cancelRecording').onclick=()=>this.dismissRecording(false);
    document.getElementById('confirmRecording').onclick=()=>this.dismissRecording(true);
    this.recordingConfirm.addEventListener('cancel',e=>{e.preventDefault();this.dismissRecording(false);});
    this.recordingConfirm.addEventListener('click',e=>{const r=this.recordingConfirm.getBoundingClientRect();if(e.target===this.recordingConfirm&&(e.clientX<r.left||e.clientX>r.right||e.clientY<r.top||e.clientY>r.bottom))this.dismissRecording(false);});
    this.keypad=document.getElementById('settingsKeypad');
    this.deleteConfirm=document.getElementById('settingsDeleteConfirm');this.deleteStage=0;this.pendingDelete=null;this.confirmBusy=false;
    this.confirmYes=document.getElementById('settingsDeleteYes');this.confirmNo=document.getElementById('settingsDeleteNo');
    this.confirmYes.onclick=()=>{if(this.confirmBusy)return;if(this.deleteStage===1){this.deleteStage=2;this.renderDeleteConfirmation();}else this.applyDeleteThreshold(true);};
    this.confirmNo.onclick=()=>{if(!this.confirmBusy)this.applyDeleteThreshold(false);};
    this.deleteConfirm.addEventListener('cancel',e=>{e.preventDefault();if(!this.confirmBusy)this.cancelDeleteConfirmation();});
    this.deleteConfirm.addEventListener('click',e=>{const r=this.deleteConfirm.getBoundingClientRect();if(e.target===this.deleteConfirm&&!this.confirmBusy&&(e.clientY<r.top||e.clientY>r.bottom||e.clientX<r.left||e.clientX>r.right))this.cancelDeleteConfirmation();});
    this.form.addEventListener('submit',e=>{e.preventDefault();document.activeElement?.blur();this.flush();});
    for(const key of settingsKeys){
      const field=this.form.elements.namedItem(key);
      if(!['capacityAuto','fastAutoOff'].includes(key)){
        field.readOnly=true;field.inputMode='none';field.setAttribute('aria-haspopup','dialog');
        field.addEventListener('pointerdown',e=>e.preventDefault());
        (field.closest('.setting-input')||field).addEventListener('click',()=>this.edit(key));
        field.addEventListener('keydown',e=>{if(e.key==='Enter'||e.key===' '){e.preventDefault();this.edit(key);}});
      }
      const change=()=>{if(!this.ready)return;this.dirty=true;this.setStatus('');clearTimeout(this.timer);this.timer=setTimeout(()=>this.flush(),600);};
      field.addEventListener('input',change);field.addEventListener('change',()=>{change();clearTimeout(this.timer);this.flush();});
    }
    for(const button of this.keypad.querySelectorAll('[data-key]'))button.onclick=()=>this.press(button.dataset.key);
    document.getElementById('keypadApply').onclick=()=>this.commitEditor();
    document.getElementById('keypadCancel').onclick=()=>this.dismissEditor();
    this.keypad.addEventListener('cancel',e=>{e.preventDefault();this.dismissEditor();});
    this.keypad.addEventListener('click',e=>{const r=this.keypad.getBoundingClientRect();if(e.target===this.keypad&&(e.clientY<r.top||e.clientX<r.left||e.clientX>r.right))this.dismissEditor();});
    this.keypad.addEventListener('keydown',e=>{
      let key=/^[0-9]$/.test(e.key)?e.key:{'.':'dot','-':'sign',Backspace:'backspace',Delete:'clear'}[e.key];
      if(key){e.preventDefault();this.press(key);}
      else if(e.key==='Enter'){e.preventDefault();this.commitEditor();}
    });
  }
  setStatus(text,error=false){this.status.textContent=error?text:'';this.status.hidden=!error;this.status.classList.toggle('error',error);}
  updateRecording(enabled,problem){
    if(this.recordingBusy||typeof enabled!=='boolean')return;
    if(typeof problem==='string')this.recordingProblem=problem;
    this.recordingValue=enabled;this.recording.checked=enabled;this.recording.disabled=false;
    document.getElementById('recordingHint').textContent=enabled?'后台持续记录，退出 App 不停止。':this.recordingProblem?`${this.recordingProblem}，记录已关闭。开启时重新检查。`:'已停止记录，重新开启后建立新记录。';
    this.updateFast();
  }
  updateFast(enabled){
    if(typeof enabled==='boolean'&&!this.fastBusy)this.fastValue=enabled;
    this.fastButton.textContent=this.fastValue?'关闭':'开启';
    this.fastButton.disabled=!this.ready||this.fastBusy||this.recordingValue===false;
    this.panel.querySelector('.fast-control').classList.toggle('mode-active',this.fastValue);
  }
  updatePowerDisplay(enabled,error=''){
    if(this.powerDisplayBusy||typeof enabled!=='boolean')return;
    this.powerDisplayValue=enabled;this.powerDisplay.checked=enabled;this.powerDisplay.disabled=false;
    if(error)this.setStatus(error,true);
  }
  async resetDefaults(){
    if(!this.ready||this.resetBusy||this.fastBusy||this.recordingBusy||this.powerDisplayBusy)return false;
    this.resetBusy=true;const button=document.getElementById('resetSettings');button.disabled=true;
    try{
      if(!await this.askChange('恢复默认设置？','恢复设置项和跟随系统主题；游戏性能统计与功耗悬浮显示将关闭，记录总开关将开启。已有记录和备份不会删除。'))return false;
      if(!await this.flush())throw Error('当前设置未能保存，请重试。');
      let finishReset;this.resetPromise=new Promise(resolve=>{finishReset=resolve;});this.finishReset=finishReset;
      for(const field of this.form.elements)field.disabled=true;
      this.fastButton.disabled=true;this.recording.disabled=true;this.powerDisplay.disabled=true;document.getElementById('themeToggle').disabled=true;
      const defaults=(await this.api('default_settings')).settings,args=encodeSettings({...DEFAULT_SETTINGS,...defaults});
      const result=await this.api('set_settings',...args,0);
      this.lastSaved=JSON.stringify(args);this.dirty=false;this.fill(result.settings);this.onSaved(result.settings);
      if(this.fastValue){const fast=await this.api('set_fast',0);this.updateFast(fast.fastEnabled);this.onSaved(fast.settings);}
      if(this.powerDisplayValue){const power=await this.api('set_power_display',0);this.updatePowerDisplay(power.powerDisplayEnabled,power.powerDisplayError);this.onSaved(power.settings);}
      if(this.recordingValue===false){const recording=await this.api('set_recording',1);this.updateRecording(recording.recordingEnabled,recording.recordingProblem);this.onSaved(recording.settings);}
      this.onResetUi?.();this.setStatus('');return true;
    }catch(error){this.setStatus(`恢复未全部完成，请检查当前设置。${error.message}`,true);return false;}
    finally{
      if(this.resetPromise){for(const field of this.form.elements)field.disabled=!this.ready;this.recording.disabled=false;this.powerDisplay.disabled=false;document.getElementById('themeToggle').disabled=false;this.updateFast();this.finishReset?.();this.resetPromise=null;this.finishReset=null;}
      this.resetBusy=false;button.disabled=false;
    }
  }
  async togglePowerDisplay(enabled){
    if(this.powerDisplayBusy)return;
    const previous=this.powerDisplayValue??false;this.powerDisplayBusy=true;this.powerDisplay.disabled=true;this.powerDisplay.checked=previous;this.setStatus('');
    let result;
    try{
      if(!await this.flush())throw Error('设置未能保存，请重试。');
      result=await this.api('set_power_display',enabled?1:0);this.onSaved(result.settings);
    }catch(error){this.setStatus(error.message,true);}
    finally{this.powerDisplayBusy=false;this.updatePowerDisplay(result?.powerDisplayEnabled??previous,result?.powerDisplayError??'');}
  }
  askChange(title,hint,stop=false){
    document.getElementById('settingsChangeTitle').textContent=title;document.getElementById('settingsChangeHint').textContent=hint;
    this.changeConfirm.dataset.action=stop?'stop':'start';this.changeAccepted=false;
    history.pushState({...history.state,settingConfirm:true},'',location.href);
    return new Promise(resolve=>{this.changeResolve=resolve;this.changeConfirm.showModal();document.getElementById('settingsChangeCancel').focus({preventScroll:true});});
  }
  dismissChange(accepted){
    this.changeAccepted=accepted;
    if(history.state?.settingConfirm)history.back();else this.closeChange();
  }
  closeChange(){
    if(this.changeConfirm.open)this.changeConfirm.close();
    const resolve=this.changeResolve,accepted=this.changeAccepted;this.changeResolve=null;this.changeAccepted=false;resolve?.(accepted);
  }
  async toggleFast(){
    if(!this.ready||this.fastBusy)return;const enabled=!this.fastValue;this.fastBusy=true;this.updateFast();this.setStatus('');
    try{
      if(!await this.flush())throw Error('设置未能保存，请重试。');
      const result=await this.api('set_fast',enabled?1:0);this.fastValue=result.fastEnabled;this.onSaved(result.settings);
    }catch(e){this.setStatus(e.message,true);}
    finally{this.fastBusy=false;this.updateFast();}
  }
  async toggleRecording(enabled){
    if(this.recordingBusy)return;this.recordingBusy=true;this.recording.disabled=true;this.setStatus('');let success=false;
    const previous=this.recordingValue??!enabled;this.recording.checked=previous;
    try{
      if(!await this.askRecording(enabled))return;
      const data=await this.api('set_recording',enabled?1:0);
      this.recordingProblem=data.recordingProblem??'';
      success=true;this.onSaved(data.settings);
    }catch(e){this.setStatus(e.message,true);}
    finally{this.recordingBusy=false;this.updateRecording(success?enabled:previous);}
  }
  askRecording(enabled){
    this.recordingConfirm.dataset.action=enabled?'start':'stop';
    document.getElementById('recordingConfirmTitle').textContent=enabled?'开启记录？':'停止记录？';
    document.getElementById('recordingConfirmHint').textContent=enabled?'重新读取剩余容量和系统电量，可用后开始记录。退出 App 后后台仍会继续记录。':'补读并结束当前记录，然后暂停记录。短记录仍按自动删除设置处理。';
    document.getElementById('confirmRecording').textContent=enabled?'确认开启':'确认停止';
    history.pushState({page:'settings',recordingConfirm:true},'',`${location.pathname}${location.search}#settings-recording`);
    return new Promise(resolve=>{this.recordingResolve=resolve;this.recordingConfirm.showModal();document.getElementById('cancelRecording').focus({preventScroll:true});});
  }
  dismissRecording(accepted){
    const resolve=this.recordingResolve;this.recordingResolve=null;if(this.recordingConfirm.open)this.recordingConfirm.close();resolve?.(accepted);
    if(history.state?.recordingConfirm)history.back();
  }
  closeRecordingConfirmation(){if(this.recordingConfirm.open)this.recordingConfirm.close();const resolve=this.recordingResolve;this.recordingResolve=null;resolve?.(false);}
  edit(key,push=true){
    if(!this.ready||this.keypad.open)return;
    const names={deletePct:'自动删除百分比',hidePct:'自动隐藏百分比',energyWh:'默认能量量程',capacityAh:'默认容量量程',voltageMin:'电压下限',voltageMax:'电压上限',tempMin:'温度下限',tempMax:'温度上限',screenOnMinutes:'亮屏记录间隔',screenOffMinutes:'息屏记录间隔',fastSeconds:'功耗采样间隔',performanceSeconds:'性能采样间隔',chargeSeconds:'充电记录间隔'};
    const field=this.form.elements.namedItem(key),decimal=['energyWh','capacityAh','voltageMin','voltageMax'].includes(key),signed=key.startsWith('temp');
    this.editor={key,field,draft:field.value,fresh:true,decimal,signed};
    document.getElementById('keypadTitle').textContent=names[key];
    const unit=key.endsWith('Pct')?'%':key==='energyWh'?'Wh':key==='capacityAh'?'Ah':key.startsWith('voltage')?'V':key.endsWith('Minutes')?'分钟':['fastSeconds','performanceSeconds','chargeSeconds'].includes(key)?'秒':'℃';
    document.getElementById('keypadUnit').textContent=unit;
    const minimum=decimal?Number(field.min).toFixed(2):field.min,maximum=decimal?Number(field.max).toFixed(2):field.max;
    document.getElementById('keypadRange').textContent=`${minimum}–${maximum} ${unit}${decimal?'':'\n整数'}`;
    this.keypad.querySelector('[data-key=dot]').disabled=!decimal;
    this.keypad.querySelector('[data-key=sign]').disabled=!signed;
    this.editorError('');this.drawEditor();
    if(push)history.pushState({page:'settings',keypad:true,key},'',`${location.pathname}${location.search}#settings-keypad`);
    this.keypad.showModal();document.getElementById('keypadCancel').focus({preventScroll:true});
  }
  drawEditor(){document.getElementById('keypadValue').textContent=this.editor?.draft||'—';}
  editorError(text){const box=document.getElementById('keypadError');box.textContent=text;box.hidden=!text;}
  press(key){
    const e=this.editor;if(!e)return;
    this.editorError('');
    if(key==='clear'){e.draft='';e.fresh=false;}
    else if(key==='backspace'){e.draft=e.draft.slice(0,-1);e.fresh=false;}
    else if(key==='sign'&&e.signed){e.draft=e.fresh?'-':e.draft.startsWith('-')?e.draft.slice(1):'-'+e.draft;e.fresh=false;}
    else if(key==='dot'&&e.decimal){if(e.fresh)e.draft='0.';else if(!e.draft.includes('.'))e.draft=(e.draft===''?'0':e.draft==='-'?'-0':e.draft)+'.';e.fresh=false;}
    else if(/^(?:[0-9]|00)$/.test(key)){
      if(e.fresh){e.draft='';e.fresh=false;}
      for(const digit of key){const fraction=e.draft.split('.')[1];if(e.draft.length<8&&!(fraction!==undefined&&fraction.length>=2))e.draft+=digit;}
    }
    this.drawEditor();
  }
  async commitEditor(){
    const e=this.editor;if(!e||this.editorBusy)return;
    if(e.key==='energyWh'&&e.draft===''){e.field.value='';e.field.dispatchEvent(new Event('change',{bubbles:true}));this.dismissEditor();return;}
    if(!/^-?\d+(?:\.\d*)?$/.test(e.draft)){this.editorError('请输入完整数值。');return;}
    let value;
    try{value=this.values();value[e.key]=Number(e.draft);encodeSettings(value);}
    catch(error){this.editorError(error.message);return;}
    if(e.key==='deletePct'){this.openDeleteConfirmation(value.deletePct);return;}
    if(['screenOnMinutes','screenOffMinutes','fastSeconds','performanceSeconds','chargeSeconds'].includes(e.key)){
      this.editorBusy=true;document.getElementById('keypadApply').disabled=true;
      try{
        const name=document.getElementById('keypadTitle').textContent,unit=['fastSeconds','performanceSeconds','chargeSeconds'].includes(e.key)?'秒':'分钟';
        if(!await this.askChange(`修改${name}？`,`改为 ${value[e.key]} ${unit}，继续当前记录。`))return;
        if(!await this.flush())throw Error('设置未能保存，请重试。');
        value=this.values();value[e.key]=Number(e.draft);encodeSettings(value);
        // Publish the confirmed value before joining the shared save loop, so a
        // later checkbox change cannot send an older interval back to the daemon.
        e.field.value=String(value[e.key]);this.dirty=true;
        if(!await this.flush())throw Error('设置未能保存，请重试。');
        if(this.editor===e)this.dismissEditor();
      }catch(error){this.editorError(error.message);}
      finally{this.editorBusy=false;document.getElementById('keypadApply').disabled=false;}
      return;
    }
    e.field.value=e.decimal?value[e.key].toFixed(2):String(value[e.key]);
    e.field.dispatchEvent(new Event('change',{bubbles:true}));this.dismissEditor();
  }
  openDeleteConfirmation(value){
    if(this.deleteConfirm.open)return;
    this.pendingDelete=value;this.deleteStage=1;this.confirmBusy=false;this.confirmYes.disabled=false;this.confirmNo.disabled=false;
    document.getElementById('settingsDeleteError').hidden=true;this.renderDeleteConfirmation();
    history.pushState({...history.state,confirmDelete:true},'',`${location.pathname}${location.search}#settings-confirm`);
    this.deleteConfirm.showModal();this.confirmNo.focus({preventScroll:true});
  }
  renderDeleteConfirmation(){
    const final=this.deleteStage===2;
    document.getElementById('settingsDeleteTitle').textContent=final?'确认删除？':`是否删除 ${this.pendingDelete}% 及以下的已有记录？`;
    document.getElementById('settingsDeleteHint').textContent=final?'删除后无法恢复。':'选择“否”仅更新阈值，保留已有记录。';
    this.deleteConfirm.dataset.stage=String(this.deleteStage);
    if(this.deleteConfirm.open)this.confirmNo.focus({preventScroll:true});
  }
  async applyDeleteThreshold(prune){
    if(this.confirmBusy||this.pendingDelete===null)return;
    const editor=this.editor,threshold=this.pendingDelete;this.confirmBusy=true;this.confirmYes.disabled=true;this.confirmNo.disabled=true;
    try{
      if(!await this.flush())throw Error('设置未能保存，请重试。');
      const value=this.values();value.deletePct=threshold;const args=encodeSettings(value);
      // Share the same in-flight barrier as normal saves and page-exit flushes.
      // The confirmation stays modal until this one-time prune has completed.
      let failure;
      const write=(async()=>{
        try{
          const data=await this.api('set_settings',...args,prune?1:0);
          this.lastSaved=JSON.stringify(args);this.onSaved(data.settings);this.fill(data.settings);this.setStatus('');return true;
        }catch(error){failure=error;return false;}
      })();
      this.running=write;let saved;
      try{saved=await write;}finally{if(this.running===write)this.running=null;}
      if(!saved)throw failure;
      if(this.editor===editor)this.dismissEditor();
    }catch(e){const box=document.getElementById('settingsDeleteError');box.textContent=e.message;box.hidden=false;}
    finally{this.confirmBusy=false;this.confirmYes.disabled=false;this.confirmNo.disabled=false;}
  }
  cancelDeleteConfirmation(){if(history.state?.confirmDelete)history.back();else this.closeDeleteConfirmation();}
  closeDeleteConfirmation(){if(this.deleteConfirm.open)this.deleteConfirm.close();this.pendingDelete=null;this.deleteStage=0;}
  dismissEditor(){if(history.state?.confirmDelete)history.go(-2);else if(history.state?.keypad)history.back();else this.closeEditor();}
  closeEditor(){if(this.gameInfo.open&&!history.state?.gameInfo)this.gameInfo.close();this.closeDeleteConfirmation();if(!history.state?.settingConfirm)this.closeChange();if(this.keypad.open)this.keypad.close();this.editor=null;if(!history.state?.recordingConfirm)this.closeRecordingConfirmation();}
  handleHistory(state){if(state?.gameInfo&&!this.gameInfo.open)this.gameInfo.showModal();else if(!state?.gameInfo&&this.gameInfo.open)this.gameInfo.close();if(!state?.settingConfirm)this.closeChange();if(!state?.recordingConfirm)this.closeRecordingConfirmation();if(!state?.confirmDelete)this.closeDeleteConfirmation();if(!state?.keypad)this.closeEditor();else if(!this.keypad.open&&state.key)this.edit(state.key,false);else if(state.confirmDelete&&!this.deleteConfirm.open){history.replaceState({...state,confirmDelete:false},'',`${location.pathname}${location.search}#settings-keypad`);}}
  values(){
    const result={};
    for(const key of settingsKeys){
      const field=this.form.elements.namedItem(key);
      if(['capacityAuto','fastAutoOff'].includes(key)){result[key]=field.checked;continue;}
      if(key==='energyWh'&&field.value.trim()===''){result[key]=null;field.removeAttribute('aria-invalid');continue;}
      const good=field.value.trim()!==''&&field.checkValidity();field.setAttribute('aria-invalid',String(!good));
      if(!good)throw Error('请输入完整数值。');
      result[key]=field.valueAsNumber;
    }
    encodeSettings(result);return result;
  }
  fill(value){
    for(const key of settingsKeys){
      const field=this.form.elements.namedItem(key),v=value[key];
      if(['capacityAuto','fastAutoOff'].includes(key))field.checked=v;
      else field.value=v===null||v===undefined?'':['energyWh','capacityAh','voltageMin','voltageMax'].includes(key)?v.toFixed(2):String(v);
      field.removeAttribute('aria-invalid');
    }
  }
  async open(cached){
    if(cached){
      const value={...DEFAULT_SETTINGS,...cached};this.fill(value);this.lastSaved=JSON.stringify(encodeSettings(value));this.ready=true;this.dirty=false;this.setStatus('');
      for(const field of this.form.elements)field.disabled=false;
      this.updateFast();
      return;
    }
    this.ready=false;this.dirty=false;clearTimeout(this.timer);this.form.disabled=true;
    for(const field of this.form.elements)field.disabled=true;
    this.setStatus('');
    try{
      const data=await this.api('settings'),value={...DEFAULT_SETTINGS,...data.settings};this.fill(value);this.updateRecording(data.recordingEnabled,data.recordingProblem);this.updateFast(data.fastEnabled);this.updatePowerDisplay(data.powerDisplayEnabled,data.powerDisplayError);
      this.lastSaved=JSON.stringify(encodeSettings(value));this.ready=true;this.setStatus('');
    }catch(e){this.setStatus(e.message,true);}
    finally{for(const field of this.form.elements)field.disabled=!this.ready;this.updateFast();}
  }
  async flush(){
    clearTimeout(this.timer);
    if(this.resetPromise)await this.resetPromise;
    if(!this.ready)return true;
    if(this.running){const write=this.running,success=await write;if(this.running===write)this.running=null;if(!success)return false;if(this.running||this.dirty)return this.flush();return true;}
    if(!this.dirty)return true;
    let value,args,key;
    try{value=this.values();args=encodeSettings(value);key=JSON.stringify(args);}
    catch(e){this.setStatus(e.message,true);return false;}
    if(key===this.lastSaved){this.dirty=false;this.setStatus('');return true;}
    this.dirty=false;this.setStatus('');
    this.running=(async()=>{
      try{
        const data=await this.api('set_settings',...args,0);this.lastSaved=key;this.onSaved(data.settings);
        if(!this.dirty)this.fill(data.settings);
        this.setStatus('');return true;
      }catch(e){this.dirty=true;this.setStatus(e.message,true);return false;}
    })();
    const write=this.running,success=await write;if(this.running===write)this.running=null;
    if(success&&(this.running||this.dirty))return this.flush();return success;
  }
}
