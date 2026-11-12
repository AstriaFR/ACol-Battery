const assert=require('node:assert/strict'),path=require('node:path'),{pathToFileURL}=require('node:url');
(async()=>{
 const url=name=>pathToFileURL(path.join(__dirname,'../src/main/assets/webroot',name)).href;
 const{csvCell,csvRow,saveCsv,recordCsv,performanceCsv}=await import(url('chart-export.js'));
 const{remainingEstimate}=await import(url('estimate.js'));
 const{healthScale,loadHealthTrend}=await import(url('battery-history.js'));
 assert.equal(csvRow(['a,b','a"b',null,NaN,-2,'=formula']),'"a,b","a""b",,,-2,\'=formula\r\n');
 assert.equal(csvCell('line\nbreak'),'"line\nbreak"');
 const flat=healthScale([6000,6000,null]);assert.ok(flat.base<6000&&flat.full>6000&&flat.base>0);
 const wide=healthScale([400,550]);assert.ok(wide.base<=400&&wide.full>=550);
 assert.deepEqual([healthScale([483,527],10).base,healthScale([483,527],10).full],[480,530]);
 assert.deepEqual([healthScale([5815,6123],100).base,healthScale([5815,6123],100).full],[5800,6200]);
 assert.deepEqual([healthScale([500,500],10).base,healthScale([500,500],10).full],[490,510]);
 assert.deepEqual([healthScale([0],10).base,healthScale([0],10).full],[0,10]);
 let calls=0;const items=await loadHealthTrend(async(action,after,through)=>{
  assert.equal(action,'battery_history_trend');calls++;
  if(!after)return{through:9,items:[{id:1,wall:20}],nextAfter:1};
  assert.equal(through,9);return{through:9,items:[{id:9,wall:10}],nextAfter:null};
 });assert.equal(calls,2);assert.deepEqual(items.map(i=>i.id),[9,1]);
 assert.equal(await loadHealthTrend(async()=>({items:[]}),()=>true),null);
 const raw=[0,5000,80,-100,4000,1,0,2000,4000,4000,30,null,0,1700000000000,3,8];
 let page=0;const records=[];
 for await(const row of recordCsv(async(action,id,cursor,end)=>{
  assert.equal(action,'export_points');assert.equal(id,7);
  if(page++===0)return{record:{kind:'discharge',cell_count:2,start_wall:1700000000000},highDesignCapacity:true,end:2000,points:[raw],nextAfter:0};
  assert.equal(cursor,1);assert.equal(end,2001);
  const unclassified=[2000,...raw.slice(1)];unclassified[4]=8200;unclassified[6]=32;
  return{record:{kind:'discharge',cell_count:2,start_wall:1700000000000},end:2000,points:[unclassified],nextAfter:null};
 },7))records.push(row);
 assert.equal(records.length,3);assert.equal(records[1][4],-200);assert.equal(records[1][5],4);
 assert.equal(records[1][10],Number(remainingEstimate({rm:raw[1],voltage:raw[4],cellCount:2},null,true).whText));
 assert.equal(records[2][4],-100);assert.equal(records[2][5],8.2);assert.equal(records[2][10],null);
 const perf=[];for await(const row of performanceCsv(async()=>({end:1,points:[[0,60,300,20,0,0,0,0,1200,50,42,4],[2,90]],wallTimes:[[0,1700000000000]],cursor:3,more:true,meta:{clusters:[{label:'CPU0-3'}],temperatureIndex:10,memoryIndex:11}}),9))perf.push(row);
 assert.equal(perf.length,2);assert.ok(perf[0].includes('CPU0-3频率_MHz'));assert.equal(perf[1].at(-1),4);
 let output='',finished=0,cancelled=0;
 globalThis.window={AcoNative:{beginChartExport:()=> 'test',appendChartExport:(id,text)=>{assert.equal(id,'test');output+=text;return true},finishChartExport:()=>{finished++;return true},cancelChartExport:()=>cancelled++}};
 async function* rows(){yield['标题'];for(let i=0;i<410;i++)yield[i,null];}
 await saveCsv('测试.csv',rows());assert.ok(output.startsWith('\ufeff标题\r\n'));assert.ok(output.endsWith('409,\r\n'));assert.equal(finished,1);assert.equal(cancelled,0);
 window.AcoNative.appendChartExport=()=>false;await assert.rejects(saveCsv('测试.csv',rows()),/暂存失败/);assert.equal(cancelled,1);
 async function* failing(){yield['ok'];throw Error('分页失败');}
 await assert.rejects(saveCsv('测试.csv',failing()),/分页失败/);assert.equal(cancelled,2);
 console.log('Chart export/trend: CSV escaping, missing values, paging bounds, compensation, metadata, auto ranges, cancellation and chunk failure passed');
})().catch(error=>{console.error(error);process.exitCode=1});
