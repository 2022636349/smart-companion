import { describe,it,expect } from 'vitest';
import {distributionOption,classIndicatorOption,chartTheme,lastPage,requestGate,percentText,summaryEntries,observationCell,materialLabel,batchStatusLabels} from './experienceDisplay';
describe('全范围统计与请求时序',()=>{
 it('plots server counts directly independent of current page',()=>{const chart=distributionOption([{label:'达到要求',count:83}]);expect((chart.series as any[])[0].data).toEqual([83]);});
 it('returns valid last page after deletion including empty datasets',()=>{expect(lastPage(20,20)).toBe(1);expect(lastPage(0,20)).toBe(1);expect(lastPage(21,20)).toBe(2);});
 it('rejects older request after scope or role changes',()=>{const gate=requestGate(),old=gate.next();const current=gate.next();expect(gate.current(old)).toBe(false);expect(gate.current(current)).toBe(true);});
});

it('formats percentages to one decimal and keeps missing evidence explicit',()=>{expect(percentText(66.666666666667)).toBe('66.7%');expect(percentText(null)).toBe('暂无');expect(summaryEntries({scorePercent:52.657,scoredCount:20})[0].value).toBe('52.7%');});
it('aligns heatmap cells by indicator id rather than incoming order',()=>{expect(observationCell({observations:[{indicatorId:'T01I2',scorePercent:70},{indicatorId:'T01I1',scorePercent:30}]},'T01I1').scorePercent).toBe(30);expect(observationCell({},'missing')).toBeUndefined();});


it('shows original question identifiers without changing versioned ids',()=>{expect(materialLabel('Q043~abcdef1234567890')).toBe('Q043');expect(materialLabel('Q043~abcdef','原题43')).toBe('原题43');expect(batchStatusLabels.CONFIRMED).toBe('已导入');});


it('compares class indicators without plotting missing evidence as zero or ALL as class',()=>{const base={nodeId:'N1',studentCount:20,coveredStudentCount:10,scoredCount:12,needsPracticeCount:2,insufficientCount:3,noEvidenceCount:7,notAssessedCount:0};const option=classIndicatorOption([{...base,classId:'DC1',indicatorId:'I1',scorePercent:66.666},{...base,classId:'DC2',indicatorId:'I1',scorePercent:null},{...base,classId:'ALL',indicatorId:'I1',scorePercent:80}],[{id:'I1',label:'完整指标名'}]);const series=option.series as any[];expect(series.map(s=>s.name)).toEqual(['DC1','DC2']);expect(series[1].data[0].value).toBeNull();const formatter=(option.tooltip as any).formatter;expect(formatter({data:series[0].data[0]})).toContain('有效人数 10/20');expect(formatter({data:series[0].data[0]})).toContain('完整指标名');expect(formatter({data:series[0].data[0]})).toContain('66.7%');});

it('keeps multiple measurement scopes in separate class series',()=>{const base={classId:'DC1',indicatorId:'I1',studentCount:20,coveredStudentCount:10,scoredCount:12,needsPracticeCount:2,insufficientCount:3,noEvidenceCount:7,notAssessedCount:0};const option=classIndicatorOption([{...base,nodeId:'N01',scorePercent:60},{...base,nodeId:'N02',scorePercent:80}],[{id:'I1',label:'指标'}]);expect((option.series as any[]).map(s=>[s.name,s.data[0].value])).toEqual([['DC1 · N01',60],['DC1 · N02',80]]);});

it('applies readable dark and light theme colors without losing tooltip formatter or percent axis',()=>{const source={tooltip:{formatter:'label'},yAxis:{axisLabel:{formatter:'{value}%'}}};const dark=chartTheme(source,'dark'),light=chartTheme(source,'light');expect((dark.tooltip as any).formatter).toBe('label');expect((dark.yAxis as any).axisLabel.formatter).toBe('{value}%');expect((dark.legend as any).textStyle.color).not.toBe((light.legend as any).textStyle.color);expect((dark.xAxis as any).axisLabel.color).toBe('#a9bbd3');});
