const path=require('node:path'),assert=require('node:assert/strict');
const{pathToFileURL}=require('node:url');
(async()=>{
 const{scrollThumb,EdgeScrollbar}=await import(pathToFileURL(path.join(__dirname,'../src/main/assets/webroot/edge-scrollbar.js')).href);
 assert.equal(scrollThumb(100,100,0,96),null);
 assert.deepEqual(scrollThumb(100,400,150,96),{height:24,offset:36});
 assert.equal(scrollThumb(100,400,-30,96).offset,0);assert.equal(scrollThumb(100,400,999,96).offset,72);
 assert.equal(scrollThumb(0,100,0,96),null);
 const frames=new Map(),events=new Map(),classes=new Set();let sequence=0;
 globalThis.requestAnimationFrame=fn=>{frames.set(++sequence,fn);return sequence};globalThis.cancelAnimationFrame=id=>frames.delete(id);
 const flush=()=>{const current=[...frames.values()];frames.clear();current.forEach(fn=>fn())};
 const makeNode=()=>({hidden:false,style:{},children:[],className:'',setAttribute(){},append(node){this.children.push(node)},remove(){this.removed=true}});
 const scroller=hidden=>({hidden,clientHeight:100,scrollHeight:400,scrollTop:0,children:[makeNode()],events:new Map(),
  closest(){return this.hidden?{}:null},getClientRects(){return this.hidden?[]:[{}]},getBoundingClientRect(){return{top:80,bottom:180}},
  addEventListener(name,fn,options){this.events.set(name,{fn,options})},removeEventListener(name){this.events.delete(name)}});
 const record=scroller(false),settings=scroller(true),detail=scroller(true),body=makeNode();body.classList={contains:key=>classes.has(key)};
 const ids={appShell:makeNode(),recordsPage:makeNode(),settingsPanel:makeNode(),detail:makeNode(),detailScroll:detail};
 globalThis.document={body,hidden:false,querySelector:selector=>selector.includes('recordsPage')?record:settings,getElementById:key=>ids[key],createElement:makeNode,
  addEventListener:(name,fn)=>events.set(name,fn),removeEventListener:name=>events.delete(name)};
 globalThis.window={innerHeight:240,addEventListener:(name,fn)=>events.set(name,fn),removeEventListener:name=>events.delete(name)};
 globalThis.ResizeObserver=class{constructor(fn){this.fn=fn;this.nodes=[]}observe(node){this.nodes.push(node)}disconnect(){this.closed=true}};
 globalThis.MutationObserver=class{constructor(fn){this.fn=fn;this.nodes=[]}observe(node,options){assert.deepEqual(options.attributeFilter,['hidden','class']);assert.ok(!options.subtree);this.nodes.push(node)}disconnect(){this.closed=true}};
 const bar=new EdgeScrollbar();flush();assert.equal(bar.track.hidden,false);
 for(const node of [record,settings,detail])assert.equal(node.events.get('scroll').options.passive,true);
 record.scrollTop=150;record.events.get('scroll').fn();record.events.get('scroll').fn();assert.equal(frames.size,1,'scroll updates must batch into one animation frame');flush();assert.equal(bar.thumb.style.transform,'translateY(36px)');
 record.hidden=true;settings.hidden=false;settings.scrollHeight=100;bar.visibility.fn();flush();assert.equal(bar.track.hidden,true,'a short page must not show a scrollbar');
 settings.hidden=true;detail.hidden=false;bar.visibility.fn();flush();assert.equal(bar.track.hidden,false);
 classes.add('chartExpanded');bar.visibility.fn();flush();assert.equal(bar.track.hidden,true,'fullscreen charts must not retain the page indicator');
 classes.clear();detail.hidden=true;bar.visibility.fn();flush();assert.equal(bar.track.hidden,true,'home and unrelated pages have no indicator');
 bar.destroy();assert.equal(bar.track.removed,true);assert.equal(record.events.size,0);assert.equal(bar.resize.closed,true);assert.equal(bar.visibility.closed,true);
 console.log('Edge scrollbar: proportional position, bounds, short pages, three-page visibility, passive batched updates, fullscreen and cleanup passed');
})().catch(error=>{console.error(error);process.exitCode=1});
