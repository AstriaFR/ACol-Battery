const assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path');
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

  const html=fs.readFileSync(path.join(root,'index.html'),'utf8');
  assert.match(html,/<dialog id="batteryEstimateExplanation"[^>]*tabindex="-1"/);
  const css=fs.readFileSync(path.join(root,'style.css'),'utf8');
  assert.ok(css.includes('.battery-explanation-pages:focus-visible{outline:none}'));
  assert.ok(css.includes('.battery-explanation p{font-size:13px;line-height:1.75;margin:0}'));
  const pagesRule=css.match(/\.battery-explanation-pages\{([^}]*)\}/)?.[1]??'';
  assert.match(pagesRule,/display:grid/);
  assert.doesNotMatch(pagesRule,/overflow-y:auto/);
  assert.ok(css.includes('.battery-explanation-pages>[data-explanation-page][hidden]{display:block!important;visibility:hidden'));
  console.log('Battery explanation: focus ring removed and underlying page scroll preserved');
})().catch(error=>{console.error(error);process.exitCode=1;});
