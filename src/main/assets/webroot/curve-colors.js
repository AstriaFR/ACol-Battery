// Dark enough for thin lines on white. Each metric in a chart receives a unique slot.
const palette=['#2457D6','#B85C00','#9B32B0','#087F3E','#D12C3B','#252B35','#008AA6',
  '#877200','#D03F88','#5D4B9A','#7E4D22','#526374','#556B1F','#1A3768','#783C53'];
export function curveColor(index){
  return palette[index]??`hsl(${Math.round((index*137.508)%360)} 72% ${index%2?32:42}%)`;
}
export const batteryColors={rm:curveColor(3),pct:curveColor(0),current:curveColor(1),
  voltage:curveColor(2),power:curveColor(5),temp:curveColor(4)};
