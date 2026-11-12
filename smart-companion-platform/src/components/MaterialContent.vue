<script setup lang="ts">
import { computed } from 'vue';
import { blankSegments, canonicalMaterialId, materialKind, materialTitle, parseChoiceOptions, questionPresentation } from '../materialPresentation';
const props = withDefaults(defineProps<{ material: Record<string, any>; showTeacherReference?: boolean }>(), { showTeacherReference: false });
const kind = computed(() => materialKind(props.material));
const presentation = computed(() => questionPresentation(props.material.questionType));
const choice = computed(() => parseChoiceOptions(props.material.options));
const segments = computed(() => blankSegments(props.material.body));
const hasBlank = computed(() => segments.value.some(segment => segment.blank));
const badge = computed(() => kind.value === 'question' ? props.material.questionType || '题目' : canonicalMaterialId(props.material).toUpperCase().startsWith('B') ? '教材' : props.material.type === '学习卡' ? '学习卡' : '学习材料');
</script>

<template>
  <article class="material-content" data-testid="material-content" :data-kind="kind" :data-question-type="kind === 'question' ? ({ blank: 'fill', general: 'generic', choice: 'choice', solution: 'solution' }[presentation]) : undefined">
    <header class="material-heading"><span class="material-badge">{{ badge }}</span><span class="material-id">{{ materialTitle(material) }}</span><span v-if="kind === 'question' && material.maxScore != null" class="material-score">{{ material.maxScore }} 分</span></header>
    <template v-if="kind === 'resource'">
      <div class="material-reading">{{ material.body || '暂无材料正文。' }}</div>
    </template>
    <template v-else>
      <p v-if="presentation === 'blank'" class="material-body"><template v-for="(segment, index) in segments" :key="index"><span :class="{ 'material-inline-blank': segment.blank }">{{ segment.text }}</span></template></p>
      <p v-else class="material-body">{{ material.body || '暂无题干。' }}</p>
      <div v-if="presentation === 'choice'" class="material-choices" :role="choice.parsed ? 'list' : undefined" :aria-label="choice.parsed ? '选项' : undefined">
        <template v-if="choice.parsed"><div v-for="option in choice.options" :key="option.label" class="material-option" role="listitem"><span class="material-option-letter">{{ option.label }}</span><span>{{ option.text }}</span></div></template>
        <template v-else><p class="material-note">{{ choice.raw ? '选项保留原文展示。' : '该题未提供可展示的选项。' }}</p><p v-if="choice.raw" class="material-raw">{{ choice.raw }}</p></template>
      </div>
      <div v-else-if="presentation === 'blank' && !hasBlank" class="material-blank-preview"><span>填空预览</span><span class="material-answer-line" aria-hidden="true"></span></div>
      <p v-else-if="presentation === 'solution'" class="material-note material-requirement"><strong>解答要求</strong> 请结合题意，写出解题过程与结论。</p>
      <p v-if="presentation !== 'choice' && material.options" class="material-raw">{{ material.options }}</p>
      <p class="material-preview-note">只读题目预览</p>
      <details v-if="showTeacherReference && (material.answer || material.explanation || material.scoringPoints)" class="material-reference">
        <summary>答案与评分依据</summary>
        <section v-if="material.answer"><h4>参考答案</h4><p>{{ material.answer }}</p></section>
        <section v-if="material.explanation"><h4>解析</h4><p>{{ material.explanation }}</p></section>
        <section v-if="material.scoringPoints"><h4>评分点</h4><p>{{ material.scoringPoints }}</p></section>
      </details>
    </template>
    <footer v-if="material.source" class="material-source"><strong>{{ kind === 'question' ? '题目来源' : '材料来源' }}</strong><p>{{ material.source }}</p></footer>
  </article>
</template>

<style scoped>
.material-content { min-width: 0; max-width: 100%; color: var(--text, #eef3fc); font: inherit; font-family: var(--font-ui, inherit); font-size: 14px; line-height: 1.85; overflow-wrap: anywhere; }
.material-heading { display: flex; flex-wrap: wrap; align-items: center; gap: 8px 12px; margin-bottom: 18px; }
.material-badge { padding: 3px 9px; border: 1px solid var(--line, #2a3c53); border-radius: 6px; color: var(--blue, #84b9ff); background: var(--blue-soft, #182f4d); font-size: 12px; }
.material-id { font-weight: 600; }
.material-score { margin-left: auto; color: var(--muted, #a3b3ca); font-size: 12px; }
.material-body, .material-reading, .material-raw, .material-reference p, .material-source p { margin: 0; white-space: pre-wrap; font: inherit; }
.material-reading { padding: 16px 18px; border-left: 2px solid var(--blue, #84b9ff); background: var(--control, #0b1726); border-radius: 0 8px 8px 0; line-height: 2; }
.material-choices { display: grid; gap: 9px; margin-top: 18px; }
.material-option { display: flex; align-items: baseline; gap: 12px; padding: 11px 14px; border: 1px solid var(--line, #2a3c53); border-radius: 8px; background: var(--control, #0b1726); white-space: pre-wrap; }
.material-option-letter { flex: 0 0 22px; font-weight: 600; color: var(--blue, #84b9ff); }
.material-option > :last-child { min-width: 0; }
.material-inline-blank { border-bottom: 1px solid var(--blue, #84b9ff); padding: 0 6px 2px; }
.material-blank-preview { display: flex; align-items: center; flex-wrap: wrap; gap: 12px; margin-top: 18px; color: var(--muted, #a3b3ca); font-size: 12px; }
.material-answer-line { width: 120px; max-width: 100%; border-bottom: 1px solid var(--blue, #84b9ff); }
.material-note { margin: 0; color: var(--muted, #a3b3ca); font-size: 13px; }
.material-requirement { margin-top: 18px; padding: 10px 12px; background: var(--control, #0b1726); border-radius: 7px; }
.material-requirement strong { display: inline-block; margin-right: 9px; color: var(--text, #eef3fc); font-weight: 500; }
.material-preview-note { margin: 12px 0 0; color: var(--muted, #a3b3ca); font-size: 12px; }
.material-reference { margin-top: 20px; padding-top: 14px; border-top: 1px solid var(--line, #2a3c53); }
.material-reference summary { cursor: pointer; color: var(--blue, #84b9ff); }
.material-reference section { margin-top: 14px; }
.material-reference h4 { margin: 0 0 4px; font: inherit; font-weight: 600; }
.material-source { margin-top: 22px; padding-top: 14px; border-top: 1px solid var(--line, #2a3c53); color: var(--muted, #a3b3ca); font-size: 12px; }
.material-source strong { font-weight: 500; }
.material-source p { margin-top: 4px; }
@media (max-width: 480px) { .material-reading { padding: 12px; } .material-option { padding: 10px; } }
</style>
