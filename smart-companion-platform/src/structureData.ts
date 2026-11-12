import type { EChartsCoreOption } from 'echarts/core';
import { uiFontFamily } from './experienceDisplay';

export interface StructureSource { id: string; title: string; location: string; excerpt: string }
export interface StructureMaterial { id: string; type: string; questionType?: string; maxScore?: number | null; source?: string }
export interface StructurePrerequisite { indicatorId: string; knowledgePointId: string; label: string; reason: string; sourceIds?: string[] }
export interface StructureIndicator {
  id: string;
  label: string;
  topicId: string;
  topicName: string;
  topicGoal: string;
  standardReference: string;
  standardPages: string;
  textbookPages: string;
  knowledgePointId: string;
  summary: string;
  learningSteps: string[];
  knowledgeStatus: string;
  standards: StructureSource[];
  textbooks: StructureSource[];
  prerequisites: StructurePrerequisite[];
  questionCount: number;
  materials: StructureMaterial[];
}
export interface StructureTopic { id: string; name: string; goal: string; indicatorIds: string[] }
export interface StructureEdge { source: string; target: string; relation: string }
export interface StructurePayload {
  workspaceId?: string;
  revision: number;
  nodeId: string;
  knowledgeVersion: string;
  topics: StructureTopic[];
  indicators: StructureIndicator[];
  edges: StructureEdge[];
  limitations?: string[];
}

export function materialCode(id: string) { return id.split('~')[0]; }
export function shortLabel(value: string, limit = 12) { return value.length > limit ? value.slice(0, limit) + '…' : value; }

// 学习内容与要求梳理的三类依据，缺项时给出可读占位而不是空字符串。
export function requirementRows(item: StructureIndicator) {
  const locators = item.textbooks.map(source => source.location).filter(Boolean);
  return [
    { label: '课标要求', value: item.standards[0]?.excerpt || item.standardReference || '未登记课标要求', detail: [item.standardReference, item.standardPages].filter(Boolean).join(' · ') || '未登记页码' },
    { label: '教材位置', value: item.textbookPages || '未登记教材页', detail: locators.slice(0, 2).join('；') || '暂无教材出处' }
  ];
}
export function sourceRows(item: StructureIndicator) {
  return [
    ...item.standards.map(source => ({ ...source, kind: '课标' })),
    ...item.textbooks.map(source => ({ ...source, kind: '教材' }))
  ];
}
export function prerequisiteNames(payload: StructurePayload, id: string) {
  const byId = new Map(payload.indicators.map(item => [item.id, item]));
  return (payload.indicators.find(item => item.id === id)?.prerequisites || []).map(p => ({
    ...p, resolvedId: p.indicatorId || '', label: byId.get(p.indicatorId)?.label || p.label
  }));
}
export function unlocks(payload: StructurePayload, id: string) {
  return payload.edges.filter(edge => edge.source === id).map(edge => ({
    id: edge.target, label: payload.indicators.find(item => item.id === edge.target)?.label || edge.target
  }));
}
export function filterStructure(payload: StructurePayload, options: { topicId?: string; query?: string } = {}) {
  const query = (options.query || '').trim().toLowerCase();
  const matched = payload.indicators.filter(item => {
    if (options.topicId && item.topicId !== options.topicId) return false;
    if (!query) return true;
    const haystack = [item.id, item.label, item.topicName, item.topicGoal, item.summary, item.standardReference, item.standardPages, item.textbookPages, ...(item.learningSteps || [])]
      .join(' ').toLowerCase();
    return haystack.includes(query);
  });
  return { indicators: matched, total: payload.indicators.length, hidden: payload.indicators.length - matched.length };
}
export function structureSummary(payload: StructurePayload) {
  const sourceIds = new Set<string>();
  let standards = 0, textbooks = 0, questions = 0;
  for (const item of payload.indicators) {
    for (const source of item.standards) { standards++; sourceIds.add(source.id); }
    for (const source of item.textbooks) { textbooks++; sourceIds.add(source.id); }
    questions += Number(item.questionCount) || 0;
  }
  return {
    topics: payload.topics.filter(topic => topic.indicatorIds.length > 0).length,
    indicators: payload.indicators.length,
    standards,
    textbooks,
    questions,
    sources: sourceIds.size,
    ready: payload.indicators.filter(item => item.knowledgeStatus === 'READY').length
  };
}
export function topicGroups(payload: StructurePayload, visible: StructureIndicator[]) {
  const shown = new Set(visible.map(item => item.id));
  return payload.topics
    .map(topic => ({ ...topic, indicators: visible.filter(item => shown.has(item.id) && item.topicId === topic.id) }))
    .filter(topic => topic.indicators.length > 0);
}

export interface StructureGraphOptions { motion?: boolean; selectedId?: string; compact?: boolean }

