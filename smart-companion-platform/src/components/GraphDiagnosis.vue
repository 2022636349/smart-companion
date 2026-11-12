<script setup lang="ts">
import { notifyError } from '../notification';
import { onBeforeUnmount as onErrorScopeUnmount } from 'vue';
let errorScope=0,disposed=false;onErrorScopeUnmount(()=>{disposed=true;errorScope++;});
import UiSelect from './UiSelect.vue';

import { percent } from '../analysis';
import { defaultDiagnosisIndicator, diagnosisNodeReference, diagnosisSourceReference } from '../diagnosisDisplay';
import { graphLabel } from '../graphLabels';
import { computed, ref, watch } from 'vue';
import { graphApi, type Bindings, type Diagnosis, type PublishedNode } from '../graphApi';
import type { Report } from '../types';
const props = defineProps<{ report: Report; studentId: string; nodeId: string }>();
const bindings = ref<Bindings>(), points = ref<PublishedNode[]>([]), drafts = ref<Diagnosis[]>([]), chosen = ref<Record<string,string>>({});
const busy = ref(false), error = ref(''), indicatorId = ref(''), dirty = ref(false);
const expanded = ref<Record<string,boolean>>({});
const current = computed(() => bindings.value?.currentGraphVersion || '');
const stale = computed(() => !!bindings.value && bindings.value.graphVersion !== current.value);
const visible = computed(() => drafts.value.filter(d => d.studentId===props.studentId && d.nodeId===props.nodeId));
async function run(action: () => Promise<void>) { if (busy.value) return; const errorOpener=document.activeElement instanceof HTMLElement?document.activeElement:null;busy.value=true; error.value=''; const scope=errorScope;try { await action(); } catch(e) { if(disposed||scope!==errorScope)return;notifyError(e,undefined,errorOpener); error.value=e instanceof Error?e.message:'请求失败，请重试'; } finally { busy.value=false; } }
async function load() {
  const value = await graphApi.bindings(props.report.importId); bindings.value=value;
  const graph = await graphApi.graph(); points.value=graph.nodes.filter(n=>n.level>=3);
  chosen.value=Object.fromEntries(value.bindings.map(b=>[b.indicatorId,b.knowledgePointId]));
  drafts.value=(await graphApi.diagnoses(props.report.importId)).items;
  indicatorId.value=defaultDiagnosisIndicator(props.report.indicators,value.bindings); dirty.value=false;
}
function save() { void run(async()=>{ bindings.value=await graphApi.saveBindings(props.report.importId,{ ...bindings.value!, graphVersion: current.value, bindings:Object.entries(chosen.value).filter(([,value])=>value).map(([indicatorId,knowledgePointId])=>({indicatorId,knowledgePointId})) }); dirty.value=false; }); }
function generate() { void run(async()=>{ const result=await graphApi.diagnose(props.report.importId,props.studentId,props.nodeId,indicatorId.value); drafts.value=[result,...drafts.value.filter(d=>!(d.studentId===result.studentId&&d.nodeId===result.nodeId&&d.indicatorId===result.indicatorId))]; }); }
watch(()=>[props.report.importId,props.studentId,props.nodeId],()=>{errorScope++;});
watch(()=>props.report.importId,()=>{ void run(load); },{immediate:true});
</script>
<template>
<section class="card kg-diagnosis">
  <div class="section-title"><div><p class="eyebrow">人工绑定 + 已审核图谱 + 直接作答</p><h3>模型解释与学习步骤</h3></div><button :disabled="busy" @click="run(load)">重新读取</button></div>
  <p v-if="busy" role="status">正在处理模型解释或图谱数据…</p>
  <p class="muted">统计观察由已有评分计算。模型根据统计与教材来源提出学习建议，不判定逐题错因；原始作答和评分点可在下方展开核对。解释是教师复核草稿，不改成绩；前置关系仅提示核查。</p>
  <template v-if="bindings">
    <details><summary>指标与知识点人工绑定（保存即确认）</summary><p>当前课程版本 {{ current || '暂无已发布版本' }} · 绑定版本 {{ bindings.graphVersion || '尚未确认' }} · 修订 {{ bindings.revision }}</p><p v-if="stale" class="error">图谱版本已变化或尚未确认。请逐项核对后重新保存；旧绑定不能生成现行解释。</p><p v-if="!points.length" class="muted">尚无可选的已审核知识点，请先在知识图谱工作台审核发布。</p><UiSelect v-for="i in report.indicators" :key="i.id" class="kg-binding" v-model="chosen[i.id]" :disabled="busy" @change="dirty=true" :label="i.label" :options="[{value:'',label:'留空'},...(chosen[i.id]&amp;&amp;!points.some(p=>p.id===chosen[i.id])?[{value:chosen[i.id],label:'当前图中不可用，请重选'}]:[]),...points.map(p=>({value:p.id,label:p.name,description:p.id}))]"/><button :disabled="busy||!current" @click="save">确认并保存全部绑定</button></details>
    <div class="kg-form"><UiSelect v-model="indicatorId" label="解释指标" :options="[...(report.indicators||[]).map(i=&gt;({value:i.id,label:String(i.label)}))]"/><button class="primary" :disabled="busy||stale||dirty||!chosen[indicatorId]||!studentId||!nodeId" @click="generate">生成当前学生解释草稿</button></div><p v-if="dirty" class="muted">绑定有未保存修改，请先确认并保存。</p>
  </template>
  <p v-if="!visible.length" class="muted">当前学生与测验暂无已保存的模型解释。绑定后可生成；失败时原统计仍可查看。</p>
  <article v-for="d in visible" :key="d.indicatorId" class="kg-candidate"><h4>{{ report.indicators.find(i=>i.id===d.indicatorId)?.label || d.indicatorId }} · {{ d.knowledgePointName }} · {{ graphLabel(d.status) }} / {{ graphLabel(d.modelStatus) }}</h4><p>实际模型 {{ d.provider }} / {{ d.model }} · 图谱 {{ d.graphVersion }} · 绑定修订 {{ d.bindingRevision }} · {{ d.generatedAt }}</p><p v-if="d.graphVersion!==current||d.bindingRevision!==bindings?.revision" class="error">此草稿的图谱或绑定版本已过期，不能作为现行结论，请重新确认绑定并生成。</p><p class="diagnosis-explanation" :class="{clamped:!expanded[d.indicatorId]}">{{ d.explanation }}</p><button type="button" :aria-expanded="!!expanded[d.indicatorId]" @click="expanded[d.indicatorId]=!expanded[d.indicatorId]">{{ expanded[d.indicatorId]?'收起解释':'展开完整解释' }}</button><p>直接观察：{{ graphLabel(d.observation.status) }} · {{ d.observation.scoredCount }}/{{ d.observation.availableQuestionCount }} 条已评分 · {{ percent(d.observation.scorePercent) }}</p>
    <details><summary>直接作答证据（{{ d.directEvidence.length }}）</summary><article v-for="e in d.directEvidence" :key="e.recordId" class="kg-candidate"><strong>{{ e.recordId }} · {{ e.materialId }} · {{ e.score ?? '待评分' }}/{{ e.maxScore }}</strong><p>作答：{{ e.answer }}</p><p>过程：{{ e.process }}</p><p>分步评分：{{ e.pointScores }}</p></article></details>
    <h4>前置知识核查</h4><p v-if="!d.prerequisites.length">图谱未返回直接前置点。</p><article v-for="p in d.prerequisites" :key="p.knowledgePointId"><strong>{{ p.name }} · {{ graphLabel(p.assessment) }}</strong><p>{{ p.reason }}</p><p v-if="!p.observations.length" class="muted">无直接作答证据，不能判为薄弱。</p><p v-for="(o,i) in p.observations" :key="i">直接观察 {{ o.indicatorId }} · {{ graphLabel(o.status) }} · {{ percent(o.scorePercent) }} · 证据 {{ o.evidenceIds.join('、') }}</p></article>
    <ol><li v-for="(s,i) in d.steps" :key="i"><strong>{{ s.title }}</strong><p>{{ s.action }}</p><p>{{ s.rationale }}</p><details><summary>查看依据与材料</summary><p>知识点：{{ s.knowledgePointIds.map(id=>diagnosisNodeReference(d,id)).join('、') || '未引用' }}</p><p>来源：{{ s.sourceIds.map(id=>diagnosisSourceReference(d,id)).join('、') || '未引用' }}</p><p>作答编号：{{ s.evidenceIds.join('、') || '未引用' }}</p><p>材料编号：{{ s.materialIds.join('、') || '未引用' }}</p></details></li></ol>
    <details><summary>已审核来源（{{ d.sources.length }}）</summary><article v-for="s in d.sources" :key="s.sourceId" class="kg-candidate"><strong>{{ s.title }} · {{ s.sourceId }}</strong><p>{{ s.locator }} · 物理页 {{ s.pageFrom ?? '未提供' }}–{{ s.pageTo ?? '未提供' }}</p><blockquote>{{ s.excerpt }}</blockquote></article></details><p v-for="(c,i) in d.cautions" :key="i" class="muted">{{ c }}</p>
  </article>
</section>
</template>

<style scoped>
.diagnosis-explanation { white-space:pre-wrap; overflow-wrap:anywhere; }
.diagnosis-explanation.clamped { display:-webkit-box; -webkit-line-clamp:4; -webkit-box-orient:vertical; overflow:hidden; }
.kg-diagnosis details p { overflow-wrap:anywhere; }
</style>
