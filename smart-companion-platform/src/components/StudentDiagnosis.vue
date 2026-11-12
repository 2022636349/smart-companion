<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue';
import ChartView from '../ChartView.vue';
import UiSelect from './UiSelect.vue';
import StudentAnswerHistory from './StudentAnswerHistory.vue';
import type { StudentDetail } from '../experienceApi';
import type { Indicator, Node } from '../types';
import { abilityRows, abilityChartOption, abilityStatusOption, diagnosisStatus, indicatorFromChart, statusFromChart, statusCounts } from '../diagnosisCharts';

const props = defineProps<{ detail: StudentDetail; indicators: Indicator[]; nodes: Node[]; nodeId: string; busy?: boolean; motion?: boolean }>();
const emit = defineEmits<{ learning: [id: string]; plan: [] }>();
const rows = computed(() => abilityRows(props.detail.observations, props.indicators));
const status = ref('');
const chosen = ref('');
const historyId = ref('');
const opener = ref<HTMLElement | null>(null);
const keyboardOpen = ref<HTMLButtonElement>();
const filteredRows = computed(() => status.value ? rows.value.filter(row => row.status === status.value) : rows.value);
const chartOption = computed(() => abilityChartOption(filteredRows.value));
const ringOption = computed(() => abilityStatusOption(rows.value));
const counts = computed(() => statusCounts(rows.value));
const selected = computed(() => rows.value.find(row => row.indicatorId === chosen.value));
const historyIndicator = computed(() => rows.value.find(row => row.indicatorId === historyId.value));
const chartHeight = computed(() => Math.max(300, filteredRows.value.reduce((height, row) => height + Math.max(68, Math.ceil(row.label.length / 10) * 18 + 34), 58)));
const weak = computed(() => rows.value.filter(row => row.status === 'NEEDS_PRACTICE').length);
const gaps = computed(() => rows.value.filter(row => ['INSUFFICIENT', 'NO_EVIDENCE', 'NOT_ASSESSED', 'UNKNOWN'].includes(row.status)).length);
const filterLabel = computed(() => counts.value.find(row => row.status === status.value)?.name);
function selectStatus(event: unknown) { const value = statusFromChart(event, rows.value); if (value) status.value = status.value === value ? '' : value; }
function toggleStatus(value: string) { status.value = status.value === value ? '' : value; }
function openHistory(id: string) {
  if (!rows.value.some(row => row.indicatorId === id)) return;
  chosen.value = id;
  opener.value = document.activeElement instanceof HTMLElement && document.activeElement !== document.body ? document.activeElement : keyboardOpen.value || null;
  historyId.value = id;
}
function selectAbility(event: unknown) { const id = indicatorFromChart(event, filteredRows.value); if (id) openHistory(id); }
async function closeHistory() { historyId.value = ''; await nextTick(); if (opener.value?.isConnected) opener.value.focus(); }
watch(() => [props.detail.student.id, props.nodeId, props.detail.revision], () => { historyId.value = ''; status.value = ''; chosen.value = rows.value[0]?.indicatorId || ''; }, { immediate: true });
watch(filteredRows, value => { if (!value.some(row => row.indicatorId === chosen.value)) chosen.value = value[0]?.indicatorId || ''; });
</script>

<template>
<section class="card student-diagnosis" data-testid="student-diagnosis" aria-labelledby="diagnosis-title">
  <header class="diagnosis-heading">
    <div><span class="eyebrow">能力与作答</span><h2 id="diagnosis-title">能力诊断与作答依据</h2></div>
    <button :disabled="busy" @click="emit('plan')">编排教学方案 →</button>
  </header>
  <p class="diagnosis-summary"><strong>{{ weak }}</strong> 项需要练习 <span>·</span> <strong>{{ gaps }}</strong> 项记录不足或未测量 <span>·</span> 共 {{ rows.length }} 项能力</p>
  <p class="diagnosis-note">得分率来自已有评分。点击柱条或能力名称查看完整题目与历史作答。</p>
  <div v-if="rows.length" class="diagnosis-grid">
    <div class="ability-panel">
      <div class="panel-heading"><h3>各能力得分率</h3><button v-if="status" class="clear-filter" @click="status=''">{{ filterLabel }} · 显示全部 ×</button></div>
      <ChartView data-testid="ability-chart" :option="chartOption" :height="chartHeight" :motion="motion" label="各能力已有评分得分率，范围0至100%；缺失评分不计零分，点击能力查看作答" @select="selectAbility"/>
    </div>
    <aside class="status-panel" aria-label="能力状态分布">
      <h3>能力状态分布</h3>
      <div class="ring-wrap">
        <ChartView data-testid="ability-status-chart" :option="ringOption" :height="230" :motion="motion" label="各诊断状态下的能力数量；点击状态筛选左侧能力图" @select="selectStatus"/>
        <div class="ring-center" aria-hidden="true"><strong>{{ rows.length }}</strong><span>项能力</span></div>
      </div>
      <div class="status-legend">
        <button v-for="entry in counts" :key="entry.status" :aria-pressed="status===entry.status" @click="toggleStatus(entry.status)"><span class="status-dot" :style="{background:diagnosisStatus(entry.status).color}"></span><span>{{ entry.name }}</span><strong>{{ entry.value }}</strong></button>
      </div>
      <p class="diagnosis-note">按能力数量统计，点击状态可筛选。</p>
    </aside>
  </div>
  <p v-else class="diagnosis-empty">当前范围暂无能力诊断记录。</p>
  <div v-if="rows.length" class="history-entry">
    <UiSelect v-model="chosen" label="查看知识点记录" :options="filteredRows.map(row=>({value:row.indicatorId,label:row.label,description:(row.status==='UNKNOWN'?'暂无诊断记录':diagnosisStatus(row.status).label)+' · 已评分 '+(row.scoredCount??'暂无')+' / 可用题 '+(row.availableQuestionCount??'暂无')}))"/>
    <div class="history-actions"><button ref="keyboardOpen" :disabled="!selected" @click="chosen&&openHistory(chosen)">查看历史作答</button><button class="learning-link" :disabled="!selected||busy" @click="chosen&&emit('learning',chosen)">学习卡与来源 ↗</button></div>
  </div>
  <p class="diagnosis-note">未测量与记录不足不代表未掌握；零分保留为真实评分。</p>
  <StudentAnswerHistory v-if="historyIndicator" :key="detail.student.id+'|'+nodeId+'|'+historyId+'|'+detail.revision" :student-id="detail.student.id" :node-id="nodeId" :indicator="{id:historyIndicator.indicatorId,label:historyIndicator.label}" :nodes="nodes" :open="true" :data-revision="detail.revision" @close="closeHistory" @learning="emit('learning',$event)"/>
