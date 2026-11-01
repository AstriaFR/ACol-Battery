const path=require('node:path'),assert=require('node:assert/strict');
const{pathToFileURL}=require('node:url');
const{runCases}=require('./helpers.cjs');

async function scales(){
const {Chart,series,signalScale,setChartSettings,plotPoints,plotAxisMinimum}=await import(pathToFileURL(path.join(__dirname,'../src/main/assets/webroot/chart.js')).href);
 const signedScale={base:0,full:20,min:-15};
 for(const kind of ['charge','discharge']){
  assert.equal(plotAxisMinimum(kind,[signedScale],false),0);
  assert.equal(plotAxisMinimum(kind,[signedScale],true),-110);
 }
 const energy=series.find(s=>s.key==='energy');
 const energyRows=plotPoints([[0,5000,90,null,4000,3,0,30000],[30000,1000,20,null,3000,2,0,30000]],'discharge');
 assert.equal(energyRows[0][11],5000*3.632/1000);assert.equal(energyRows[1][11],2.95);
 assert.equal(plotPoints([[0,5000,90,null,null,3,0,30000]],'discharge')[0][11],null);
 const chargingRows=[
  [0,5000,90,null,4000,-12,0,3000,null,null,30,null,1],
  [3000,5001,90,null,4000,2,0,3000,null,null,30,null,1],
  [6000,4999,90,null,4000,2,0,3000,null,null,30,null,0]
 ];
 const gameEnergy=plotPoints(chargingRows,'discharge',2,true);
 assert.equal(gameEnergy[0][11],null);assert.equal(gameEnergy[1][11],null);assert.ok(gameEnergy[2][11]>0);
 assert.equal(chargingRows[2][11],null,'derived energy never changes stored samples');
 setChartSettings({energyWh:null});assert.equal(signalScale(energy,energyRows.map(p=>p[11]),'discharge').full,18.16);
 setChartSettings({energyWh:25.34});assert.equal(signalScale(energy,[18.16],'discharge').full,25.34);
 setChartSettings({energyWh:15,capacityAuto:true});let energyScale=signalScale(energy,[18.16],'discharge');assert.equal(energyScale.full,18.16);assert.equal(signalScale(energy,[10],'discharge',false,energyScale).full,18.16);
 setChartSettings({energyWh:15,capacityAuto:false});assert.equal(signalScale(energy,[18.16],'discharge',false,energyScale).full,15);
 setChartSettings({energyWh:null,capacityAuto:false});assert.equal(signalScale(energy,[18.16],'discharge').full,18.16);
 setChartSettings({energyWh:0});assert.equal(signalScale(energy,[18.16],'discharge').full,18.16);
 setChartSettings({});
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
 const chargingGame=next(power,[-35,4],null,'discharge',true);assert.equal(chargingGame.full,35);assert.equal(chargingGame.min,-35);
 assert.equal(next(current,[]).full,2000);
 assert.equal(next(power,[]).full,10);
 // Current range must use the displayed single-/dual-cell current basis.
 for(const [cells,expected]of [[1,6000],[2,10000]]){
  const fake={cellCount:cells,kind:'charge',fast:false,ranges:{current:[0,5000]},points:[],scaleCache:new Map()};
  const actual=Chart.prototype.scaleFor.call(fake,current);assert.equal(actual.full,expected);
 }
 const points=[[0,5000,50,5000,4000,50,0,null,4000,4000,30]];
 const copy=JSON.stringify(points);assert.equal(plotPoints(points,'charge',2)[0][3],10000);assert.equal(JSON.stringify(points),copy);
 assert.equal(plotPoints([[0,5000,50,null,4000,-12,0,null]],'discharge',2,true)[0][5],-12);
 const shortIntervals=[null,9999,10000,19999,20000].map((dt,i)=>[i,5000,50,null,4000,2,0,dt]);
 assert.deepEqual(plotPoints(shortIntervals,'discharge',2,false).map(row=>row[0]),[0,4]);
 assert.equal(plotPoints(shortIntervals,'discharge',2,true).length,5);
 assert.equal(plotPoints(shortIntervals,'charge',2,false).length,5);
 setChartSettings({capacityAh:5.6,capacityAuto:true});
 const capacity=series.find(s=>s.key==='rm');
 const capacityStart=next(capacity,[5600]);assert.equal(next(capacity,[5601],capacityStart).full,5601);
}

