import { expect, it } from 'vitest';
import { defaultDiagnosisIndicator, diagnosisNodeReference, diagnosisSourceReference } from './diagnosisDisplay';
import type { Diagnosis } from './graphApi';
it('defaults to a bound indicator in report order and falls back for unbound or empty reports',()=>{
 expect(defaultDiagnosisIndicator([{id:'T01'},{id:'T03'}],[{indicatorId:'T03',knowledgePointId:'n'}])).toBe('T03');
 expect(defaultDiagnosisIndicator([{id:'T01'}],[])).toBe('T01');expect(defaultDiagnosisIndicator([],[])).toBe('');
});
it('maps references to readable names and locators while retaining original IDs',()=>{
 const d={knowledgePointId:'target',knowledgePointName:'分数',prerequisites:[{knowledgePointId:'pre',name:'除法'}],sources:[{sourceId:'source',title:'教材',locator:'第6页'}]} as Diagnosis;
 expect(diagnosisNodeReference(d,'target')).toBe('分数（target）');expect(diagnosisNodeReference(d,'pre')).toBe('除法（pre）');expect(diagnosisNodeReference(d,'unknown')).toBe('unknown');expect(diagnosisSourceReference(d,'source')).toBe('教材 · 第6页（source）');expect(diagnosisSourceReference(d,'missing')).toBe('missing');
});
