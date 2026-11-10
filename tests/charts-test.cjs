const path=require('node:path'),assert=require('node:assert/strict');
const{pathToFileURL}=require('node:url');
const{runCases}=require('./helpers.cjs');

async function scales(){
globalThis.document={documentElement:{dataset:{}}};
const {Chart,series,seriesFor,signalScale,setChartSettings,plotPoints,plotAxisMinimum,gameSampleText}=await import(pathToFileURL(path.join(__dirname,'../src/main/assets/webroot/chart.js')).href);
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
 assert.ok(seriesFor('discharge',2,true).some(s=>s.key==='current'&&s.name==='电流'));
 assert.ok(!seriesFor('discharge',2,false).some(s=>s.key==='current'));
 const ram=seriesFor('discharge',2,false).find(s=>s.key==='ram');assert.equal(ram.index,14);assert.equal(ram.unit,'GB');
 assert.ok(!seriesFor('charge',2,false).some(s=>s.key==='ram'));
 assert.ok(!seriesFor('discharge',2,true).some(s=>s.key==='ram'),'game RAM remains in the performance chart');
 const ramChart={ranges:{ram:[5,7],ramTotal:[11.665641472,11.665641472]},points:[],scaleCache:new Map()};
 assert.equal(Chart.prototype.scaleFor.call(ramChart,ram).full,11.665641472,'full-record total fixes RAM scale while zooming');
 ramChart.ranges={};ramChart.points=[Array(16).fill(null)];ramChart.points[0][14]=6;ramChart.points[0][15]=11.665641472;
 assert.equal(Chart.prototype.scaleFor.call(ramChart,ram).full,11.665641472,'raw live samples retain total');
 ramChart.points=[];assert.equal(Chart.prototype.scaleFor.call(ramChart,ram).label,'暂无数据');
 const gameRows=[[0,5000,80,5000,4000,-40,0,3000,null,null,30,null,1],[3000,4999,80,-1000,4000,8,0,3000,null,null,30,null,0]];
 for(const cells of [1,2]){
  const plotted=plotPoints(gameRows,'discharge',cells,true),factor=cells===2?2:1;
  assert.equal(plotted[0][3],-5000*factor);assert.equal(plotted[1][3],1000*factor);
  assert.equal(gameRows[0][3],5000,'plotting never mutates stored charging-positive current');
  assert.equal(gameSampleText(plotted[0][3],'current',0),'+'+(5000*factor));assert.equal(gameSampleText(plotted[0][5],'power'),'+40.00');
  assert.equal(gameSampleText(plotted[1][3],'current',0),'−'+1000*factor);assert.equal(gameSampleText(plotted[1][5],'power'),'−8.00');
  const fake={cellCount:cells,kind:'discharge',fast:true,ranges:{current:[-1000,5000]},points:[],scaleCache:new Map()};
  const actual=Chart.prototype.scaleFor.call(fake,current);assert.equal(actual.min,-5000*factor);
  assert.equal(actual.full,signalScale(current,[-5000*factor,1000*factor],'charge').full);
 }
 assert.equal(signalScale(current,[-10999.9],'discharge',true).full,10000);
 assert.equal(signalScale(current,[-11000],'discharge',true).full,12000);
 const shortIntervals=[null,9999,10000,19999,20000].map((dt,i)=>[i,5000,50,null,4000,2,0,dt]);
 assert.deepEqual(plotPoints(shortIntervals,'discharge',2,false).map(row=>row[0]),[0,4]);
 assert.equal(plotPoints(shortIntervals,'discharge',2,true).length,5);
 assert.equal(plotPoints(shortIntervals,'charge',2,false).length,5);
 setChartSettings({capacityAh:5.6,capacityAuto:true});
 const capacity=series.find(s=>s.key==='rm');
 const capacityStart=next(capacity,[5600]);assert.equal(next(capacity,[5601],capacityStart).full,5601);
}

