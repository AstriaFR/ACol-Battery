import{isoWeek,firstUsageWeek,calendarMonth,firstUsageMonth}from'./usage-calendar.js?v=acol-2.1';
export const usagePages=new Set(['usage-day','usage-week','usage-month','usage-app','usage-app-week']);
// Page switching replaces the current date; opening another page pushes native
// history. Both header Back and system Back consume that same browser history.
export function usageState(input){
  let day=Number(input.day);
  if(input.page.endsWith('week')){let week=isoWeek(day);if(week.year<2026)week=firstUsageWeek();day=week.start;}
  if(input.page==='usage-month'){let month=calendarMonth(day);if(month.year<2026)month=firstUsageMonth();day=month.start;}
  const state={page:input.page,day};
  if(input.page==='usage-app'||input.page==='usage-app-week'){state.pkg=input.pkg;state.label=input.label||input.pkg;}
  return state;
}
