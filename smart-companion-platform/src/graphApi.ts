import { request, workspaceId } from './api';
import type { GraphIngestionJob, GraphCandidatePage, GraphCandidateDecision, PageData } from './graphTypes';
import type { Observation } from './types';
const scope = `/courses/${encodeURIComponent(workspaceId)}/knowledge/ingestion-jobs`;
const job = (id: string) => scope + '/' + encodeURIComponent(id);
const batch = (id: string) => `/courses/${encodeURIComponent(workspaceId)}/companion/imports/${encodeURIComponent(id)}`;
export interface PublishedNode { id: string; name: string; level: number; parentId: string | null; description: string; approvedResources: unknown[] }
export interface PublishedSource { id: string; title: string; locator: string; pageFrom: number | null; pageTo: number | null; excerpt: string | null }
export interface PublishedGraph { nodes: PublishedNode[]; edges: { sourceId: string; targetId: string; relation: string }[] }
export interface Bindings { currentGraphVersion: string; revision: number; graphVersion: string; bindings: { indicatorId: string; knowledgePointId: string }[] }
export interface Diagnosis {
  studentId: string; nodeId: string; indicatorId: string; graphVersion: string; bindingRevision: number; knowledgePointId: string; knowledgePointName: string;
  generatedAt: string; status: string; modelStatus: string; provider: string; model: string; observation: Observation;
  explanation: string; cautions: string[];
  directEvidence: { recordId: string; materialId: string; score: number | null; maxScore: number; answer: string; process: string; pointScores: string }[];
  prerequisites: { knowledgePointId: string; name: string; assessment: string; reason: string; observations: Observation[] }[];
  sources: { sourceId: string; title: string; locator: string; pageFrom: number | null; pageTo: number | null; excerpt: string }[];
  steps: { title: string; action: string; rationale: string; knowledgePointIds: string[]; sourceIds: string[]; evidenceIds: string[]; materialIds: string[] }[];
}
export const graphApi = {
  jobs: () => request<PageData<GraphIngestionJob>>(scope + '?page=1&size=100'),
  job: (id: string) => request<GraphIngestionJob>(job(id)),
  async upload(file: File, title: string, copyrightStatus: string) {
    const data = new FormData(); data.append('file', file); data.append('purpose', 'KNOWLEDGE_SOURCE');
    const source = await request<{ id: string }>('/files', 'POST', data);
    return request<GraphIngestionJob>(scope, 'POST', { courseId: Number(workspaceId), fileId: String(source.id), title, sourceType: 'TEXTBOOK', copyrightStatus });
  },
  candidates: (id: string, page = 1) => request<GraphCandidatePage>(job(id) + `/candidates?page=${page}&size=100`),
  decide: (id: string, expectedRevision: number, decisions: GraphCandidateDecision[]) => request<GraphIngestionJob>(job(id) + '/decisions', 'POST', { expectedRevision, decisions }),
  retry: (id: string) => request<GraphIngestionJob>(job(id) + '/retry', 'POST', {}),
  publish: (value: GraphIngestionJob, targetGraphVersion: string) => request<GraphIngestionJob>(job(value.id) + '/publish', 'POST', { expectedJobVersion: value.version, expectedDraftRevision: value.draftRevision, targetGraphVersion }),
  graph: () => request<PublishedGraph>(`/knowledge/graph?courseId=${encodeURIComponent(workspaceId)}&depth=5`),
  sources: (id: string) => request<PublishedSource[]>(`/knowledge/nodes/${encodeURIComponent(id)}/sources?courseId=${encodeURIComponent(workspaceId)}&approvedOnly=true`),
  bindings: (id: string) => request<Bindings>(batch(id) + '/knowledge-bindings'),
  saveBindings: (id: string, value: Bindings) => request<Bindings>(batch(id) + '/knowledge-bindings', 'PUT', { expectedRevision: value.revision, graphVersion: value.graphVersion, bindings: value.bindings }),
  diagnoses: (id: string) => request<{ items: Diagnosis[] }>(batch(id) + '/diagnoses'),
  diagnose: (id: string, studentId: string, nodeId: string, indicatorId: string) => request<Diagnosis>(batch(id) + '/diagnoses', 'POST', { studentId, nodeId, indicatorId })
};
