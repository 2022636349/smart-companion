<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import ChartView from '../ChartView.vue';
import UiSelect from './UiSelect.vue';
import StudentRecordList from './StudentRecordList.vue';
import { abilityChartOption, abilityRows, abilityStatusOption, diagnosisStatus, indicatorFromChart, statusCounts, statusFromChart } from '../diagnosisCharts';
import { progressMetrics, progressTrend, trendLabel, trendOption } from '../studentProgress';
import { experience, type StudentDetail } from '../experienceApi';
import type { Indicator, Node, Observation } from '../types';

const props = defineProps<{ detail: StudentDetail; indicators: Indicator[]; nodes: Node[]; nodeId: string; busy?: boolean; motion?: boolean; refresh?: number }>();
const emit = defineEmits<{ learning: [id: string]; material: [id: string] }>();

const status = ref('');
const chosen = ref('');
const all = ref<Observation[]>([]);
const trendError = ref(false);
let trendTurn = 0;

const rows = computed(() => abilityRows(props.detail.observations, props.indicators));
const filtered = computed(() => (status.value ? rows.value.filter(row => row.status === status.value) : rows.value));
const counts = computed(() => statusCounts(rows.value));
const metrics = computed(() => progressMetrics(props.detail.summary));
const chartOption = computed(() => abilityChartOption(filtered.value, { dense: true, hideState: true }));
const ringOption = computed(() => abilityStatusOption(rows.value));
const points = computed(() => progressTrend(all.value, props.nodes, props.indicators));
const trend = computed(() => trendOption(points.value));
const selected = computed(() => rows.value.find(row => row.indicatorId === chosen.value) || null);
const filterLabel = computed(() => counts.value.find(entry => entry.status === status.value)?.name || '');
const weak = computed(() => rows.value.filter(row => row.status === 'NEEDS_PRACTICE').length);
const gaps = computed(() => rows.value.filter(row => ['INSUFFICIENT', 'NO_EVIDENCE', 'NOT_ASSESSED', 'UNKNOWN'].includes(row.status)).length);
const chartHeight = computed(() => Math.max(280, filtered.value.reduce((height, row) => height + Math.max(62, Math.ceil(row.label.length / 9) * 18 + 26), 44)));

async function loadTrend() {
  const turn = ++trendTurn;
  try {
    const page = await experience.page('observations', { studentId: props.detail.student.id, pageSize: 100 });
    if (turn !== trendTurn) return;
    all.value = (page.items || []) as Observation[];
    trendError.value = false;
  } catch {
    if (turn === trendTurn) trendError.value = true;
  }
}
onMounted(loadTrend);
onBeforeUnmount(() => { trendTurn++; });
watch(() => [props.detail.student.id, props.detail.revision, props.refresh], () => { status.value = ''; void loadTrend(); });
watch(rows, value => { if (!value.some(row => row.indicatorId === chosen.value)) chosen.value = value[0]?.indicatorId || ''; }, { immediate: true });

// 柱条与环形图都走同一条选择路径；键盘用户仍可用下拉框选能力。
function clickBar(event: unknown) { const id = indicatorFromChart(event, filtered.value); if (id) chosen.value = id; }
function clickRing(event: unknown) { const value = statusFromChart(event, rows.value); if (value) status.value = status.value === value ? '' : value; }
</script>

