// Isolate browser/native stand-ins between cases in a shared Node.js process.
const mocks=['window','document','localStorage','matchMedia','getComputedStyle','history','requestAnimationFrame','cancelAnimationFrame','ResizeObserver','MutationObserver','AcoNative','__timers','devicePixelRatio'];
exports.runCases=async cases=>{
  for(const test of cases){
    const previous=new Map(mocks.map(key=>[key,Object.getOwnPropertyDescriptor(globalThis,key)]));
    try{await test();}
    catch(error){error.message=`${test.name}: ${error.message}`;throw error;}
    finally{for(const [key,descriptor]of previous){if(descriptor)Object.defineProperty(globalThis,key,descriptor);else delete globalThis[key];}}
  }
};