async function chartTimes(){
 const{Chart,systemTimeLabel,sampleSystemWall,chartSystemWall,wallTimeTicks,elapsedTimeTicks,elapsedMinuteLabel,timeAxisLabels}=await import(pathToFileURL(path.join(__dirname,'../src/main/assets/webroot/chart.js')).href);
 const start=new Date(2026,10,2,23,59,30).getTime(),row=Array(14).fill(null);row[0]=60000;row[6]=0;row[13]=start+3600000;
 assert.equal(systemTimeLabel(start),'23:59');assert.equal(systemTimeLabel(start,true),'11/02 23:59:30');
 assert.equal(systemTimeLabel(start+60000),'00:00');assert.equal(systemTimeLabel(null),'—');
 assert.equal(elapsedMinuteLabel(59000),'0:00');assert.equal(elapsedMinuteLabel(5*60000),'0:05');
 assert.equal(elapsedMinuteLabel(65*60000+59000),'1:05');assert.equal(elapsedMinuteLabel(27*3600000),'27:00');
 for(const minutes of[25,45,75,120,360,1440]){
  const grid=elapsedTimeTicks([3*60000,(minutes+3)*60000],360);
  assert.ok(grid.length>=3&&grid.length<=6);
  assert.ok(grid.every(t=>t.x%(5*60000)===0&&t.x>3*60000&&t.x<(minutes+3)*60000),'charging ticks align to elapsed minutes, independent of timezone');
 }
 assert.deepEqual(elapsedTimeTicks([1000,59000],360),[]);
 const measure=text=>text.length*6;
 const crowded=timeAxisLabels('10:03','11:01',[{x:100,text:'10:15'},{x:343,text:'11:00'}],360,8,measure);
 assert.equal(crowded[0].x,3,'start label sits near the canvas edge');
 assert.ok(crowded.some(t=>t.text==='11:00'&&t.dy===-6),'the nearby round tick stays visible');
 assert.equal(crowded.at(-1).dy,6);assert.equal(crowded.at(-1).x,357,'end label stays at the right canvas edge on the lower row');assert.equal(crowded.at(-1).align,'right');
 assert.ok(crowded.every(t=>16+t.dy<25),'labels stay within the existing axis gutter');
 const sparse=timeAxisLabels('0:00','1:25',[{x:180,text:'0:30'}],360,8,measure);
 assert.ok(sparse.every(t=>t.dy===0),'no overlap keeps one row');
 assert.equal(timeAxisLabels('0:00','1:25',[],360,8,measure,34)[0].x,34,'charging start stays at the plot axis');
 assert.equal(sampleSystemWall(row,'charge'),start+3600000);
 const walls=new Map([[60000,start+120000]]);assert.equal(sampleSystemWall(row,'performance',walls),start+120000);
 assert.equal(sampleSystemWall(row,'performance',new Map()),null,'performance values are never mistaken for timestamps');
 assert.equal(chartSystemWall([row],start,90000),row[13]+30000,'ticks follow the saved system clock after a clock correction');
 assert.equal(chartSystemWall([[0]],start,60000),start+60000);assert.equal(chartSystemWall([],null,0),null);
 const gridStart=new Date(2026,10,2,10,3).getTime();
 const ticks=wallTimeTicks([],gridStart,[0,60*60000],360);
 assert.ok(ticks.length>=3&&ticks.length<=6);assert.ok(ticks.every(t=>new Date(t.wall).getMinutes()%5===0));
 assert.deepEqual(wallTimeTicks([],gridStart,[0,60000],360),[]);
 for(const minutes of[25,45,75,120,360,1440]){
  const grid=wallTimeTicks([],gridStart,[0,minutes*60000],360);
  assert.ok(grid.length>=3&&grid.length<=6,`${minutes} minute window has a readable grid`);
  assert.ok(grid.every((t,i)=>!i||(t.x-grid[i-1].x)*360/(minutes*60000)>=45));
 }
 const initial=Array(14).fill(null);initial[0]=0;initial[13]=gridStart;
 const changed=Array(14).fill(null);changed[0]=30*60000;changed[13]=gridStart+90*60000;
 const shifted=wallTimeTicks([initial,changed],gridStart,[0,60*60000],360);
 assert.ok(shifted.every(t=>new Date(t.wall).getMinutes()%5===0));
 assert.ok(shifted.some(t=>t.x<changed[0]&&t.wall===gridStart+t.x));
 assert.ok(shifted.some(t=>t.x>=changed[0]&&t.wall===changed[13]+t.x-changed[0]));
 const performancePoints=[initial,changed].map(point=>{const p=[...point];p[13]=999;return p;});
 const performanceWalls=new Map([[0,initial[13]],[changed[0],changed[13]]]);
 assert.equal(chartSystemWall(performancePoints,gridStart,45*60000,'performance',performanceWalls),changed[13]+15*60000);
 assert.deepEqual(wallTimeTicks(performancePoints,gridStart,[0,60*60000],360,'performance',performanceWalls),shifted,'game battery and performance grids use the same recorded wall time');
 assert.equal(chartSystemWall(performancePoints,gridStart,45*60000,'performance',new Map()),gridStart+45*60000,'old performance records fall back to record start, never a metric column');
 const midnight=wallTimeTicks([],start,[0,60*60000],360);
 assert.ok(midnight.length>=3&&midnight.every(t=>new Date(t.wall).getDate()===3));
 const chart=Object.assign(Object.create(Chart.prototype),{points:[row],window:[0,120000],left:0,right:0,width:120,kind:'charge',startWall:start,tooltip:{},markerLine:{style:{}},positionTooltip(){},enabled:new Set(),signals:()=>[],cellCount:1,
   draw(){throw Error('Moving an inspection marker must not redraw the plot');}});
 Chart.prototype.inspect.call(chart,60,0,true);
 assert.ok(chart.tooltip.textContent.startsWith('时间  '+systemTimeLabel(row[13],true)+'\n计时  1:00\n'));
 delete row[13];Chart.prototype.inspect.call(chart,60,0,true);assert.ok(chart.tooltip.textContent.startsWith('时间  '+systemTimeLabel(start+60000,true)+'\n计时  1:00'));
 const reads=[];chart.readRawInspection=(from,to,target)=>new Promise(resolve=>reads.push({from,to,target,resolve}));
 chart.inspect(60,0,true);chart.inspect(66,0,true);
 assert.equal(reads.length,1,'dragging coalesces the raw sample reads while IPC is busy');
 const flush=()=>new Promise(setImmediate);
 reads[0].resolve(row);await flush();
 assert.equal(reads[1].target,66000);assert.equal(chart.tooltip.textContent,'正在读取采样点…','an older result must not replace the current tooltip');
 const exact=[...row];exact[0]=65000;reads[1].resolve(exact);await flush();
 assert.equal(chart.marker,65000);assert.match(chart.tooltip.textContent,/计时  1:05/);
 assert.equal(chart.markerLine.hidden,false);assert.equal(chart.markerLine.style.transform,'translateX(65px)');
 chart.clearInspect();assert.equal(chart.markerLine.hidden,true);
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
  enabled:new Set(['power']),signals:()=>[{key:'power',index:1}],scaleFor:()=>({}),traceSignal:Chart.prototype.traceSignal,pointPixel:row=>row.slice(0,2)};
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
 const{plateauAnchors,smoothPath}=await import(pathToFileURL(path.join(__dirname,'../src/main/assets/webroot/chart.js')).href);
 const raw=[[0,100],[2,100],[3,99],[5,99],[9,98],[16,98],[18,97],[22,97]],original=JSON.stringify(raw);
 const anchors=plateauAnchors(raw);assert.equal(anchors.length,4);assert.deepEqual(anchors[0],raw[0]);assert.deepEqual(anchors.at(-1),raw.at(-1));
 assert.equal(JSON.stringify(raw),original,'raw readings stay unchanged');
 assert.deepEqual(plateauAnchors([[0,90],[5,90],[10,90]]),[[0,90],[10,90]]);
 const reversal=plateauAnchors([[0,90],[2,89],[3,89],[5,90],[9,90],[11,88]]);assert.ok(reversal.some(p=>p[1]===89)&&reversal.some(p=>p[1]===90),'real reversals are retained');
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
  const{seriesFor,signalScale,plotPoints,softenTemperature}=await import(pathToFileURL(path.join(root,'chart.js')).href);
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
  const step=[[0,30],[30,30],[59,30],[60,40],[61,40]];
  assert.deepEqual(softenTemperature(step,.3),step,'a temperature step must not move into the plateau');
  const jitter=[[0,30],[1,30.2],[2,30],[3,30.2],[4,30]];
  assert.deepEqual(softenTemperature(jitter,.3).slice(1,-1),[[1,30.1],[2,30.1],[3,30.1]]);
  assert.equal(jitter[1][1],30.2,'the raw readings remain available');
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