// 主题包含关系用细线，前置关系用带箭头的琥珀色线：source 是前置，target 是需要它的后续内容。
export function structureGraphOption(payload: StructurePayload, visible: StructureIndicator[], options: StructureGraphOptions = {}): EChartsCoreOption {
  const compact = !!options.compact;
  const shown = new Set(visible.map(item => item.id));
  const nodes: Record<string, unknown>[] = [];
  for (const topic of payload.topics) {
    const members = topic.indicatorIds.filter(id => shown.has(id));
    if (!members.length) continue;
    nodes.push({ id: topic.id, name: topic.name || topic.id, kind: 'TOPIC', category: 0, symbolSize: compact ? 36 : 52, value: members.length,
      itemStyle: { color: '#4bd0b1', borderColor: '#75e3c8', borderWidth: 1 } });
  }
  for (const item of visible) {
    nodes.push({ id: item.id, name: shortLabel(item.label, compact ? 5 : 10), kind: 'INDICATOR', category: 1,
      symbolSize: compact ? (options.selectedId === item.id ? 28 : 22) : (options.selectedId === item.id ? 40 : 32), value: item.questionCount,
      itemStyle: { color: options.selectedId === item.id ? '#a9cfff' : '#3f6f9e', borderColor: options.selectedId === item.id ? '#eef3fc' : '#2a3c53', borderWidth: options.selectedId === item.id ? 2 : 1 } });
  }
  const links: Record<string, unknown>[] = [];
  for (const topic of payload.topics) for (const id of topic.indicatorIds) if (shown.has(id)) {
    links.push({ source: topic.id, target: id, relation: 'CONTAINS', lineStyle: { color: '#2f4a68', width: 1, curveness: 0 } });
  }
  for (const edge of payload.edges) {
    if (!shown.has(edge.source) || !shown.has(edge.target)) continue;
    links.push({ source: edge.source, target: edge.target, relation: edge.relation,
      lineStyle: { color: '#e4b568', width: 1.6, curveness: 0.08 }, symbol: ['none', 'arrow'], symbolSize: 7 });
  }
  return {
    textStyle: { fontFamily: uiFontFamily, color: '#d5e2f5' },
    legend: { show: true, top: 0, data: ['主题', '观察指标'], textStyle: { color: '#a3b3ca', fontFamily: uiFontFamily } },
    tooltip: {
      trigger: 'item', renderMode: 'richText', confine: true, backgroundColor: '#16273c', borderColor: '#2a3c53',
      textStyle: { color: '#eef3fc', fontFamily: uiFontFamily },
      formatter: (raw: unknown) => {
        const data = (raw as { data?: Record<string, any> }).data || {};
        if (data.kind === 'TOPIC') return `${data.name}\n包含 ${data.value} 项观察指标`;
        const item = data.indicator as StructureIndicator | undefined;
        if (!item) return String(data.name || '');
        return `${item.label}\n主题：${item.topicName}\n课标：${item.standardReference || '未登记'} · ${item.standardPages || '未登记'}\n教材：${item.textbookPages || '未登记'}\n关联题目：${item.questionCount} 项`;
      }
    },
    series: [{
      type: 'graph', layout: 'force', roam: true, draggable: true, categories: [{ name: '主题' }, { name: '观察指标' }],
      top: compact ? 18 : 32, bottom: compact ? 14 : 24, left: compact ? 10 : 24, right: compact ? 10 : 24, zoom: compact ? 0.58 : 0.82,
      force: { repulsion: compact ? 220 : 420, edgeLength: compact ? [64, 104] : [90, 150], gravity: compact ? 0.2 : 0.14, layoutAnimation: !!options.motion },
      label: { show: true, position: 'bottom', distance: 3, color: '#cfe0f5', fontFamily: uiFontFamily, fontSize: compact ? 9 : 10.5, lineHeight: compact ? 11 : 13,
        formatter: (raw: unknown) => {
          const data = (raw as { data?: Record<string, any> }).data || {};
          if (data.kind === 'TOPIC') return `{topic|${data.name}}`;
          const name = String(data.indicator?.label || data.name || '');
          const width = compact ? 6 : 8;
          return name.length > width ? name.replace(new RegExp('(.{' + width + '})', 'gu'), '$1\n') : name;
        },
        rich: { topic: { color: '#4bd0b1', fontSize: compact ? 11 : 12.5, fontWeight: 600, lineHeight: compact ? 14 : 16 } } },
      labelLayout: { hideOverlap: true },
      emphasis: { focus: 'adjacency', lineStyle: { width: 2.4 }, label: { show: true } },
      lineStyle: { color: '#2f4a68', width: 1 },
      edgeSymbol: ['none', 'none'], edgeSymbolSize: 6,
      data: nodes, links
    }]
  };
}

export function structureGraphLabel(summary: ReturnType<typeof structureSummary>) {
  return `学习内容与要求梳理知识图谱：${summary.topics} 个主题、${summary.indicators} 项观察指标、${summary.sources} 处课标与教材来源；点击主题或指标查看要求与学习内容`;
}
