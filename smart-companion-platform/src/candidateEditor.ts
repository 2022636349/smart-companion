import type { GraphCandidate, GraphCandidateDecision } from './graphTypes';

export interface CandidateEdits { name: string; description: string; evidence: string; sourceId: string; targetId: string; relation: string; excerpt: string; locator: string; pageFrom: string | number | null; pageTo: string | number | null; comment: string }
export function initialCandidateEdits(c: GraphCandidate): CandidateEdits {
  return { name:c.node?.name||'', description:c.node?.description||'', evidence:(c.node?.evidence||c.edge?.evidence||[]).join('\n'), sourceId:c.edge?.sourceId||'', targetId:c.edge?.targetId||'', relation:c.edge?.relation||'REQUIRES', excerpt:c.source?.excerpt||'', locator:c.source?.locator||'', pageFrom:c.source?.pageFrom?.toString()||'', pageTo:c.source?.pageTo?.toString()||'', comment:c.humanReviewComment||'' };
}
export function buildEditedDecision(c: GraphCandidate, edits: CandidateEdits, decision: 'APPROVE'|'REJECT', nodeIds: string[]): GraphCandidateDecision {
  if(!edits.comment.trim()) throw new Error('请填写本项审核意见。');
  const result: GraphCandidateDecision={candidateType:c.candidateType,candidateId:c.candidateId,decision,comment:edits.comment.trim()};
  const evidence=edits.evidence.split('\n').map(s=>s.trim()).filter(Boolean);
  if(c.node) { if(!edits.name.trim()||!evidence.length) throw new Error('节点名称及证据不能为空。'); result.editedNode={...c.node,name:edits.name.trim(),description:edits.description,evidence}; }
  else if(c.edge) {
    if(!nodeIds.includes(edits.sourceId)||!nodeIds.includes(edits.targetId)||edits.sourceId===edits.targetId) throw new Error('请选择两个不同的当前图节点。');
    if(!['REQUIRES','RELATED','SUPPORTED_BY'].includes(edits.relation)||!evidence.length) throw new Error('请选择合法关系并填写证据。');
    result.editedEdge={...c.edge,sourceId:edits.sourceId,targetId:edits.targetId,relation:edits.relation as NonNullable<GraphCandidate['edge']>['relation'],evidence};
  } else if(c.source) {
    const page=(value:string | number | null)=>{ if(value===null || (typeof value==='string'&&!value.trim())) return null; const n=Number(value); if(!Number.isInteger(n)||n<1) throw new Error('物理页必须为空或正整数。'); return n; };
    const pageFrom=page(edits.pageFrom),pageTo=page(edits.pageTo);
    if(pageFrom!==null&&pageTo!==null&&pageTo<pageFrom) throw new Error('结束物理页不能早于起始页。');
    if(!edits.locator.trim()||!edits.excerpt.trim()) throw new Error('请填写来源定位及原文摘录。');
    result.editedSource={...c.source,excerpt:edits.excerpt,locator:edits.locator.trim(),pageFrom,pageTo};
  } else throw new Error('候选内容缺失，请重新读取。');
  return result;
}
