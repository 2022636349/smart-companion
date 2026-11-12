import type {PlanContext,TeachingPlan,Proposal} from './teachingPlan';
export interface UnsavedPlan {draft:TeachingPlan;context:PlanContext;proposal:Proposal|null;adopted:string[];focus:string[]}
const drafts=new Map<string,UnsavedPlan>();
export function planDraftKey(p:Pick<TeachingPlan,'scopeType'|'classId'|'studentId'|'nodeId'>){return JSON.stringify([p.scopeType,p.classId,p.scopeType==='STUDENT'?p.studentId:null,p.nodeId]);}
export function planHasEdits(draft:TeachingPlan,context:PlanContext){return JSON.stringify({constraints:draft.constraints,steps:draft.steps})!==JSON.stringify({constraints:context.plan.constraints,steps:context.plan.steps});}
export function cacheUnsavedPlan(draft:TeachingPlan,context:PlanContext,proposal:Proposal|null=null,adopted:string[]=[],focus:string[]=[]){const key=planDraftKey(draft);if(planHasEdits(draft,context)||proposal)drafts.set(key,JSON.parse(JSON.stringify({draft,context,proposal,adopted,focus})));else drafts.delete(key);}
export function readUnsavedPlan(key:string):UnsavedPlan|undefined{const value=drafts.get(key);return value?JSON.parse(JSON.stringify(value)):undefined;}
export function clearUnsavedPlan(key:string){drafts.delete(key);}
