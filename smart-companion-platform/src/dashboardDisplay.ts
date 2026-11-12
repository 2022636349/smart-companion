export interface DashboardIndicator {id:string;label:string;topicId?:string;topicName?:string}
export interface DashboardSummary {classId:string;indicatorId:string;studentCount:number;notAssessedCount:number;[key:string]:unknown}
export function className(id:string){return !id||id==='ALL'?'全部班级':id+' 班';}
export function measuredIndicatorIds(indicators:DashboardIndicator[],rows:DashboardSummary[]){return indicators.filter(i=>rows.some(r=>r.classId!=='ALL'&&r.indicatorId===i.id&&r.studentCount>r.notAssessedCount)).map(i=>i.id);}
export function comparisonRows<T extends {classId:string}>(rows:T[],selected:string[]){const allowed=new Set(selected);return rows.filter(r=>r.classId!=='ALL'&&allowed.has(r.classId));}
export function groupSummary<T extends {classId:string;nodeId?:string}>(rows:T[],classId:string,nodeId:string){return rows.filter(r=>r.classId===(classId||'ALL')&&(nodeId?r.nodeId===nodeId:!r.nodeId||r.nodeId==='ALL'));}
export function matrixColumnCount(width:number){return width<480?2:width<700?3:width<1050?4:6;}
export function matrixColumns<T extends {id:string}>(indicators:T[],selected:string[],page:number,size:number){const ids=new Set(selected);const ordered=indicators.filter(i=>ids.has(i.id));const pages=Math.max(1,Math.ceil(ordered.length/size));const current=Math.max(1,Math.min(page,pages));return {items:ordered.slice((current-1)*size,current*size),pages,page:current,total:ordered.length};}
export function setChoice(values:string[],id:string,checked:boolean){return checked?[...new Set([...values,id])]:values.filter(v=>v!==id);}
