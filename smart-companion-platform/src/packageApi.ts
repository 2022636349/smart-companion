import { request, workspaceId } from './api';
export interface PackageDocument { path: string; fileName: string; sizeBytes: number; role: 'TEXTBOOK'|'STANDARD'|'INSTRUCTIONS'|'UNKNOWN'; recommended: boolean; reason: string; jobId: string|null; status: string; failureMessage: string|null }
export interface PackageDetail { id: string; courseId: string; fileName: string; sha256: string; status: 'READY'|'PROCESSING'|'COMPLETED'|'NEEDS_ATTENTION'; createdAt: string; importId: string|null; analysisStatus: 'READY'|'ANALYZED'|'NEEDS_MAPPING'|'NOT_PRESENT'|'FAILED'; analysisMessage: string; documents: PackageDocument[]; warnings: string[] }
export interface PackageStart { sourceLabel: string; acknowledgeWarnings: true; copyrightStatus: 'CONFIRMED'|'AUTHORIZED'|'PUBLIC_DOMAIN'|'UNKNOWN'; documentPaths: string[] }
const scope=`/courses/${encodeURIComponent(workspaceId)}/companion/packages`;
function verify(value: PackageDetail) { if(String(value.courseId)!==workspaceId) throw new Error('资源包课程空间不一致，请重新读取。'); return value; }
export const packageApi = {
  async upload(file: File) { if(!/\.zip$/i.test(file.name)||file.size>100*1024*1024) throw new Error('请选择不超过100 MiB的ZIP学习资源包。'); const form=new FormData(); form.append('file',file); return verify(await request<PackageDetail>(scope,'POST',form)); },
  async list() { const data=await request<{items: PackageDetail[]}>(scope); return data.items.map(verify); },
  async get(id: string) { return verify(await request<PackageDetail>(scope+'/'+encodeURIComponent(id))); },
  async start(id: string,value: PackageStart) { return verify(await request<PackageDetail>(scope+'/'+encodeURIComponent(id)+'/start','POST',value)); }
};
export const isExtractableDocument = (path: string) => /\.(pdf|docx|txt|md)$/i.test(path);

/** Rules/instructions require an explicit user choice even if misrecommended. */
export function recommendedDocuments(documents: PackageDocument[]): string[] { return documents.filter(d=>isExtractableDocument(d.path)&&d.recommended&&d.role!=='INSTRUCTIONS').map(d=>d.path); }
