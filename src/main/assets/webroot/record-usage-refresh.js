/** Foreground-only settlement; completion does not depend on another curve sample. */
export class RecordUsageRefresh{
  constructor(api,getRecord,visible,apply,options={}){
    this.api=api;this.getRecord=getRecord;this.visible=visible;this.apply=apply;
    this.now=options.now??Date.now;
    this.setTimer=options.setTimer??((callback,delay)=>setTimeout(callback,delay));
    this.clearTimer=options.clearTimer??(timer=>clearTimeout(timer));
    this.generation=0;this.sequence=0;this.timer=null;this.busy=null;this.key=null;this.pending=false;this.nextAt=0;
  }
  recordKey(record){return record?`${record.id}:${record.start_wall}`:null;}
  update(data){
    const record=data.record;if(record?.kind!=='discharge'){this.close();return;}
    const key=this.recordKey(record);
    if(key!==this.key){this.close();this.key=key;this.nextAt=this.now()+500;}
    const signature=`${data.statsEpoch??''}:${data.statsVersion??''}:${record.app_revision??0}:${data.usagePending===true}`;
    if(signature!==this.signature){this.sequence++;this.signature=signature;}
    this.pending=data.usagePending===true;
    if(this.pending)this.resume();else this.pause();
  }
  pause(){if(this.timer!==null)this.clearTimer(this.timer);this.timer=null;}
  close(){this.pause();this.generation++;this.key=null;this.pending=false;this.busy=null;this.signature=null;}
  resume(){
    if(!this.pending||this.busy||this.timer!==null||!this.visible()||this.recordKey(this.getRecord())!==this.key)return;
    this.timer=this.setTimer(()=>{this.timer=null;this.refresh();},Math.max(0,this.nextAt-this.now()));
  }
  async refresh(){
    const record=this.getRecord();
    if(!this.pending||this.busy||!this.visible()||this.recordKey(record)!==this.key)return;
    const generation=this.generation,sequence=this.sequence,job={};this.busy=job;let delay=3000;
    try{
      const result=await this.api('settle_usage',record.id);
      if(generation!==this.generation||sequence!==this.sequence||!this.visible()||this.recordKey(this.getRecord())!==this.key)return;
      if(result.settling){
        delay=750;
        const signature=`${result.statsEpoch??''}:${result.statsVersion??''}:${result.appRevision??0}:${result.usagePending===true}`;
        if(signature===this.signature)return;
      }
      this.apply(result,record.id);
    }catch{delay=5000;}
    finally{
      if(this.busy===job)this.busy=null;
      if(generation===this.generation){this.nextAt=this.now()+delay;this.resume();}
    }
  }
}
