<script setup lang="ts">
import { notifyError } from '../notification';
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue';
import { experience, experienceRequest, type Page } from '../experienceApi';
import { requestGate, scopeLabel } from '../experienceDisplay';
import { choiceAnswerText, evidenceText, recordScore, submissionTime } from '../answerHistory';
import { materialTitle } from '../materialPresentation';
import type { Material, RecordEvidence } from '../types';
import MaterialContent from './MaterialContent.vue';
const props=defineProps<{studentId:string;nodeId:string;indicator:{id:string;label:string};open:boolean;dataRevision:number;nodes?:{id:string;description:string}[]}>();
const emit=defineEmits<{close:[];learning:[id:string]}>();
const dialog=ref<HTMLDialogElement>();
const all=ref(false),page=ref(1),loading=ref(false),error=ref(''),materialLoading=ref(false),materialError=ref('');
const result=ref<Page<RecordEvidence>>({revision:0,page:1,pageSize:5,total:0,items:[]});
const selected=ref<RecordEvidence>(),material=ref<Material>();
const listGate=requestGate(),materialGate=requestGate();
let opener:HTMLElement|null=null;
const pages=computed(()=>Math.max(1,Math.ceil(result.value.total/5)));
const choiceStudent=computed(()=>choiceAnswerText(selected.value?.answer,material.value?.options));
const choiceReference=computed(()=>choiceAnswerText(material.value?.answer,material.value?.options));
function nodeLabel(id:string){const node=props.nodes?.find(node=>node.id===id);return node?scopeLabel(node):id;}
function nodeTitle(id:string){return props.nodes?.find(node=>node.id===id)?.description||id;}
function close(){listGate.next();materialGate.next();dialog.value?.close();emit('close');opener?.focus();}
async function choose(row:RecordEvidence){
 const turn=materialGate.next();selected.value=row;material.value=undefined;materialLoading.value=true;materialError.value='';
 try{
  const response=await experienceRequest<{revision:number;material:Material}>('/materials/'+encodeURIComponent(row.materialId));
  if(!materialGate.current(turn))return;
  if(response.revision!==props.dataRevision)throw new Error('数据已更新，请关闭详情并刷新学生分析后重试。');
  if(response.material.id!==row.materialId)throw new Error('题目版本与作答记录不一致，请刷新后重试。');
  material.value=response.material;
 }catch(e){if(materialGate.current(turn)){materialError.value=(e as Error).message;notifyError(e);}}
 finally{if(materialGate.current(turn))materialLoading.value=false;}
}
async function load(){
 const turn=listGate.next();materialGate.next();selected.value=undefined;material.value=undefined;materialError.value='';materialLoading.value=false;loading.value=true;error.value='';
 try{
  const response=await experience.page('records',{studentId:props.studentId,nodeId:all.value?undefined:props.nodeId,indicatorId:props.indicator.id,page:page.value,pageSize:5});
  if(!listGate.current(turn))return;
  if(response.revision!==props.dataRevision)throw new Error('数据已更新，请关闭详情并刷新学生分析后重试。');
  result.value=response as Page<RecordEvidence>;
  if(result.value.items[0])void choose(result.value.items[0]);
 }catch(e){if(listGate.current(turn)){error.value=(e as Error).message;notifyError(e);}}
 finally{if(listGate.current(turn))loading.value=false;}
}
function scope(value:boolean){if(all.value===value)return;all.value=value;page.value=1;void load();}
function go(value:number){page.value=value;void load();}
watch(()=>[props.open,props.studentId,props.nodeId,props.indicator.id,props.dataRevision],async()=>{
 listGate.next();materialGate.next();
 if(!props.open){dialog.value?.close();opener?.focus();return;}
 await nextTick();
 if(!dialog.value?.open){opener=document.activeElement instanceof HTMLElement?document.activeElement:null;dialog.value?.showModal();}
 all.value=false;page.value=1;void load();
},{immediate:true});
onBeforeUnmount(()=>{listGate.next();materialGate.next();dialog.value?.close();opener?.focus();});
</script>

