import { beforeEach, expect, it, vi } from 'vitest';
import { buildEditedDecision, initialCandidateEdits } from './candidateEditor';
import { graphApi } from './graphApi';
import type { GraphCandidate } from './graphTypes';
const candidate=(kind:'NODE'|'EDGE'|'SOURCE')=>({candidateId:'c1',candidateType:kind,node:kind==='NODE'?{id:'n1',name:'原名称',description:'说明',evidence:['原证据'],extractorConfidence:.8,rightsStatus:'ALLOWED',parentId:'parent',aliases:['别名']}:null,edge:kind==='EDGE'?{sourceId:'n1',targetId:'n2',relation:'REQUIRES',evidence:['证据'],extractorConfidence:.9}:null,source:kind==='SOURCE'?{id:'s1',nodeId:'n1',fileId:'file',rightsStatus:'ALLOWED',excerpt:'原摘录',locator:'p1',pageFrom:1,pageTo:1,extractorConfidence:.7}:null}) as unknown as GraphCandidate;
const fetchMock=vi.fn();
beforeEach(()=>{vi.stubGlobal('sessionStorage',{getItem:()=>JSON.stringify({accessToken:'test-token'})});vi.stubGlobal('fetch',fetchMock);fetchMock.mockReset();});
it('merges editable node fields into full payload without changing identity or provenance',()=>{
 const c=candidate('NODE'), edits=initialCandidateEdits(c);edits.name='修正章节';edits.evidence='行一\n\n行二';edits.comment='核对原页';
 const d=buildEditedDecision(c,edits,'APPROVE',[]);
 expect(d.editedNode).toEqual({...c.node,name:'修正章节',evidence:['行一','行二']});expect(c.node?.name).toBe('原名称');expect(d.editedEdge).toBeUndefined();
});
it('validates graph endpoints and preserves explicit source to target prerequisite direction',()=>{
 const c=candidate('EDGE'),e=initialCandidateEdits(c);e.sourceId='n2';e.targetId='n1';e.comment='先学n2';
 expect(buildEditedDecision(c,e,'REJECT',['n1','n2']).editedEdge).toMatchObject({sourceId:'n2',targetId:'n1',relation:'REQUIRES'});
 e.targetId='other';expect(()=>buildEditedDecision(c,e,'APPROVE',['n1','n2'])).toThrow('当前图节点');
});
it('edits source evidence and physical pages while preserving protected fields',()=>{
 const c=candidate('SOURCE'),e=initialCandidateEdits(c);e.excerpt='修正原文';e.locator='第二页';e.pageFrom='2';e.pageTo='3';e.comment='逐字核对';
 expect(buildEditedDecision(c,e,'APPROVE',[]).editedSource).toEqual({...c.source,excerpt:'修正原文',locator:'第二页',pageFrom:2,pageTo:3});
 e.pageTo='1';expect(()=>buildEditedDecision(c,e,'APPROVE',[])).toThrow('不能早于');e.pageTo='2.5';expect(()=>buildEditedDecision(c,e,'APPROVE',[])).toThrow('正整数');
});
it('requires a deliberate review comment',()=>{expect(()=>buildEditedDecision(candidate('NODE'),initialCandidateEdits(candidate('NODE')),'APPROVE',[])).toThrow('审核意见');});
it('accepts Vue numeric v-model page values and empty or null pages',()=>{
 const c=candidate('SOURCE'),e=initialCandidateEdits(c);e.comment='核对';e.pageFrom=6;e.pageTo=7;
 expect(buildEditedDecision(c,e,'APPROVE',[]).editedSource).toMatchObject({pageFrom:6,pageTo:7,fileId:'file',extractorConfidence:.7});
 e.pageFrom='';e.pageTo=null;expect(buildEditedDecision(c,e,'APPROVE',[]).editedSource).toMatchObject({pageFrom:null,pageTo:null});
 e.pageFrom=NaN;expect(()=>buildEditedDecision(c,e,'APPROVE',[])).toThrow('正整数');e.pageFrom=1.5;expect(()=>buildEditedDecision(c,e,'APPROVE',[])).toThrow('正整数');
});
it('submits the exact revision with edits and retains input on 409',async()=>{
 const c=candidate('SOURCE'),e=initialCandidateEdits(c);e.comment='核对';e.excerpt='保留输入';const d=buildEditedDecision(c,e,'APPROVE',[]);
 fetchMock.mockResolvedValue({ok:false,status:409,json:async()=>({message:'修订冲突'})});
 await expect(graphApi.decide('4',12,[d])).rejects.toMatchObject({status:409});
 expect(JSON.parse(fetchMock.mock.calls[0][1].body)).toEqual({expectedRevision:12,decisions:[d]});expect(e.excerpt).toBe('保留输入');
});
