// Current cell-mean voltage (0.01 V) -> expected remaining mean voltage (0.001 V).
const table=[
  [445,3875],[440,3855],[435,3820],[430,3795],[425,3768],[420,3742],
  [415,3718],[410,3692],[405,3665],[400,3640],[395,3612],[390,3582],
  [385,3550],[380,3520],[375,3490],[370,3460],[365,3430],[360,3400],
  [355,3370],[350,3340],[345,3310],[340,3280],[335,3250],[330,3210],
  [325,3180],[320,3150],[315,3120],[310,3080],[305,3040],[300,3000]
];
const highCapacityTable=[
  ...table.map(([voltage,mean])=>[voltage,mean-75]),
  [290,2850],[280,2780],[270,2700]
];
const finite=v=>typeof v==='number'&&Number.isFinite(v);
export function canEstimateRemaining(record,reading){
  return !(Number(record?.fast_seconds)>0)||(reading?.onlineKnown!==false&&reading?.online===false);
}
export function remainingMeanVoltage(millivolts,highDesignCapacity=false){
  if(!finite(millivolts)||millivolts<=0)return null;
  const cv=Math.round(millivolts/10);
  const points=highDesignCapacity?highCapacityTable:table;
  if(cv<points.at(-1)[0])return cv/100;
  if(cv>=points[0][0])return points[0][1]/1000;
  for(let i=1;i<points.length;i++){
    const [hi,meanHi]=points[i-1],[lo,meanLo]=points[i];
    if(cv>=lo)return Math.round(meanLo+(meanHi-meanLo)*(cv-lo)/(hi-lo))/1000;
  }
  return null;
}
export function remainingEstimate(reading,averagePower,highDesignCapacity){
  if(reading?.cellCount===0)return null;
  const mean=remainingMeanVoltage(reading?.voltage,highDesignCapacity);
  if(mean===null||!finite(reading?.rm)||reading.rm<0)return null;
  // battery_rm is the equivalent remaining mAh used by the discharge accounting.
  // Use the single-cell mean voltage basis without another 2S multiplier.
  const wh=reading.rm*mean/1000;
  const minutes=finite(averagePower)&&averagePower>0?Math.round(wh/averagePower*60):null;
  return{wh,whText:wh.toFixed(wh<2?2:1),minutes,voltage:Math.round(reading.voltage/10)/100,mean,
    averagePower:finite(averagePower)&&averagePower>0?averagePower:null,wall:reading.wall};
}
export const unclassifiedCells=32;
export function remainingTime(minutes,wh){
  if(minutes===null||!Number.isSafeInteger(minutes)||minutes<0)return'—';
  if(minutes===0)return wh>0?'少于 1 分钟':'0 分钟';
  const h=Math.floor(minutes/60),m=minutes%60;
  return h?`${h}小时${m}分`:`${m}分钟`;
}
