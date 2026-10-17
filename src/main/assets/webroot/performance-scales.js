export const fpsLevels=[60,90,120,144,165,185,200,240];
export function fpsLimit(peak){
  // 100% is the legend value; only reaching the visible 110% boundary advances a tier.
  for(const limit of fpsLevels)if(!(peak*100>=limit*110))return limit;
  return (Math.floor(peak/11)+1)*10;
}
export function cpuLimit(clusters){
  let highest=0;for(const c of clusters??[])if(Number.isFinite(c.maxMHz))highest=Math.max(highest,c.maxMHz);
  return highest;
}
export function frequencyLimit(limit,value){
  return value*100>=limit*110?Math.max(limit+10,Math.ceil(value/10)*10):limit;
}
