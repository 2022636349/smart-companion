export const uiFontFamily="'Segoe UI','等线',DengXian,sans-serif";
interface ScopeNode {id:string;description:string;scope?:string;date?:string}
interface TopicLabel {topicId?:string;topicName?:string}
export function scopeText(node:ScopeNode,indicators:TopicLabel[]=[]){
 const topics=new Map(indicators.filter(i=>i.topicId&&i.topicName).map(i=>[i.topicId!,i.topicName!]));
 const readable=(text:string)=>text.replace(/\bT\d+\b/g,id=>topics.get(id)||'相关知识');
 const description=readable(node.description||'测验');
 const scope=readable(node.scope||'');
 return scope&&!description.includes(scope)?description+' · '+scope:description;
}
export function scopeLabel(node:ScopeNode,indicators:TopicLabel[]=[]){
 const full=scopeText(node,indicators);const heading=full.split(/[（(：:·]/)[0].replace(/覆盖.*$/,'').trim()||'测验';
 return heading.length>22?heading.slice(0,22)+'…':heading;
}
const reviewedNames:Record<string,string>={"T01I1": "表示数量关系并代入求值", "T01I2": "识别同类项、去括号并化简", "T01I3": "用整式解释具体问题", "T02I1": "运用等式性质解一元一次方程", "T02I2": "用消元法解二元一次方程组", "T02I3": "列方程或方程组解决简单实际问题并检验", "T03I1": "运用不等式性质求解，负数乘除时正确改变方向", "T03I2": "求两个一元一次不等式解集的交集", "T03I3": "根据实际限制列不等式并解释取值范围"};
export function indicatorLabel(id:string,indicators?:{id:string;label:string}[]){const supplied=indicators?.find(i=>i.id===id)?.label;return supplied&&supplied!==id?supplied:reviewedNames[id]||'学习能力';}
export function shortIndicatorLabel(id:string,indicators?:{id:string;label:string}[]){const name=indicatorLabel(id,indicators);return name.length>10?name.slice(0,10)+'…':name;}
import type { EChartsCoreOption } from 'echarts/core';
export function distributionOption(rows:{label:string;count:number}[]):EChartsCoreOption{return {tooltip:{trigger:'axis',renderMode:'richText'},grid:{left:55,right:20,bottom:50},xAxis:{type:'category',data:rows.map(r=>r.label),axisLabel:{interval:0,lineHeight:18,formatter:(value:string)=>value.length>7?value.replace(/[–—-]/,'$&\n'):value}},yAxis:{type:'value',minInterval:1},series:[{type:'bar',data:rows.map(r=>r.count),itemStyle:{color:'#4b97ea'}}]};}
export function lastPage(total:number,size:number){return Math.max(1,Math.ceil(total/size));}
export function requestGate(){let revision=0;return {next:()=>++revision,current:(value:number)=>value===revision};}
export function percentText(value:number|null|undefined){return value==null||!Number.isFinite(value)?'暂无':`${value.toFixed(1)}%`;}
export const fieldNames:Record<string,string>={newMaterials:'新增题目与资料',duplicateMaterials:'复用题目与资料',newRecords:'新增有效作答',duplicateRecords:'重复作答',revisions:'新增修订',pendingRecords:'待处理记录',scoredCount:'已评分',pendingCount:'待评分',unansweredCount:'未作答',availableQuestionCount:'可用题目',coveragePercent:'证据覆盖率',earnedScore:'得分',maxScore:'总分',scorePercent:'得分率',meanRecordPercent:'平均作答得分率',studentMeanPercent:'学生平均得分率',studentId:'学生',nodeId:'测验范围',rawRecordCount:'原始作答',effectiveRecordCount:'有效作答',duplicateCount:'重复',invalidCount:'异常',conflictCount:'冲突',supersededCount:'旧修订'};
export function summaryEntries(value:Record<string,unknown>|null|undefined,context?:{nodes:ScopeNode[];indicators?:TopicLabel[]}){return Object.entries(value||{}).filter(([key,v])=>key in fieldNames&&v!=null).map(([key,v])=>({label:fieldNames[key],value:key==='nodeId'&&context?(context.nodes.find(n=>n.id===String(v))?scopeLabel(context.nodes.find(n=>n.id===String(v))!,context.indicators):'当前测验范围'):key.endsWith('Percent')?percentText(v as number):typeof v==='number'?Number.isInteger(v)?String(v):v.toFixed(1):String(v)}));}
export function observationCell(row:Record<string,any>,id:string){return (row.observations||[]).find((o:any)=>o.indicatorId===id);}
export function materialLabel(id:string,originalId?:string|null){return originalId||id.split('~')[0];}
export const batchStatusLabels:Record<string,string>={PREVIEW:'待确认',CONFIRMED:'已导入',REMOVED:'已移除',BLOCKED:'需处理'};
export interface ClassIndicatorSummary {classId:string;nodeId:string;indicatorId:string;studentCount:number;coveredStudentCount:number;scoredCount:number;scorePercent:number|null;needsPracticeCount:number;insufficientCount:number;noEvidenceCount:number;notAssessedCount:number}
export function classIndicatorOption(rows:ClassIndicatorSummary[],indicators:({id:string;label:string}&TopicLabel)[],nodes:{id:string;description:string}[]=[],horizontal=false):EChartsCoreOption {
 const scoped=rows.filter(r=>r.classId!=='ALL');const multipleNodes=new Set(scoped.map(r=>r.nodeId)).size>1;const classes=[...new Map(scoped.map(r=>[r.classId+'|'+r.nodeId,{classId:r.classId,nodeId:r.nodeId}])).values()];
 const option:EChartsCoreOption={legend:{top:0},tooltip:{trigger:'item',renderMode:'richText',formatter:(raw:any)=>{const r=raw.data?.evidence as ClassIndicatorSummary|undefined;return r?`${r.classId} · ${nodes.find(n=>n.id===r.nodeId)?scopeText(nodes.find(n=>n.id===r.nodeId)!,indicators):r.nodeId} · ${indicatorLabel(r.indicatorId,indicators)}\n得分率 ${percentText(r.scorePercent)}\n有效人数 ${r.coveredStudentCount}/${r.studentCount}\n证据不足 ${r.insufficientCount} · 暂无证据 ${r.noEvidenceCount} · 未测量 ${r.notAssessedCount}`:'';}},grid:{left:55,right:20,top:45,bottom:45},xAxis:{type:'category',data:indicators.map(i=>shortIndicatorLabel(i.id,indicators)),axisLabel:{interval:0,rotate:25}},yAxis:{type:'value',min:0,max:100,axisLabel:{formatter:'{value}%'}},series:classes.map(({classId,nodeId})=>({name:multipleNodes?classId+' · '+(nodes.find(n=>n.id===nodeId)?scopeLabel(nodes.find(n=>n.id===nodeId)!,indicators):nodeId):classId,type:'bar' as const,data:indicators.map(i=>{const row=rows.find(r=>r.classId===classId&&r.nodeId===nodeId&&r.indicatorId===i.id);return {value:row?.scorePercent!=null&&Number.isFinite(row.scorePercent)?Number(row.scorePercent.toFixed(1)):null,evidence:row};})}))};
 if(horizontal){option.yAxis={type:'category',inverse:true,data:indicators.map(i=>shortIndicatorLabel(i.id,indicators)),axisLabel:{width:115,overflow:'break',lineHeight:20,formatter:(value:string)=>value.match(/.{1,6}/g)?.join('\n')||value}};option.xAxis={type:'value',min:0,max:100,axisLabel:{formatter:'{value}%'}};option.grid={left:126,right:24,top:66,bottom:35};}
 return option;
}

export function chartTheme(option:EChartsCoreOption,theme:string):EChartsCoreOption {const dark=theme==='dark',text=dark?'#d5e2f5':'#263b55',muted=dark?'#a9bbd3':'#52657c',line=dark?'#2a3c53':'#d8e1ed',surface=dark?'#16273c':'#ffffff';const axis=(value:any)=>({...value,axisLabel:{...value?.axisLabel,color:muted},axisLine:{lineStyle:{color:line}},splitLine:{lineStyle:{color:line}}});return {...option,textStyle:{color:text,fontFamily:uiFontFamily},legend:{...(option.legend as object),textStyle:{color:text,fontFamily:uiFontFamily}},tooltip:{...(option.tooltip as object),textStyle:{color:text,fontFamily:uiFontFamily},backgroundColor:surface,borderColor:line},xAxis:axis(option.xAxis),yAxis:axis(option.yAxis)};}
