// Current cell-mean voltage (0.01 V) -> expected remaining mean voltage (0.001 V).
const table=[
  [445,3870],[440,3850],[435,3815],[430,3787],[425,3760],[420,3735],
  [415,3710],[410,3685],[405,3659],[400,3632],[395,3604],[390,3574],
  [385,3542],[380,3508],[375,3474],[370,3440],[365,3407],[360,3376],
  [355,3348],[350,3320],[345,3290],[340,3250],[335,3210],[330,3170],
  [325,3140],[320,3100],[315,3070],[310,3000],[300,2950],[290,2900]
];
const finite=v=>typeof v==='number'&&Number.isFinite(v);
export function remainingMeanVoltage(millivolts){
  if(!finite(millivolts)||millivolts<=0)return null;
  const cv=Math.round(millivolts/10);
  if(cv<290)return cv/100;
  if(cv>=445)return 3.870;
  for(let i=1;i<table.length;i++){
    const [hi,meanHi]=table[i-1],[lo,meanLo]=table[i];
    if(cv>=lo)return Math.round(meanLo+(meanHi-meanLo)*(cv-lo)/(hi-lo))/1000;
  }
  return null;
}
export function remainingEstimate(reading,averagePower){
  const mean=remainingMeanVoltage(reading?.voltage);
  if(mean===null||!finite(reading?.rm)||reading.rm<0)return null;
  // battery_rm is the equivalent remaining mAh used by the discharge accounting.
  // Use the single-cell mean voltage basis without another 2S multiplier.
  const wh=reading.rm*mean/1000;
  const minutes=finite(averagePower)&&averagePower>0?Math.round(wh/averagePower*60):null;
  return{wh,whText:wh.toFixed(wh<2?2:1),minutes,voltage:Math.round(reading.voltage/10)/100,mean,
    averagePower:finite(averagePower)&&averagePower>0?averagePower:null,wall:reading.wall};
}
export function remainingTime(minutes,wh){
  if(minutes===null||!Number.isSafeInteger(minutes)||minutes<0)return'—';
  if(minutes===0)return wh>0?'少于 1 分钟':'0 分钟';
  const h=Math.floor(minutes/60),m=minutes%60;
  return h?`${h}小时${m}分`:`${m}分钟`;
}
