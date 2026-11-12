import type { EChartsCoreOption } from 'echarts/core';
import type { Indicator, Node, Observation } from './types';
import { percentText, scopeLabel, uiFontFamily } from './experienceDisplay';

export interface ProgressMetric { key: string; label: string; display: string; percent: number | null; hint: string }

// 0–100 的展示比例；缺失值保持 null，绝不补成 0 分或 100%。
export function clampPercent(value: number | null | undefined) {
  return value == null || !Number.isFinite(value) ? null : Math.min(100, Math.max(0, value));
}
export function progressMetrics(summary: Record<string, any>): ProgressMetric[] {
  const scored = Number(summary?.scoredCount) || 0;
  const available = Number(summary?.availableQuestionCount) || 0;
  const pending = Number(summary?.pendingCount) || 0;
  const unanswered = Number(summary?.unansweredCount) || 0;
  return [
    { key: 'score', label: '本次得分率', display: percentText(summary?.scorePercent), percent: clampPercent(summary?.scorePercent), hint: '按已有评分计算，未评分题目不计零分' },
    { key: 'coverage', label: '证据覆盖率', display: percentText(summary?.coveragePercent), percent: clampPercent(summary?.coveragePercent), hint: '已评分题目 / 可用题目' },
    { key: 'scored', label: '已评分题目', display: `${scored} / ${available}`, percent: available ? clampPercent((100 * scored) / available) : null, hint: '缺少证据不代表未掌握' },
    { key: 'pending', label: '待评分 / 未作答', display: `${pending} / ${unanswered}`, percent: null, hint: '这部分不计入得分率，等教师核对' }
  ];
}

export interface TrendPoint { nodeId: string; label: string; date: string; scorePercent: number | null; covered: number; total: number }

// 跨测验范围按能力简单平均，只统计有评分的观察，并保留覆盖数以免把"没记录"当成"退步"。
export function progressTrend(observations: Observation[], nodes: Node[], indicators: Indicator[] = []): TrendPoint[] {
  const byNode = new Map<string, Observation[]>();
  for (const observation of observations || []) {
    const list = byNode.get(observation.nodeId) || [];
    list.push(observation);
    byNode.set(observation.nodeId, list);
  }
  return [...byNode.entries()].map(([nodeId, rows]) => {
    const scored = rows.filter(row => row.scorePercent != null && Number.isFinite(row.scorePercent));
    const mean = scored.length ? scored.reduce((total, row) => total + (row.scorePercent as number), 0) / scored.length : null;
    const node = (nodes || []).find(item => item.id === nodeId);
    return {
      nodeId,
      label: node ? scopeLabel(node, indicators) : nodeId,
      date: node?.date || '',
      scorePercent: mean == null ? null : Number(mean.toFixed(1)),
      covered: scored.length,
      total: rows.length
    };
  }).sort((a, b) => (a.date || a.nodeId).localeCompare(b.date || b.nodeId));
}
export function trendOption(points: TrendPoint[]): EChartsCoreOption {
  return {
    textStyle: { fontFamily: uiFontFamily, color: '#d5e2f5' },
    grid: { left: 46, right: 26, top: 26, bottom: 38 },
    tooltip: {
      trigger: 'item', renderMode: 'richText', confine: true, backgroundColor: '#16273c', borderColor: '#2a3c53',
      textStyle: { color: '#eef3fc', fontFamily: uiFontFamily },
      formatter: (raw: unknown) => {
        const point = points[(raw as { dataIndex: number }).dataIndex];
        if (!point) return '';
        return `${point.label}\n平均得分率 ${percentText(point.scorePercent)}\n有评分的观察 ${point.covered} / ${point.total} 项能力`;
      }
    },
    xAxis: { type: 'category', data: points.map(point => point.label), axisLabel: { color: '#a9bbd3', interval: 0, width: 92, overflow: 'truncate', fontFamily: uiFontFamily }, axisTick: { show: false }, axisLine: { lineStyle: { color: '#2a3c53' } } },
    yAxis: { type: 'value', min: 0, max: 100, interval: 25, axisLabel: { color: '#a9bbd3', formatter: '{value}%' }, splitLine: { lineStyle: { color: '#2a3c53' } } },
    series: [{
      type: 'line', smooth: true, connectNulls: true, symbolSize: 9, barMaxWidth: 40,
      data: points.map(point => point.scorePercent),
      lineStyle: { width: 2.5, color: '#84b9ff' }, itemStyle: { color: '#84b9ff' },
      areaStyle: { color: 'rgba(132,185,255,0.14)' },
      label: { show: true, position: 'top', color: '#eef3fc', fontFamily: uiFontFamily, formatter: (raw: unknown) => percentText((raw as { value: number | null }).value) }
    }]
  };
}
export function trendLabel(points: TrendPoint[]) {
  const covered = points.filter(point => point.scorePercent != null).length;
  return `各测验范围平均得分率折线：共 ${points.length} 次测验，其中 ${covered} 次有评分证据`;
}
