import{ScreenTimeCache}from'./screen-time-cache.js?v=acol-2.2s';

export class ChargeIntegralCache extends ScreenTimeCache{
  constructor(){
    super('acol.charge-integral.cache');
    this.gcKey='acol.charge-integral.cache.gc';
    this.pruneAt=0;
    this.pruning=false;
  }
  key(record){
    return `${record.id}:${record.start_wall}:${record.end_elapsed-record.start_elapsed}:${record.cell_count}`;
  }
  async prune(api){
    const now=performance.now();
    if(this.pruning||now<this.pruneAt)return;
    this.pruneAt=now+60000;
    this.pruning=true;
    try{await super.prune(api);}
    finally{this.pruning=false;}
  }
  async read(record,api,cancelled){
    const key=this.key(record),recordId=record.id;
    if(!this.canSave(recordId,key)||cancelled())return;
    let saved;
    try{saved=await this.run('readonly',store=>store.get(key));}catch{}
    if(cancelled())return;
    if(saved&&(saved.mah===null||Number.isFinite(saved.mah)))return saved.mah;
    let task=this.pending.get(key);
    if(!task){
      task=(async()=>{
        const {mah}=await api('charge_integral',recordId,record.start_wall,record.end_elapsed-record.start_elapsed);
        if(mah!==null&&!Number.isFinite(mah))return;
        await this.save({key,recordId,mah});
        return mah;
      })().finally(()=>this.pending.delete(key));
      this.pending.set(key,task);
    }
    return task;
  }
}
