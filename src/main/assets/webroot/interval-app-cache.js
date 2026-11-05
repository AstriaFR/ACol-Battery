import{ScreenTimeCache}from'./screen-time-cache.js?v=acol-2.2s';

/** One compact persistent result per record; read only the selected record. */
export class IntervalAppCache extends ScreenTimeCache{
  constructor(){super('acol.interval-app.cache');this.gcKey='acol.interval-app.cache.gc';}
  async read(record){
    try{
      const saved=await this.run('readonly',store=>store.get(this.key(record)));
      return saved?.schema===3&&saved.revision===(record.app_revision??0)&&Number.isSafeInteger(saved.end)&&saved.end>=0&&saved.end<=record.end_elapsed-record.start_elapsed&&typeof saved.complete==='boolean'&&Number.isSafeInteger(saved.cursor)&&saved.cursor>=0&&(saved.retryCursor===null||Number.isSafeInteger(saved.retryCursor)&&saved.retryCursor>=0)&&Array.isArray(saved.names)?saved:null;
    }catch{this.available=false;return null;}
  }
  async save(record,state){
    if(!this.canSave(record.id,this.key(record)))return;
    try{await this.run('readwrite',store=>this.canSave(record.id,this.key(record))?store.put({key:this.key(record),recordId:record.id,schema:3,revision:record.app_revision??0,end:state.loadedEnd,cursor:state.cursor,complete:state.complete,retryCursor:state.retryCursor,retryAt:state.retryAt,names:[...state.names]}):undefined);}
    catch{this.available=false;}
  }
}
