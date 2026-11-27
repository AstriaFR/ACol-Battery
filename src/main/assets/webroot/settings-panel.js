import{DEFAULT_SETTINGS,settingsKeys,settingSlots,encodeSettings}from'./config.js?v=acol-2.2s';
export class SettingsPanel{
  constructor(api,onSaved,requestNotificationPermission){
    this.api=api;this.onSaved=onSaved;this.requestNotificationPermission=requestNotificationPermission;
    this.panel=document.getElementById('settingsPanel');this.form=document.getElementById('settingsForm');this.status=document.getElementById('settingsStatus');
    this.ready=false;this.openSequence=0;this.pendingChecks=new Map();this.running=null;this.editor=null;
    this.fastButton=document.getElementById('toggleFast');this.fastBusy=false;this.fastValue=false;this.editorBusy=false;
    this.fastButton.onclick=()=>this.toggleFast();
    this.infoDialogs=new Map();
    for(const [key,prefix] of [['gameInfo','gameMode'],['gaugeInfo','gaugeInterval']]){
      const dialog=document.getElementById(`${prefix}Explanation`);
      this.infoDialogs.set(key,dialog);
      document.getElementById(`${prefix}Info`).onclick=()=>{
        history.pushState({...history.state,[key]:true},'',location.href);
        dialog.showModal();
      };
      const dismiss=()=>{if(history.state?.[key])history.back();else dialog.close();};
      dialog.querySelector('.game-explanation-actions button').onclick=dismiss;
      dialog.addEventListener('cancel',e=>{e.preventDefault();dismiss();});
    }
    this.changeConfirm=document.getElementById('settingsChangeConfirm');this.changeResolve=null;this.changeAccepted=false;
    document.getElementById('settingsChangeCancel').onclick=()=>this.dismissChange(false);
    document.getElementById('settingsChangeApply').onclick=()=>this.dismissChange(true);
    this.changeConfirm.addEventListener('cancel',e=>{e.preventDefault();this.dismissChange(false);});
    this.changeConfirm.addEventListener('click',e=>{const r=this.changeConfirm.getBoundingClientRect();if(e.target===this.changeConfirm&&(e.clientX<r.left||e.clientX>r.right||e.clientY<r.top||e.clientY>r.bottom))this.dismissChange(false);});
    this.recording=document.getElementById('recordingEnabled');this.recordingBusy=false;
    this.recording.onchange=()=>this.toggleRecording(this.recording.checked);
    this.dischargeNotification=document.getElementById('dischargeNotificationEnabled');this.dischargeNotificationBusy=false;
    this.dischargeNotification.onchange=()=>this.toggleDischargeNotification(this.dischargeNotification.checked);
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
      if(field.type!=='checkbox'){
        field.readOnly=true;field.inputMode='none';field.setAttribute('aria-haspopup','dialog');
        field.addEventListener('pointerdown',e=>e.preventDefault());
        (field.closest('.setting-input')||field).addEventListener('click',()=>this.edit(key));
        field.addEventListener('keydown',e=>{if(e.key==='Enter'||e.key===' '){e.preventDefault();this.edit(key);}});
      }else field.addEventListener('change',()=>{
        if(!this.ready)return;
        this.pendingChecks.set(key,field.checked);
        this.flush();
      });
    }
    for(const button of this.keypad.querySelectorAll('[data-key]'))button.onclick=()=>this.press(button.dataset.key);
    document.getElementById('keypadApply').onclick=()=>this.commitEditor();
    document.getElementById('keypadRestore').onclick=()=>this.applyEditorAction(this.editor?.restore);
    document.getElementById('keypadCancel').onclick=()=>this.dismissEditor();
    this.keypad.addEventListener('cancel',e=>{e.preventDefault();this.dismissEditor();});
    this.keypad.addEventListener('click',e=>{const r=this.keypad.getBoundingClientRect();if(e.target===this.keypad&&(e.clientY<r.top||e.clientX<r.left||e.clientX>r.right))this.dismissEditor();});
    this.keypad.addEventListener('keydown',e=>{
      let key=/^[0-9]$/.test(e.key)?e.key:{'.':'dot','-':'sign',Backspace:'backspace',Delete:'clear'}[e.key];
      if(key){e.preventDefault();this.press(key);}
    });
  }
  setStatus(text,error=false){this.status.textContent=error?text:'';this.status.hidden=!error;this.status.classList.toggle('error',error);}
  updateRecording(enabled,problem){
    if(this.recordingBusy||typeof enabled!=='boolean')return;
    if(typeof problem==='string')this.recordingProblem=problem;
    this.recordingValue=enabled;this.recording.checked=enabled;this.recording.disabled=false;
    const hint=document.getElementById('recordingHint'),text=enabled?'后台持续记录，退出 App 不停止。':this.recordingProblem?`${this.recordingProblem}，记录已关闭。开启时重新检查。`:'已停止记录，重新开启后建立新记录。';
    if(hint.textContent!==text)hint.textContent=text;
    this.updateFast();
  }
  updateFast(enabled){
    if(typeof enabled==='boolean'&&!this.fastBusy)this.fastValue=enabled;
    const label=this.fastValue?'关闭':'开启';
    if(this.fastButton.textContent!==label)this.fastButton.textContent=label;
    this.fastButton.disabled=!this.ready||this.fastBusy;
    this.panel.querySelector('.fast-control').classList.toggle('mode-active',this.fastValue);
  }
  updatePowerDisplay(enabled,error=''){
    if(this.powerDisplayBusy||typeof enabled!=='boolean')return;
    this.powerDisplayValue=enabled;this.powerDisplay.checked=enabled;this.powerDisplay.disabled=false;
    if(error)this.setStatus(error,true);
  }
  updateDischargeNotification(enabled){
    if(!this.dischargeNotification)return;
    if(typeof enabled==='boolean'&&!this.dischargeNotificationBusy)this.dischargeNotificationValue=enabled;
    this.dischargeNotification.checked=this.dischargeNotificationValue===true;
    this.dischargeNotification.disabled=this.dischargeNotificationBusy||!!this.resetPromise||typeof this.dischargeNotificationValue!=='boolean';
  }
  async toggleDischargeNotification(enabled){
    if(this.dischargeNotificationBusy||this.resetPromise)return;
    this.dischargeNotificationBusy=true;this.updateDischargeNotification();this.setStatus('');
    try{
      if(enabled&&!await this.requestNotificationPermission())return;
      const result=await this.api('set_discharge_notification',enabled?1:0);
      this.dischargeNotificationValue=result.settings.dischargeNotificationEnabled;
      this.onSaved(result.settings);
    }catch(error){this.setStatus(error.message,true);}
    finally{this.dischargeNotificationBusy=false;this.updateDischargeNotification();}
  }
  async resetDefaults(){
    if(!this.ready||this.resetBusy||this.fastBusy||this.recordingBusy||this.powerDisplayBusy||this.dischargeNotificationBusy)return false;
    this.resetBusy=true;const button=document.getElementById('resetSettings');button.disabled=true;
    try{
      if(!await this.askChange('恢复默认设置？','恢复默认设置和跟随系统主题。游戏性能统计与性能监视器将关闭，记录总开关将开启。已有记录和备份会保留。'))return false;
      if(!await this.flush())throw Error('当前设置未能保存，请重试。');
      let finishReset;this.resetPromise=new Promise(resolve=>{finishReset=resolve;});this.finishReset=finishReset;
      this.updateDischargeNotification();
      for(const field of this.form.elements)field.disabled=true;
      this.fastButton.disabled=true;this.recording.disabled=true;this.powerDisplay.disabled=true;document.getElementById('themeToggle').disabled=true;
      const result=await this.api('reset_settings');
      this.updateDischargeNotification(result.settings.dischargeNotificationEnabled);
      this.fill(result.settings);this.onSaved(result.settings);
      if(this.fastValue){const fast=await this.api('set_fast',0);this.updateFast(fast.fastEnabled);this.onSaved(fast.settings);}
      if(this.powerDisplayValue){const power=await this.api('set_power_display',0);this.updatePowerDisplay(power.powerDisplayEnabled,power.powerDisplayError);this.onSaved(power.settings);}
      if(this.recordingValue===false){const recording=await this.api('set_recording',1);this.updateRecording(recording.recordingEnabled,recording.recordingProblem);this.onSaved(recording.settings);}
      this.onResetUi?.();this.setStatus('');return true;
    }catch(error){this.setStatus(`恢复未全部完成，请检查当前设置。${error.message}`,true);return false;}
    finally{
      if(this.resetPromise){for(const field of this.form.elements)field.disabled=!this.ready;this.recording.disabled=false;this.powerDisplay.disabled=false;document.getElementById('themeToggle').disabled=false;this.updateFast();this.finishReset?.();this.resetPromise=null;this.finishReset=null;}
      this.resetBusy=false;button.disabled=false;this.updateDischargeNotification();
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
      this.updateFast(data.fastEnabled);
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
    const field=this.form.elements.namedItem(key),digits=key==='fastSeconds'||key==='performanceSeconds'?1:2;
    const decimal=digits===1||['energyWh','capacityAh','voltageMin','voltageMax'].includes(key),signed=key.startsWith('temp');
    const unit=key.endsWith('Pct')?'%':key==='energyWh'?'Wh':key==='capacityAh'?'Ah':key.startsWith('voltage')?'V':key.endsWith('Minutes')?'分钟':['fastSeconds','performanceSeconds','chargeSeconds'].includes(key)?'秒':'℃';
    const minimum=decimal?Number(field.min).toFixed(digits):field.min,maximum=decimal?Number(field.max).toFixed(digits):field.max;
    const range=`${minimum}–${maximum} ${unit}${decimal?'':'\n整数'}`;
    const restore=async()=>{
      if(!await this.flush())throw Error('设置未能保存，请重试。');
      await this.writeSetting('reset_setting',settingSlots.indexOf(key));
    };
    this.openEditor({key,field,draft:field.value,decimal,signed,digits,restore},names[key],unit,range,push?{page:'settings',keypad:true,key}:null);
  }
  openEditor(editor,title,unit,range,state){
    if(this.keypad.open||this.editorBusy)return;
    this.editor={...editor,fresh:true};
    document.getElementById('keypadTitle').textContent=title;
    document.getElementById('keypadUnit').textContent=unit;
    document.getElementById('keypadRange').textContent=range;
    const restore=document.getElementById('keypadRestore');
    document.getElementById('keypadApply').disabled=false;
    restore.hidden=!editor.restore;
    restore.disabled=editor.restoreDisabled??false;
    const hint=document.getElementById('keypadHint');
    hint.textContent=editor.hint??'';
    hint.hidden=!editor.hint;
    this.keypad.querySelector('[data-key=dot]').disabled=!editor.decimal;
    this.keypad.querySelector('[data-key=sign]').disabled=!editor.signed;
    this.editorError('');this.drawEditor();
    if(state)history.pushState(state,'',`${location.pathname}${location.search}#${state.page}-keypad`);
    this.keypad.showModal();document.getElementById('keypadCancel').focus({preventScroll:true});
  }
  drawEditor(){document.getElementById('keypadValue').textContent=this.editor?.draft||'—';}
  editorError(text){const box=document.getElementById('keypadError');box.textContent=text;box.hidden=!text;}
  async applyEditorAction(action){
    const editor=this.editor;
    if(!editor||!action||this.editorBusy)return;
    const apply=document.getElementById('keypadApply'),restore=document.getElementById('keypadRestore');
    const wasDisabled=restore.disabled;
    this.editorBusy=apply.disabled=restore.disabled=true;
    this.editorError('');
    let saved=false;
    try{saved=await action()!==false;}
    catch(error){if(this.editor===editor)this.editorError(error.message);}
    finally{this.editorBusy=false;apply.disabled=false;restore.disabled=wasDisabled;}
    if(saved&&this.editor===editor){
      this.closeEditor();
      if(history.state?.keypad)history.back();
    }
  }
  press(key){
    const e=this.editor;if(!e||this.editorBusy)return;
    this.editorError('');
    if(key==='clear'){e.draft='';e.fresh=false;}
    else if(key==='backspace'){e.draft=e.draft.slice(0,-1);e.fresh=false;}
    else if(key==='sign'&&e.signed){e.draft=e.fresh?'-':e.draft.startsWith('-')?e.draft.slice(1):'-'+e.draft;e.fresh=false;}
    else if(key==='dot'&&e.decimal){if(e.fresh)e.draft='0.';else if(!e.draft.includes('.'))e.draft=(e.draft===''?'0':e.draft==='-'?'-0':e.draft)+'.';e.fresh=false;}
    else if(/^(?:[0-9]|00)$/.test(key)){
      if(e.fresh){e.draft='';e.fresh=false;}
      for(const digit of key){const fraction=e.draft.split('.')[1];if(e.draft.length<8&&!(fraction!==undefined&&fraction.length>=(e.digits??2)))e.draft+=digit;}
    }
    this.drawEditor();
  }
  async commitEditor(){
    const e=this.editor;if(!e||this.editorBusy)return;
    if(e.key==='energyWh'&&e.draft===''){await this.applyEditorAction(e.restore);return;}
    if(!/^-?\d+(?:\.\d*)?$/.test(e.draft)){this.editorError('请输入完整数值。');return;}
    if(e.commit){
      await this.applyEditorAction(()=>e.commit(Number(e.draft)));
      return;
    }
    let value;
    try{value=this.values();value[e.key]=Number(e.draft);encodeSettings(value);}
    catch(error){this.editorError(error.message);return;}
    if(e.key==='deletePct'){this.openDeleteConfirmation(value.deletePct);return;}
    if(['screenOnMinutes','screenOffMinutes','fastSeconds','performanceSeconds','chargeSeconds'].includes(e.key)){
      const apply=document.getElementById('keypadApply'),restore=document.getElementById('keypadRestore');
      this.editorBusy=apply.disabled=restore.disabled=true;
      try{
        const name=document.getElementById('keypadTitle').textContent,unit=['fastSeconds','performanceSeconds','chargeSeconds'].includes(e.key)?'秒':'分钟';
        if(!await this.askChange(`修改${name}？`,`改为 ${value[e.key]} ${unit}，继续当前记录。`))return;
        await this.saveField(e.key,value[e.key]);
        if(this.editor===e)this.dismissEditor();
      }catch(error){this.editorError(error.message);}
      finally{this.editorBusy=apply.disabled=restore.disabled=false;}
      return;
    }
    await this.applyEditorAction(()=>this.saveField(e.key,value[e.key]));
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
      await this.saveField('deletePct',threshold,prune);
      if(this.editor===editor)this.dismissEditor();
    }catch(e){const box=document.getElementById('settingsDeleteError');box.textContent=e.message;box.hidden=false;}
    finally{this.confirmBusy=false;this.confirmYes.disabled=false;this.confirmNo.disabled=false;}
  }
  cancelDeleteConfirmation(){if(history.state?.confirmDelete)history.back();else this.closeDeleteConfirmation();}
  closeDeleteConfirmation(){if(this.deleteConfirm.open)this.deleteConfirm.close();this.pendingDelete=null;this.deleteStage=0;}
  dismissEditor(){if(this.editor?.commit&&this.editorBusy)return;if(history.state?.confirmDelete)history.go(-2);else if(history.state?.keypad)history.back();else this.closeEditor();}
  closeEditor(){
    for(const [key,dialog] of this.infoDialogs)if(dialog.open&&!history.state?.[key])dialog.close();
    this.closeDeleteConfirmation();if(!history.state?.settingConfirm)this.closeChange();if(this.keypad.open)this.keypad.close();this.editor=null;if(!history.state?.recordingConfirm)this.closeRecordingConfirmation();
  }
  handleHistory(state){
    for(const [key,dialog] of this.infoDialogs){
      if(state?.[key]&&!dialog.open)dialog.showModal();
      else if(!state?.[key]&&dialog.open)dialog.close();
    }
    if(!state?.settingConfirm)this.closeChange();if(!state?.recordingConfirm)this.closeRecordingConfirmation();if(!state?.confirmDelete)this.closeDeleteConfirmation();if(!state?.keypad)this.closeEditor();else if(!this.keypad.open&&state.key)this.edit(state.key,false);else if(state.confirmDelete&&!this.deleteConfirm.open){history.replaceState({...state,confirmDelete:false},'',`${location.pathname}${location.search}#settings-keypad`);}
  }
  values(){
    const result={};
    for(const key of settingsKeys){
      const field=this.form.elements.namedItem(key);
      if(field.type==='checkbox'){result[key]=field.checked;continue;}
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
      if(field.type==='checkbox')field.checked=this.pendingChecks?.get(key)??v;
      else field.value=v===null||v===undefined?'':key==='fastSeconds'||key==='performanceSeconds'?v.toFixed(1):['energyWh','capacityAh','voltageMin','voltageMax'].includes(key)?v.toFixed(2):String(v);
      field.removeAttribute('aria-invalid');
    }
  }
  initializeForm(settings){
    const value={...DEFAULT_SETTINGS,...settings};
    this.fill(value);this.ready=true;this.setStatus('');
    for(const field of this.form.elements)field.disabled=false;
    this.updateDischargeNotification(value.dischargeNotificationEnabled);this.updateFast();
  }
  recoverFromStatus(settings){
    if(!this.ready&&settings)this.initializeForm(settings);
  }
  async open(cached){
    const sequence=++this.openSequence;
    if(cached){this.initializeForm(cached);return;}
    this.ready=false;
    for(const field of this.form.elements)field.disabled=true;
    this.setStatus('');this.updateFast();
    try{
      const data=await this.api('settings');
      if(sequence!==this.openSequence||this.ready)return;
      this.initializeForm(data.settings);
      this.updateRecording(data.recordingEnabled,data.recordingProblem);
      this.updateFast(data.fastEnabled);this.updatePowerDisplay(data.powerDisplayEnabled,data.powerDisplayError);
    }catch(error){if(sequence===this.openSequence&&!this.ready)this.setStatus(error.message,true);}
  }
  async saveField(key,value,prune=false){
    if(!await this.flush())throw Error('设置未能保存，请重试。');
    const slot=settingSlots.indexOf(key);
    const encoded=encodeSettings({...this.values(),[key]:value})[slot];
    await this.writeSetting('set_setting',slot,encoded,prune?1:0);
  }
  async writeSetting(action,...args){
    const write=(async()=>{
      const data=await this.api(action,...args);
      this.fill(data.settings);
      this.onSaved(data.settings,data);
      this.setStatus('');
    })();
    this.running=write;
    try{await write;}
    finally{if(this.running===write)this.running=null;}
  }
  async flush(){
    if(this.resetPromise)await this.resetPromise;
    if(!this.ready)return true;
    if(this.running){
      try{await this.running;}catch{return false;}
      return this.flush();
    }
    if(!this.pendingChecks?.size)return true;
    const [key,checked]=this.pendingChecks.entries().next().value;
    this.pendingChecks.delete(key);
    try{await this.writeSetting('set_setting',settingSlots.indexOf(key),checked?1:0,0);}
    catch(error){
      if(!this.pendingChecks.has(key))this.pendingChecks.set(key,checked);
      this.setStatus(error.message,true);
      return false;
    }
    return this.flush();
  }
}
