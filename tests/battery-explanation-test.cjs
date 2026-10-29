const assert=require('node:assert/strict'),path=require('node:path');
const{pathToFileURL}=require('node:url');

(async()=>{
  const root=path.join(__dirname,'../src/main/assets/webroot/');
  const{BatteryInfoPanel}=await import(pathToFileURL(path.join(root,'battery-info.js')).href);
  const scroller={scrollTop:137};
  let shown=false,focusOptions=null;
  const explanation={
    open:false,
    showModal(){shown=true;this.open=true;},
    focus(options){focusOptions=options;}
  };
  const panel=Object.create(BatteryInfoPanel.prototype);
  panel.active=true;panel.explanation=explanation;panel.panel={querySelector:selector=>selector==='.page-scroll'?scroller:null};
  panel.setExplanationPage=()=>{};
  globalThis.history={state:{explanationPage:0}};
  globalThis.requestAnimationFrame=callback=>callback();

  panel.showExplanation(false);
  assert.ok(shown);
  assert.deepEqual(focusOptions,{preventScroll:true});
  assert.equal(scroller.scrollTop,137,'opening the explanation must restore the battery page scroll position');

  console.log('Battery explanation: opening preserves focus behavior and underlying page scroll');
})().catch(error=>{console.error(error);process.exitCode=1;});
