import { beforeEach, describe, expect, it, vi } from 'vitest';
import { packageApi, recommendedDocuments, isExtractableDocument, type PackageDocument } from './packageApi';
const fetchMock=vi.fn();
beforeEach(()=>{vi.stubGlobal('sessionStorage',{getItem:()=>JSON.stringify({accessToken:'test'}),removeItem:vi.fn()});vi.stubGlobal('fetch',fetchMock);fetchMock.mockReset();});
function respond(data:unknown,status=200){fetchMock.mockResolvedValueOnce({ok:status<400,status,json:async()=>status<400?{data}:{message:'包内格式需要适配'}});}
describe('learning package contract',()=>{
 it('uploads only ZIP up to 100MiB as multipart and preserves actual NEEDS_MAPPING',async()=>{
  await expect(packageApi.upload(new File(['x'],'unknown.csv'))).rejects.toThrow('ZIP');expect(fetchMock).not.toHaveBeenCalled();
  respond({id:'9',courseId:'3',analysisStatus:'NEEDS_MAPPING',documents:[]});
  const value=await packageApi.upload(new File(['x'],'new-subject.zip'));
  expect(value.analysisStatus).toBe('NEEDS_MAPPING');expect(fetchMock.mock.calls[0][0]).toBe('/api/courses/3/companion/packages');
  expect((fetchMock.mock.calls[0][1].body as FormData).get('file')).toBeInstanceOf(File);
 });
 it('defaults to recommended documents and never defaults instructions into knowledge',()=>{
  const items=[{path:'math.txt',role:'TEXTBOOK',recommended:true},{path:'standard.pdf',role:'STANDARD',recommended:true},{path:'rules.md',role:'INSTRUCTIONS',recommended:true},{path:'unknown.docx',role:'UNKNOWN',recommended:false},{path:'scores.csv',role:'UNKNOWN',recommended:true}] as PackageDocument[];
  expect(recommendedDocuments(items)).toEqual(['math.txt','standard.pdf']);
  expect(isExtractableDocument('chapter.MD')).toBe(true);expect(isExtractableDocument('scores.csv')).toBe(false);expect(isExtractableDocument('data.xlsx')).toBe(false);expect(isExtractableDocument('figure.png')).toBe(false);
  expect(recommendedDocuments([{path:'later.txt',role:'TEXTBOOK',recommended:true,status:'SKIPPED'} as PackageDocument])).toEqual(['later.txt']);
 });
 it('sends one explicit confirmation with exact package paths and no automatic publish',async()=>{
  respond({courseId:'3',documents:[]});const body={sourceLabel:'新教材来源',acknowledgeWarnings:true as const,copyrightStatus:'AUTHORIZED' as const,documentPaths:['教材/第一章.txt']};
  await packageApi.start('9',body);expect(fetchMock.mock.calls[0][0]).toBe('/api/courses/3/companion/packages/9/start');expect(JSON.parse(fetchMock.mock.calls[0][1].body)).toEqual(body);
 });
 it('restores linked import/job and real failure state from persisted detail',async()=>{
  const detail={courseId:'3',id:'9',importId:'17',status:'NEEDS_ATTENTION',documents:[{path:'chapter.txt',jobId:'21',status:'FAILED',failureMessage:'模型服务不可用'}]};respond(detail);
  expect(await packageApi.get('9')).toEqual(detail);expect(fetchMock.mock.calls[0][0]).toBe('/api/courses/3/companion/packages/9');
 });
 it('rejects cross-course responses and surfaces start failure without inventing progress',async()=>{
  respond({items:[{courseId:'4',documents:[]}]});await expect(packageApi.list()).rejects.toThrow('课程空间不一致');
  respond({},503);await expect(packageApi.start('9',{sourceLabel:'来源',acknowledgeWarnings:true,copyrightStatus:'UNKNOWN',documentPaths:[]})).rejects.toMatchObject({status:503});
 });
});
