let sequence=0;
// Only an explicit response from the page may allow the Activity to exit.
window.__acoBack=()=> 'wait';
export function cancelTopDialog(){
  const dialogs=[...document.querySelectorAll('dialog[open]')];
  const dialog=document.activeElement?.closest('dialog[open]')||dialogs.at(-1);
  if(!dialog)return false;
  const event=new Event('cancel',{cancelable:true});dialog.dispatchEvent(event);
  if(!event.defaultPrevented)dialog.close();
  return true;
}
const page=`${Date.now().toString(16)}-${Math.random().toString(16).slice(2,10)}`;
const pending=new Map();
class Emitter{constructor(){this.listeners=new Map();}on(name,handler){const list=this.listeners.get(name)||[];list.push(handler);this.listeners.set(name,list);return this;}emit(name,value){for(const handler of this.listeners.get(name)||[])handler(value);}}
function send(command,stream=false){
  const id=`${page}-${(++sequence).toString(16)}`;
  if(stream){
    const child=new Emitter();child.stdout=new Emitter();child.stderr=new Emitter();pending.set(id,{child});
    queueMicrotask(()=>{try{AcoNative.post(JSON.stringify({id,command,stream:true}));}catch{pending.delete(id);child.emit('error',Error('root 连接不可用，请重新打开 App。'));}});return child;
  }
  return new Promise((resolve,reject)=>{
    const timer=setTimeout(()=>{pending.delete(id);reject(Error('操作超时，请重试。'));},35000);pending.set(id,{resolve,reject,timer});
    try{AcoNative.post(JSON.stringify({id,command,stream:false}));}catch{clearTimeout(timer);pending.delete(id);reject(Error('root 连接不可用，请重新打开 App。'));}
  });
}
window.__acoDeliver=message=>{
  if(message.event==='disconnected'){
    for(const item of pending.values()){if(item.child)item.child.emit('error',Error('root 连接已断开，请重新打开 App。'));else{clearTimeout(item.timer);item.reject(Error('root 连接已断开，请重新打开 App。'));}}pending.clear();return;
  }
  const item=pending.get(message.id);if(!item)return;
  if(item.child){
    if(message.event==='stdout')item.child.stdout.emit('data',message.data);
    else if(message.event==='stderr')item.child.stderr.emit('data',message.data);
    else if(message.event==='exit'||message.event==='error'){pending.delete(message.id);item.child.emit(message.event,message.event==='error'?Error(message.message):message.code);}
  }else if(message.event==='result'||message.event==='error'){
    pending.delete(message.id);clearTimeout(item.timer);
    if(message.event==='error')item.reject(Error(message.message||'操作失败。'));else item.resolve({errno:message.errno,stdout:message.stdout||'',stderr:message.stderr||''});
  }
};
export function exec(command){return send(command);}
export function spawn(command,args=[]){return send([command,...args].join(' '),true);}
export function fullScreen(enabled){AcoNative.fullScreen(!!enabled);}
