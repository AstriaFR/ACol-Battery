// OPLUS SM8750 v2 charger driver tables. Array index 0 here corresponds to level 1.
const bidirectional=[1500,1500,2000,2500,3000,3500,4000,4500,5000,5500,6000,6300,6500,7000,7300,
  8000,8500,9000,9500,10000,10500,11000,11500,12000,12500,13000,13500,14000,14500,15000];
const chargePump=[2000,2000,2400,3000,3400,4000,4400,5000,5400,6000,6400,7000,7400,8000,
  9000,10000,11000,12000,12600,13000,14000,15000,16000,17000,18000,19000,20000];
const voocTables=[
  [2000,3000,4000,5000,6000,7000,8000,9000,10000,1200,12000,12600],
  [2000,2000,2000,3000,4000,5000,6000,7000,8000,9000,10000,11000,12000,12600],
  bidirectional.slice(0,25),
  chargePump
];
const $=id=>document.getElementById(id);

export function createCoolDownDetailsPage(api){
  const root=$('coolDownDetailsPage'),status=$('coolDownDetailsStatus'),tables=$('coolDownTables');
  let revision=0;

  function message(text){
    status.textContent=text;
    status.hidden=!text;
  }

  function appendText(parent,tag,value){
    const node=document.createElement(tag);
    node.textContent=value;
    parent.append(node);
    return node;
  }

  function section(title,note,columns=[]){
    const group=document.createElement('section');
    group.className='advanced-definitions';
    appendText(group,'h2',title);
    appendText(group,'p',note);
    const max=Math.max(0,...columns.map(column=>column.values.length));
    if(max){
      const table=document.createElement('table');
      table.className='cool-down-table';
      table.setAttribute('aria-label',title);
      const header=table.createTHead().insertRow();
      for(const label of ['档位',...columns.map(column=>column.label)])appendText(header,'th',label).scope='col';
      const body=table.createTBody();
      const zero=body.insertRow();
      appendText(zero,'th','0').scope='row';
      for(const {zeroValue='—'} of columns)appendText(zero,'td',String(zeroValue));
      for(let level=1;level<=max;level++){
        const row=body.insertRow();
        appendText(row,'th',String(level)).scope='row';
        for(const {values} of columns){
          const value=values[level-1];
          appendText(row,'td',Number.isInteger(value)&&value>=0?String(value):'—');
        }
      }
      group.append(table);
    }
    tables.append(group);
  }

  function render(details){
    const wired=details?.wired;
    for(const [key,title] of [['normal','普通有线'],['pdqc','PD / QC'],['vooc','VOOC 有线输入']]){
      const current=wired?.[key];
      if(!current?.length)continue;
      const columns=[{label:'输入限流（mA）',values:current}];
      if(key==='pdqc'&&wired.pdqcVoltage?.length){
        columns.push({label:'电压档（mV）',values:wired.pdqcVoltage.slice(0,current.length)});
      }
      section(title,`设备树实读，上限 ${current.length} 档。更高档位按末档处理。`,columns);
    }
    if(details?.vooc){
      const {dataWidth,tableType,max}=details.vooc;
      const values=voocTables[tableType];
      if(dataWidth===7&&values&&max>0){
        const limit=Math.min(max,values.length);
        const conversion=tableType===3?'用于 SUPERVOOC 时，驱动还会换算为协议侧电流。':'';
        section('VOOC / SUPERVOOC 快充',`设备配置到 ${max} 档，电流来自公开驱动参考表。${conversion}`,
          [{label:'查表电流（mA）',values:values.slice(0,limit)}]);
      }else{
        section('VOOC / SUPERVOOC 快充','未读到可匹配的快充档位配置。');
      }
    }
    if(details?.pps){
      section('PPS 快充',`公开驱动参考值。长表到 ${bidirectional.length} 档，短表到 ${chargePump.length} 档。短表超出范围时按末档处理。驱动还会按电荷泵倍率换算为协议侧电流。`,[
        {label:'长表（mA）',values:bidirectional,zeroValue:bidirectional.at(-1)},
        {label:'短表（mA）',values:chargePump,zeroValue:chargePump.at(-1)}
      ]);
    }
    if(details?.ufcs){
      const type=details.ufcs.tableType;
      const values=type===0?bidirectional:type===1?chargePump:null;
      if(values){
        section('UFCS 快充',`按设备配置选用公开驱动参考表，上限 ${values.length} 档。更高档位按末档处理。驱动还会按电荷泵倍率换算为协议侧电流。`,
          [{label:'查表电流（mA）',values,zeroValue:values.at(-1)}]);
      }else section('UFCS 快充','未读到可匹配的快充档位配置。');
    }
    message(tables.childElementCount?'':'未读到档位配置。');
  }

  return{
    get active(){return !root.hidden;},
    async open(){
      if(!root.hidden)return;
      const requested=++revision;
      root.hidden=false;
      root.querySelector('.page-scroll').scrollTop=0;
      tables.replaceChildren();
      message('正在读取…');
      try{
        const {details}=await api('cool_down_details');
        if(requested===revision)render(details);
      }catch(error){if(requested===revision)message(error.message);}
    },
    close(){revision++;root.hidden=true;}
  };
}
