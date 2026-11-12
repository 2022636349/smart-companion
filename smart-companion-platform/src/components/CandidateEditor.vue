<script setup lang="ts">
import { notifyError } from '../notification';
import { onBeforeUnmount as onErrorScopeUnmount } from 'vue';
let errorScope=0,disposed=false;onErrorScopeUnmount(()=>{disposed=true;errorScope++;});
import UiSelect from './UiSelect.vue';

import { ref, watch } from 'vue';
import { buildEditedDecision, initialCandidateEdits } from '../candidateEditor';
import { graphApi } from '../graphApi';
import type { GraphCandidate, GraphIngestionJob } from '../graphTypes';
const props=defineProps<{candidate:GraphCandidate; jobId:string; revision:number; nodes:{id:string;name:string}[]; disabled:boolean}>();
const emit=defineEmits<{saved:[job:GraphIngestionJob]}>();
const edits=ref(initialCandidateEdits(props.candidate)), open=ref(false), saving=ref(false), error=ref(''), notice=ref(''), conflict=ref(false);
watch(()=>[props.jobId,props.candidate.candidateId],()=>{errorScope++;});
// Snapshot when opening: refreshing the workspace never rebases unsaved edits silently.
const baseRevision=ref(props.revision);
const awaitingSavedRefresh=ref(false);
watch(()=>props.candidate, c=>{ if(awaitingSavedRefresh.value) { edits.value=initialCandidateEdits(c); baseRevision.value=props.revision; awaitingSavedRefresh.value=false; } });
function begin() { open.value=true; }
async function save(decision:'APPROVE'|'REJECT') {
  if(saving.value||props.disabled||conflict.value) return;
  const errorOpener=document.activeElement instanceof HTMLElement?document.activeElement:null;const scope=errorScope;saving.value=true; error.value='';
  try {
    if(baseRevision.value!==props.revision) { conflict.value=true; throw new Error('草稿修订已变化，请重新读取并核对。输入保留，确认后可采用当前修订。'); }
    const value=buildEditedDecision(props.candidate,edits.value,decision,props.nodes.map(n=>n.id));
    const job=await graphApi.decide(props.jobId,baseRevision.value,[value]);
    open.value=false; awaitingSavedRefresh.value=true; emit('saved',job);
  } catch(e) { if(disposed||scope!==errorScope)return; if((e as {status?:number}).status===409) { conflict.value=true; error.value='审核修订冲突。请点击工作台“重新读取”，核对最新详情后采用当前修订；输入已保留。'; } else error.value=e instanceof Error?e.message:'保存失败，输入已保留。'; notifyError(error.value,'审核保存失败',errorOpener); }
  finally { saving.value=false; }
}
function acknowledge() { baseRevision.value=props.revision; conflict.value=false;error.value=''; notice.value='已采用当前修订，请再次核对并明确批准或拒绝。'; }
</script>
<template>
  <button v-if="!open" :disabled="disabled" @click="begin">编辑并审核</button>
  <form v-else class="candidate-editor" @submit.prevent>
    <h4>编辑候选 · 尚未保存</h4><p class="muted">仅明确点击下方批准或拒绝才提交修改。置信度、权限、文件身份及节点 ID 保持固定，Spring 将校验修改。</p>
    <fieldset :disabled="disabled||saving"><template v-if="candidate.node"><label>节点名称<input v-model="edits.name" required /></label><label>描述<textarea v-model="edits.description" rows="3" /></label></template>
      <template v-if="candidate.edge"><p>REQUIRES 方向：箭头起点 source 是先学的知识，终点 target 是依赖它的后续知识。</p><UiSelect v-model="edits.sourceId" label="起点 source" :options="[...(nodes||[]).map(n=&gt;({value:n.id,label:String(n.name)+&#x27;·&#x27;+String(n.id)}))]"/><UiSelect v-model="edits.targetId" label="终点 target" :options="[...(nodes||[]).map(n=&gt;({value:n.id,label:String(n.name)+&#x27;·&#x27;+String(n.id)}))]"/><UiSelect v-model="edits.relation" label="关系" :options="[{value:&#x27;REQUIRES&#x27;,label:&#x27;REQUIRES · 先修于&#x27;},{value:&#x27;RELATED&#x27;,label:&#x27;RELATED · 相关&#x27;},{value:&#x27;SUPPORTED_BY&#x27;,label:&#x27;SUPPORTED_BY · 支持依据&#x27;}]"/></template>
      <label v-if="candidate.node||candidate.edge">证据（每行一条）<textarea v-model="edits.evidence" rows="4" required /></label>
      <template v-if="candidate.source"><label>原文摘录<textarea v-model="edits.excerpt" rows="5" required /></label><label>来源定位<input v-model="edits.locator" required /></label><label>起始物理页<input v-model="edits.pageFrom" type="number" min="1" step="1" /></label><label>结束物理页<input v-model="edits.pageTo" type="number" min="1" step="1" /></label></template>
      <label>本项审核意见<textarea v-model="edits.comment" maxlength="2000" required rows="2" /></label>
    </fieldset>
    <p v-if="notice" role="status">{{ notice }}</p><p v-if="saving" role="status">正在提交审核…</p>
    <button v-if="conflict" type="button" :disabled="disabled||saving||revision===baseRevision" @click="acknowledge">已核对最新详情，采用修订 {{ revision }}</button>
    <div class="button-row"><button type="button" :disabled="disabled||saving||conflict" @click="save('APPROVE')">保存修改并批准此项</button><button type="button" :disabled="disabled||saving||conflict" @click="save('REJECT')">保存修改并拒绝此项</button><button type="button" :disabled="saving" @click="open=false">取消（保留输入）</button></div>
  </form>
</template>
<style scoped>
.candidate-editor { margin-top:1rem; padding:1rem; border:1px solid var(--line); border-radius:12px; min-width:0; }
fieldset { border:0; padding:0; margin:0; display:grid; gap:.8rem; min-width:0; }
label { display:grid; gap:.35rem; min-width:0; }
input,textarea,select { width:100%; max-width:100%; box-sizing:border-box; }
textarea { font:inherit; font-size:14px; padding:10px 12px; border:1px solid var(--line); border-radius:8px; background:var(--control); color:var(--text); resize:vertical; }
textarea:focus-visible { outline:2px solid var(--blue); outline-offset:3px; }
p { overflow-wrap:anywhere; }
</style>
