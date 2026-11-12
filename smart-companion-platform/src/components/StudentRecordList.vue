<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue';
import { notifyError } from '../notification';
import { experience, type Page, type Row } from '../experienceApi';
import { choiceAnswerText, recordScore, submissionTime } from '../answerHistory';
import MaterialContent from './MaterialContent.vue';
import type { Material } from '../types';

const props = defineProps<{ studentId: string; nodeId: string; indicatorId: string; label?: string; refresh?: number }>();
const emit = defineEmits<{ material: [id: string]; learning: [id: string] }>();

const size = 5;
const page = ref(1);
const loading = ref(false);
const materialLoading = ref(false);
const error = ref('');
const result = ref<Page<Row>>({ revision: 0, page: 1, pageSize: size, total: 0, items: [] });
const materials = ref<Record<string, Material | null>>({});
let turn = 0;

const pages = computed(() => Math.max(1, Math.ceil(result.value.total / size)));
const entries = computed(() => result.value.items.map(row => ({ row, material: materials.value[row.materialId] ?? null })));
const scored = computed(() => result.value.items.filter(row => row.score != null).length);
const earned = computed(() => result.value.items.reduce((total, row) => total + (Number(row.score) || 0), 0));
const maximum = computed(() => result.value.items.reduce((total, row) => total + (Number(row.maxScore) || 0), 0));

async function load() {
  const current = ++turn;
  loading.value = true;
  error.value = '';
  materials.value = {};
  try {
    const data = await experience.page('records', { studentId: props.studentId, nodeId: props.nodeId, indicatorId: props.indicatorId, page: page.value, pageSize: size });
    if (current !== turn) return;
    result.value = data;
    materialLoading.value = true;
    // Each record keeps its own question; one failing material must not hide the other records.
    const loaded = await Promise.all(data.items.map(async row => {
      try { return [row.materialId, (await experience.material(row.materialId)).material] as const; }
      catch { return [row.materialId, null] as const; }
    }));
    if (current === turn) materials.value = Object.fromEntries(loaded);
  } catch (e) {
    if (current === turn) { error.value = (e as Error).message; notifyError(e, '作答记录读取失败'); }
  } finally {
    if (current === turn) { loading.value = false; materialLoading.value = false; }
  }
}
watch(() => [props.studentId, props.nodeId, props.indicatorId, props.refresh], () => { page.value = 1; void load(); }, { immediate: true });
watch(page, () => void load());
onBeforeUnmount(() => { turn++; });

interface RecordTone { key: 'good' | 'ok' | 'weak' | 'pending' | 'unanswered'; label: string }
function toneOf(row: Row): RecordTone {
  if (row.status === 'UNANSWERED') return { key: 'unanswered', label: '未作答' };
  if (row.score == null) return { key: 'pending', label: '待评分' };
  const max = Number(row.maxScore) || 0;
  const percent = max ? (100 * Number(row.score)) / max : null;
  if (percent == null) return { key: 'pending', label: '待评分' };
  return percent >= 85 ? { key: 'good', label: '掌握较好' } : percent >= 60 ? { key: 'ok', label: '基本达成' } : { key: 'weak', label: '需要练习' };
}
function answerOf(row: Row) {
  const choice = choiceAnswerText(row.answer, materials.value[row.materialId]?.options);
  return choice ? `${row.answer}\n\n${choice}` : (row.answer || '未作答');
}
</script>