<template>
 <dialog ref="dialog" class="answer-history" data-testid="answer-history" aria-labelledby="history-title" @cancel.prevent="close">
  <header class="history-header"><div><p class="eyebrow">学生 {{studentId}} · 作答依据</p><h2 id="history-title">{{indicator.label}}</h2></div><button type="button" aria-label="关闭作答历史" autofocus @click="close">关闭</button></header>
  <div class="history-scope"><button type="button" :aria-pressed="!all" @click="scope(false)">当前测验</button><button type="button" :aria-pressed="all" @click="scope(true)">全部测验</button><span>{{all?'跨测验有效作答':nodeLabel(nodeId)}}</span></div>
  <div class="history-layout">
   <aside class="history-list" aria-label="有效作答记录">
    <h3>有效作答记录</h3><p class="muted history-list-note" title="按当前清洗口径保留的有效作答，修订版本不重复计入。时间见各条记录。">按记录编号分页 · 修订不重复计入</p>
    <p v-if="loading" role="status">正在读取作答记录…</p>
    <p v-else-if="error">读取失败，可重试。 <button @click="load">重试</button></p>
    <template v-else>
     <p v-if="!result.items.length" class="history-empty">该范围没有作答记录，不能据此判断未掌握。</p>
     <button v-for="row in result.items" :key="row.id" class="history-record" :data-record-id="row.id" :aria-pressed="selected?.id===row.id" @click="choose(row)"><strong>{{materialTitle(row.materialId)}}</strong><span>{{recordScore(row)}}</span><span :title="row.submittedAt">{{submissionTime(row.submittedAt)}}</span><span :title="nodeTitle(row.nodeId)">{{nodeLabel(row.nodeId)}}</span></button>
    </template>
    <footer class="history-pages"><button :disabled="loading||page<=1" @click="go(page-1)">上一页</button><span>第 {{page}} / {{pages}} 页 · 共 {{result.total}} 条</span><button :disabled="loading||page>=pages" @click="go(page+1)">下一页</button></footer>
   </aside>
   <section class="history-detail" aria-label="题目与作答对照">
    <p v-if="loading" role="status">正在读取…</p>
    <template v-else-if="selected">
     <p v-if="materialLoading" role="status">正在读取对应版本题目…</p>
     <p v-else-if="materialError">读取失败，可重试。 <button @click="choose(selected)">重试题目</button></p>
     <div v-else-if="material" data-testid="record-comparison" :data-record-id="selected.id">
      <MaterialContent :material="material" :show-teacher-reference="false" />
      <div class="comparison-heading"><h3>作答与参考对照</h3><strong>{{recordScore(selected)}}</strong></div>
      <div class="comparison-columns"><section><h4>学生作答</h4><p>{{evidenceText(selected.answer)}}</p><p v-if="choiceStudent" class="option-context">选项文本：{{choiceStudent}}</p><h4>原始解答过程</h4><p>{{evidenceText(selected.process)}}</p><h4>评分点得分</h4><p>{{evidenceText(selected.pointScores)}}</p></section><section><h4>参考答案</h4><p>{{evidenceText(material.answer)}}</p><p v-if="choiceReference" class="option-context">选项文本：{{choiceReference}}</p><h4>解析</h4><p>{{evidenceText(material.explanation)}}</p><h4>评分依据</h4><p>{{evidenceText(material.scoringPoints)}}</p></section></div>
      <p class="history-note">仅展示原有作答与已有评分，不在此重新判分。</p>
      <details class="history-trace"><summary>记录来源与版本</summary><p>记录 {{selected.id}} · 测验 {{nodeLabel(selected.nodeId)}} · 作答时间 {{evidenceText(selected.submittedAt)}}</p><p>事件 {{selected.eventId}} · 作答修订 {{selected.revision}} · 材料版本 {{selected.materialId}} · 数据版本 {{result.revision}}</p></details>
     </div>
    </template>
    <p v-else class="history-empty">选择左侧作答记录查看题目与对照。</p>
   </section>
  </div>
 </dialog>
