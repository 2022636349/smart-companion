import { describe, it, expect } from 'vitest';
import type { StructureIndicator, StructurePayload } from './structureData';
import { filterStructure, materialCode, prerequisiteNames, requirementRows, sourceRows, structureGraphLabel, structureGraphOption, structureSummary, topicGroups, unlocks } from './structureData';

const source = (id: string, title = '出处') => ({ id, title, location: 'PDF第2页', excerpt: '摘录' });
const indicator = (id: string, topicId: string, extra: Partial<StructureIndicator> = {}): StructureIndicator => ({
  id, label: `${id} 观察指标`, topicId, topicName: topicId === 'T01' ? '数与式' : '方程与不等式', topicGoal: '理解并能应用',
  standardReference: `${id}-STD`, standardPages: 'PDF第2页／正文55页', textbookPages: '第12页',
  knowledgePointId: `SC-${id}`, summary: '摘要', learningSteps: ['先读规则', '再练习'], knowledgeStatus: 'READY',
  standards: [source(`${id}-STD`, '义务教育数学课程标准')], textbooks: [source(`${id}-S1`, '教材切片')],
  prerequisites: [], questionCount: 2, materials: [
    { id: `${id}-Q1~2`, type: '题目', questionType: '填空', maxScore: 4 },
    { id: `${id}-L1`, type: '学习材料' }
  ], ...extra
});

const payload: StructurePayload = {
  revision: 42, nodeId: 'N01', knowledgeVersion: 'official-20261106-learning-v1',
  topics: [
    { id: 'T01', name: '数与式', goal: '理解数量关系', indicatorIds: ['T01I1', 'T01I2'] },
    { id: 'T02', name: '方程与不等式', goal: '会列会解', indicatorIds: ['T02I1'] },
    { id: 'T03', name: '未使用主题', goal: '', indicatorIds: [] }
  ],
  indicators: [
    indicator('T01I1', 'T01'),
    indicator('T01I2', 'T01', { prerequisites: [{ indicatorId: 'T01I1', knowledgePointId: 'SC-T01I1', label: '表示数量关系', reason: '学习顺序联系' }] }),
    indicator('T02I1', 'T02', { knowledgeStatus: 'NOT_READY', learningSteps: [] })
  ],
  edges: [{ source: 'T01I1', target: 'T01I2', relation: 'PREREQUISITE' }]
};

describe('学习内容与要求梳理数据', () => {
  it('groups indicators by topic and drops topics without visible indicators', () => {
    const groups = topicGroups(payload, payload.indicators);
    expect(groups.map(group => group.id)).toEqual(['T01', 'T02']);
    expect(groups[0].indicators.map(item => item.id)).toEqual(['T01I1', 'T01I2']);
  });
  it('filters by topic and by requirement text while reporting hidden counts', () => {
    expect(filterStructure(payload, { topicId: 'T01' }).indicators.map(item => item.id)).toEqual(['T01I1', 'T01I2']);
    expect(filterStructure(payload, { query: '正文55页' }).total).toBe(3);
    expect(filterStructure(payload, { query: '不存在的内容' })).toMatchObject({ total: 3, hidden: 3 });
  });
  it('summarises unique sources instead of double counting pages', () => {
    expect(structureSummary(payload)).toMatchObject({ topics: 2, indicators: 3, standards: 3, textbooks: 3, questions: 6, sources: 6, ready: 2 });
  });
  it('keeps prerequisite direction: source is the earlier content', () => {
    const option = structureGraphOption(payload, payload.indicators, { motion: false, selectedId: 'T01I2' });
    const series = (option.series as any[])[0];
    const prerequisiteLinks = series.links.filter((link: any) => link.relation === 'PREREQUISITE');
    expect(prerequisiteLinks).toEqual([expect.objectContaining({ source: 'T01I1', target: 'T01I2' })]);
    const nodes = series.data as any[];
    expect(nodes.filter(node => node.kind === 'TOPIC').map(node => node.id)).toEqual(['T01', 'T02']);
    expect(nodes.find(node => node.id === 'T01I2').itemStyle.borderWidth).toBe(2);
    expect(series.force.layoutAnimation).toBe(false);
  });
  it('never draws topic or prerequisite edges to indicators hidden by a filter', () => {
    const option = structureGraphOption(payload, [payload.indicators[2]], {});
    const series = (option.series as any[])[0];
    expect(series.links).toHaveLength(1);
    expect(series.links[0]).toMatchObject({ source: 'T02', target: 'T02I1', relation: 'CONTAINS' });
  });
  it('shrinks nodes and layout for narrow screens so labels stay inside the canvas', () => {
    const wide = (structureGraphOption(payload, payload.indicators, {}) as any).series[0];
    const compact = (structureGraphOption(payload, payload.indicators, { compact: true }) as any).series[0];
    expect(compact.zoom).toBeLessThan(wide.zoom);
    expect(compact.data[0].symbolSize).toBeLessThan(wide.data[0].symbolSize);
    expect(compact.force.repulsion).toBeLessThan(wide.force.repulsion);
  });
  it('resolves readable requirement rows and sources with explicit fallbacks', () => {
    const rows = requirementRows(payload.indicators[0]);
    expect(rows[0]).toMatchObject({ label: '课标要求', value: '摘录' });
    expect(rows[0].detail).toContain('T01I1-STD');
    expect(rows[1]).toMatchObject({ label: '教材位置', value: '第12页' });
    expect(rows[1].detail).toContain('PDF第2页');
    expect(requirementRows({ ...payload.indicators[2], standardReference: '', standardPages: '', textbookPages: '', standards: [], textbooks: [] }).map(row => row.value))
      .toEqual(['未登记课标要求', '未登记教材页']);
    expect(sourceRows(payload.indicators[0]).map(row => row.kind)).toEqual(['课标', '教材']);
    expect(materialCode('T01I1-Q1~2')).toBe('T01I1-Q1');
  });
  it('resolves prerequisite names and downstream unlocks for the detail panel', () => {
    expect(prerequisiteNames(payload, 'T01I2')).toEqual([expect.objectContaining({ resolvedId: 'T01I1', label: 'T01I1 观察指标' })]);
    expect(unlocks(payload, 'T01I1')).toEqual([{ id: 'T01I2', label: 'T01I2 观察指标' }]);
    expect(unlocks(payload, 'T02I1')).toEqual([]);
  });
  it('labels the chart for screen readers with the real counts', () => {
    expect(structureGraphLabel(structureSummary(payload))).toContain('2 个主题');
    expect(structureGraphLabel(structureSummary(payload))).toContain('6 处课标与教材来源');
  });
});