<template>
<section class="card student-progress" data-testid="student-progress" aria-labelledby="student-progress-title">
  <header class="progress-heading">
    <div><span class="eyebrow">我的学情</span><h2 id="student-progress-title">能力与证据可视化</h2></div>
    <span class="status-badge"><strong>{{ weak }}</strong> 项需要练习 · <strong>{{ gaps }}</strong> 项记录不足</span>
  </header>
  <p class="progress-note">得分率来自已有评分，缺少证据不代表未掌握。点击柱条或环形图查看对应能力的作答记录；未评分题目不计零分。</p>
  <ul class="progress-metrics" aria-label="关键学情指标">
    <li v-for="metric in metrics" :key="metric.key">
      <small>{{ metric.label }}</small>
      <strong>{{ metric.display }}</strong>
      <span v-if="metric.percent != null" class="metric-bar" role="img" :aria-label="`${metric.label} ${metric.display}`"><i :style="{ width: metric.percent + '%' }"></i></span>
      <span class="metric-hint">{{ metric.hint }}</span>
    </li>
  </ul>

  <div v-if="rows.length" class="progress-grid">
    <div class="ability-panel">
      <div class="panel-heading">
        <h3>各能力得分率</h3>
        <button v-if="status" class="clear-filter" @click="status = ''">{{ filterLabel }} · 显示全部 ×</button>
      </div>
      <ChartView data-testid="student-ability-chart" :option="chartOption" :height="chartHeight" :motion="motion" label="我的各能力已有评分得分率，范围0至100%；缺失评分不计零分，点击能力查看作答记录" @select="clickBar"/>
    </div>
    <aside class="status-panel" aria-label="能力状态分布">
      <h3>能力状态分布</h3>
      <div class="ring-wrap">
        <ChartView data-testid="student-status-chart" :option="ringOption" :height="216" :motion="motion" label="我的各诊断状态能力数量；点击状态筛选左侧能力图" @select="clickRing"/>
        <div class="ring-center" aria-hidden="true"><strong>{{ rows.length }}</strong><span>项能力</span></div>
      </div>
      <div class="status-legend">
        <button v-for="entry in counts" :key="entry.status" :aria-pressed="status === entry.status" @click="status = status === entry.status ? '' : entry.status">
          <span class="status-dot" :style="{ background: diagnosisStatus(entry.status).color }"></span><span>{{ entry.name }}</span><strong>{{ entry.value }}</strong>
        </button>
      </div>
      <p class="progress-note">按能力数量统计，点击状态可筛选。</p>
    </aside>
  </div>
  <p v-else class="muted">当前范围暂无能力诊断记录。</p>

  <div class="trend-block">
    <div class="panel-heading"><h3>各测验范围得分率变化</h3><span class="progress-note">按能力简单平均，仅统计有评分的观察</span></div>
    <ChartView v-if="points.length > 1 && !trendError" data-testid="student-trend-chart" :option="trend" :height="240" :motion="motion" :label="trendLabel(points)"/>
    <p v-else class="progress-note">{{ trendError ? '趋势数据暂时无法读取，可稍后刷新。' : '当前只有一个测验范围，暂无跨测验趋势。' }}</p>
  </div>

  <div v-if="rows.length" class="history-entry">
    <UiSelect v-model="chosen" label="查看知识点记录" :options="filtered.map(row => ({ value: row.indicatorId, label: row.label, description: (row.status === 'UNKNOWN' ? '暂无诊断记录' : diagnosisStatus(row.status).label) + ' · 已评分 ' + (row.scoredCount ?? '暂无') + ' / 可用题 ' + (row.availableQuestionCount ?? '暂无') }))"/>
    <button class="learning-link" :disabled="!selected || busy" @click="selected && emit('learning', selected.indicatorId)">学习卡与来源 ↗</button>
  </div>
  <section v-if="selected" :key="selected.indicatorId" class="evidence-block" aria-label="我的作答记录">
    <div class="panel-heading"><h3>{{ selected.label }} · 我的作答记录</h3><span class="status-badge">{{ diagnosisStatus(selected.status).label }}</span></div>
    <StudentRecordList :student-id="detail.student.id" :node-id="nodeId" :indicator-id="selected.indicatorId" :refresh="detail.revision" @material="emit('material', $event)" @learning="emit('learning', $event)"/>
  </section>
  <p class="progress-note">未测量与记录不足不代表未掌握；零分保留为真实评分。原始作答仅供你与教师核对，不改变成绩。</p>
</section>
</template>

