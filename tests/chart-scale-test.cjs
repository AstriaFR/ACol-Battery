const path=require('path'),assert=require('assert/strict');
const {pathToFileURL}=require('url');
(async()=>{
 const {Chart,series,signalScale,setChartSettings,plotPoints}=await import(pathToFileURL(path.join(__dirname,'../src/main/assets/webroot/chart.js')).href);
 const current=series.find(s=>s.key==='current'),power=series.find(s=>s.key==='power');
 const next=(s,values,previous,kind='charge',fast=false)=>signalScale(s,values,kind,fast,previous);
 let scale=next(current,[0,10000]);assert.equal(scale.full,10000);assert.equal(scale.label,'10 A');
 for(const peak of [10000,10001,10999.9]){scale=next(current,[0,peak],scale);assert.equal(scale.full,10000);}
 scale=next(current,[0,11000],scale);assert.equal(scale.full,12000);assert.equal(scale.label,'12 A');
 scale=next(current,[13199.9],scale);assert.equal(scale.full,12000);
 scale=next(current,[13200],scale);assert.equal(scale.full,14000);
 scale=next(current,[500],scale);assert.equal(scale.full,14000); // The record range never shrinks.
 scale=next(current,[27500],scale);assert.equal(scale.full,26000); // Select the first tier whose 110% contains the peak.
 for(const peak of [10000,10190,10999.9])assert.equal(next(current,[peak]).full,10000);
 assert.equal(next(current,[11000]).full,12000);
 assert.equal(next(current,[13199.9]).full,12000);
 assert.equal(next(current,[13200]).full,14000);
 const negative=next(current,[-11000],next(current,[-10000]));
 assert.equal(negative.full,12000);assert.equal(negative.min,-11000);
 for(const [kind,fast]of [['charge',false]]){
  let s=next(power,[0,50],null,kind,fast);assert.equal(s.full,50);assert.equal(s.label,'50 W');
  for(const peak of [50,50.1,54.999]){s=next(power,[peak],s,kind,fast);assert.equal(s.full,50);}
  s=next(power,[55],s,kind,fast);assert.equal(s.full,60);assert.equal(s.label,'60 W');
  s=next(power,[65.999],s,kind,fast);assert.equal(s.full,60);
  s=next(power,[66],s,kind,fast);assert.equal(s.full,70);
  s=next(power,[129],s,kind,fast);assert.equal(s.full,120);
  s=next(power,[1],s,kind,fast);assert.equal(s.full,120);
 }
 assert.equal(next(power,[42.65118]).full,40); // Actual peak from charge record 111.
 assert.equal(next(power,[43.9999]).full,40);
 assert.equal(next(power,[44]).full,50);
 assert.equal(next(power,[54.9999]).full,50);
 assert.equal(next(power,[55]).full,60);
 // Opening a complete record and growing its peak live must select identical tiers.
 for(const [signal,increment,count]of [[current,137,500],[power,.731,400]]){
  let live=null;
  for(let i=0;i<count;i++){
   const peak=i*increment;live=next(signal,[peak],live);
   assert.equal(live.full,next(signal,[peak]).full,signal.key+' first/live mismatch at '+peak);
  }
 }
 let ordinary=next(power,[6.19],null,'discharge');assert.equal(ordinary.full,6.19);
 ordinary=next(power,[6.2],ordinary,'discharge');assert.equal(ordinary.full,6.2);
 ordinary=next(power,[6.8089],ordinary,'discharge');assert.equal(ordinary.full,6.8089);
 ordinary=next(power,[6.809],ordinary,'discharge');assert.equal(ordinary.full,6.809);
 ordinary=next(power,[20,-1],ordinary,'discharge');assert.equal(ordinary.full,6.809);assert.equal(ordinary.min,0);
 const absent=next(power,[null,NaN,-1,16],null,'discharge');assert.equal(absent.full,1);assert.equal(absent.label,'暂无峰值');
 let game=next(power,[50],null,'discharge',true);assert.equal(game.full,50);
 game=next(power,[50.01],game,'discharge',true);assert.equal(game.full,50.01);
 game=next(power,[54.999],game,'discharge',true);assert.equal(game.full,54.999);
 game=next(power,[55],game,'discharge',true);assert.equal(game.full,55);
 game=next(power,[60.499],game,'discharge',true);assert.equal(game.full,60.499);
 game=next(power,[60.5],game,'discharge',true);assert.equal(game.full,60.5);
 game=next(power,[1],game,'discharge',true);assert.equal(game.full,60.5);
 assert.equal(next(current,[]).full,2000);
 assert.equal(next(power,[]).full,10);
 // Current range must use the displayed single-/dual-cell current basis.
 for(const [cells,expected]of [[1,6000],[2,10000]]){
  const fake={cellCount:cells,kind:'charge',fast:false,ranges:{current:[0,5000]},points:[],scaleCache:new Map()};
  const actual=Chart.prototype.scaleFor.call(fake,current);assert.equal(actual.full,expected);
 }
 const points=[[0,5000,50,5000,4000,50,0,null,4000,4000,30]];
 const copy=JSON.stringify(points);assert.equal(plotPoints(points,'charge',2)[0][3],10000);assert.equal(JSON.stringify(points),copy);
 setChartSettings({capacityAh:5.6,capacityAuto:true});
 const capacity=series.find(s=>s.key==='rm');
 const capacityStart=next(capacity,[5600]);assert.equal(next(capacity,[5601],capacityStart).full,5601);
 console.log('Chart scales: 110% boundary, charging 2 A / 10 W grids, discharge/game peak policy, dual-cell basis and capacity policy passed');
})().catch(error=>{console.error(error);process.exitCode=1;});
