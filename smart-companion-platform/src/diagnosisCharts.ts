import type { EChartsCoreOption } from 'echarts/core';
import type { Observation } from './types';
import { indicatorLabel, percentText, uiFontFamily } from './experienceDisplay';

export const diagnosisStatuses: Record<string, { label: string; color: string }> = {
  NEEDS_PRACTICE: { label: '需要练习', color: '#f09a92' },
  DEVELOPING: { label: '仍需巩固', color: '#e8c67b' },
  READY: { label: '当前表现较好', color: '#69c5b2' },
  INSUFFICIENT: { label: '证据不足', color: '#ad9ee9' },
  NO_EVIDENCE: { label: '尚无证据', color: '#8fa5c0' },
  NOT_ASSESSED: { label: '本次未测量', color: '#657a96' },
};
export function diagnosisStatus(status: string) { return diagnosisStatuses[status] || { label: status, color: '#8fa5c0' }; }
export interface AbilityRow {
  indicatorId: string; label: string; status: string; scorePercent: number | null;
  scoredCount: number | null; availableQuestionCount: number | null;
}
// A missing observation stays unknown. It is never invented as a zero score or unmeasured status.
export function abilityRows(observations: Observation[], indicators: { id: string; label: string }[]): AbilityRow[] {
  const ids = [...new Set([...indicators.map(i => i.id), ...observations.map(o => o.indicatorId)])];
  return ids.map(indicatorId => {
    const o = observations.find(row => row.indicatorId === indicatorId);
    return { indicatorId, label: indicatorLabel(indicatorId, indicators), status: o?.status || 'UNKNOWN',
      scorePercent: o?.scorePercent != null && Number.isFinite(o.scorePercent) ? o.scorePercent : null,
      scoredCount: o?.scoredCount ?? null, availableQuestionCount: o?.availableQuestionCount ?? null };
  });
}
export function statusCounts(rows: AbilityRow[]) {
  return [...Object.keys(diagnosisStatuses), ...(rows.some(row => row.status === 'UNKNOWN') ? ['UNKNOWN'] : [])]
    .map(status => ({ status, name: status === 'UNKNOWN' ? '暂无诊断记录' : diagnosisStatus(status).label, value: rows.filter(row => row.status === status).length, itemStyle: { color: diagnosisStatus(status).color } }))
    .filter(row => row.value > 0);
}
export function abilityTooltip(row: AbilityRow) {
  const status = row.status === 'UNKNOWN' ? '暂无诊断记录' : diagnosisStatus(row.status).label;
  return `${row.label}\n${status} · 得分率 ${percentText(row.scorePercent)}\n已评分 ${row.scoredCount ?? '暂无'} · 可用题 ${row.availableQuestionCount ?? '暂无'}\n点击查看历史作答`;
}
export function indicatorFromChart(event: unknown, rows: AbilityRow[]): string | null {
  if (!event || typeof event !== 'object') return null;
  const e = event as { componentType?: string; value?: unknown; data?: { indicatorId?: unknown } };
  const id = e.componentType === 'yAxis' ? e.value : e.data?.indicatorId;
  return typeof id === 'string' && rows.some(row => row.indicatorId === id) ? id : null;
}
export function statusFromChart(event: unknown, rows: AbilityRow[]): string | null {
  if (!event || typeof event !== 'object') return null;
  const status = (event as { data?: { status?: unknown } }).data?.status;
  return typeof status === 'string' && rows.some(row => row.status === status) ? status : null;
}
export interface AbilityChartOptions { dense?: boolean; hideState?: boolean }
export function abilityChartOption(rows: AbilityRow[], options: AbilityChartOptions = {}): EChartsCoreOption {
  const byId = new Map(rows.map(row => [row.indicatorId, row]));
  const wrap = options.dense ? 9 : 10;
  return {
    textStyle: { fontFamily: uiFontFamily, color: '#d5e2f5' },
    tooltip: { trigger: 'item', renderMode: 'richText', confine: true, backgroundColor: '#16273c', borderColor: '#2a3c53', textStyle: { color: '#eef3fc', fontFamily: uiFontFamily }, formatter: (raw: unknown) => {
      const e = raw as { data?: { indicatorId?: string }; value?: string };
      const row = byId.get(e.data?.indicatorId || e.value || ''); return row ? abilityTooltip(row).replace(row.label, row.label.match(/.{1,18}/gu)?.join('\n') || row.label) : '';
    } },
    grid: { left: options.dense ? 172 : 146, right: 44, top: options.dense ? 26 : 12, bottom: options.dense ? 46 : 42 },
    xAxis: { type: 'value', min: 0, max: 100, interval: 25, axisLabel: { color: '#a9bbd3', formatter: '{value}%' }, splitLine: { lineStyle: { color: '#2a3c53' } } },
    yAxis: { type: 'category', inverse: true, triggerEvent: true, data: rows.map(row => row.indicatorId), axisTick: { show: false }, axisLine: { show: false }, axisLabel: { interval: 0, color: '#d5e2f5', fontFamily: uiFontFamily, fontSize: options.dense ? 11 : 12, lineHeight: 18, formatter: (id: string) => {
      const row = byId.get(id)!;
      const lines = row.label.match(new RegExp('.{1,' + wrap + '}', 'gu'))?.join('\n') || row.label;
      // Dense student view keeps one status vocabulary in the ring legend and bar colours only,
      // so a 9-row chart does not need a third text line per row.
      if (options.hideState) return lines;
      return lines + '\n{state|' + (row.status === 'UNKNOWN' ? '暂无诊断记录' : diagnosisStatus(row.status).label) + '}';
    }, rich: { state: { color: '#9cb0c9', fontSize: 11, lineHeight: 20 } } } },
    series: [{ name: '已有评分得分率', type: 'bar', barWidth: 17, barMinHeight: 3, showBackground: true, backgroundStyle: { color: '#172a40', borderRadius: 3 }, itemStyle: { borderRadius: 3 }, label: { show: true, position: 'right', color: '#eef3fc', fontFamily: uiFontFamily, formatter: (raw: unknown) => { const e = raw as { data: { scorePercent: number | null } }; return e.data.scorePercent == null ? '' : percentText(e.data.scorePercent); } }, data: rows.map(row => ({ ...row, value: row.scorePercent, itemStyle: { color: diagnosisStatus(row.status).color } })) }],
  };
}
export function abilityStatusOption(rows: AbilityRow[]): EChartsCoreOption {
  return { textStyle: { fontFamily: uiFontFamily, color: '#d5e2f5' }, tooltip: { trigger: 'item', renderMode: 'richText', confine: true, backgroundColor: '#16273c', borderColor: '#2a3c53', textStyle: { color: '#eef3fc', fontFamily: uiFontFamily }, formatter: '{b}：{c} 项能力（{d}%）' }, series: [{ type: 'pie', radius: ['57%', '79%'], center: ['50%', '50%'], avoidLabelOverlap: true, label: { show: false }, labelLine: { show: false }, itemStyle: { borderColor: '#101e30', borderWidth: 3 }, data: statusCounts(rows) }] };
}

// The individual diagnosis/plan API needs one measurement; aggregate pages retain all-scope.
export function diagnosisScope(page: string, current: string, nodes: { id: string }[]): string {
  if (!['students', 'plans'].includes(page)) return current;
  return nodes.some(node => node.id === current) ? current : nodes[0]?.id || '';
}
