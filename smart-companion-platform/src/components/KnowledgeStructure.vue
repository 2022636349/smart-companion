<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue';
import ChartView from '../ChartView.vue';
import UiSelect from './UiSelect.vue';
import { materialLabel } from '../experienceDisplay';
import {
  filterStructure, prerequisiteNames, requirementRows, shortLabel, sourceRows, structureGraphLabel,
  structureGraphOption, structureSummary, topicGroups, unlocks, type StructureIndicator, type StructurePayload
} from '../structureData';

const props = defineProps<{ payload: StructurePayload | null; busy?: boolean; motion?: boolean; role: 'teacher' | 'student' }>();
const emit = defineEmits<{ learning: [id: string]; material: [id: string]; diagnose: []; suggest: [id: string] }>();

const topicId = ref('');
const query = ref('');
const chosen = ref('');
const visibleMaterials = computed(() => (selected.value?.materials || []).slice(0, 8));
const filtered = computed(() => props.payload ? filterStructure(props.payload, { topicId: topicId.value, query: query.value }) : { indicators: [] as StructureIndicator[], total: 0, hidden: 0 });
const summary = computed(() => props.payload ? structureSummary(props.payload) : { topics: 0, indicators: 0, standards: 0, textbooks: 0, questions: 0, sources: 0, ready: 0 });
const groups = computed(() => props.payload ? topicGroups(props.payload, filtered.value.indicators) : []);
const selected = computed<StructureIndicator | null>(() => filtered.value.indicators.find(item => item.id === chosen.value) || filtered.value.indicators[0] || null);
const requirements = computed(() => (selected.value ? requirementRows(selected.value) : []));
const sources = computed(() => (selected.value ? sourceRows(selected.value) : []));
const prerequisites = computed(() => (props.payload && selected.value ? prerequisiteNames(props.payload, selected.value.id) : []));
const unlocked = computed(() => (props.payload && selected.value ? unlocks(props.payload, selected.value.id) : []));
const narrow = ref(false);
const media = window.matchMedia('(max-width: 700px)');
function syncNarrow(event?: MediaQueryListEvent) { narrow.value = event ? event.matches : media.matches; }
syncNarrow();
media.addEventListener('change', syncNarrow);
onBeforeUnmount(() => media.removeEventListener('change', syncNarrow));

const graph = computed(() => props.payload ? structureGraphOption(props.payload, filtered.value.indicators, { motion: props.motion, selectedId: selected.value?.id, compact: narrow.value }) : {});
const graphLabel = computed(() => structureGraphLabel(summary.value));
const topicOptions = computed(() => [{ value: '', label: '全部主题', description: `${summary.value.indicators} 项观察指标` },
  ...(props.payload?.topics || []).map(topic => ({ value: topic.id, label: topic.name || topic.id, description: `${topic.indicatorIds.length} 项指标` }))]);

watch(filtered, value => { if (!value.indicators.some(item => item.id === chosen.value)) chosen.value = value.indicators[0]?.id || ''; }, { immediate: true });
watch(() => props.payload?.revision, () => { chosen.value = props.payload?.indicators[0]?.id || ''; });

// 图表点击与列表点击走同一条选择路径，键盘用户始终有文本入口。
function pick(event: unknown) {
  const data = (event as { data?: { id?: string; kind?: string; indicator?: StructureIndicator } }).data;
  if (!data) return;
  if (data.kind === 'TOPIC') { topicId.value = data.id || ''; return; }
  if (data.id) chosen.value = data.id;
}
</script>

<template>
<section v-if="!payload" class="card" data-testid="knowledge-structure">
  <h1>学习内容与要求梳理</h1>
  <p class="muted">{{ busy ? '正在读取已审核的学习内容与课标要求…' : '当前范围还没有可展示的学习内容。' }}</p>
