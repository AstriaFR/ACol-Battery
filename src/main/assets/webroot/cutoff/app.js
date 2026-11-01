import{installPageDepth}from'../page-navigation.js?v=acol-2.1';
import { exec as localExec, cancelTopDialog } from '../root-bridge.js?v=acol-2.1';
import { ThemeController } from '../theme.js?v=acol-2.1';
const embedded=window.parent!==window&&new URLSearchParams(location.search).get('embedded')==='1';
const cutoffTheme=ThemeController.applyStored({followParent:embedded});
if(!embedded)installPageDepth();
const exec=command=>embedded?window.parent.__acoCutoffExec(command):localExec(command);
const command = '/system/bin/sh /data/adb/aco_battery_app/runtime/cutoff.sh';
const $ = id => document.getElementById(id);
const homeUrl=new URL('../index.html',location.href).href,cutoffUrl=location.href;
const fromSettings=new URLSearchParams(location.search).get('from')==='settings',parentUrl=fromSettings?homeUrl+'#settings':homeUrl;
let fromHome=false;try{const ref=new URL(document.referrer);fromHome=ref.origin===location.origin&&!ref.pathname.includes('/cutoff/');}catch{}
if(!embedded){
  if(!fromHome){history.replaceState({page:fromSettings?'settings':'home'},'',parentUrl);history.pushState({page:'cutoff',homeDepth:fromSettings?2:1},'',cutoffUrl);}
  else history.replaceState({page:'cutoff',homeDepth:fromSettings?2:1},'',cutoffUrl);
}
window.addEventListener('popstate',e=>{
  if(embedded)return;
  if(e.state?.page==='cutoff'){
    if(e.state.keypad){if(!keypadDialog.open)openKeypad(e.state.key,false);}
    else if(keypadDialog.open)closeKeypadDialog();
    return;
  }
  if(keypadDialog.open)closeKeypadDialog();
  location.replace(parentUrl);
});
document.querySelector('.back').href=parentUrl;
document.querySelector('.page-home').onclick=()=>embedded?window.parent.__acoCutoffHome():location.replace(homeUrl);
document.querySelector('.back').addEventListener('click',e=>{e.preventDefault();embedded?window.parent.__acoCutoffBack():history.back();});
window.__acoBack=()=>{
  if(cancelTopDialog())return'handled';
  document.querySelector('.back').click();return'handled';
};
document.addEventListener('pointerdown', e => { if (e.pointerType === 'touch' && !e.target.closest('input,textarea')) e.preventDefault(); }, {capture:true,passive:false});
document.addEventListener('contextmenu', e => { if (!e.target.closest('input,textarea')) e.preventDefault(); });
const dialog = $('confirmation');
const lowVoltageDialog = $('lowVoltageConfirmation');
const keypadDialog = $('cutoffKeypad');
let lastState = null;
let pending = null;
let busy = false;
let edited = false;
let keypadEditor = null;
let pageActive=!embedded,lifecycle=0;
function message(text, error = false) {
  $('message').textContent = text;
  $('message').classList.toggle('error', error);
}
function setBusy(value) {
  busy = value;
  for (const id of ['shutdownInput', 'termInput', 'apply', 'restore']) $(id).disabled = value || !lastState;
  for (const id of ['refresh', 'confirm', 'cancel', 'lowVoltageConfirm', 'lowVoltageCancel']) $(id).disabled = value;
}
async function run(args) {
  const result = await exec(`${command} ${args}`);
  if (Number(result.errno) !== 0) throw new Error(result.stderr?.trim() || '操作失败，请刷新状态。');
  try { return JSON.parse(result.stdout.trim()); }
  catch { throw new Error('未收到有效状态，请重新打开 App 完成初始化。'); }
}
function voltage(id, value, forced) {
  const reading=document.createElement('span'),mode=document.createElement('span');
  reading.className='voltage-value';reading.textContent=`${value || '—'} mV`;
  mode.className='voltage-mode';mode.textContent=forced==='1'?'手动设置':'厂商策略';
  $(id).replaceChildren(reading,mode);
}
function render(state, replaceInputs = false) {
  lastState = state;
  voltage('shutdownLive', state.shutdown, state.shutdownForced);
  voltage('termLive', state.term, state.termForced);
  if (!edited || replaceInputs) {
    $('shutdownInput').value = state.shutdown;
    $('termInput').value = state.term;
    edited = false;
  }
}
async function refresh() {
  if (busy || dialog.open || lowVoltageDialog.open || keypadDialog.open) return;
  const token=lifecycle;
  setBusy(true);
  try { const state=await run('status');if(token===lifecycle){render(state);message('');} }
  catch (e) { if(token===lifecycle)message(e.message, true); }
  finally { setBusy(false);if(embedded&&pageActive&&token!==lifecycle)refresh(); }
}
async function plan(action) {
  if (busy || !lastState || dialog.open || lowVoltageDialog.open) return;
  let args = 'plan stock';
  if (action === 'set') {
    const shutdown = $('shutdownInput').value;
    const term = $('termInput').value;
    if (![shutdown, term].every(v => /^\d{4}$/.test(v) && Number(v) >= 2600 && Number(v) <= 3400)) {
      message('请输入 2600–3400 mV 的整数电压。', true); return;
    }
    args = `plan set ${shutdown} ${term}`;
  }
  setBusy(true);
  message('');
  const token=lifecycle;
  try {
    const result=await run(args);if(token!==lifecycle)return;pending=result;
    $('confirmTitle').textContent = action === 'set' ? '确定修改截止电压？' : '确定恢复厂商截止电压？';
    $('confirmText').textContent = action === 'set'
      ? `电量计截止：${pending.term} mV\n软件关机：${pending.shutdown} mV\n\n请确认这两个设置值。`
      : '取消这两个截止电压的手动覆盖，交回厂商动态策略。恢复值由驱动决定。';
    dialog.showModal();
  } catch (e) { if(token===lifecycle){pending = null; message(e.message, true);} }
  finally { setBusy(false);if(embedded&&pageActive&&token!==lifecycle)refresh(); }
}
$('voltageForm').addEventListener('submit', e => { e.preventDefault(); plan('set'); });
$('restore').addEventListener('click', () => plan('stock'));
for (const id of ['shutdownInput', 'termInput']) {
  const input=$(id);
  input.addEventListener('pointerdown',e=>e.preventDefault());
  input.addEventListener('click',()=>openKeypad(id));
  input.addEventListener('keydown',e=>{if(e.key==='Enter'||e.key===' '){e.preventDefault();openKeypad(id);}});
  input.addEventListener('input',()=>{edited=true;});
}
function openKeypad(id,push=true){
  const input=$(id);if(busy||keypadDialog.open||!lastState||input.disabled)return;
  keypadEditor={id,input,draft:input.value,fresh:true};
  $('cutoffKeypadTitle').textContent=id==='termInput'?'电量计截止电压':'软件关机电压';
  $('cutoffKeypadRange').textContent=`${input.min}–${input.max} mV\n整数`;
  $('cutoffKeypadValue').textContent=keypadEditor.draft||'—';$('cutoffKeypadError').hidden=true;
  keypadDialog.querySelector('[data-key=sign]').disabled=true;
  keypadDialog.querySelector('[data-key=dot]').disabled=true;
  if(push){if(embedded)window.parent.__acoCutoffKeypad(id);else history.pushState({...history.state,page:'cutoff',keypad:true,key:id},'',`${cutoffUrl}#keypad-${id}`);}
  keypadDialog.showModal();$('cutoffKeypadCancel').focus({preventScroll:true});
}
function closeKeypadDialog(){if(keypadDialog.open)keypadDialog.close();keypadEditor=null;}
function dismissKeypad(){if(embedded){if(window.parent.history.state?.page==='cutoff'&&window.parent.history.state?.keypad)window.parent.history.back();else closeKeypadDialog();}else if(history.state?.page==='cutoff'&&history.state?.keypad)history.back();else closeKeypadDialog();}
function drawKeypad(){ $('cutoffKeypadValue').textContent=keypadEditor?.draft||'—'; }
function pressKey(key){
  const editor=keypadEditor;if(!editor)return;
  $('cutoffKeypadError').hidden=true;
  if(key==='clear'){editor.draft='';editor.fresh=false;}
  else if(key==='backspace'){editor.draft=editor.draft.slice(0,-1);editor.fresh=false;}
  else if(/^(?:[0-9]|00)$/.test(key)){
    if(editor.fresh){editor.draft='';editor.fresh=false;}
    for(const digit of key)if(editor.draft.length<4)editor.draft+=digit;
  }
  drawKeypad();
}
function commitKeypad(){
  const editor=keypadEditor;if(!editor)return;
  const value=Number(editor.draft);
  if(!/^\d{4}$/.test(editor.draft)||value<Number(editor.input.min)||value>Number(editor.input.max)){
    $('cutoffKeypadError').textContent=`请输入 ${editor.input.min}–${editor.input.max} mV 的整数。`;
    $('cutoffKeypadError').hidden=false;return;
  }
  editor.input.value=String(value);editor.input.dispatchEvent(new Event('input',{bubbles:true}));dismissKeypad();
}
for(const button of keypadDialog.querySelectorAll('[data-key]'))button.onclick=()=>pressKey(button.dataset.key);
$('cutoffKeypadCancel').onclick=dismissKeypad;
$('cutoffKeypadApply').onclick=commitKeypad;
keypadDialog.addEventListener('cancel',e=>{e.preventDefault();dismissKeypad();});
keypadDialog.addEventListener('click',e=>{const r=keypadDialog.getBoundingClientRect();if(e.target===keypadDialog&&(e.clientY<r.top||e.clientY>r.bottom||e.clientX<r.left||e.clientX>r.right))dismissKeypad();});
keypadDialog.addEventListener('keydown',e=>{
  let key=/^[0-9]$/.test(e.key)?e.key:{Backspace:'backspace',Delete:'clear'}[e.key];
  if(key){e.preventDefault();pressKey(key);}else if(e.key==='Enter'){e.preventDefault();commitKeypad();}
});
$('cancel').addEventListener('click', () => { pending = null; dialog.close(); });
dialog.addEventListener('cancel', e => { if (busy) e.preventDefault(); else pending = null; });
$('lowVoltageCancel').addEventListener('click', () => { pending = null; lowVoltageDialog.close(); });
lowVoltageDialog.addEventListener('cancel', e => { if (busy) e.preventDefault(); else pending = null; });
async function commit(acceptedLowVoltage = false) {
  if (busy || !pending) return;
  const choice = pending;
  if (!['set', 'stock'].includes(choice.action) || !/^[a-f0-9-]{36}$/.test(choice.token)) {
    message('确认凭据无效，请刷新后重试。', true); return;
  }
  const lowVoltage=choice.action==='set'&&(Number(choice.shutdown)<2700||Number(choice.term)<2700);
  if(lowVoltage&&!acceptedLowVoltage){
    $('lowVoltageText').textContent=`电量计截止：${choice.term} mV\n软件关机：${choice.shutdown} mV\n\n设置低于 2700 mV 的电压可能会对电池造成损伤。是否仍要应用？`;
    dialog.close();lowVoltageDialog.showModal();return;
  }
  if(acceptedLowVoltage&&(!lowVoltage||!lowVoltageDialog.open))return;
  setBusy(true);
  try {
    render(await run(`commit ${choice.token}`), true);
    message(choice.action === 'set' ? '两个截止电压已应用。' : '两个截止电压已交回厂商策略。');
    pending = null;
    dialog.close();
    lowVoltageDialog.close();
  } catch (e) {
    pending = null;
    dialog.close();
    lowVoltageDialog.close();
    message(e.message, true);
    try { render(await run('status')); } catch { /* Keep the original error. */ }
  } finally { setBusy(false); }
}
$('confirm').addEventListener('click', () => commit());
$('lowVoltageConfirm').addEventListener('click', () => commit(true));
$('refresh').addEventListener('click', refresh);
document.addEventListener('visibilitychange', () => { if (!document.hidden&&pageActive) refresh(); });
window.__acoCutoffSync=state=>{if(state?.keypad){if(!keypadDialog.open)openKeypad(state.key,false);}else if(keypadDialog.open)closeKeypadDialog();};
window.__acoCutoffOpen=()=>{cutoffTheme.apply();pageActive=true;lifecycle++;edited=false;refresh();};
window.__acoCutoffClose=()=>{pageActive=false;lifecycle++;closeKeypadDialog();if(dialog.open)dialog.close();if(lowVoltageDialog.open)lowVoltageDialog.close();pending=null;};
if(embedded)window.parent.__acoCutoffReady();else refresh();