<style scoped>
.student-progress { --progress-line: #2a3c53; }
.progress-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; flex-wrap: wrap; }
.progress-heading h2 { margin: 4px 0 0; }
.progress-heading .status-badge strong { font-size: 15px; }
.eyebrow { color: #84b9ff; font-size: 11px; letter-spacing: .12em; }
.progress-note { color: var(--muted); font-size: 12px; line-height: 1.8; margin: 8px 0; }
.progress-metrics { list-style: none; margin: 18px 0 0; padding: 0; display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); gap: 14px; }
.progress-metrics li { display: grid; gap: 7px; padding: 16px; border: 1px solid var(--progress-line); border-radius: 12px; background: var(--control); min-width: 0; }
.progress-metrics small { color: var(--muted); font-size: 12px; }
.progress-metrics strong { font-size: 24px; font-weight: 600; color: #eef3fc; line-height: 1.2; }
.metric-bar { display: block; height: 6px; border-radius: 4px; background: #172a40; overflow: hidden; }
.metric-bar i { display: block; height: 100%; border-radius: 4px; background: linear-gradient(90deg, #4b97ea, #84b9ff); }
.metric-hint { color: var(--muted); font-size: 11px; line-height: 1.7; }
.progress-grid { display: grid; grid-template-columns: minmax(0, 2.2fr) minmax(235px, 1fr); gap: 24px; margin-top: 24px; }
.ability-panel, .status-panel { min-width: 0; }
.panel-heading { display: flex; align-items: center; justify-content: space-between; gap: 14px; flex-wrap: wrap; min-height: 34px; }
h3 { margin: 0; font-size: 14px; font-weight: 600; color: #d5e2f5; }
.status-panel { border-left: 1px solid var(--progress-line); padding-left: 24px; }
.ring-wrap { position: relative; }
.ring-center { position: absolute; top: 50%; left: 50%; transform: translate(-50%, -50%); display: flex; flex-direction: column; text-align: center; pointer-events: none; color: var(--muted); font-size: 12px; }
.ring-center strong { color: #eef3fc; font-size: 28px; font-weight: 600; line-height: 1.3; }
.status-legend { display: grid; gap: 6px; }
.status-legend button { display: flex; align-items: center; gap: 9px; width: 100%; text-align: left; background: transparent; border: 1px solid transparent; padding: 7px 8px; font-size: 12px; color: #b8c9df; }
.status-legend button[aria-pressed="true"] { background: var(--blue-soft); border-color: var(--blue); color: #eef3fc; }
.status-legend strong { margin-left: auto; font-size: 14px; }
.status-dot { width: 8px; height: 8px; border-radius: 50%; flex: none; }
.clear-filter { font-size: 11px; padding: 5px 8px; }
.trend-block { border-top: 1px solid var(--progress-line); margin-top: 24px; padding-top: 18px; }
.history-entry { border-top: 1px solid var(--progress-line); padding-top: 18px; margin-top: 18px; display: grid; grid-template-columns: minmax(160px, 1fr) auto; align-items: end; gap: 16px; }
.history-entry > button { justify-self: start; }
.learning-link { color: #a9bbd3; background: transparent; }
.evidence-block { margin-top: 20px; padding-top: 16px; border-top: 1px dashed var(--progress-line); }
button:focus-visible { outline: 2px solid #84b9ff; outline-offset: 3px; }
@media (max-width: 1100px) {
  .progress-grid { grid-template-columns: minmax(0, 1fr); }
  .status-panel { border-left: 0; border-top: 1px solid var(--progress-line); padding: 22px 0 0; display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); align-items: center; gap: 10px 18px; }
  .status-panel h3, .status-panel > .progress-note { grid-column: 1 / -1; }
  .ring-wrap { min-width: 0; }
}
@media (max-width: 600px) {
  .progress-metrics { grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); gap: 10px; }
  .progress-metrics li { padding: 12px; }
  .progress-metrics strong { font-size: 19px; }
  .history-entry { grid-template-columns: minmax(0, 1fr); }
  .status-panel { grid-template-columns: minmax(0, 1fr); }
  .status-legend { grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); gap: 5px; }
}
</style>
