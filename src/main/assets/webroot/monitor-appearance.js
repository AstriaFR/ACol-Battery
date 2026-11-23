import{installMonitorOrder}from'./monitor-order.js';

const $=id=>document.getElementById(id);
const thresholdGroups=[
  {first:1,last:3,unit:'%',max:100},
  {first:7,last:9,unit:'W',max:999},
  {first:10,last:12,unit:'℃',max:999}
];
const thresholdGroup=index=>thresholdGroups.find(group=>index>=group.first&&index<=group.last);

export function createMonitorAppearancePage(api,keypad){
  const root=$('monitorAppearancePage'),controls=$('monitorAppearanceControls');
  const status=$('monitorAppearanceStatus');
  const sliders=[...root.querySelectorAll('[data-monitor-slider]')].map(input=>({
    input,output:root.querySelector(`output[for="${input.id}"]`),index:Number(input.dataset.monitorSlider)
  }));
  const followSystem=$('monitorFollowSystem');
  const options=[...root.querySelectorAll('[data-monitor-option]')];
  const reset=$('resetMonitorAppearance');
  const switches=[...root.querySelectorAll('[data-monitor-bit]')];
  const thresholds=[...root.querySelectorAll('[data-monitor-threshold]')];
  let values=null,order=null,pending=null;
  const metricOrder=installMonitorOrder($('monitorMetricOrder'),
    ()=>!!values&&!controls.disabled&&!pending&&!root.hidden,
    next=>request('set_monitor_order',[next]).catch(()=>{}));

  function message(text){
    status.textContent=text;
    status.hidden=!text;
  }
  function fill(saved,savedOrder){
    values=saved;order=savedOrder;
    metricOrder.apply(order);
    for(const input of switches)input.checked=!!(saved[0]&Number(input.dataset.monitorBit));
    for(const button of thresholds){
      const index=Number(button.dataset.monitorThreshold);
      button.textContent=`${saved[index]}${thresholdGroup(index).unit}`;
    }
    for(const {input,output,index} of sliders){
      input.value=saved[index];
      output.textContent=`${saved[index]}%`;
    }
    followSystem.checked=saved[13]===1;
    for(const button of options){
      const index=Number(button.dataset.monitorOption);
      button.disabled=index===5&&followSystem.checked;
      button.setAttribute('aria-pressed',String(!button.disabled&&Number(button.value)===saved[index]));
    }
  }
  async function request(action,args=[]){
    if(pending)await pending.catch(()=>{});
    metricOrder.cancel();
    controls.disabled=reset.disabled=true;
    message('');
    const work=api(action,...args);
    pending=work;
    try{const result=await work;fill(result.values,result.order);}
    catch(error){
      if(values)fill(values,order);
      message(error.message);
      throw error;
    }finally{
      pending=null;
      controls.disabled=reset.disabled=!values;
    }
  }
  function change(index,value){
    if(!values||pending)return;
    const updated=[...values];
    updated[index]=value;
    return request('set_monitor_appearance',updated);
  }
  for(const input of switches)input.onchange=()=>{
    if(!values||pending)return;
    const bit=Number(input.dataset.monitorBit);
    change(0,input.checked?values[0]|bit:values[0]&~bit)?.catch(()=>{});
  };
  for(const {input,output,index} of sliders){
    input.oninput=()=>{output.textContent=`${input.value}%`;};
    input.onchange=()=>change(index,Number(input.value))?.catch(()=>{});
  }
  followSystem.onchange=()=>change(13,followSystem.checked?1:0)?.catch(()=>{});
  for(const button of options)button.onclick=()=>{
    const index=Number(button.dataset.monitorOption),value=Number(button.value);
    if(values&&value!==values[index])change(index,value)?.catch(()=>{});
  };
  reset.onclick=()=>request('reset_monitor_appearance').catch(()=>{});

  function editThreshold(button,push){
    if(!values||pending||root.hidden)return;
    const index=Number(button.dataset.monitorThreshold);
    const group=thresholdGroup(index),unit=group.unit;
    const min=index===group.first?0:values[index-1]+1;
    const max=index===group.last?group.max:values[index+1]-1;
    keypad.openEditor({
      draft:String(values[index]),decimal:false,signed:false,digits:0,
      commit:async value=>{
        if(!Number.isInteger(value)||value<min||value>max)throw Error(`请输入 ${min}–${max} ${unit} 的整数。`);
        await change(index,value);
      },
      restore:()=>request('reset_monitor_threshold',[index])
    },button.getAttribute('aria-label'),unit,`${min}–${max} ${unit}\n整数`,
    push?{page:'monitor-appearance',keypad:true,key:index}:null);
  }
  for(const button of thresholds)button.onclick=()=>editThreshold(button,true);

  function handleHistory(entry){
    if(entry?.keypad){
      const button=thresholds.find(item=>Number(item.dataset.monitorThreshold)===entry.key);
      if(button)editThreshold(button,false);
    }else if(keypad.editor?.commit)keypad.closeEditor();
  }
  return {
    get active(){return !root.hidden;},
    open(){
      if(!root.hidden)return;
      root.hidden=false;
      request('monitor_appearance').then(()=>{
        if(history.state?.page==='monitor-appearance')handleHistory(history.state);
      }).catch(()=>{});
    },
    handleHistory,
    close(){
      if(root.hidden)return;
      metricOrder.cancel();
      root.hidden=true;
      if(keypad.editor?.commit)keypad.closeEditor();
    }
  };
}
