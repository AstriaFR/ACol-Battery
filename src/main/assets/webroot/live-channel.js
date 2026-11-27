import{spawn,exec}from'./root-bridge.js?v=acol-2.2s';
const client='/data/adb/aco_battery_app/runtime/bin/curve-client';
export const DETAIL_WATCH_MODE=0, BATTERY_INFO_WATCH_MODE=1, SETTINGS_WATCH_MODE=4, HOME_WATCH_MODE=5, SUMMARY_WATCH_MODE=6;
export const LIVE_READING_REFRESH_MS=1500;

export const recordRank=record=>record.active?(Number(record.fast_seconds)>0?0:1):2;
export function compareRecords(a,b){return recordRank(a)-recordRank(b)||b.id-a.id;}

export class LiveChannel{
  constructor(){this.connection=null;this.latest=null;this.sequence=0;this.consumed=0;this.waiters=[];this.rows=new Map();this.resumeId=0;this.resumeAfter=0;this.detailId=0;this.detailAfter=0;this.lists=null;this.listSequence=0;this.mode=3;this.small=0;this.infoSnapshot=null;this.deleted=new Set();this.minimumListVersion=0;}
  selectSmall(value){
    this.small=Number(!!value);
  }
  setMode(mode){if(this.mode===mode)return;this.close();this.mode=mode;this.detailId=0;this.detailAfter=0;this.latest=null;this.receivedAt=0;this.consumed=this.sequence;if(mode===BATTERY_INFO_WATCH_MODE)this.infoSnapshot=null;}
  seedRecord(id,after){
    if(this.mode!==DETAIL_WATCH_MODE)return;
    if(this.detailId!==id){
      this.close();this.consumed=this.sequence;
      this.rows.delete(id); // A general watch can have cached only the record's tail.
      this.detailId=id;
    }
    this.detailAfter=after;
  }
  stopProcess(connection){
    if(!connection.pid||connection.killed)return;
    connection.killed=true;
    exec(`${client} stop_watch ${connection.pid}`).catch(()=>{});
  }
  close(){
    const connection=this.connection;this.connection=null;
    if(connection){connection.closed=true;this.stopProcess(connection);}
    for(const waiter of this.waiters.splice(0)){clearTimeout(waiter.timer);waiter.reject(Error('页面已暂停'));}
  }
  open(){
    if(this.connection&&!this.connection.closed)return;
    const connection={closed:false,pid:null,killed:false};this.connection=connection;
    const pinned=this.mode===DETAIL_WATCH_MODE&&this.detailId>0;
    const id=pinned?this.detailId:this.resumeId,after=pinned?this.detailAfter:this.resumeAfter;
    const args=['watch',String(id),String(after),String(this.mode),'1',String(this.small),'0'];
    if(pinned)args.push('1');
    const child=spawn(client,args);
    const failed=()=>{
      if(connection.closed)return;
      connection.closed=true;if(this.connection!==connection)return;
      this.connection=null;
      for(const waiter of this.waiters.splice(0)){clearTimeout(waiter.timer);waiter.reject(Error('实时连接已断开，正在重连'));}
    };
    child.stdout.on('data',data=>{
      for(const line of String(data).split('\n')){
        if(!line.trim())continue;
        let packet;try{packet=JSON.parse(line);}catch{continue;}
        if(Number.isInteger(packet.streamPid)&&packet.streamPid>1){connection.pid=packet.streamPid;if(connection.closed)this.stopProcess(connection);continue;}
        if(connection.closed||this.connection!==connection)continue;
        if(!packet.ok){failed();this.stopProcess(connection);continue;}
        this.accept(packet);
      }
    });
    child.on('exit',code=>{
      if(code===0&&!connection.closed&&this.connection===connection&&!document.hidden){
        connection.closed=true;this.connection=null;this.open();
      }else failed();
    });child.on('error',failed);
  }
  merge(packet){
    if(!packet)return;
    const id=packet.record?.id??packet.recordId;
    if(!id)return;
    if(this.deleted.has(id))return;
    if(packet.record===null){this.rows.set(id,{record:null,points:[]});return;}
    const old=this.rows.get(id),points=old?.points??[];
    // A queued watch packet must not restore empty totals after foreground
    // settlement. A new recorder epoch permits its version counter to restart.
    if(old?.statsEpoch&&packet.statsEpoch===old.statsEpoch&&Number.isFinite(old.statsVersion)&&Number.isFinite(packet.statsVersion)&&packet.statsVersion<old.statsVersion){
      packet={...packet,record:packet.record?{...packet.record,app_revision:old.record?.app_revision??packet.record.app_revision}:packet.record};
      for(const key of ['apps','drainSummary','statsVersion','usagePending'])delete packet[key];
    }
    for(const point of packet.points??[])if(!points.length||point[0]>points.at(-1)[0])points.push(point);
    let pointsFloor=old?.pointsFloor??(id===this.detailId?this.detailAfter:0);
    const excess=points.length-24000;
    if(excess>0){pointsFloor=points[excess-1][0];points.splice(0,excess);}
    this.rows.set(id,{...old,...packet,points,pointsFloor});
    while(this.rows.size>3)this.rows.delete(this.rows.keys().next().value);
  }
  settle(record,result){
    const row=this.rows.get(record.id);if(!row?.record||row.record.start_wall!==record.start_wall)return;
    this.merge({...result,record:{...row.record,app_revision:result.appRevision??row.record.app_revision},points:[]});
  }
  accept(packet){
    if(packet.batteryInfo)this.infoSnapshot=packet.batteryInfo;
    this.merge(packet.previous);this.merge(packet.parallelPrevious);this.merge(packet.parallel);this.merge(packet);
    this.latest=packet;this.receivedAt=Date.now();++this.sequence;
    const resumeId=Math.max(0,packet.status.session);
    this.resumeAfter=resumeId?Math.max(this.rows.get(resumeId)?.points.at(-1)?.[0]??0,resumeId===this.resumeId?this.resumeAfter:0):0;
    this.resumeId=resumeId;
    if(Array.isArray(packet.records)&&(packet.listVersion??0)>=this.minimumListVersion){
      if(packet.listSmall!==undefined){
        this.replaceHead(!!packet.listSmall,packet,packet.status.settings?.hidePct??this.lists?.hidePct);
      }else{
      const hidePct=packet.status.settings?.hidePct??this.lists?.hidePct;
      const normal=packet.records.filter(row=>!this.deleted.has(row.id)),all=packet.recordsAll.filter(row=>!this.deleted.has(row.id));
      const total=packet.total-(packet.records.length-normal.length),totalAll=packet.totalAll-(packet.recordsAll.length-all.length);
      this.lists={normal:this.mergeHead(normal,total,this.lists?.normal,this.lists?.total,hidePct===this.lists?.hidePct),total,
        all:this.mergeHead(all,totalAll,this.lists?.all,this.lists?.totalAll,true),totalAll,hidePct};++this.listSequence;
      }
    }
    for(const waiter of this.waiters.splice(0)){
      clearTimeout(waiter.timer);waiter.resolve(packet);
    }
  }
  wait(after){
    this.open();
    if(this.latest&&this.sequence>after)return Promise.resolve(this.latest);
    return new Promise((resolve,reject)=>{
      const waiter={resolve,reject,timer:null};
      const timeout=this.mode===BATTERY_INFO_WATCH_MODE?30000:10000;
      waiter.timer=setTimeout(()=>{this.waiters=this.waiters.filter(w=>w!==waiter);this.close();reject(Error('实时连接超时'));},timeout);
      this.waiters.push(waiter);
    });
  }
  async status(){
    if(!this.latest||Date.now()-this.receivedAt>2000)await this.wait(this.sequence);
    return this.latest.status;
  }
  mergeHead(head,total,previous=[],previousTotal=0,keep=true){
    previous??=[];const oldest=head.filter(row=>!row.active).at(-1)?.id;
    // A periodic first-page snapshot updates the head without losing loaded pages.
    const tail=keep&&total>=previousTotal&&oldest?previous.filter(row=>!row.active&&row.id<oldest&&!this.deleted.has(row.id)):[];
    return[...head,...tail].sort(compareRecords).slice(0,total);
  }
  replaceHead(small,page,hidePct){
    const key=small?'all':'normal',totalKey=small?'totalAll':'total',versionKey=small?'versionAll':'version';
    const rows=page.records.filter(row=>!this.deleted.has(row.id));
    this.lists??={normal:null,all:null,total:0,totalAll:0,hidePct};
    const sameVersion=page.listVersion===undefined||this.lists[versionKey]===undefined||page.listVersion===this.lists[versionKey];
    this.lists[key]=this.mergeHead(rows,page.total,this.lists[key],this.lists[totalKey],sameVersion&&(small||hidePct===this.lists.hidePct));
    this.lists[versionKey]=page.listVersion;
    this.lists[totalKey]=page.total;this.lists.totalAll=page.totalRecords;this.lists.hidePct=hidePct;++this.listSequence;
    return this.cachedList(small);
  }
  appendList(small,page){
    if((page.listVersion??0)<this.minimumListVersion)return this.cachedList(small);
    if(!this.lists)return page;
    const key=small?'all':'normal',totalKey=small?'totalAll':'total',rows=new Map();
    for(const row of [...(this.lists[key]??[]),...page.records])if(!this.deleted.has(row.id)&&!rows.has(row.id))rows.set(row.id,row);
    this.lists[key]=[...rows.values()].sort(compareRecords);this.lists[totalKey]=page.total;if(Number.isInteger(page.totalRecords))this.lists.totalAll=page.totalRecords;
    return this.cachedList(small);
  }
  forgetRecord(record,version=0){
    if(this.deleted.has(record.id))return;
    this.minimumListVersion=Math.max(this.minimumListVersion,version);
    this.deleted.add(record.id);this.rows.set(record.id,{record:null,points:[]});
    if(this.lists){
      this.lists.normal=this.lists.normal?.filter(row=>row.id!==record.id);this.lists.all=this.lists.all?.filter(row=>row.id!==record.id);
      this.lists.totalAll=Math.max(0,this.lists.totalAll-1);
      if(record.active||record.fast_seconds>0||Math.abs(record.pct_end-record.pct_start)>this.lists.hidePct)this.lists.total=Math.max(0,this.lists.total-1);
      ++this.listSequence;
    }
  }
  cachedList(small){
    const records=small?this.lists?.all:this.lists?.normal;
    if(!Array.isArray(records))return null;
    // Pagination and active-row updates must not mutate the shared snapshots.
    const active=this.deleted.has(this.latest?.activeRecord?.id)?null:this.latest?.activeRecord;
    return{records:records.map(row=>row.id===active?.id?active:row.active&&this.rows.get(row.id)?.record||row).sort(compareRecords),
      total:small?this.lists.totalAll:this.lists.total,totalRecords:this.lists.totalAll,listVersion:small?this.lists.versionAll:this.lists.version};
  }
  async live(args){
    const [id,after,wantList,small]=args;
    this.selectSmall(small);
    if(id>0)this.seedRecord(id,after);
    await this.wait(this.consumed);this.consumed=this.sequence;
    const packet=this.latest;
    const result={ok:true,status:packet.status,activeRecord:packet.activeRecord,batteryInfo:this.infoSnapshot};
    const row=id>0?this.rows.get(id):null;
    // A cursor older than the retained points falls back to an exact live read.
    if(row&&after>=(row.pointsFloor??0)){
      const nextAfter=id===this.detailId&&Number.isFinite(row.nextAfter)&&row.nextAfter>after?row.nextAfter:null;
      Object.assign(result,{record:row.record,points:row.points.filter(p=>p[0]>after),nextAfter,ranges:row.ranges,rawCount:row.rawCount,statsVersion:row.statsVersion,statsEpoch:row.statsEpoch,usagePending:row.usagePending,apps:row.apps,drainSummary:row.drainSummary});
    }
    if(wantList){const list=this.cachedList(small);if(list)Object.assign(result,list);}
    return result;
  }
}
