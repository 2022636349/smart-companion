import { parseChoiceOptions } from './materialPresentation';
import type { RecordEvidence } from './types';

export function evidenceText(value: unknown): string {
  return value == null || String(value).trim() === '' ? '未提供' : String(value);
}
/** Preserve the timestamp's recorded wall time; the full timezone stays in provenance. */
export function submissionTime(value: unknown): string {
  const raw = evidenceText(value);
  const match = raw.match(/^(\d{4}-\d{2}-\d{2})[T ](\d{2}:\d{2})/);
  return match ? `${match[1]} ${match[2]}` : raw;
}
export function recordScore(row: Pick<RecordEvidence, 'status' | 'score' | 'maxScore'>): string {
  if (row.status === 'UNANSWERED') return '未作答 · 暂无评分';
  if (row.status === 'PENDING' || row.score == null) return '待评分';
  return `已有评分 ${row.score}/${row.maxScore}`;
}
/** Supplemental option text only. The caller continues to show the unmodified answer. */
export function choiceAnswerText(answer: unknown, options: unknown): string {
  if (typeof answer !== 'string') return '';
  const parsed = parseChoiceOptions(options);
  const label = answer.trim().replace(/[Ａ-Ｈ]/g, letter => String.fromCharCode(letter.charCodeAt(0) - 0xfee0));
  if (!parsed.parsed || !/^[A-H](?:[、,，;；\s]*[A-H])*$/.test(label)) return '';
  const labels = label.match(/[A-H]/g) || [];
  if (labels.some(key => !parsed.options.some(option => option.label === key))) return '';
  return labels.map(key => { const option = parsed.options.find(option => option.label === key)!; return `${key}：${option.text}`; }).join('\n');
}
