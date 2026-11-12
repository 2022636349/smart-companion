import type { Diagnosis } from './graphApi';
export function defaultDiagnosisIndicator(indicators:{id:string}[], bindings:{indicatorId:string;knowledgePointId:string}[]): string {
 return indicators.find(i=>bindings.some(b=>b.indicatorId===i.id&&b.knowledgePointId))?.id || indicators[0]?.id || '';
}
export function diagnosisNodeReference(d:Diagnosis,id:string):string {
 const name=id===d.knowledgePointId?d.knowledgePointName:d.prerequisites.find(p=>p.knowledgePointId===id)?.name;
 return name?`${name}（${id}）`:id;
}
export function diagnosisSourceReference(d:Diagnosis,id:string):string {
 const source=d.sources.find(s=>s.sourceId===id);
 return source?`${source.title} · ${source.locator}（${id}）`:id;
}
