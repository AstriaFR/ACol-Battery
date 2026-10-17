import{spawn,exec}from'./root-bridge.js';
const client='/data/adb/aco_battery_app/runtime/bin/curve-client';

export class LiveChannel{
  constructor(){this.connection=null;this.latest=null;this.sequence=0;this.consumed=0;this.waiters=[];this.rows=new Map();this.resumeId=0;this.resumeAfter=0;this.lists=null;this.listSequence=0;this.sentList=0;this.mode=0;this.infoSnapshot=null;this.deleted=new Set();this.minimumListVersion=0;}
  setMode(mode){if(this.mode===mode)return;this.close();this.mode=mode;this.latest=null;this.receivedAt=0;this.consumed=this.sequence;if(mode===1)this.infoSnapshot=null;}
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
    const child=spawn(client,['watch',String(this.resumeId),String(this.resumeAfter),String(this.mode),this.infoSnapshot?'0':'1']);
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
    for(const point of packet.points??[])if(!points.length||point[0]>points.at(-1)[0])points.push(point);
    if(points.length>24000)points.splice(0,points.length-24000);
    this.rows.set(id,{...old,...packet,points});
    while(this.rows.size>3)this.rows.delete(this.rows.keys().next().value);
  }
  accept(packet){
    if(packet.batteryInfo)this.infoSnapshot=packet.batteryInfo;
    this.merge(packet.previous);this.merge(packet);
    this.latest=packet;this.receivedAt=Date.now();++this.sequence;
    this.resumeId=Math.max(0,packet.status.session);
    this.resumeAfter=this.resumeId?(this.rows.get(this.resumeId)?.points.at(-1)?.[0]??this.resumeAfter):0;
    if(Array.isArray(packet.records)&&(packet.listVersion??0)>=this.minimumListVersion){
      const hidePct=packet.status.settings?.hidePct??this.lists?.hidePct;
      const normal=packet.records.filter(row=>!this.deleted.has(row.id)),all=packet.recordsAll.filter(row=>!this.deleted.has(row.id));
      const total=packet.total-(packet.records.length-normal.length),totalAll=packet.totalAll-(packet.recordsAll.length-all.length);
      this.lists={normal:this.mergeHead(normal,total,this.lists?.normal,this.lists?.total,hidePct===this.lists?.hidePct),total,
        all:this.mergeHead(all,totalAll,this.lists?.all,this.lists?.totalAll,true),totalAll,hidePct};++this.listSequence;
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
      waiter.timer=setTimeout(()=>{this.waiters=this.waiters.filter(w=>w!==waiter);this.close();reject(Error('实时连接超时'));},12000);
      this.waiters.push(waiter);
    });
  }
  async status(){
    if(!this.latest||Date.now()-this.receivedAt>2000)await this.wait(this.sequence);
    return this.latest.status;
  }
  mergeHead(head,total,previous=[],previousTotal=0,keep=true){
    const oldest=head.at(-1)?.id;
    // A periodic first-page snapshot updates the head without losing loaded pages.
    const tail=keep&&total>=previousTotal&&oldest?previous.filter(row=>row.id<oldest&&!this.deleted.has(row.id)):[];
    return[...head,...tail].slice(0,total);
  }
  appendList(small,page){
    if((page.listVersion??0)<this.minimumListVersion)return this.cachedList(small);
    if(!this.lists)return page;
    const key=small?'all':'normal',totalKey=small?'totalAll':'total',rows=new Map();
    for(const row of [...this.lists[key],...page.records])if(!this.deleted.has(row.id)&&!rows.has(row.id))rows.set(row.id,row);
    this.lists[key]=[...rows.values()].sort((a,b)=>b.id-a.id);this.lists[totalKey]=page.total;
    return this.cachedList(small);
  }
  forgetRecord(record,version=0){
    if(this.deleted.has(record.id))return;
    this.minimumListVersion=Math.max(this.minimumListVersion,version);
    this.deleted.add(record.id);this.rows.set(record.id,{record:null,points:[]});
    if(this.lists){
      this.lists.normal=this.lists.normal.filter(row=>row.id!==record.id);this.lists.all=this.lists.all.filter(row=>row.id!==record.id);
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
    return{records:records.map(row=>row.id===active?.id?active:row),total:small?this.lists.totalAll:this.lists.total};
  }
  async live(args){
    await this.wait(this.consumed);this.consumed=this.sequence;
    const [id,after,wantList,small]=args,packet=this.latest;
    const result={ok:true,status:packet.status,activeRecord:packet.activeRecord,batteryInfo:this.infoSnapshot,frequencies:packet.frequencies};
    if(id>0&&this.rows.has(id)){
      const row=this.rows.get(id);
      Object.assign(result,{record:row.record,points:row.points.filter(p=>p[0]>after),nextAfter:null,ranges:row.ranges,rawCount:row.rawCount,statsVersion:row.statsVersion,apps:row.apps,drainSummary:row.drainSummary});
    }
    const list=this.cachedList(small);
    if(list&&(wantList||this.sentList!==this.listSequence)){
      Object.assign(result,list);this.sentList=this.listSequence;
    }
    return result;
  }
}