<template>
<section class="record-list" aria-label="作答记录列表">
  <header class="record-summary">
    <p><strong>{{ result.total }}</strong> 条作答 · 本页已评分 <strong>{{ scored }}</strong> / {{ result.items.length }} 题 · 本页得分 <strong>{{ earned }} / {{ maximum }}</strong></p>
    <p class="record-note">题目为只读预览，与考试时的版本一致；得分来自教师已有评分，学生端不展示参考答案与评分点。</p>
  </header>
  <p v-if="loading" role="status">正在读取作答记录…</p>
  <p v-else-if="error">读取失败，可重试。<button @click="load">重试</button></p>
  <p v-else-if="!result.items.length" class="record-note">当前知识点还没有作答记录。</p>
  <template v-else>
    <article v-for="entry in entries" :key="entry.row.id" class="record-card" :data-tone="toneOf(entry.row).key">
      <header class="record-head">
        <span class="record-id">{{ entry.row.id }}</span>
        <span class="record-badge">{{ recordScore(entry.row) }}</span>
        <span class="record-tone">{{ toneOf(entry.row).label }}</span>
        <span class="record-time">提交 {{ submissionTime(entry.row.submittedAt) }}</span>
      </header>
      <MaterialContent v-if="entry.material" :material="entry.material" :show-teacher-reference="false"/>
      <p v-else-if="materialLoading" class="record-note">正在读取题目…</p>
      <p v-else class="record-note">题目暂时无法读取，可稍后重试。</p>
      <section class="record-answer">
        <h4>我的作答</h4>
        <p class="record-text">{{ answerOf(entry.row) }}</p>
        <details v-if="entry.row.process">
          <summary>作答过程与记录来源</summary>
          <p class="record-text">{{ entry.row.process }}</p>
        </details>
      </section>
      <div class="button-row">
        <button @click="emit('material', entry.row.materialId)">在弹窗中查看完整题目</button>
        <button v-if="entry.row.indicatorId" class="learning-link" @click="emit('learning', entry.row.indicatorId)">学习卡与来源 ↗</button>
      </div>
    </article>
    <nav v-if="pages > 1" class="button-row record-pages" aria-label="作答记录分页">
      <button :disabled="loading || page <= 1" @click="page--">上一页</button>
      <span>第 {{ page }} / {{ pages }} 页 · 共 {{ result.total }} 条</span>
      <button :disabled="loading || page >= pages" @click="page++">下一页</button>
    </nav>
  </template>
</section>
</template>

<style scoped>
.record-list { display: grid; gap: 16px; min-width: 0; }
.record-summary { display: grid; gap: 6px; }
.record-summary p { margin: 0; color: #d5e2f5; font-size: 13px; line-height: 1.8; }
.record-summary strong { color: #eef3fc; font-size: 15px; }
.record-note { margin: 0; color: var(--muted); font-size: 12px; line-height: 1.8; }
.record-card { display: grid; gap: 14px; padding: 18px 20px; border: 1px solid var(--line); border-left: 3px solid var(--line); border-radius: 12px; background: var(--control); min-width: 0; }
.record-card[data-tone="good"] { border-left-color: var(--teal); }
.record-card[data-tone="ok"] { border-left-color: var(--amber); }
.record-card[data-tone="weak"] { border-left-color: var(--red); }
.record-card[data-tone="pending"], .record-card[data-tone="unanswered"] { border-left-color: var(--muted); }
.record-head { display: flex; flex-wrap: wrap; align-items: center; gap: 8px 14px; font-size: 12px; color: var(--muted); }
.record-id { color: #eef3fc; font-weight: 600; font-size: 13px; }
.record-badge { padding: 3px 9px; border: 1px solid var(--line); border-radius: 6px; background: var(--raised); color: #d5e2f5; }
.record-tone { color: #d5e2f5; }
.record-time { margin-left: auto; }
.record-answer { display: grid; gap: 6px; padding-top: 12px; border-top: 1px solid var(--line); }
.record-answer h4 { margin: 0; font-size: 13px; font-weight: 600; color: #b7d3f5; }
.record-text { margin: 0; white-space: pre-wrap; line-height: 1.9; color: #eef3fc; overflow-wrap: anywhere; }
.record-pages { justify-content: center; align-items: center; font-size: 12px; }
.learning-link { color: #a9bbd3; background: transparent; }
@media (max-width: 600px) {
  .record-card { padding: 14px; gap: 12px; }
  .record-time { margin-left: 0; width: 100%; }
}
</style>