</section>
<template v-else>
<section class="card structure-head" data-testid="knowledge-structure">
  <header class="structure-heading">
    <div><span class="eyebrow">学习内容与要求梳理</span><h2>知识结构与课标要求</h2></div>
    <span class="status-badge">{{ summary.ready }} / {{ summary.indicators }} 项学习内容已审核</span>
  </header>
  <p class="structure-summary"><strong>{{ summary.topics }}</strong> 个主题 <span>·</span> <strong>{{ summary.indicators }}</strong> 项观察指标 <span>·</span> <strong>{{ summary.sources }}</strong> 处课标与教材来源 <span>·</span> <strong>{{ summary.questions }}</strong> 道关联题目</p>
  <p class="muted">课标与教材底图在后台一次建好并人工审核；学情、题目与作答按上传增量合并，本页不会重建教材图谱。前置关系只用于核查学习顺序，不单凭下游失分判定上游薄弱。</p>
  <div class="flow-filters">
    <UiSelect v-model="topicId" label="主题" :options="topicOptions" :disabled="!!busy"/>
    <label class="structure-search">搜索指标或要求<input v-model="query" type="search" placeholder="知识点、课标编号或页码"/></label>
  </div>
  <p v-if="filtered.hidden" class="muted">已按当前条件隐藏 {{ filtered.hidden }} 项指标，共 {{ filtered.total }} 项。</p>
  <ChartView v-if="filtered.indicators.length" data-testid="structure-graph" :option="graph" :height="narrow ? 360 : 520" :motion="motion" :label="graphLabel" @select="pick"/>
  <p v-else class="structure-empty">没有符合条件的学习内容，请调整主题或搜索词。</p>
</section>
<div v-if="filtered.indicators.length" class="structure-body">
  <section class="card structure-list" aria-label="按主题列出的学习内容与要求">
    <h2>按主题查看</h2>
    <div v-for="group in groups" :key="group.id" class="structure-group">
      <h3>{{ group.name }}</h3>
      <p class="muted">{{ group.goal }}</p>
      <button v-for="item in group.indicators" :key="item.id" class="structure-item" :class="{ chosen: selected?.id === item.id }" :aria-pressed="selected?.id === item.id" @click="chosen = item.id">
        <span class="structure-item-name">{{ item.label }}</span>
        <span class="structure-item-meta">{{ item.questionCount }} 道题目 · {{ item.knowledgeStatus === 'READY' ? '已审核' : '学习内容待补充' }}</span>
      </button>
    </div>
  </section>
  <section v-if="selected" :key="selected.id" class="card structure-detail">
    <header class="section-heading">
      <h2>{{ selected.label }}</h2>
      <span class="status-badge" :class="{ pending: selected.knowledgeStatus !== 'READY' }">{{ selected.knowledgeStatus === 'READY' ? '已审核学习内容' : '学习内容待补充' }}</span>
    </header>
    <p class="muted">{{ selected.topicName }} · {{ selected.topicGoal }}</p>
    <p class="structure-abstract">{{ selected.summary || '暂无学习内容摘要。' }}</p>
    <dl class="flow-summary structure-requirements">
      <div v-for="row in requirements" :key="row.label"><dt>{{ row.label }}</dt><dd>{{ row.value }}<small>{{ row.detail }}</small></dd></div>
    </dl>
    <h3>学习步骤</h3>
    <ol class="structure-steps"><li v-for="step in selected.learningSteps" :key="step">{{ step }}</li></ol>
    <p v-if="!selected.learningSteps.length" class="muted">暂无已审核的学习步骤。</p>
    <h3>前置学习</h3>
    <p v-if="!prerequisites.length" class="muted">无已登记的前置联系。</p>
    <ul v-else class="structure-pre">
      <li v-for="item in prerequisites" :key="item.knowledgePointId">
        <button v-if="item.resolvedId" class="learning-link" @click="chosen = item.resolvedId">{{ item.label }}</button>
        <span v-else>{{ item.label }}</span>
        <small>{{ item.reason }}</small>
      </li>
    </ul>
    <details v-if="sources.length"><summary>课标与教材出处 · {{ sources.length }} 处</summary>
      <blockquote v-for="source in sources" :key="source.id"><p>{{ source.kind }} · {{ source.title }} · {{ source.location }}</p><p>{{ source.excerpt }}</p></blockquote>
    </details>
    <h3>关联题目与材料 · {{ selected.materials.length }} 项</h3>
    <div class="button-row">
      <button :disabled="!!busy" @click="emit('learning', selected.id)">查看学习卡</button>
      <button v-for="material in visibleMaterials" :key="material.id" :disabled="!!busy" @click="emit('material', material.id)">{{ materialLabel(material.id) }}<span class="muted"> · {{ material.type === '题目' ? (material.questionType || '题目') : '学习材料' }}</span></button>
    </div>
    <p v-if="selected.materials.length > visibleMaterials.length" class="muted">已显示前 {{ visibleMaterials.length }} 项，其余可在数据管理中查看。</p>
    <div class="button-row structure-actions">
      <button v-if="role === 'teacher'" class="primary" :disabled="!!busy" @click="emit('diagnose')">查看该主题的学情分析 →</button>
      <button v-else class="primary" :disabled="!!busy" @click="emit('suggest', selected.id)">让 AI 结合我的学情给建议 →</button>
      <button v-if="unlocked.length" class="learning-link" @click="chosen = unlocked[0].id">后续内容：{{ shortLabel(unlocked[0].label) }}</button>
    </div>
    <p class="structure-note">Q 开头为练习题，L 开头为学习材料，B 开头为教材；答案解析与评分点按角色裁剪，学生端不显示参考答案。</p>
  </section>
