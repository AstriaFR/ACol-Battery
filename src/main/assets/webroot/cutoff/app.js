import { exec, cancelTopDialog } from '../root-bridge.js';
const command = '/system/bin/sh /data/adb/aco_battery_app/runtime/cutoff.sh';
const $ = id => document.getElementById(id);
const homeUrl=new URL('../index.html',location.href).href,cutoffUrl=location.href;
const fromSettings=new URLSearchParams(location.search).get('from')==='settings',parentUrl=fromSettings?homeUrl+'#settings':homeUrl;
let fromHome=false;try{const ref=new URL(document.referrer);fromHome=ref.origin===location.origin&&!ref.pathname.includes('/cutoff/');}catch{}
if(!fromHome){history.replaceState({page:fromSettings?'settings':'home'},'',parentUrl);history.pushState({page:'cutoff'},'',cutoffUrl);}
else history.replaceState({page:'cutoff'},'',cutoffUrl);
window.addEventListener('popstate',e=>{if(e.state?.page!=='cutoff')location.replace(parentUrl);});
document.querySelector('.back').href=parentUrl;
document.querySelector('.back').addEventListener('click',e=>{e.preventDefault();history.back();});
window.__acoBack=()=>{
  if(cancelTopDialog())return'handled';
  document.querySelector('.back').click();return'handled';
};
document.addEventListener('pointerdown', e => { if (e.pointerType === 'touch' && !e.target.closest('input,textarea')) e.preventDefault(); }, {capture:true,passive:false});
document.addEventListener('contextmenu', e => { if (!e.target.closest('input,textarea')) e.preventDefault(); });
const dialog = $('confirmation');
const lowVoltageDialog = $('lowVoltageConfirmation');
let lastState = null;
let pending = null;
let busy = false;
let edited = false;
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
  if (busy || dialog.open || lowVoltageDialog.open) return;
  setBusy(true);
  try { render(await run('status')); message(''); }
  catch (e) { message(e.message, true); }
  finally { setBusy(false); }
}
async function plan(action) {
  if (busy || !lastState || dialog.open || lowVoltageDialog.open) return;
  let args = 'plan stock';
  if (action === 'set') {
    const shutdown = $('shutdownInput').value;
    const term = $('termInput').value;
    if (![shutdown, term].every(v => /^\d{4}$/.test(v) && Number(v) >= 2500 && Number(v) <= 3500)) {
      message('请输入 2500–3500 mV 的整数电压。', true); return;
    }
    args = `plan set ${shutdown} ${term}`;
  }
  setBusy(true);
  message('');
  try {
    pending = await run(args);
    $('confirmTitle').textContent = action === 'set' ? '确定修改截止电压？' : '确定恢复厂商截止电压？';
    $('confirmText').textContent = action === 'set'
      ? `电量计截止：${pending.term} mV\n软件关机：${pending.shutdown} mV\n\n请确认这两个设置值。`
      : '取消这两个截止电压的手动覆盖，交回厂商动态策略。恢复值由驱动决定。';
    dialog.showModal();
  } catch (e) { pending = null; message(e.message, true); }
  finally { setBusy(false); }
}
$('voltageForm').addEventListener('submit', e => { e.preventDefault(); plan('set'); });
$('restore').addEventListener('click', () => plan('stock'));
for (const id of ['shutdownInput', 'termInput']) $(id).addEventListener('input', () => { edited = true; });
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
document.addEventListener('visibilitychange', () => { if (!document.hidden) refresh(); });
refresh();
