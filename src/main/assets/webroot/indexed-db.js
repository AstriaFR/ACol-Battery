/** Share an in-flight open, but never retain a failed connection attempt. */
export function openDatabase(owner,name,version,upgrade){
  if(owner.ready)return owner.ready;
  let ready;
  const attempt=new Promise((resolve,reject)=>{
    let finished=false;
    const finish=(error,db)=>{
      if(finished){db?.close();return;}
      finished=true;clearTimeout(timer);error?reject(error):resolve(db);
    };
    const timer=setTimeout(()=>finish(Error('缓存打开超时')),10000);
    try{
      const request=indexedDB.open(name,version);
      request.onupgradeneeded=()=>{try{upgrade(request.result,request.transaction);}catch(error){try{request.transaction.abort();}catch{}finish(error);}};
      request.onsuccess=()=>{
        const db=request.result;
        db.onversionchange=()=>{db.close();if(owner.ready===ready)owner.ready=null;};
        db.onclose=()=>{if(owner.ready===ready)owner.ready=null;};
        finish(null,db);
      };
      request.onerror=()=>finish(request.error||Error('缓存打开失败'));
      request.onblocked=()=>finish(Error('缓存正在被占用'));
    }catch(error){finish(error);}
  });
  ready=attempt.then(db=>{owner.available=true;return db;},error=>{
    if(owner.ready===ready)owner.ready=null;
    owner.available=false;throw error;
  });
  owner.ready=ready;return ready;
}
