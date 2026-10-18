const prefix='battery_curves.legend.';
const defaults={charge:['current','voltage','power','temp'],discharge:['rm','pct','voltage','power'],game:['rm','pct','voltage','power']};
export function loadSelection(kind,available){
  const allowed=new Set(available);
  try{
    const stored=JSON.parse(localStorage.getItem(prefix+kind));
    if(Array.isArray(stored))return new Set(stored.filter(key=>allowed.has(key)));
  }catch{}
  return new Set((defaults[kind]??[]).filter(key=>allowed.has(key)));
}
export function saveSelection(kind,enabled,available){
  try{localStorage.setItem(prefix+kind,JSON.stringify(available.filter(key=>enabled.has(key))));return true;}
  catch{return false;}
}
const homePriorityKey='acol.home.recordPriority';
export function loadHomePriority(){
  try{return localStorage.getItem(homePriorityKey)==='percentage'?'percentage':'capacity';}
  catch{return'capacity';}
}
export function saveHomePriority(value){
  if(!['capacity','percentage'].includes(value))return false;
  try{localStorage.setItem(homePriorityKey,value);return true;}
  catch{return false;}
}
