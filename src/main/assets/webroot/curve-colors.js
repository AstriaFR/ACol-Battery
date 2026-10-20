// Separate palettes keep thin lines distinct on white and OLED black.
const lightPalette=['#2457D6','#B85C00','#9B32B0','#087F3E','#D12C3B','#252B35','#008AA6',
  '#877200','#D03F88','#5D4B9A','#7E4D22','#526374','#556B1F','#1A3768','#783C53'];
const darkPalette=['#70D6FF','#FFD166','#E78AFF','#69E6A4','#FF7B89','#F3F4F6','#4DD9D0',
  '#F4A261','#FF8FC7','#B7A0FF','#E8D184','#A6C7E8','#B6E36B','#86B7FF','#FFB0A1'];
export function curveColor(index){
  const palette=document.documentElement.dataset.theme==='dark'?darkPalette:lightPalette;
  return palette[index]??`hsl(${Math.round((index*137.508)%360)} 82% ${document.documentElement.dataset.theme==='dark'?72:32}%)`;
}
const indices={rm:3,pct:0,current:1,voltage:2,power:5,temp:4};
export function batteryColor(key){return curveColor(indices[key]??0);}