async function performanceMemory(){
 globalThis.window={};globalThis.document={documentElement:{dataset:{}},getElementById:()=>({})};globalThis.localStorage={getItem:()=>null};
 const{PerformancePanel,PerformanceChart}=await import(pathToFileURL(path.join(__dirname,'../src/main/assets/webroot/performance-panel.js')).href);
 const fake={points:[[0,null,500,0,null,null,1,0,1200,0,2200,0,35]],chart:{maximum:new Map(),frequencyRanges:new Map(),ingest(){}},legend(){}};
 const meta={clusters:[{label:'CPU 0–5',maxMHz:2400},{label:'CPU 6–7',maxMHz:3200}],temperatureIndex:12};
 PerformancePanel.prototype.configure.call(fake,meta);assert.ok(!fake.chart.metrics.some(s=>s.key==='ram'));
 PerformancePanel.prototype.configure.call(fake,{...meta,memoryIndex:13,memoryTotalGB:11.665641472});
 const ram=fake.chart.metrics.find(s=>s.key==='ram');assert.equal(ram.index,13);assert.equal(ram.unit,'GB');
 assert.equal(fake.points[0][ram.index],undefined,'old samples retain missing RAM instead of zero');
 PerformanceChart.prototype.ingest.call({...fake.chart,metrics:[ram]},[[1,...Array(12).fill(null),6.91429376]]);
 assert.equal(PerformanceChart.prototype.scaleFor.call(fake.chart,ram).full,11.665641472,'RAM uses MemTotal, not observed peak');
 globalThis.localStorage={getItem:()=>JSON.stringify(['ram'])};PerformancePanel.prototype.configure.call(fake,{...meta,memoryIndex:13,memoryTotalGB:11.665641472});assert.ok(fake.chart.enabled.has('ram'));
 PerformancePanel.prototype.configure.call(fake,{...meta,memoryIndex:13,memoryTotalGB:null});assert.ok(!fake.chart.metrics.some(s=>s.key==='ram'),'invalid total does not create a fake scale');
}
async function intervalApplications(){
 globalThis.document={hidden:false};const{IntervalAppDetails}=await import(pathToFileURL(path.join(__dirname,'../src/main/assets/webroot/interval-app-details.js')).href);
 let record={id:3,start_wall:1000,start_elapsed:0,end_elapsed:240000,kind:'discharge',fast_seconds:0},calls=0;
 const disk=new Map(),cache={read:async r=>disk.get(r.id)??null,save:async(r,state)=>disk.set(r.id,{names:[...state.names],cursor:state.cursor,end:state.loadedEnd,complete:state.complete,retryCursor:state.retryCursor,retryAt:state.retryAt}),remove:async id=>disk.delete(id)};
 const chart={recordId:3,marker:120000,tooltip:{textContent:'采样详情',hidden:false},positionTooltip(){}};
 const api=async(action,id,after)=>{calls++;assert.equal(action,'interval_apps');assert.equal(id,3);return after===0?{apps:[[0,null,'none'],[120000,'a','app']],labels:{a:'示例应用'},cursor:120001,more:true}:{apps:[[240000,'b','app']],labels:{b:'另一应用'},cursor:240001,more:false};};
 const helper=new IntervalAppDetails(chart,api,()=>record,cache);
 await Promise.all([helper.preload(record),helper.preload(record)]);assert.equal(calls,2,'all result pages preload once');
 const row=[120000,null,null,null,null,null,0,120000];helper.inspect(row);assert.equal(chart.tooltip.textContent,'采样详情\n示例应用');assert.equal(calls,2,'inspection performs no IPC');
 chart.tooltip.textContent='另一点';chart.marker=240000;helper.inspect([240000]);assert.equal(chart.tooltip.textContent,'另一点\n另一应用');assert.equal(calls,2);
 const fresh=new IntervalAppDetails(chart,async()=>{throw Error('persistent cache should avoid IPC')},()=>record,cache);await fresh.preload(record);chart.tooltip.textContent='重新进入';fresh.inspect([240000]);assert.equal(chart.tooltip.textContent,'重新进入\n另一应用');
 await fresh.remove(3);assert.equal(disk.has(3),false);await fresh.preload(record);assert.equal(fresh.records.size,0,'deleted records cannot recreate persistent cache');
 let release;const late=new IntervalAppDetails(chart,()=>new Promise(resolve=>release=resolve),()=>record,{...cache,read:async()=>null});const loading=late.preload(record);await new Promise(resolve=>setImmediate(resolve));chart.tooltip.textContent='已关闭';late.inspect(null);chart.tooltip.hidden=true;release({apps:[[240000,'late','app']],labels:{late:'迟到结果'},cursor:240001,more:false});await loading;assert.equal(chart.tooltip.textContent,'已关闭');
 record={...record,fast_seconds:10};await helper.preload(record);assert.equal(calls,2,'game charts are excluded');
 const{IntervalAppCache}=await import(pathToFileURL(path.join(__dirname,'../src/main/assets/webroot/interval-app-cache.js')).href);const persistent=new IntervalAppCache();let saved;
 persistent.run=async(mode,action)=>action({put:value=>{saved=value;return value},get:()=>saved});record={...record,fast_seconds:0};
 await persistent.save(record,{names:new Map([[120000,'应用']]),loadedEnd:240000,cursor:240000,complete:true,retryCursor:null,retryAt:0});assert.ok(await persistent.read(record));assert.equal(await persistent.read({...record,app_revision:1}),null,'repaired attribution invalidates old names');
 assert.equal(await persistent.read({...record,end_elapsed:120000}),null,'restored shorter records invalidate future cache');
 const waiting=new IntervalAppDetails(chart,async()=>({apps:[],labels:{},cursor:0,more:false,through:-1,complete:false}),()=>record,{...cache,read:async()=>null});
 await waiting.preload(record);clearTimeout(waiting.retryTimer);assert.equal(waiting.records.get(waiting.key(record)).loadedEnd,-1,'uncaptured events are not finalized as empty application data');
 const{DailyUsageChart}=await import(pathToFileURL(path.join(__dirname,'../src/main/assets/webroot/daily-usage-chart.js')).href);
 let day=null;const daily={previousDay:100,nextDay:300,onDate:value=>day=value};DailyUsageChart.prototype.changeDay.call(daily,-1);assert.equal(day,100);DailyUsageChart.prototype.changeDay.call(daily,1);assert.equal(day,300);daily.nextDay=null;day=null;DailyUsageChart.prototype.changeDay.call(daily,1);assert.equal(day,null);
}

runCases([scales,chartTimes,hitGeometry,percentageInterpolation,batteryTemperature,screenOffProjection,performanceMemory,intervalApplications]).then(()=>console.log('Charts: scales, time axes, hit geometry, temperature, RAM, interval apps and day navigation passed')).catch(error=>{console.error(error);process.exitCode=1;});
