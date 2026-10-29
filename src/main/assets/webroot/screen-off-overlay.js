/** Load saved screen spans only while the record chart is on screen and checked. */
export class ScreenOffOverlay{
  constructor(chart,api,visible,onError){
    this.chart=chart;this.api=api;this.visible=visible;this.onError=onError;this.close();
  }
  close(){
    this.generation=(this.generation??0)+1;this.id=null;this.key=null;this.target=null;this.pending=null;
    this.chart.screenOffSpans=[];
  }
  stamp(record,version){
    const settled=record.fast_seconds>0&&Number.isFinite(version)?`stats:${version}`:`end:${record.end_elapsed}`;
    return `${record.id}:${record.start_wall}:${settled}:${record.active}`;
  }
  update(record,version){
    if(this.id!==record.id){this.close();this.id=record.id;}
    const key=this.stamp(record,version);this.target={record,version,key};
    if(record.kind!=='discharge'||!this.chart.enabled.has('screenOff')||!this.visible()||this.key===key||this.pending)return;
    const pending={key,generation:this.generation};this.pending=pending;
    this.api('screen_off_spans',record.id).then(data=>{
      if(pending.generation!==this.generation)return;
      this.key=key;this.chart.screenOffSpans=data.spans??[];
      if(this.chart.enabled.has('screenOff')&&this.visible())this.chart.draw();
    }).catch(error=>{
      if(pending.generation===this.generation&&this.chart.enabled.has('screenOff')&&this.visible())this.onError?.(`息屏时段读取失败：${error.message}`);
    }).finally(()=>{
      if(this.pending!==pending)return;this.pending=null;
      // A minute may have settled while the previous foreground request was in flight.
      if(this.target&&this.target.key!==key)this.update(this.target.record,this.target.version);
    });
  }
}
