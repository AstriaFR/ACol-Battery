// Gregorian civil-day arithmetic in UTC avoids local DST offsets changing ISO
// week numbers. ISO weeks run Monday–Sunday; week 1 contains January 4.
const DAY=86400000;
const civilStamp=wall=>{const date=new Date(wall);return Date.UTC(date.getFullYear(),date.getMonth(),date.getDate())};
const localDay=stamp=>{const date=new Date(stamp);return new Date(date.getUTCFullYear(),date.getUTCMonth(),date.getUTCDate()).getTime()};
export function isoWeek(wall){
  const stamp=civilStamp(wall);if(!Number.isFinite(stamp))throw Error('日期无效');
  const monday=stamp-((new Date(stamp).getUTCDay()+6)%7)*DAY;
  const year=new Date(monday+3*DAY).getUTCFullYear(),jan4=Date.UTC(year,0,4);
  const firstMonday=jan4-((new Date(jan4).getUTCDay()+6)%7)*DAY;
  const number=1+Math.floor((monday-firstMonday)/(7*DAY));
  return{year,number,label:`${String(year%100).padStart(2,'0')}/${String(number).padStart(2,'0')}`,start:localDay(monday),end:localDay(monday+7*DAY),days:Array.from({length:7},(_,i)=>localDay(monday+i*DAY))};
}
export function moveWeek(start,amount){return isoWeek(localDay(civilStamp(start)+amount*7*DAY));}
export function firstUsageWeek(){return isoWeek(new Date(2026,0,4).getTime());}
export function calendarMonth(wall){
  const date=new Date(wall),year=date.getFullYear(),month=date.getMonth();if(!Number.isFinite(date.getTime()))throw Error('日期无效');
  const count=new Date(year,month+1,0).getDate();
  return{year,number:month+1,label:`${String(year%100).padStart(2,'0')}/${String(month+1).padStart(2,'0')}`,start:new Date(year,month,1).getTime(),end:new Date(year,month+1,1).getTime(),days:Array.from({length:count},(_,i)=>new Date(year,month,i+1).getTime())};
}
export function moveMonth(start,amount){const date=new Date(start);return calendarMonth(new Date(date.getFullYear(),date.getMonth()+amount,1).getTime());}
export function firstUsageMonth(){return calendarMonth(new Date(2026,0,1).getTime());}
