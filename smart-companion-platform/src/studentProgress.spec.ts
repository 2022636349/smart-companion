import { describe, it, expect } from 'vitest';
import { clampPercent, progressMetrics, progressTrend, trendLabel, trendOption } from './studentProgress';
import type { Observation } from './types';

const observation = (nodeId: string, indicatorId: string, scorePercent: number | null): Observation => ({
  studentId: 'D01', nodeId, indicatorId, availableQuestionCount: 3, scoredCount: scorePercent == null ? 0 : 2,
  scorePercent, status: scorePercent == null ? 'NO_EVIDENCE' : scorePercent < 60 ? 'NEEDS_PRACTICE' : 'READY', evidenceIds: []
});
const nodes = [
  { id: 'N02', date: '2026-10-20', scope: 'T02', description: '第二次阶段测验（覆盖T02）', availableMaterialIds: [] },
  { id: 'N01', date: '2026-09-15', scope: 'T01', description: '第一次阶段测验（覆盖T01）', availableMaterialIds: [] }
];
const indicators = [{ id: 'T01I1', topicId: 'T01', topicName: '代数式与整式运算', topicGoal: '', label: '表示数量关系并代入求值', standardReference: 'CR01', standardPages: '62／55', textbookPages: '上册1-20' }];

describe('学生学情可视化', () => {
  it('keeps missing percentages missing and clamps the drawable ratio', () => {
    expect(clampPercent(null)).toBeNull();
    expect(clampPercent(Number.NaN)).toBeNull();
    expect(clampPercent(-5)).toBe(0);
    expect(clampPercent(140)).toBe(100);
    expect(clampPercent(44.44)).toBeCloseTo(44.44);
  });
  it('renders the four headline metrics without turning missing scores into zero', () => {
    const rows = progressMetrics({ scorePercent: 44.44, coveragePercent: null, scoredCount: 12, availableQuestionCount: 27, pendingCount: 0, unansweredCount: 0 });
    expect(rows.map(row => row.display)).toEqual(['44.4%', '暂无', '12 / 27', '0 / 0']);
    expect(rows[0].percent).toBeCloseTo(44.44);
    expect(rows[1].percent).toBeNull();
    expect(rows[2].percent).toBeCloseTo((100 * 12) / 27);
    expect(rows[3].percent).toBeNull();
    expect(rows[0].hint).toContain('未评分题目不计零分');
  });
  it('averages only scored observations per measurement scope and sorts by date', () => {
    const points = progressTrend([
      observation('N02', 'T01I1', 80), observation('N02', 'T01I2', 60), observation('N02', 'T01I3', null),
      observation('N01', 'T01I1', 40), observation('N01', 'T01I2', null)
    ], nodes, indicators);
    expect(points.map(point => point.nodeId)).toEqual(['N01', 'N02']);
    expect(points[0]).toMatchObject({ scorePercent: 40, covered: 1, total: 2 });
    expect(points[1]).toMatchObject({ scorePercent: 70, covered: 2, total: 3 });
    expect(points[0].label).not.toContain('N01');
  });
  it('plots nulls instead of inventing a drop and labels the chart with real coverage', () => {
    const points = progressTrend([observation('N01', 'T01I1', null)], nodes, indicators);
    const option = trendOption(points) as any;
    expect(option.series[0].data).toEqual([null]);
    expect(option.xAxis.data).toHaveLength(1);
    expect(trendLabel(points)).toContain('其中 0 次有评分证据');
    expect(trendLabel(progressTrend([observation('N01', 'T01I1', 55)], nodes, indicators))).toContain('其中 1 次有评分证据');
  });
  it('keeps a single scope out of the trend chart range', () => {
    const points = progressTrend([observation('N01', 'T01I1', 55)], nodes, indicators);
    expect(points).toHaveLength(1);
  });
});
