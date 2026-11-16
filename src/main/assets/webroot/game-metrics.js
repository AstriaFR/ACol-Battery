const energy=64,energyInputs=1|8,minimumFps=32;

export function gameMetricSelection(group,mask,bit,enabled){
  let next=enabled?mask|bit:mask&~bit;
  if(group==='battery'){
    if(bit===energy&&enabled)next|=energyInputs;
    if((next&energyInputs)!==energyInputs)next&=~energy;
  }
  return next;
}

export function createGameMetricsPage(api){
  const root=document.getElementById('gameMetricsPage');
  const controls=document.getElementById('gameMetricsControls');
  const status=document.getElementById('gameMetricsStatus');
  const toggles=[...controls.querySelectorAll('[data-game-metric]')];
  const sources=[...controls.querySelectorAll('[data-game-fps-source]')];
  let saved=null,pending=null;

  function render(){
    controls.disabled=!!pending||!saved;
    if(!saved)return;
    for(const input of toggles){
      input.checked=(saved[input.dataset.gameMetric]&Number(input.value))!==0;
    }
    const singleLayer=(saved.performance&minimumFps)!==0;
    for(const button of sources){
      button.disabled=singleLayer;
      button.setAttribute('aria-pressed',String(Number(button.value)===(singleLayer?0:Number(saved.taskFpsCallback))));
    }
  }
  async function request(action,...args){
    if(pending)await pending;
    status.hidden=true;
    pending=api(action,...args).then(result=>{saved=result;},error=>{
      status.textContent=error.message;
      status.hidden=false;
    });
    render();
    try{await pending;}
    finally{pending=null;render();}
  }
  for(const input of toggles)input.onchange=()=>{
    if(!saved||pending)return;
    const group=input.dataset.gameMetric,bit=Number(input.value);
    const next={...saved,[group]:gameMetricSelection(group,saved[group],bit,input.checked)};
    request('set_game_metrics',next.battery,next.performance);
  };
  for(const button of sources)button.onclick=()=>{
    if(!saved||pending||button.disabled||Number(button.value)===Number(saved.taskFpsCallback))return;
    request('set_game_metrics',saved.battery,saved.performance,Number(button.value));
  };
  return {
    get active(){return !root.hidden;},
    open(){
      if(!root.hidden)return;
      root.hidden=false;
      saved=null;
      request('game_metrics');
    },
    close(){root.hidden=true;}
  };
}
