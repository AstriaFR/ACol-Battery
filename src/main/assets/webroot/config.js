export const DEFAULT_SETTINGS=Object.freeze({deletePct:5,hidePct:30,energyWh:null,capacityAh:5.60,capacityAuto:true,voltageMin:3.00,voltageMax:4.50,tempMin:30,tempMax:40,screenOnMinutes:2,screenOffMinutes:30,fastSeconds:10,fastAutoOff:true,chargeSeconds:2,performanceSeconds:2});
export const settingsKeys=Object.keys(DEFAULT_SETTINGS);
export function encodeSettings(value){
  const finite=v=>typeof v==='number'&&Number.isFinite(v);
  const step=(v,n)=>Math.abs(v*n-Math.round(v*n))<1e-7;
  const integer=(v,lo,hi)=>finite(v)&&Number.isInteger(v)&&v>=lo&&v<=hi;
  if(!integer(value.deletePct,0,40)||!integer(value.hidePct,0,80))throw Error('删除或隐藏百分比无效。');
  if(value.energyWh!==null&&(!finite(value.energyWh)||value.energyWh<0||value.energyWh>100||!step(value.energyWh,100)))throw Error('默认能量量程无效。');
  if(!finite(value.capacityAh)||value.capacityAh<.01||value.capacityAh>20||!step(value.capacityAh,100))throw Error('容量数值无效。');
  if(![value.voltageMin,value.voltageMax].every(v=>finite(v)&&v>=2.2&&v<=4.8&&step(v,100))||value.voltageMin>=value.voltageMax)throw Error('电压数值无效，且下限须小于上限。');
  if(![value.tempMin,value.tempMax].every(v=>integer(v,-20,60))||value.tempMin>=value.tempMax)throw Error('温度数值无效，且下限须小于上限。');
  if(!integer(value.screenOnMinutes,1,10)||!integer(value.screenOffMinutes,1,120)||!integer(value.fastSeconds,1,60)||!integer(value.performanceSeconds,1,60)||!integer(value.chargeSeconds,1,10))throw Error('记录间隔超出范围，仅限整数。');
  if(typeof value.fastAutoOff!=='boolean')throw Error('自动关闭设置无效。');
  return[value.deletePct,value.hidePct,Math.round(value.capacityAh*100),value.capacityAuto?1:0,Math.round(value.voltageMin*100),Math.round(value.voltageMax*100),value.tempMin+20,value.tempMax+20,value.screenOnMinutes,value.screenOffMinutes,value.fastSeconds,value.fastAutoOff?1:0,value.chargeSeconds,value.performanceSeconds,value.energyWh===null?10001:Math.round(value.energyWh*100)];
}
