// Remember the path depth on each browser entry, rather than assigning a fixed
// depth to a detail-page type. Date changes and dialogs keep the page depth.
export function installPageDepth(){
  const depth=state=>Number.isInteger(state?.homeDepth)&&state.homeDepth>=0?state.homeDepth:0;
  const sync=()=>{for(const button of document.querySelectorAll('.page-home'))button.hidden=depth(history.state)<=1;};
  for(const method of ['pushState','replaceState']){
    const original=history[method].bind(history);
    history[method]=function(state,...args){
      if(state&&typeof state==='object'&&typeof state.page==='string'){
        let homeDepth;
        if(state.page==='home')homeDepth=0;
        else if(state.page==='records'||state.page==='settings')homeDepth=1;
        else if(method==='pushState')homeDepth=depth(history.state)+(state.page===history.state?.page?0:1);
        else homeDepth=Number.isInteger(state.homeDepth)?state.homeDepth:depth(history.state);
        state={...state,homeDepth};
      }
      const result=original(state,...args);sync();return result;
    };
  }
  window.addEventListener('popstate',sync);window.addEventListener('pageshow',sync);sync();
}
