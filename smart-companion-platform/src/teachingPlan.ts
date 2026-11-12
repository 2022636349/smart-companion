import type {Plan,Step,Row} from './experienceApi';
export type PlanScope='STUDENT'|'CLASS';
export interface Constraints {objective:string;durationMinutes:number;maxSteps:number}
export interface TeachingPlan extends Omit<Plan,'studentId'> {scopeType:PlanScope;studentId:string|null;classId:string;constraints:Constraints;updatedAt?:string|null}
export interface PlanContext {revision:number;knowledgeVersion:string;plan:TeachingPlan;summary:Row}
export interface Proposal {scopeType:PlanScope;studentId:string|null;classId:string;nodeId:string;dataRevision:number;basePlanRevision:number;knowledgeVersion:string;status:'DRAFT';provider:string;model:string;explanation:string;cautions:string[];constraints:Constraints;steps:Step[];sources:{id:string;title:string;location:string;excerpt:string}[]}
export function proposalCurrent(p:Proposal,c:PlanContext){return p.scopeType===c.plan.scopeType&&p.studentId===c.plan.studentId&&p.classId===c.plan.classId&&p.nodeId===c.plan.nodeId&&p.dataRevision===c.revision&&p.basePlanRevision===c.plan.revision&&p.knowledgeVersion===c.knowledgeVersion;}
export function appendProposal(edited:Step[],proposal:Proposal,context:PlanContext,ids:string[]){if(!proposalCurrent(proposal,context))throw new Error('候选依据或计划版本已变化，请重新生成');const seen=new Set(edited.map(s=>s.id));const added=proposal.steps.filter(s=>ids.includes(s.id)&&!seen.has(s.id));if(edited.length+added.length>30)throw new Error('方案最多保留30个步骤，请先整理现有步骤');return [...edited,...JSON.parse(JSON.stringify(added))];}
export function constraintError(c:Constraints,count:number){if(c.objective.length>500)return '教学目标最多500字';if(!Number.isInteger(c.durationMinutes)||c.durationMinutes<10||c.durationMinutes>180)return '总时长须为10至180分钟';if(!Number.isInteger(c.maxSteps)||c.maxSteps<1||c.maxSteps>12)return '候选最多步数须为1至12';if(c.maxSteps<count)return '候选最多步数不能少于所选知识点数';if(c.durationMinutes<count)return '总时长不足以安排所选知识点';return '';}
export function teachingSaveInput(p:TeachingPlan,c:PlanContext,status:string){if(p.scopeType!==c.plan.scopeType||p.studentId!==c.plan.studentId||p.classId!==c.plan.classId||p.nodeId!==c.plan.nodeId)throw new Error('学习对象已变化，请重新读取方案');const error=constraintError(p.constraints,0);if(error)throw new Error(error);if(p.steps.some(s=>s.durationMinutes!=null&&(!Number.isInteger(s.durationMinutes)||s.durationMinutes<1||s.durationMinutes>180)))throw new Error('步骤时长须为1至180分钟');if(p.steps.reduce((n,s)=>n+(s.durationMinutes||0),0)>p.constraints.durationMinutes)throw new Error('步骤总时长超过方案限定时长，请调整后保存');return {scopeType:p.scopeType,...(p.scopeType==='STUDENT'?{studentId:p.studentId}:{}),classId:p.classId,nodeId:p.nodeId,expectedRevision:c.plan.revision,expectedDataRevision:c.revision,status,constraints:p.constraints,steps:p.steps};}
export function selectorFilters(classId:string,page=1,q=''){return {selector:true,classId,page,pageSize:20,q};}
export function selectedStudentClass(rows:Row[],studentId:string){return String(rows.find(s=>String(s.id)===studentId)?.classId||'');}

// Retain unadopted candidates after a successful write of the same target.
export function rebaseProposal(proposal:Proposal|null,before:PlanContext,saved:TeachingPlan):Proposal|null{
 if(!proposal||!proposalCurrent(proposal,before))return null;
 const next={...before,plan:saved};const rebased={...proposal,basePlanRevision:saved.revision};
 return saved.dataRevision===before.revision&&proposalCurrent(rebased,next)?rebased:null;
}
