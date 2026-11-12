import type { Observation, RecordEvidence, Report } from './types';

export const statusText: Record<string, string> = {
  NOT_ASSESSED: '本次未测量', NO_EVIDENCE: '暂无评分证据',
  INSUFFICIENT: '题量不足，继续观察', NEEDS_PRACTICE: '建议基础练习',
  DEVELOPING: '继续巩固', READY: '可尝试变式'
};
export const percent = (value: number | null | undefined) => value == null ? '暂无评分' : value.toFixed(1) + '%';

export function studentIds(report: Report, classId: string) {
  return report.students.filter(student => classId === 'ALL' || student.classId === classId).map(student => student.id);
}

export function distributions(report: Report, nodeId: string, classId: string) {
  const students = new Set(studentIds(report, classId));
  const values = report.studentSummaries.filter(row => row.nodeId === nodeId && students.has(row.studentId));
  const bins = [0, 0, 0, 0, 0];
  let missing = 0;
  for (const row of values) {
    if (row.scorePercent == null) missing++;
    else bins[Math.min(4, Math.floor(row.scorePercent / 20))]++;
  }
  return { bins, missing, total: values.length };
}

export function observationsFor(report: Report, nodeId: string, studentId: string) {
  return report.observations.filter(row => row.nodeId === nodeId && row.studentId === studentId);
}

export function firstLostRecord(report: Report, observation: Observation) {
  const ids = new Set(observation.evidenceIds);
  return report.records.find(row => ids.has(row.id) && row.status === 'SCORED' && row.score! < row.maxScore);
}

export function explainObservation(row: Observation) {
  if (row.status === 'NOT_ASSESSED') {
    return { finding: '这次测验没有安排这个知识点的题目。', next: '结合后续教学进度再安排观察。' };
  }
  if (row.status === 'NO_EVIDENCE') {
    return { finding: '本次有 ' + row.availableQuestionCount + ' 道可用题，目前没有已评分作答。',
      next: '先补充练习或复核待评分作答，再判断需要巩固的内容。' };
  }
  const finding = '目前有 ' + row.scoredCount + ' 道已评分题，得分率为 ' + percent(row.scorePercent) + '。';
  const steps: Record<string, string> = {
    INSUFFICIENT: row.availableQuestionCount < 3
      ? '本次可用题较少，先核对已有过程，在后续测验中补充不同题目。'
      : '继续补充推荐的不同题目，并核对解题步骤，再更新观察。',
    NEEDS_PRACTICE: '先对照失分题的过程，结合关联材料复习关键步骤，再完成推荐练习。',
    DEVELOPING: '用不同题目继续巩固，说明每一步的依据，再根据新作答调整建议。',
    READY: '尝试解释方法选择或完成变式题，继续观察迁移表现。'
  };
  return { finding, next: steps[row.status] || '结合已有作答继续观察。' };
}

export function rubric(report: Report, record: RecordEvidence) {
  const material = report.materials.find(row => row.id === record.materialId);
  const earned = new Map(record.pointScores.split(/[；;]/).map(value => {
    const match = value.trim().match(/^(\S+)\s*=\s*(\d+)$/);
    return match ? [match[1], Number(match[2])] : ['', null];
  }));
  return (material?.scoringPoints || '').split(/\r?\n/).flatMap(line => {
    const match = line.trim().match(/^(\S+)\s+(.+?)\s+(\d+(?:\.\d+)?)分$/);
    return match ? [{ id: match[1], label: match[2], earned: earned.get(match[1]) ?? null, max: Number(match[3]) }] : [];
  });
}
