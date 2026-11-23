const ns='http://www.w3.org/2000/svg';

export function compareUsageApps(a,b){return b.ms-a.ms||a.pkg.localeCompare(b.pkg);}

export function rankUsageApps(rows){
  const top=rows.filter(row=>row.ms>0&&!row.pkg.startsWith('__')).sort(compareUsageApps).slice(0,3);
  return new Map(top.map((row,index)=>[row.pkg,index+1]));
}

export function usageSegments(total,apps,ranks){
  const segments=[];
  let remaining=total;
  for(const [pkg,rank] of ranks){
    const ms=Math.min(remaining,apps.find(row=>row.pkg===pkg)?.ms??0);
    if(ms>0){segments.push({rank,ms});remaining-=ms;}
  }
  if(remaining>0)segments.push({rank:0,ms:remaining});
  return segments;
}

export function svg(tag,attributes){
  const node=document.createElementNS(ns,tag);
  for(const [key,value] of Object.entries(attributes))node.setAttribute(key,String(value));
  return node;
}

export function drawUsageStack(group,bar,segments,total,key){
  if(segments.length<2){
    group.append(svg('rect',{...bar,class:'screen-time-bar','data-usage-rank':segments[0]?.rank??0}));
    return;
  }
  const id=`usage-stack-${key}`,clip=svg('clipPath',{id}),stack=svg('g',{'clip-path':`url(#${id})`});
  clip.append(svg('rect',bar));
  group.append(clip,stack);
  let bottom=bar.y+bar.height,elapsed=0;
  for(const segment of segments){
    elapsed+=segment.ms;
    const y=bar.y+bar.height*(1-elapsed/total);
    stack.append(svg('rect',{x:bar.x,y,width:bar.width,height:bottom-y,class:'screen-time-bar','data-usage-rank':segment.rank}));
    bottom=y;
  }
}
