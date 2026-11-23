const assert=require('node:assert/strict'),path=require('node:path');
const{pathToFileURL}=require('node:url');

(async()=>{
  const root=path.join(__dirname,'../src/main/assets/webroot/');
  const{BatteryInfoPanel,isBatteryUncompensated,selectCoefficientRow,validCoefficientRows}=await import(pathToFileURL(path.join(root,'battery-info.js')).href);
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

  const subThreeRows=validCoefficientRows([{cutoffMv:3000,fccOffset:800,sohOffset:3},{cutoffMv:2850,fccOffset:400,sohOffset:2}]);
  assert.equal(selectCoefficientRow(subThreeRows,2900)?.cutoffMv,2850,'matched sub-3V row must be selected');
  assert.equal(selectCoefficientRow(subThreeRows,2800),null,'cutoff below lowest device tier selects no row');
  assert.equal(selectCoefficientRow(validCoefficientRows([{cutoffMv:0,fccOffset:0,sohOffset:0}]),3000),null);
  assert.equal(isBatteryUncompensated({cutoffMv:2900,coefficients:subThreeRows}),false);
  assert.equal(isBatteryUncompensated({cutoffMv:3000,coefficients:[{cutoffMv:3100,fccOffset:600,sohOffset:4}]}),true);
  assert.equal(isBatteryUncompensated({cutoffMv:3150,coefficients:[]}),false);

  const dom=require('./helpers.cjs').recordDom();globalThis.document=dom.document;
  const makeInfoPanel=()=>{
    const fields=new Map(),labels=new Map();
    for(const key of['voltage','batteryCurrent','chargingPower','chargingProtocol','usbInputVoltage','targetInputCurrent','temperature','rm','cycles','fcc','rawFcc','soh','rawSoh','qmax','cutoffMv','designCapacity','lockedCapacity','serial','manufactureDate','batteryType']){
      const classes=new Set();
      fields.set(key,{textContent:'',classes,classList:{toggle(name,on){on?classes.add(name):classes.delete(name);},remove(name){classes.delete(name);}}});
    }
    for(const key of ['rm','voltage','batteryCurrent','temperature'])labels.set(key,{textContent:''});
    const p=Object.create(BatteryInfoPanel.prototype);
    p.active=true;p.loaded=false;
    p.panel={hidden:false,querySelector(sel){
      const m=sel.match(/^\[data-info="(.+)"\]$/);if(m)return fields.get(m[1]);
      const l=sel.match(/^\[data-info-label=(.+)\]$/);if(l)return labels.get(l[1]);
      return null;
    }};
    return{p,fields};
  };

  // Sub-3V tier active: highlights 2850 mV row and marks rawFcc near-actual.
  {
    const{p,fields}=makeInfoPanel();
    p.update({status:{reading:{}},batteryInfo:{cycles:100,qmax:6200,fcc:6100,rawFcc:5712,soh:99,rawSoh:97.06,cutoffMv:2900,designCapacity:6500,noCompensation:false,coefficients:subThreeRows}});
    assert.ok(fields.get('rawFcc').classes.has('battery-near-actual')&&!fields.get('fcc').classes.has('battery-near-actual'));
    assert.match(fields.get('rawFcc').textContent,/5,712 mAh \(87\.9%\)/);
    assert.equal(fields.get('lockedCapacity').textContent,'488 mAh (-7.9%)');
    const rendered=dom.get('batteryCoefficientRows').children;
    assert.equal(rendered.length,2);
    assert.equal(rendered[0].className,'current-coefficient');
    assert.equal(rendered[1].className,'');
  }

  // >= 3V but below device's 3100 mV minimum tier: uncompensated, marks fcc near-actual, no row highlighted.
  {
    const{p,fields}=makeInfoPanel();
    p.update({status:{reading:{}},batteryInfo:{cycles:100,qmax:6200,fcc:6100,rawFcc:null,soh:100,rawSoh:null,cutoffMv:3000,designCapacity:6500,noCompensation:true,coefficients:[{cutoffMv:3100,fccOffset:600,sohOffset:4}]}});
    assert.ok(fields.get('fcc').classes.has('battery-near-actual')&&!fields.get('rawFcc').classes.has('battery-near-actual'));
    assert.equal(fields.get('rawFcc').textContent,'——');
    assert.equal(fields.get('rawSoh').textContent,'——');
    assert.equal(fields.get('lockedCapacity').textContent,'100 mAh (-1.6%)');
    const rendered=dom.get('batteryCoefficientRows').children;
    assert.equal(rendered.length,1);
    assert.equal(rendered[0].className,'');
  }

  // Unreadable/empty table: neither fcc nor rawFcc is marked near-actual, raw fields stay '—'.
  {
    const{p,fields}=makeInfoPanel();
    p.update({status:{reading:{}},batteryInfo:{cycles:100,qmax:6200,fcc:6100,rawFcc:null,soh:98,rawSoh:null,cutoffMv:3150,designCapacity:6500,noCompensation:false,coefficients:[]}});
    assert.ok(!fields.get('fcc').classes.has('battery-near-actual')&&!fields.get('rawFcc').classes.has('battery-near-actual'));
    assert.equal(fields.get('rawFcc').textContent,'—');
    assert.equal(fields.get('rawSoh').textContent,'—');
    assert.equal(fields.get('lockedCapacity').textContent,'—');
  }

  console.log('Battery explanation: opening preserves focus behavior and underlying page scroll');
})().catch(error=>{console.error(error);process.exitCode=1;});
