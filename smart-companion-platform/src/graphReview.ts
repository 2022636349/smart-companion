import { graphLabel } from './graphLabels';
import type { GraphCandidate, GraphCandidateDecision } from './graphTypes';
export const candidateKey = (candidate: GraphCandidate) => candidate.candidateType + ':' + candidate.candidateId;
export function filterCandidates(items: GraphCandidate[], type: string, status: string, keyword: string): GraphCandidate[] {
  const term = keyword.trim().toLocaleLowerCase();
  return items.filter(c => (!type || c.candidateType===type) && (!status || c.decisionStatus===status) && (!term || JSON.stringify([c.candidateId,graphLabel(c.reviewLane),graphLabel(c.decisionStatus),graphLabel(c.edge?.relation || ''),c.node,c.edge,c.source,c.validationIssues,c.humanReviewComment]).toLocaleLowerCase().includes(term)));
}
export function buildReviewDecisions(items: GraphCandidate[], keys: string[], decision: 'APPROVE' | 'REJECT', comment: string): GraphCandidateDecision[] {
  if (!comment.trim()) throw new Error('批量审核前请填写审核意见，并核对所选条目的来源。');
  if (!keys.length || keys.length>100) throw new Error('每次请选择 1–100 项进行审核。');
  const chosen = items.filter(c=>keys.includes(candidateKey(c)));
  if(chosen.length!==keys.length) throw new Error('所选候选内容已变化，请重新核对选择。');
  return chosen.map(c=>({candidateType:c.candidateType,candidateId:c.candidateId,decision,comment:comment.trim()}));
}
/** Retain the user's selection on any failed API submission. */
export async function submitReviewSelection<T>(state: { keys: string[] }, items: GraphCandidate[], decision: 'APPROVE' | 'REJECT', comment: string, submit: (decisions: GraphCandidateDecision[]) => Promise<T>): Promise<T> {
  const result=await submit(buildReviewDecisions(items,state.keys,decision,comment));
  state.keys=[];
  return result;
}