async function hitGeometry(){
// A vector-only Canvas test double; sample recorded cubic paths to test hit geometry.
function context(){
 return{
  transform:2,states:[],lineWidth:1,
  save(){this.states.push([this.transform,this.lineWidth]);},restore(){[this.transform,this.lineWidth]=this.states.pop();},
  setTransform(value){this.transform=value;},setLineDash(){},beginPath(){this.segments=[];},moveTo(x,y){this.last=[x,y];},
  bezierCurveTo(...args){this.segments.push([...this.last,...args]);this.last=args.slice(-2);},
  isPointInStroke(x,y){
   assert.equal(this.transform,1,'hit testing must use CSS pixels at every display density');
   for(const p of this.segments){
    let prev=p.slice(0,2);
    for(let i=1;i<=200;i++){
     const t=i/200,u=1-t,point=[0,1].map(k=>u*u*u*p[k]+3*u*u*t*p[2+k]+3*u*t*t*p[4+k]+t*t*t*p[6+k]);
     const dx=point[0]-prev[0],dy=point[1]-prev[1],length=dx*dx+dy*dy;
     const a=length?Math.max(0,Math.min(1,((x-prev[0])*dx+(y-prev[1])*dy)/length)):0;
     if(Math.hypot(x-prev[0]-a*dx,y-prev[1]-a*dy)<=this.lineWidth/2)return true;
     prev=point;
    }
   }
   return false;
  }
 };
}
const{Chart}=await import(pathToFileURL(path.join(__dirname,'../src/main/assets/webroot/chart.js')).href);
 const ctx=context(),chart={ctx,kind:'discharge',fast:false,left:0,right:0,width:400,top:0,bottom:200,
  enabled:new Set(['power']),signals:()=>[{key:'power',index:1}],scaleFor:()=>({}),pointPixel:row=>row.slice(0,2)};
 const row=(x,y,quality=0)=>[x,y,null,null,null,null,quality],points=[row(0,0),row(200,200),row(400,0)];
 const hit=(x,y,data=points)=>Chart.prototype.hitCurve.call(chart,x,y,data);
 assert.equal(hit(100,125),true,'point on the rendered cubic curve');
 assert.equal(hit(100,40),false,'blank plot area');
 assert.equal(hit(-1,125),false,'outside the plot');
 assert.equal(hit(200,200,[row(200,200)]),true,'single sample');
 assert.equal(hit(100,125,[row(0,0),row(200,null),row(400,0)]),false,'missing values split the path');
 chart.kind='charge';const gap=[row(0,0),row(200,200,1),row(400,0)];
 assert.equal(hit(100,125,gap),false,'charging gaps must not be joined');
 chart.kind='discharge';assert.equal(hit(100,125,gap),true,'ordinary discharge intentionally reconnects gaps');
 assert.equal(ctx.transform,2);assert.equal(ctx.lineWidth,1);assert.equal(ctx.states.length,0);
}

async function percentageInterpolation(){
 const{percentageAnchors,smoothPath}=await import(pathToFileURL(path.join(__dirname,'../src/main/assets/webroot/chart.js')).href);
 const raw=[[0,100],[2,100],[3,99],[5,99],[9,98],[16,98],[18,97],[22,97]],original=JSON.stringify(raw);
 const anchors=percentageAnchors(raw);assert.equal(anchors.length,4);assert.deepEqual(anchors[0],raw[0]);assert.deepEqual(anchors.at(-1),raw.at(-1));
 assert.equal(JSON.stringify(raw),original,'raw readings stay unchanged');
 assert.deepEqual(percentageAnchors([[0,90],[5,90],[10,90]]),[[0,90],[10,90]]);
 const reversal=percentageAnchors([[0,90],[2,89],[3,89],[5,90],[9,90],[11,88]]);assert.ok(reversal.some(p=>p[1]===89)&&reversal.some(p=>p[1]===90),'real reversals are retained');
 let start,previous=100,curves=0;
 smoothPath({moveTo(x,y){start=[x,y];},bezierCurveTo(x1,y1,x2,y2,x,y){
  for(let i=1;i<=100;i++){const t=i/100,u=1-t,value=u*u*u*start[1]+3*u*u*t*y1+3*u*t*t*y2+t*t*t*y;assert.ok(value<=previous+1e-9&&value>=97-1e-9,'no new wiggles or overshoot');previous=value;}
  start=[x,y];curves++;
 }},raw,true);assert.equal(curves,anchors.length-1);
}

async function batteryTemperature(){
const storage=new Map();
globalThis.localStorage={
  getItem:key=>storage.has(key)?storage.get(key):null,
  setItem:(key,value)=>storage.set(key,String(value))
};
globalThis.document={documentElement:{dataset:{theme:'light'}}};
const root=path.join(__dirname,'../src/main/assets/webroot/');
  const{seriesFor,signalScale,plotPoints}=await import(pathToFileURL(path.join(root,'chart.js')).href);
  const{loadSelection}=await import(pathToFileURL(path.join(root,'preferences.js')).href);
  const temp=seriesFor('discharge',2,true).find(series=>series.key==='temp');

  assert.ok(temp,'game chart must expose battery temperature');
  assert.equal(temp.name,'电池温度');
  assert.equal(temp.index,10);
  assert.equal(seriesFor('discharge',2,false).some(series=>series.key==='temp'),true);

  const gameScale=signalScale(temp,[31,37,42],'discharge',true);
  assert.equal(gameScale.base,30);
  assert.equal(gameScale.full,42);
  assert.equal(gameScale.label,'30–42 ℃');

  const rows=[[0,5000,80,300,3900,25,0,10000,3900,3900,36]];
  const plotted=plotPoints(rows,'discharge',2,true);
  assert.equal(plotted.length,1);
  assert.equal(plotted[0][10],36);
  assert.ok(loadSelection('discharge',['rm','pct','voltage','power','temp']).has('temp'));
}

