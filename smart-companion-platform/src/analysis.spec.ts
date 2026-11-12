import { describe, expect, it } from 'vitest';
import type { Report } from './types';
import { distributions, explainObservation, rubric } from './analysis';

describe('chart data semantics', () => {
  it('keeps missing scores out of the zero-score bin and counts boundaries once', () => {
    const scores = [null, 0, 20, 40, 60, 80, 100];
    const report = {
      students: scores.map((_, index) => ({ id: String(index), classId: 'A' })),
      studentSummaries: scores.map((scorePercent, index) => ({ studentId: String(index), nodeId: 'N', scorePercent }))
    } as Report;
    expect(distributions(report, 'N', 'A')).toEqual({ bins: [1, 1, 1, 1, 2], missing: 1, total: 7 });
    expect(distributions(report, 'OTHER', 'A')).toEqual({ bins: [0, 0, 0, 0, 0], missing: 0, total: 0 });
  });
  it('joins each earned point to its own rubric and preserves an actual zero', () => {
    const report = { materials: [{ id: 'Q', scoringPoints: 'QP1 正确移项 1分\nQP2 负数除法反向 1分' }] } as Report;
    const row = { materialId: 'Q', pointScores: 'QP1=1；QP2=0' } as Report['records'][number];
    expect(rubric(report, row)).toEqual([
      { id: 'QP1', label: '正确移项', earned: 1, max: 1 },
      { id: 'QP2', label: '负数除法反向', earned: 0, max: 1 }
    ]);
  });
  it('explains an unmeasured point and missing scoring without calling either a zero score', () => {
    const row = { status: 'NOT_ASSESSED', scorePercent: null, scoredCount: 0, availableQuestionCount: 0 } as Report['observations'][number];
    expect(explainObservation(row).finding).toContain('没有安排');
    const missing = explainObservation({ ...row, status: 'NO_EVIDENCE', availableQuestionCount: 5 });
    expect(missing.finding).toContain('5 道可用题');
    expect(missing.finding).toContain('没有已评分');
    expect(missing.finding).not.toContain('0%');
  });
});