</div>
</template>
</template>

<style scoped>
.structure-head { display: grid; gap: 14px; }
.structure-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; flex-wrap: wrap; }
.structure-heading h2 { margin: 4px 0 0; }
.structure-summary { margin: 4px 0 0; color: #d5e2f5; font-size: 14px; line-height: 1.9; }
.structure-summary strong { color: #eef3fc; font-size: 20px; font-weight: 600; }
.structure-summary span { padding: 0 7px; color: #657a96; }
.structure-search { display: flex; flex-direction: column; gap: 7px; flex: 0 1 320px; min-width: 0; font-size: 12px; color: var(--muted); }
.structure-search input { width: 100%; }
.structure-empty { padding: 22px 0; color: var(--muted); }
.structure-body { display: grid; grid-template-columns: minmax(240px, 320px) minmax(0, 1fr); gap: 22px; align-items: start; }
.structure-list h2, .structure-detail h2 { margin: 0 0 6px; }
.structure-group { border-top: 1px solid var(--line); padding-top: 14px; margin-top: 14px; }
.structure-group h3 { margin: 0; font-size: 14px; color: #b7d3f5; }
.structure-group > .muted { margin: 5px 0 10px; font-size: 12px; line-height: 1.7; }
.structure-item { display: grid; gap: 5px; width: 100%; text-align: left; padding: 10px 12px; margin-top: 7px; border: 1px solid var(--line); border-radius: 9px; background: var(--control); }
.structure-item.chosen { border-color: var(--blue); background: var(--blue-soft); }
.structure-item-name { font-size: 13px; line-height: 1.6; color: var(--text); }
.structure-item-meta { font-size: 11px; color: var(--muted); }
.structure-detail { min-width: 0; }
.structure-detail .status-badge.pending { background: var(--raised); color: var(--amber); }
.structure-abstract { margin: 6px 0 2px; line-height: 1.85; }
.structure-detail h3 { margin: 22px 0 8px; font-size: 14px; color: #b7d3f5; }
.structure-steps { margin: 0; padding-left: 22px; line-height: 1.95; }
.structure-steps li::marker { color: var(--blue); }
.structure-pre { list-style: none; margin: 0; padding: 0; display: grid; gap: 8px; }
.structure-pre li { display: grid; gap: 4px; padding: 10px 12px; border: 1px solid var(--line); border-radius: 9px; background: var(--control); }
.structure-pre small { color: var(--muted); font-size: 12px; line-height: 1.7; }
.structure-detail blockquote { background: var(--control); }
.structure-detail blockquote p { margin: 4px 0; line-height: 1.8; }
.structure-actions { border-top: 1px solid var(--line); padding-top: 16px; margin-top: 18px; }
.structure-note { margin-top: 14px; color: var(--muted); font-size: 11px; line-height: 1.8; }
.structure-detail dd small { display: block; margin-top: 3px; color: var(--muted); font-size: 11px; font-weight: 400; }
.flow-summary div { min-width: 0; }
@media (max-width: 1100px) { .structure-body { grid-template-columns: minmax(0, 1fr); } }
@media (max-width: 600px) { .structure-heading h2 { font-size: 19px; } .structure-search { flex-basis: 100%; } .structure-summary strong { font-size: 17px; } }
</style>