</template>

<style scoped>
.answer-history{box-sizing:border-box;width:min(1120px,calc(100vw - 40px));max-width:none;height:min(850px,calc(100dvh - 48px));max-height:none;padding:0;border:1px solid var(--line);border-radius:16px;background:var(--panel,#111e30);color:var(--text);font:inherit;overflow:hidden;box-shadow:0 24px 90px #0008}
.answer-history::backdrop{background:#030b18bb;backdrop-filter:blur(4px)}
.answer-history[open]{display:flex;flex-direction:column}.history-header,.history-scope{flex-shrink:0}
.history-header{display:flex;justify-content:space-between;align-items:center;gap:20px;padding:22px 26px;border-bottom:1px solid var(--line)}
.history-header h2{margin:5px 0 0;font-size:23px}.eyebrow{margin:0;color:var(--muted);font-size:13px}
.history-scope{display:flex;align-items:center;gap:10px;padding:14px 26px;flex-wrap:wrap;border-bottom:1px solid var(--line)}
.history-scope span{font-size:13px;color:var(--muted)}
.answer-history button{font:inherit;cursor:pointer;max-width:100%}.answer-history button[aria-pressed=true]{border-color:var(--blue);background:var(--blue-soft);color:var(--blue)}
.history-layout{display:grid;grid-template-columns:285px minmax(0,1fr);flex:1;min-height:0}
.history-list,.history-detail{min-width:0;overflow:auto;overscroll-behavior:contain;padding:22px;overflow-wrap:anywhere}.history-list{border-right:1px solid var(--line)}
.history-list h3{margin:0;font-size:16px}.muted,.history-note{font-size:12px;color:var(--muted)}
.history-record{display:grid;gap:5px;width:100%;text-align:left;padding:13px;margin:10px 0;background:var(--control);border:1px solid var(--line);border-radius:9px}.history-record span{font-size:12px}.history-record span:last-child{color:var(--muted)}
.history-pages{display:flex;align-items:center;flex-wrap:wrap;gap:8px;margin-top:20px}.history-pages span{font-size:12px}
.comparison-heading{display:flex;justify-content:space-between;align-items:center;gap:14px;flex-wrap:wrap;margin-top:24px;border-top:1px solid var(--line);padding-top:15px}.comparison-heading h3{font-size:17px;margin:0}.comparison-heading strong{color:var(--blue);font-size:14px}
.comparison-columns{display:grid;grid-template-columns:minmax(0,1fr) minmax(0,1fr);gap:20px;margin-top:14px}.comparison-columns section{background:var(--control);border:1px solid var(--line);border-radius:10px;padding:16px;min-width:0}.comparison-columns h4{margin:19px 0 7px;font-size:14px;color:var(--muted)}.comparison-columns h4:first-child{margin-top:0}.comparison-columns p{margin:0;white-space:pre-wrap;font-size:14px;line-height:1.9}.comparison-columns .option-context{margin-top:8px;color:var(--blue);font-size:13px}
.history-trace{font-size:12px;color:var(--muted);padding-top:12px;border-top:1px solid var(--line)}.history-trace summary{cursor:pointer}.history-trace p{white-space:pre-wrap}.history-empty{line-height:1.9;color:var(--muted)}
@media(max-width:700px){.history-list-note{display:none}}
@media(max-width:700px){.answer-history{width:calc(100vw - 16px);height:calc(100dvh - 16px);border-radius:12px}.history-header{padding:16px;gap:10px}.history-header h2{font-size:19px}.history-scope{padding:12px 16px}.history-layout{display:flex;flex-direction:column;height:calc(100% - 173px)}.history-list{flex:0 0 auto;max-height:35%;border-right:0;border-bottom:1px solid var(--line);padding:14px}.history-detail{flex:1;min-height:0;padding:16px}.history-record{padding:10px;margin:8px 0}.history-pages{margin-top:10px}.comparison-columns{grid-template-columns:1fr;gap:12px}}
</style>
