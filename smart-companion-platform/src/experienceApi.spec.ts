import { beforeEach, describe, expect, it, vi } from 'vitest';
import { planSaveInput, type Plan, clearExperience, experience, experienceRequest } from './experienceApi';
const fetchMock=vi.fn();
beforeEach(()=>{clearExperience();fetchMock.mockReset();vi.stubGlobal('fetch',fetchMock);});
function respond(data:unknown,status=200){fetchMock.mockResolvedValueOnce({ok:status<400,status,json:async()=>status<400?{data}:{message:'会话已失效'}});}
describe('体验API边界',()=>{
 it('uses opaque experience session and never legacy Authorization',async()=>{respond({token:'opaque',view:'TEACHER'});await experience.session();respond({revision:1});await experience.workspace();const headers=fetchMock.mock.calls[1][1].headers as Headers;expect(headers.get('X-Companion-Session')).toBe('opaque');expect(headers.has('Authorization')).toBe(false);expect(fetchMock.mock.calls[0][0]).toBe('/api/companion-experience/session');});
 it('sends filtering and pagination to server',async()=>{respond({items:[],total:0});await experience.page('students',{q:'学生 A',page:2,pageSize:20,nodeId:'n',classId:''});const url=new URL(fetchMock.mock.calls[0][0],'http://localhost');expect(url.searchParams.get('q')).toBe('学生 A');expect(url.searchParams.get('page')).toBe('2');expect(url.searchParams.has('classId')).toBe(false);});
 it('retries upload with same operation key',async()=>{const file=new File(['x'],'learning.xlsx');respond({summary:{id:'1'}});respond({summary:{id:'1'}});await experience.upload(file,'来源','same-key');await experience.upload(file,'来源','same-key');expect(fetchMock.mock.calls.map(c=>c[1].headers.get('Idempotency-Key'))).toEqual(['same-key','same-key']);});
 it('surfaces failures without mock fallback',async()=>{respond(null,401);await expect(experienceRequest('/workspace')).rejects.toThrow('会话已失效');expect(fetchMock).toHaveBeenCalledTimes(1);});
});

it('saves edits against their displayed student evidence even when workspace and confirmed-plan data are older',async()=>{
 const edited:Plan={studentId:'D01',nodeId:'N01',revision:3,dataRevision:4,status:'NEEDS_REVIEW',steps:[]};
 const snapshot={revision:9,student:{id:'D01',classId:'班级甲',grade:'七年级'}};
 respond({revision:4});await experienceRequest('/plans/D01/N01','PUT',planSaveInput(edited,snapshot,'DRAFT'));
 const body=JSON.parse(fetchMock.mock.calls[0][1].body);
 expect(body.expectedDataRevision).toBe(9);expect(body.expectedRevision).toBe(3);expect(body.status).toBe('DRAFT');
 expect(edited.dataRevision).toBe(4);
});
it('rejects edits paired with another student snapshot',()=>{
 const edited={studentId:'D01',revision:3,dataRevision:4,steps:[]} as unknown as Plan;
 expect(()=>planSaveInput(edited,{revision:9,student:{id:'D02',classId:'班级甲',grade:'七年级'}},'DRAFT')).toThrow('学习依据已变化');
});
