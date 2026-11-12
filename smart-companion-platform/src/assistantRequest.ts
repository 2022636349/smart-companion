import type { StudentDetail } from './experienceApi';
import type { Observation } from './types';

const priority:Record<string,number>={NEEDS_PRACTICE:0,DEVELOPING:1,INSUFFICIENT:2,NO_EVIDENCE:3,READY:4,NOT_ASSESSED:5};
export function assistantObservations(detail:StudentDetail|null,studentId:string,nodeId:string):Observation[]{
 if(!detail||detail.student.id!==studentId||!studentId||!nodeId)return [];
 return detail.observations.filter(o=>o.studentId===studentId&&o.nodeId===nodeId&&!!o.indicatorId)
  .sort((a,b)=>(priority[a.status]??6)-(priority[b.status]??6));
}
export function assistantInput(detail:StudentDetail|null,studentId:string,nodeId:string,indicatorId:string,purpose:string){
 if(!detail||!Number.isInteger(detail.revision)||detail.revision<0||!assistantObservations(detail,studentId,nodeId).some(o=>o.indicatorId===indicatorId))
  throw new Error('请先选择当前学生、测验范围与可用能力，再生成建议。');
 if(!['EXPLAIN','PLAN','HINT'].includes(purpose))throw new Error('请选择有效的生成目的。');
 return {studentId,nodeId,indicatorId,expectedRevision:detail.revision,purpose};
}
