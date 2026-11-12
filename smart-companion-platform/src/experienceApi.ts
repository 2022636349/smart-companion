import type { Student, Node, Indicator, Observation, Material } from './types';
import type { StructurePayload } from './structureData';
export type Row = Record<string, any>;
export interface ExperienceSession { token:string; expiresAt:string; view:'TEACHER'|'STUDENT'; studentId:string|null; workspaceId:string }
export interface Page<T=Row> { revision:number;page:number;pageSize:number;total:number;items:T[] }
export interface Workspace { revision:number;workspaceId:string;knowledgeVersion:string;counts:Row;nodes:Node[];indicators:Indicator[];classes:(string|Row)[];method:string[];limitations:string[] }
export interface Step { id:string;title:string;action:string;reason:string;indicatorId:string;materialIds:string[];evidenceIds:string[];hints:string[];durationMinutes?:number;knowledgePointIds?:string[];sourceIds?:string[] }
export interface Plan { studentId:string;nodeId:string;revision:number;dataRevision:number;status:string;steps:Step[] }
export interface StudentDetail { revision:number;student:Student;summary:Row;observations:Observation[];recommendations:Row[];plan:Plan;classPlan?:import("./teachingPlan").TeachingPlan|null }
// Pair edits with the revision of the student evidence shown beside the editor.
// Workspace refreshes and the previous confirmed plan may refer to older data.
export function planSaveInput(plan:Plan,snapshot:Pick<StudentDetail,'revision'|'student'>,status:string){
 if(plan.studentId!==snapshot.student.id||!Number.isInteger(snapshot.revision)||snapshot.revision<0)throw new Error('学习依据已变化，请重新选择学生后保存');
 return {expectedRevision:plan.revision,expectedDataRevision:snapshot.revision,status,steps:plan.steps};
}
let token = '';
let sessionEpoch=0;
export function clearExperience() { token='';sessionEpoch++; }
export async function experienceRequest<T=Row>(path:string,method='GET',body?:object|FormData,idempotencyKey?:string):Promise<T> {
 const epoch=sessionEpoch;
 const headers = new Headers(); if(token) headers.set('X-Companion-Session',token);
 if(body && !(body instanceof FormData)) headers.set('Content-Type','application/json');
 if(idempotencyKey) headers.set('Idempotency-Key',idempotencyKey);
 const response=await fetch('/api/companion-experience'+path,{method,headers,body:body instanceof FormData?body:body?JSON.stringify(body):undefined});
 const payload=await response.json().catch(()=>({message:'服务响应无效，请重试'}));
 if(epoch!==sessionEpoch)throw new Error('页面已离开，请在当前空间重试');
 if(!response.ok || payload.success===false) throw new Error(payload.message||`请求失败（${response.status}）`);
 return payload.data as T;
}
export const experience = {
 async session(body?:object) { const epoch=sessionEpoch;const value=await experienceRequest<ExperienceSession>('/session',body?'PUT':'POST',body);if(epoch!==sessionEpoch)throw new Error('页面已离开，请在当前空间重试');token=value.token;return value; },
 workspace:()=>experienceRequest<Workspace>('/workspace'),
 page:(collection:string,filters:Row)=>experienceRequest<Page>('/data/'+collection+'?'+new URLSearchParams(Object.entries(filters).filter(([,v])=>v!==''&&v!=null).map(([k,v])=>[k,String(v)]))),
 student:(id:string,nodeId:string)=>experienceRequest<StudentDetail>('/students/'+encodeURIComponent(id)+'?'+new URLSearchParams({nodeId})),
 structure:(nodeId:string)=>experienceRequest<StructurePayload>('/structure'+(nodeId?'?'+new URLSearchParams({nodeId}):'')),
 material:(id:string)=>experienceRequest<{material:Material}>('/materials/'+encodeURIComponent(id)),
 async upload(file:File,label:string,key:string) { const form=new FormData();form.append('file',file);form.append('sourceLabel',label);return experienceRequest('/batches','POST',form,key); },
 async download() { const response=await fetch('/api/companion-experience/export',{headers:{'X-Companion-Session':token}});if(!response.ok) throw new Error('完整导出失败，请重试');const blob=await response.blob();const url=URL.createObjectURL(blob);const link=document.createElement('a');link.href=url;link.download='智学引擎-完整报告.json';link.click();setTimeout(()=>URL.revokeObjectURL(url),1000); }
};
