import { describe, it, expect } from 'vitest';
import { abilityRows, abilityChartOption, abilityStatusOption, statusCounts, indicatorFromChart, statusFromChart, abilityTooltip, diagnosisScope } from './diagnosisCharts';
import type { Observation } from './types';
const observation = (indicatorId:string,scorePercent:number|null,status:string):Observation => ({ studentId:'S',nodeId:'N',indicatorId,scorePercent,status,scoredCount:scorePercent==null?0:3,availableQuestionCount:6,evidenceIds:[] });
const indicators = [{id:'zero',label:'完整能力名称甲'},{id:'missing',label:'完整能力名称甲'},{id:'strong',label:'完整能力名称乙'}];
const rows = abilityRows([observation('zero',0,'NEEDS_PRACTICE'),observation('missing',null,'NOT_ASSESSED'),observation('strong',90,'READY')],indicators);
describe('能力图诊断语义',()=>{
 it('preserves zero and unknown values without manufacturing observations',()=>{
  const data=(abilityChartOption(rows).series as any[])[0].data;
  expect(data.map((row:any)=>row.value)).toEqual([0,null,90]);
  expect(data[0].indicatorId).toBe('zero');expect(data[1].status).toBe('NOT_ASSESSED');
  const absent=abilityRows([],indicators)[0];expect(absent.status).toBe('UNKNOWN');expect(absent.scoredCount).toBeNull();expect(absent.scorePercent).toBeNull();
  expect((abilityChartOption(rows).xAxis as any).min).toBe(0);expect((abilityChartOption(rows).xAxis as any).max).toBe(100);
 });
 it('counts abilities per state rather than weighting slices by percentages',()=>{
  expect(statusCounts(rows).map(row=>row.value)).toEqual([1,1,1]);
  const changed=rows.map(row=>({...row,scorePercent:100}));expect(statusCounts(changed)).toEqual(statusCounts(rows));
  expect((abilityStatusOption(rows).series as any[])[0].data.reduce((sum:number,row:any)=>sum+row.value,0)).toBe(3);
 });
 it('resolves axis and bar clicks to IDs even with identical human labels',()=>{
  expect((abilityChartOption(rows).yAxis as any).data).toEqual(['zero','missing','strong']);
  expect(indicatorFromChart({componentType:'yAxis',value:'missing'},rows)).toBe('missing');
  expect(indicatorFromChart({componentType:'series',data:{indicatorId:'zero'}},rows)).toBe('zero');
  expect(indicatorFromChart({componentType:'yAxis',value:'完整能力名称甲'},rows)).toBeNull();
  expect(indicatorFromChart(null,rows)).toBeNull();expect(indicatorFromChart({data:{indicatorId:'deleted'}},rows)).toBeNull();
  expect(statusFromChart({data:{status:'NOT_ASSESSED'}},rows)).toBe('NOT_ASSESSED');expect(statusFromChart({data:{status:'unknown'}},rows)).toBeNull();
 });
 it('offers full names, exact zero, counts and state in tooltip',()=>{
  expect(abilityTooltip(rows[0])).toContain('完整能力名称甲\n需要练习 · 得分率 0.0%\n已评分 3 · 可用题 6');
  expect(abilityTooltip(rows[1])).toContain('本次未测量 · 得分率 暂无');
 });
});


it('requires a real measurement for individual diagnosis and plan routes while preserving aggregate scope',()=>{
 const nodes=[{id:'N01'},{id:'N02'}];
 for(const page of ['students','plans']){
  expect(diagnosisScope(page,'',nodes)).toBe('N01');
  expect(diagnosisScope(page,'N02',nodes)).toBe('N02');
  expect(diagnosisScope(page,'removed',nodes)).toBe('N01');
  expect(diagnosisScope(page,'',[])).toBe('');
 }
 for(const page of ['overview','data']){
  expect(diagnosisScope(page,'',nodes)).toBe('');
  expect(diagnosisScope(page,'N02',nodes)).toBe('N02');
 }
});
