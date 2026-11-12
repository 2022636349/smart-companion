import type { EChartsCoreOption } from 'echarts/core';
import type { Report } from './types';
import { distributions, observationsFor, percent, statusText, studentIds } from './analysis';

export interface Colors { text: string; muted: string; line: string; blue: string; teal: string; amber: string; red: string; empty: string }
function frame(colors: Colors) {
  return {
    textStyle: { fontFamily: 'Inter, Microsoft YaHei, sans-serif', color: colors.text },
    animationDuration: 250,
    tooltip: { trigger: 'item', renderMode: 'richText', confine: true },
    grid: { left: 45, right: 25, bottom: 34, top: 20 }
  };
}

export function classChart(report: Report, nodeId: string, colors: Colors): EChartsCoreOption {
  const rows = report.groupSummaries.filter(row => row.nodeId === nodeId && row.classId !== 'ALL');
  return {
    ...frame(colors),
    xAxis: { type: 'category', data: rows.map(row => row.classId), axisLine: { show: false }, axisTick: { show: false }, axisLabel: { color: colors.muted } },
    yAxis: { type: 'value', min: 0, max: 100, axisLabel: { formatter: '{value}%', color: colors.muted }, splitLine: { lineStyle: { color: colors.line } } },
    tooltip: { renderMode: 'richText', confine: true, formatter: (params: unknown) => {
      const point = params as { dataIndex: number };
      const row = rows[point.dataIndex];
      return row.classId + '\n得分率 ' + percent(row.scorePercent) + '\n有效得分 ' + row.earnedScore + ' / ' + row.maxScore +
        '\n有评分学生 ' + row.scoredStudentCount + ' / ' + row.totalStudentCount;
    } },
    series: [{ type: 'bar', barMaxWidth: 70, data: rows.map((row, index) => ({
      value: row.scorePercent, itemStyle: { color: index ? colors.teal : colors.blue, borderRadius: [6, 6, 0, 0] }
    })), label: { show: true, position: 'top', color: colors.text, formatter: (p: unknown) => percent((p as { value: number | null }).value) } }]
  };
}

export function distributionChart(report: Report, nodeId: string, classId: string, colors: Colors): EChartsCoreOption {
  const data = distributions(report, nodeId, classId);
  return {
    ...frame(colors),
    xAxis: { type: 'category', data: ['0–<20%', '20–<40%', '40–<60%', '60–<80%', '80–100%'], axisLine: { show: false }, axisTick: { show: false }, axisLabel: { color: colors.muted, fontSize: 10, interval: 0 } },
    yAxis: { type: 'value', minInterval: 1, axisLabel: { color: colors.muted }, splitLine: { lineStyle: { color: colors.line } } },
    series: [{ type: 'bar', data: data.bins, barMaxWidth: 40, itemStyle: { color: colors.blue, borderRadius: [5, 5, 0, 0] }, label: { show: true, position: 'top', color: colors.text, formatter: '{c} 人' } }]
  };
}

export function heatmapChart(report: Report, nodeId: string, classId: string, colors: Colors): EChartsCoreOption {
  const students = studentIds(report, classId).slice(0, 50);
  const points = report.indicators;
  const rows = report.observations.filter(row => row.nodeId === nodeId && students.includes(row.studentId));
  const data = rows.map(row => ({
    value: [points.findIndex(point => point.id === row.indicatorId), students.indexOf(row.studentId), row.scorePercent ?? -1],
    studentId: row.studentId, observation: row
  }));
  return {
    ...frame(colors),
    grid: { top: 92, left: 52, right: 12, bottom: 15 },
    xAxis: { type: 'category', position: 'top', data: points.map(point => point.label), axisLabel: { color: colors.muted, fontSize: 10, interval: 0,
      formatter: (value: string) => value.length > 6 ? value.slice(0, 6) + '\n' + value.slice(6, 12) + (value.length > 12 ? '…' : '') : value
    }, axisTick: { show: false }, axisLine: { show: false }, splitArea: { show: true } },
    yAxis: { type: 'category', data: students, inverse: true, axisLabel: { color: colors.muted, fontSize: 11 }, axisTick: { show: false }, axisLine: { show: false } },
    visualMap: { type: 'piecewise', orient: 'horizontal', top: 0, left: 'center', textStyle: { color: colors.muted, fontSize: 10 },
      pieces: [{ min: -1, max: -1, label: '暂无评分 / 未测量', color: colors.empty },
        { min: 0, lt: 60, label: '<60%', color: colors.red },
        { min: 60, lt: 85, label: '60–<85%', color: colors.amber },
        { min: 85, max: 100, label: '≥85%', color: colors.teal }] },
    tooltip: { renderMode: 'richText', confine: true, formatter: (value: unknown) => {
      const row = (value as { data: typeof data[number] }).data.observation;
      const indicator = points.find(point => point.id === row.indicatorId)!;
      return row.studentId + ' · ' + indicator.label + '\n' + statusText[row.status] +
        '\n已评分题得分率 ' + percent(row.scorePercent) + '\n已评分 ' + row.scoredCount + ' / 可用 ' + row.availableQuestionCount + ' 题';
    } },
    series: [{ type: 'heatmap', data, itemStyle: { borderWidth: 3, borderColor: colors.line, borderRadius: 3 } }]
  };
}

export function studentChart(report: Report, nodeId: string, studentId: string, colors: Colors): EChartsCoreOption {
  const rows = observationsFor(report, nodeId, studentId);
  return {
    ...frame(colors),
    grid: { left: 160, right: 55, top: 15, bottom: 30 },
    xAxis: { type: 'value', min: 0, max: 100, axisLabel: { color: colors.muted, formatter: '{value}%' }, splitLine: { lineStyle: { color: colors.line } } },
    yAxis: { type: 'category', inverse: true, data: rows.map(row => {
      const label = report.indicators.find(point => point.id === row.indicatorId)?.label || row.indicatorId;
      return label + (row.scorePercent == null ? '\n' + statusText[row.status] : '');
    }), axisLabel: { color: colors.muted, width: 148, overflow: 'truncate', fontSize: 11 }, axisTick: { show: false }, axisLine: { show: false } },
    tooltip: { renderMode: 'richText', confine: true, formatter: (value: unknown) => {
      const row = rows[(value as { dataIndex: number }).dataIndex];
      return row.indicatorId + '\n' + statusText[row.status] + '\n' + percent(row.scorePercent) + '\n已评分 ' + row.scoredCount + ' 题';
    } },
    series: [{ type: 'bar', barMaxWidth: 15, data: rows.map(row => ({
      value: row.scorePercent,
      itemStyle: { color: row.scorePercent == null ? colors.empty : row.scorePercent < 60 ? colors.red : row.scorePercent < 85 ? colors.amber : colors.teal, borderRadius: [0, 4, 4, 0] }
    })), label: { show: true, position: 'right', color: colors.text, fontSize: 11, formatter: (value: unknown) => percent((value as { value: number | null }).value) } }]
  };
}
