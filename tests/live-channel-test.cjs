const fs=require('fs'),path=require('path'),assert=require('assert/strict');
(async()=>{
 const source=fs.readFileSync(path.join(__dirname,'../src/main/assets/webroot/live-channel.js'),'utf8').replace(/^import[^\n]+\n/,'');
 const {LiveChannel}=await import('data:text/javascript;base64,'+Buffer.from('const spawn=()=>{},exec=async()=>{};'+source).toString('base64'));
 const channel=new LiveChannel();
 const packet=(id,extra={})=>({ok:true,status:{session:id,settings:{hidePct:30}},...extra});
 channel.accept(packet(107,{record:{id:107},points:[[3600000,1000]]}));
 assert.equal(channel.resumeAfter,3600000);
 channel.setMode(1);channel.accept(packet(107,{batteryInfo:{cutoffMv:3050}}));
 assert.equal(channel.resumeAfter,3600000); // Same-record metadata preserves its cursor.
 channel.accept(packet(108,{batteryInfo:{cutoffMv:3050}}));
 assert.equal(channel.resumeId,108);assert.equal(channel.resumeAfter,0);
 channel.setMode(0);channel.accept(packet(108,{record:{id:108},points:[[2000,999]]}));
 assert.equal(channel.resumeAfter,2000);
 channel.accept(packet(108));assert.equal(channel.resumeAfter,2000);
 channel.setMode(1);channel.accept(packet(109,{batteryInfo:{cutoffMv:3050}}));
 assert.equal(channel.resumeAfter,0);
 channel.accept(packet(108));assert.equal(channel.resumeAfter,2000); // Reuse only this ID's cached cursor.
 channel.accept(packet(-1));assert.equal(channel.resumeId,0);assert.equal(channel.resumeAfter,0);
 console.log('LiveChannel: new-record cursor reset, same-record metadata, cached cursor and no-session checks passed');
})().catch(error=>{console.error(error);process.exitCode=1;});
