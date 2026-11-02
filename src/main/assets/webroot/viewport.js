// Use one phone design width without CSS zoom: canvas coordinates, touch
// gestures, fixed overlays and embedded pages remain in the same CSS space.
(()=>{
  if(window.parent!==window)return;
  const meta=document.querySelector('meta[name="viewport"]');if(!meta)return;
  let lastWidth=0,frame=0;
  const update=()=>{
    frame=0;
    const viewport=window.visualViewport;
    const width=Math.round(viewport?viewport.width*viewport.scale:window.innerWidth);
    if(!width||Math.abs(width-lastWidth)<2)return;
    lastWidth=width;
    const designWidth=width<600?360:width,scale=width/designWidth;
    meta.content=`width=${designWidth},initial-scale=${scale},viewport-fit=cover`;
  };
  const schedule=()=>{if(!frame)frame=requestAnimationFrame(update);};
  update();window.addEventListener('resize',schedule);
  window.visualViewport?.addEventListener('resize',schedule);
})();
