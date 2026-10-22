const assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path');
const{pathToFileURL}=require('node:url');

const storage=new Map();
globalThis.localStorage={
  getItem:key=>storage.has(key)?storage.get(key):null,
  setItem:(key,value)=>storage.set(key,String(value))
};
globalThis.document={documentElement:{dataset:{theme:'light'}}};

(async()=>{
  const root=path.join(__dirname,'../src/main/assets/webroot/');
  const{seriesFor,signalScale,plotPoints}=await import(pathToFileURL(path.join(root,'chart.js')).href);
  const{loadSelection,saveSelection,loadHomePriority,saveHomePriority}=await import(pathToFileURL(path.join(root,'preferences.js')).href);
  const{recordChanges}=await import(pathToFileURL(path.join(root,'record-display.js')).href);
  const temp=seriesFor('discharge',2,true).find(series=>series.key==='temp');

  assert.ok(temp,'game chart must expose battery temperature');
  assert.equal(temp.name,'电池温度');
  assert.equal(temp.index,10);
  assert.equal(seriesFor('discharge',2,false).some(series=>series.key==='temp'),false);

  const gameScale=signalScale(temp,[31,37,42],'discharge',true);
  assert.equal(gameScale.base,30);
  assert.equal(gameScale.full,42);
  assert.equal(gameScale.label,'30–42 ℃');

  const rows=[[0,5000,80,300,3900,25,0,10000,3900,3900,36]];
  const plotted=plotPoints(rows,'discharge',2,true);
  assert.equal(plotted.length,1);
  assert.equal(plotted[0][10],36);

  const lightColor=temp.color;
  document.documentElement.dataset.theme='dark';
  const darkTemp=seriesFor('discharge',2,true).find(series=>series.key==='temp');
  assert.notEqual(darkTemp.color,lightColor,'temperature color must be refreshed for OLED night mode');
  const css=fs.readFileSync(path.join(root,'style.css'),'utf8');
  for(const token of ['--plot-bg:#000','--plot-major:#789284','--plot-label:#829488','--plot-marker:#d7e8dc'])
    assert.ok(css.includes(token),`missing OLED chart token ${token}`);
  assert.ok(css.includes(':root[data-theme=dark] .fast-record-marker{color:#e7f2e9}'));

  const record={kind:'discharge',pct_start:81,pct_end:77,rm_start:5100,rm_end:4800};
  const percentage=recordChanges('percentage',record);
  assert.deepEqual(percentage.primary,{text:'−4%',flow:'discharge',label:'百分比变化'});
  assert.deepEqual(percentage.secondary,{text:'−300',flow:'discharge',label:'容量变化'});
  const capacity=recordChanges('capacity',record);
  assert.deepEqual(capacity.primary,{text:'−300',flow:'discharge',label:'容量变化'});
  assert.deepEqual(capacity.secondary,{text:'−4%',flow:'discharge',label:'百分比变化'});
  storage.clear();
  assert.equal(loadHomePriority(),'percentage','home priority defaults to percentage');
  assert.ok(saveHomePriority('capacity'));
  assert.equal(loadHomePriority(),'capacity','an explicit capacity preference is preserved');
  const html=fs.readFileSync(path.join(root,'index.html'),'utf8');
  const priorityHtml=html.match(/<div id="homePriority"[\s\S]*?<\/div>/)?.[0]??'';
  assert.ok(priorityHtml.indexOf('data-home-priority="percentage"')<priorityHtml.indexOf('data-home-priority="capacity"'));
  assert.match(priorityHtml,/data-home-priority="percentage" aria-pressed="true"/);
  assert.match(priorityHtml,/data-home-priority="capacity" aria-pressed="false"/);
  const explanation=html.match(/<section data-explanation-page="0">([\s\S]*?)<\/section>/)?.[1]??'';
  assert.ok(explanation.indexOf('估计锁容容量')>explanation.indexOf('截止电压 ≥ 3.00 V 时'));
  assert.ok(explanation.includes('用最大化学容量减去满充容量，括号内的负百分比表示与最大化学容量的差值比例。'));

  const available=['rm','pct','voltage','power','temp'];
  const defaults=loadSelection('game',available);
  assert.ok(defaults.has('temp'),'temperature is enabled for new game records by default');

  storage.clear();
  storage.set('battery_curves.legend.game',JSON.stringify(['rm','power']));
  const migrated=loadSelection('game',available);
  assert.ok(migrated.has('temp'),'existing game legend is migrated once to include temperature');
  migrated.delete('temp');
  assert.ok(saveSelection('game',migrated,available));
  assert.equal(loadSelection('game',available).has('temp'),false,'a later user opt-out is preserved');

  console.log('Game UI: home priority, temperature series/range, raw samples, OLED palette and one-time legend migration passed');
})().catch(error=>{console.error(error);process.exitCode=1;});