async function screenOffProjection(){
const root=path.join(__dirname,'../src/main/assets/webroot/');
const turn=()=>new Promise(resolve=>setImmediate(resolve));
const{ScreenOffOverlay}=await import(pathToFileURL(path.join(root,'screen-off-overlay.js')).href);
 const{Chart,setChartSettings}=await import(pathToFileURL(path.join(root,'chart.js')).href);
 let visible=true,draws=0,errors=0;const requests=[];
 const chart={enabled:new Set(),draw:()=>draws++};
 const overlay=new ScreenOffOverlay(chart,(action,id)=>new Promise((resolve,reject)=>requests.push({action,id,resolve,reject})),()=>visible,()=>errors++);
 const record={id:1,kind:'discharge',start_wall:100000,start_elapsed:100,end_elapsed:60100,active:1,fast_seconds:1};
 overlay.update(record,1);assert.equal(requests.length,0,'unchecked does not read');
 chart.enabled.add('screenOff');visible=false;overlay.update(record,1);assert.equal(requests.length,0,'background does not read');
 visible=true;overlay.update(record,1);assert.equal(requests[0].action,'screen_off_spans');
 requests[0].resolve({spans:[[100,300]]});await turn();assert.deepEqual(chart.screenOffSpans,[[100,300]]);assert.equal(draws,1);
 overlay.update({...record,end_elapsed:61100},1);assert.equal(requests.length,1,'game power samples do not reread minute statistics');
 overlay.update({...record,end_elapsed:120100},2);assert.equal(requests.length,2);
 const next={...record,id:2};overlay.update(next,0);assert.equal(requests.length,3);assert.deepEqual(chart.screenOffSpans,[]);
 requests[1].resolve({spans:[[0,90000]]});await turn();assert.deepEqual(chart.screenOffSpans,[],'late previous-record response is discarded');
 visible=false;requests[2].resolve({spans:[[0,1000]]});await turn();assert.equal(draws,1,'no background redraw');
 visible=true;overlay.update(next,0);assert.equal(requests.length,3,'saved foreground result remains cached');
 overlay.update({...next,end_elapsed:120100},1);overlay.close();requests[3].reject(Error('closed'));await turn();assert.equal(errors,0);
 // Actual viewport clipping: partial left/right spans never cover the axes.
 globalThis.document={hidden:false,documentElement:{}};
 globalThis.getComputedStyle=()=>({getPropertyValue:()=>''});
 const rectangles=[],ctx={save(){},restore(){},fillRect(...args){rectangles.push(args);}};
 const fake={kind:'discharge',fast:true,enabled:new Set(['screenOff']),window:[100,300],left:40,screenOffSpans:[[0,150],[200,400],[500,600]]};
 Chart.prototype.drawScreenOff.call(fake,ctx,22,200,400);
 assert.deepEqual(rectangles,[[40,22,100,200],[240,22,200,200]]);
 setChartSettings({screenOnMinutes:2});
 fake.fast=false;fake.window=[60000,180000];fake.screenOffSpans=[[0,119999],[0,120000],[100000,110000],[120000,240001]];
 rectangles.length=0;Chart.prototype.drawScreenOff.call(fake,ctx,22,200,400);assert.deepEqual(rectangles,[[40,22,200,200],[240,22,200,200]],'filter by full duration before viewport clipping; equal threshold is retained');
 setChartSettings({screenOnMinutes:3});rectangles.length=0;Chart.prototype.drawScreenOff.call(fake,ctx,22,200,400);assert.equal(rectangles.length,0,'setting changes apply without rereading spans');
 fake.fast=true;Chart.prototype.drawScreenOff.call(fake,ctx,22,200,400);assert.equal(rectangles.length,4,'game mode retains short screen-off spans');
 const painted=rectangles.length;
 document.hidden=true;Chart.prototype.drawScreenOff.call(fake,ctx,22,200,400);assert.equal(rectangles.length,painted);
 document.hidden=false;fake.kind='charge';Chart.prototype.drawScreenOff.call(fake,ctx,22,200,400);assert.equal(rectangles.length,painted);
 const stored=new Map();globalThis.localStorage={getItem:key=>stored.get(key)??null,setItem:(key,value)=>stored.set(key,value)};
 const{saveSelection,loadSelection}=await import(pathToFileURL(path.join(root,'preferences.js')).href);
 saveSelection('game',new Set(['temp','screenOff']),['temp','screenOff']);assert.ok(loadSelection('game',['temp','screenOff']).has('screenOff'));
 assert.ok(!loadSelection('discharge',['temp','screenOff']).has('screenOff'),'charge/discharge/game preferences stay independent');
}

runCases([scales,hitGeometry,percentageInterpolation,batteryTemperature,screenOffProjection]).then(()=>console.log('Charts: scales, hit geometry, battery temperature and screen-off projection passed')).catch(error=>{console.error(error);process.exitCode=1;});
