import { beforeEach, describe, expect, it, vi } from 'vitest';
import { buildReviewDecisions, candidateKey, filterCandidates, submitReviewSelection } from './graphReview';
import type { GraphCandidate } from './graphTypes';
import { graphApi } from './graphApi';
import type { Bindings } from './graphApi';
const fetchMock = vi.fn();
beforeEach(()=>{ vi.stubGlobal('sessionStorage',{getItem:()=>JSON.stringify({accessToken:'test-token'}),removeItem:vi.fn()}); vi.stubGlobal('fetch',fetchMock); fetchMock.mockReset(); });
function respond(data: unknown, status=200) { fetchMock.mockResolvedValueOnce({ok:status<400,status,json:async()=>status<400?{data}:{message:'版本已变化，请重新确认'}}); }
describe('graph workflow public API boundary',()=>{
  it('uploads source with purpose and creates a real course job',async()=>{
    respond({id:'file-1'}); respond({id:'job-1'});
    await graphApi.upload(new File(['source'],'chapter.txt'),'chapter','AUTHORIZED');
    expect(fetchMock.mock.calls[0][0]).toBe('/api/files');
    const form=fetchMock.mock.calls[0][1].body as FormData;
    expect(form.get('purpose')).toBe('KNOWLEDGE_SOURCE');
    expect(JSON.parse(fetchMock.mock.calls[1][1].body)).toMatchObject({courseId:3,fileId:'file-1',sourceType:'TEXTBOOK',copyrightStatus:'AUTHORIZED'});
    expect(fetchMock.mock.calls[1][0]).toMatch(/^\/api\/courses\/[^/]+\/knowledge\/ingestion-jobs$/);
    expect(fetchMock.mock.calls[1][1].headers.get('Idempotency-Key')).toBeTruthy();
  });
  it('sends exact candidate revision and no automatic bulk approval',async()=>{
    respond({id:'1'}); await graphApi.decide('1',7,[{candidateType:'NODE',candidateId:'n1',decision:'APPROVE',comment:'核对原文'}]);
    expect(JSON.parse(fetchMock.mock.calls[0][1].body)).toEqual({expectedRevision:7,decisions:[{candidateType:'NODE',candidateId:'n1',decision:'APPROVE',comment:'核对原文'}]});
  });
  it('uses authoritative version and full replacement for binding confirmation',async()=>{
    respond({}); await graphApi.saveBindings('1',{revision:4,currentGraphVersion:'v2',graphVersion:'v2',bindings:[]} as Bindings);
    expect(JSON.parse(fetchMock.mock.calls[0][1].body)).toEqual({expectedRevision:4,graphVersion:'v2',bindings:[]});
  });
  it('surfaces stale revision instead of claiming success',async()=>{
    respond({},409); await expect(graphApi.diagnose('1','S','N','I')).rejects.toThrow('版本已变化');
  });
  it('preserves model provenance and prerequisite CHECK_REQUIRED evidence on refresh',async()=>{
    const d={provider:'actual-provider',model:'actual-model',graphVersion:'v2',prerequisites:[{assessment:'CHECK_REQUIRED',observations:[]}]};
    respond({items:[d]}); const result=await graphApi.diagnoses('1'); expect(result.items[0]).toEqual(d);
  });
});

describe('multi-candidate review',()=>{
  const items=[{candidateType:'NODE',candidateId:'same',decisionStatus:'PENDING',node:{name:'函数',description:'输入输出'},validationIssues:[]},{candidateType:'SOURCE',candidateId:'same',decisionStatus:'APPROVED',source:{title:'教材',excerpt:'逐字原文'},validationIssues:[]}] as unknown as GraphCandidate[];
  it('filters type/status/keyword together and uses type-qualified selection IDs',()=>{
    expect(filterCandidates(items,'SOURCE','APPROVED',' 原文 ')).toEqual([items[1]]);
    expect(filterCandidates(items,'NODE','REJECTED','')).toEqual([]);
    expect(candidateKey(items[0])).not.toBe(candidateKey(items[1]));
  });
  it('requires explicit comment and limits every request to 100 selected candidates',()=>{
    expect(()=>buildReviewDecisions(items,['NODE:same'],'APPROVE','  ')).toThrow('填写审核意见');
    expect(()=>buildReviewDecisions(items,Array(101).fill('NODE:same'),'APPROVE','核对原文')).toThrow('1–100');
    expect(()=>buildReviewDecisions(items,['NODE:removed'],'APPROVE','核对原文')).toThrow('已变化');
  });
  it('submits explicit selected candidates with one expectedRevision and clears only on API success',async()=>{
    const state={keys:['NODE:same','SOURCE:same']}; respond({id:'1'});
    await submitReviewSelection(state,items,'APPROVE',' 已核对各自原文 ',decisions=>graphApi.decide('1',12,decisions));
    expect(JSON.parse(fetchMock.mock.calls[0][1].body)).toEqual({expectedRevision:12,decisions:[{candidateType:'NODE',candidateId:'same',decision:'APPROVE',comment:'已核对各自原文'},{candidateType:'SOURCE',candidateId:'same',decision:'APPROVE',comment:'已核对各自原文'}]});
    expect(state.keys).toEqual([]);
  });
  it('preserves selection and propagates 409 for a refresh and human recheck',async()=>{
    const state={keys:['SOURCE:same']}; respond({},409);
    await expect(submitReviewSelection(state,items,'REJECT','定位有误',decisions=>graphApi.decide('1',12,decisions))).rejects.toMatchObject({status:409});
    expect(state.keys).toEqual(['SOURCE:same']);
  });
});