</section>
</template>

<style scoped>
.student-diagnosis { --diagnosis-line: #2a3c53; font-family: var(--font-ui); min-width: 0; }
.diagnosis-heading, .panel-heading { display:flex; align-items:center; justify-content:space-between; gap:16px; flex-wrap:wrap; }
.diagnosis-heading h2 { margin:5px 0 0; }
.eyebrow { color:#84b9ff; font-size:11px; letter-spacing:.12em; }
.diagnosis-summary { color:#d5e2f5; font-size:14px; margin:22px 0 6px; line-height:1.8; }
.diagnosis-summary strong { color:#eef3fc; font-size:21px; font-weight:600; }
.diagnosis-summary span { color:#657a96; padding:0 9px; }
.diagnosis-note { color:#9cb0c9; font-size:12px; line-height:1.8; margin:8px 0; }
.diagnosis-grid { display:grid; grid-template-columns:minmax(0, 2.25fr) minmax(235px,1fr); gap:24px; margin-top:24px; }
.ability-panel, .status-panel { min-width:0; }
.panel-heading { min-height:36px; gap:8px; }
h3 { margin:0; font-size:14px; font-weight:600; color:#d5e2f5; }
.status-panel { border-left:1px solid var(--diagnosis-line); padding-left:24px; }
.ring-wrap { position:relative; }
.ring-center { position:absolute; top:50%; left:50%; transform:translate(-50%,-50%); display:flex; flex-direction:column; text-align:center; pointer-events:none; color:#9cb0c9; font-size:12px; }
.ring-center strong { color:#eef3fc; font-size:30px; font-weight:600; line-height:1.3; }
.status-legend { display:grid; gap:6px; }
.status-legend button { display:flex; align-items:center; gap:9px; width:100%; text-align:left; background:transparent; border:1px solid transparent; padding:7px 8px; font-size:12px; color:#b8c9df; }
.status-legend button[aria-pressed=true] { background:#182f4d; border-color:#84b9ff; color:#eef3fc; }
.status-legend strong { margin-left:auto; font-size:14px; }
.status-dot { width:8px; height:8px; border-radius:50%; flex:none; }
.clear-filter { font-size:11px; padding:5px 8px; }
.history-entry { border-top:1px solid var(--diagnosis-line); padding-top:18px; margin-top:18px; display:grid; grid-template-columns:minmax(160px,1fr) auto; align-items:end; gap:16px; }
.history-actions { display:flex; align-items:center; flex-wrap:wrap; gap:8px; }
.learning-link { color:#a9bbd3; background:transparent; }
button:focus-visible { outline:2px solid #84b9ff; outline-offset:3px; }
.diagnosis-empty { padding:28px 0; color:#9cb0c9; }
@media(max-width:1100px) { .diagnosis-grid { grid-template-columns:minmax(0,1fr); } .status-panel { border-left:0; border-top:1px solid var(--diagnosis-line); padding:22px 0 0; display:grid; grid-template-columns:minmax(0,1fr) minmax(0,1fr); align-items:center; gap:10px 18px; } .status-panel h3,.status-panel>.diagnosis-note { grid-column:1/-1; } .ring-wrap { min-width:0; } }
@media(max-width:600px) { .diagnosis-heading { align-items:flex-start; } .diagnosis-heading>button { padding:7px 10px; font-size:12px; } .diagnosis-summary { font-size:12px; } .diagnosis-summary strong { font-size:18px; } .diagnosis-summary span { padding:0 3px; } .diagnosis-grid { gap:12px; margin-top:18px; } .history-entry { grid-template-columns:minmax(0,1fr); } .status-panel { grid-template-columns:minmax(0,1fr); gap:5px; } .status-panel h3,.status-panel>.diagnosis-note { grid-column:auto; } .status-legend { grid-template-columns:minmax(0,1fr) minmax(0,1fr); gap:5px; } .status-legend button { padding:7px 5px; gap:6px; } .history-actions { align-items:stretch; } .history-actions button { flex:1; font-size:12px; padding:9px; } }
</style>
