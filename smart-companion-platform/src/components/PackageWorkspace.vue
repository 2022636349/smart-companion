<script setup lang="ts">
import { notifyError } from '../notification';
import { onBeforeUnmount as onErrorScopeUnmount } from 'vue';
let errorScope=0,disposed=false;onErrorScopeUnmount(()=>{disposed=true;errorScope++;});
import UiSelect from './UiSelect.vue';

import { computed, onMounted, onBeforeUnmount, ref } from 'vue';
import { packageApi, recommendedDocuments, isExtractableDocument, type PackageDetail, type PackageStart } from '../packageApi';
import { workspaceId } from '../api';
import { graphLabel } from '../graphLabels';
const emit=defineEmits<{graph:[jobId:string];report:[importId:string]}>();
const packages=ref<PackageDetail[]>([]), selected=ref<PackageDetail>(), paths=ref<string[]>([]), sourceLabel=ref(''), acknowledged=ref(false), rights=ref<PackageStart['copyrightStatus']>('CONFIRMED');
const error=ref(''), busy=ref(false), fileInput=ref<HTMLInputElement>();
const key='zhixue-engine.package.'+workspaceId;
const statusLabels: Record<string,string>={READY:'待确认启动',PROCESSING:'自动处理中',COMPLETED:'所选自动处理已完成；教材候选仍需审核后发布',NEEDS_ATTENTION:'需要处理',ANALYZED:'学情分析已完成',NEEDS_MAPPING:'学情格式需要适配',NOT_PRESENT:'未检测到学情表',FAILED:'处理失败',DETECTED:'已识别，待选择',SKIPPED:'本次未选择',TEXTBOOK:'教材',STANDARD:'课程标准',INSTRUCTIONS:'说明 / 竞赛规则',UNKNOWN:'角色待确认'};
const label=(s:string)=>statusLabels[s]||graphLabel(s);
const processing=computed(()=>selected.value?.status==='PROCESSING'||selected.value?.documents.some(d=>['CREATED','EXTRACTING','REVIEWING','VALIDATING','PUBLISHING'].includes(d.status)));
let timer:ReturnType<typeof setTimeout>|undefined;
async function run(action:()=>Promise<void>) { if(busy.value)return; const errorOpener=document.activeElement instanceof HTMLElement?document.activeElement:null;busy.value=true;error.value='';const scope=errorScope;try{await action();}catch(e){if(disposed||scope!==errorScope)return;notifyError(e,undefined,errorOpener);error.value=e instanceof Error?e.message:'资源包处理失败，请重试';}finally{busy.value=false;} }
function apply(value:PackageDetail,reset=false) { selected.value=value;localStorage.setItem(key,value.id);packages.value=[value,...packages.value.filter(p=>p.id!==value.id)];if(reset){paths.value=recommendedDocuments(value.documents);sourceLabel.value=value.fileName;acknowledged.value=false;}schedule(); }
function schedule() { clearTimeout(timer);if(processing.value)timer=setTimeout(()=>{void run(async()=>apply(await packageApi.get(selected.value!.id)));},3000); }
async function refresh(){packages.value=await packageApi.list();const id=selected.value?.id||localStorage.getItem(key)||packages.value.find(p=>p.status==='PROCESSING')?.id||packages.value[0]?.id;if(id)apply(await packageApi.get(id),!selected.value);}
function upload(event:Event){const input=event.target as HTMLInputElement;const file=input.files?.[0];input.value='';if(file)void run(async()=>apply(await packageApi.upload(file),true));}
function start(){void run(async()=>{if(!selected.value||!acknowledged.value||!sourceLabel.value.trim())throw new Error('请填写来源，并确认文件选择、使用权与处理提示。');apply(await packageApi.start(selected.value.id,{sourceLabel:sourceLabel.value.trim(),acknowledgeWarnings:true,copyrightStatus:rights.value,documentPaths:paths.value.filter(isExtractableDocument)}));});}
onMounted(()=>{void run(refresh);});onBeforeUnmount(()=>clearTimeout(timer));
</script>
<template>
<section class="kg-workspace package-workspace">
  <div class="section-title"><div><p class="eyebrow">上传 → 自动识别 → 一次确认 → 分析与构图</p><h2>导入学习资源包</h2></div><button :disabled="busy" @click="run(refresh)">刷新进度</button></div>
  <section class="card"><h3>换一份资源包，系统继续处理</h3><p>上传 ZIP 后，平台自动识别文件清单。确认来源版权和教材选择后，由服务器自动分析已支持的学情表，并为所选教材创建模型抽取与复核任务。</p><p class="muted">学情自动适配当前六表结构；未知表格需要字段适配，但教材构图可继续。说明和竞赛规则不默认作为知识。扫描 PDF 暂无 OCR，文本单批候选数量有限，请按片段或章节准备。</p><button class="primary" :disabled="busy" @click="fileInput?.click()">选择 ZIP 学习资源包</button><input ref="fileInput" class="hidden-input" type="file" accept=".zip" aria-label="上传ZIP学习资源包" @change="upload" /><small>ZIP · 最大100 MiB · 不展开嵌套压缩包</small></section>
  <p v-if="error">读取失败，可重试。 <button :disabled="busy" @click="run(refresh)">重新读取进度</button></p><p v-if="busy" role="status">正在读取或启动资源包处理，请稍候…</p>
  <section v-if="packages.length" class="card"><h3>已保存的资源包</h3><UiSelect :model-value="selected?.id||''" :disabled="busy" @change="value=>run(async()=>apply(await packageApi.get(value),true))" label="选择资源包" :options="packages.map(p=>({value:p.id,label:p.fileName+' · '+label(p.status)}))"/><p class="muted">任务保存在服务器。刷新页面后可继续查看进度，无需重新上传或手动填写任务ID。</p></section>
  <template v-if="selected">
    <section class="card"><h3>{{ selected.fileName }} · {{ label(selected.status) }}</h3><p>{{ selected.createdAt }}</p><p v-if="selected.status==='COMPLETED'" class="muted">所选文件已处理完成。自动生成的图谱仍是候选，须在知识图谱工作台核对后集中审核发布。</p><h4>学情数据 · {{ label(selected.analysisStatus) }}</h4><button v-if="selected.analysisStatus==='FAILED'" @click="notifyError(selected.analysisMessage,'学情处理失败')">查看失败原因</button><p v-else class="muted">{{ selected.analysisMessage }}</p><button v-if="selected.importId" :disabled="busy" @click="emit('report',selected.importId)">{{ selected.analysisStatus==='ANALYZED'?'查看统计报告':'查看学情预检' }}</button><p v-for="(w,i) in selected.warnings" :key="i" class="muted">提示：{{ w }}</p></section>
    <form class="card" @submit.prevent="start"><h3>识别清单与处理选择</h3><p class="muted">角色和推荐由系统识别；请核对文件内容与理由。勾选要提取知识的教材或课标，未勾选项保持可见。每个文件独立生成候选任务，正式发布按任务审核合并。</p><p v-if="!selected.documents.length" class="muted">没有检测到可供构图的文档。可单独分析已识别的学情表。</p>
      <article v-for="d in selected.documents" :key="d.path" class="kg-candidate"><label class="checkbox"><input v-if="isExtractableDocument(d.path)" v-model="paths" :value="d.path" type="checkbox" :disabled="busy||processing||!!d.jobId" />{{ d.fileName }}</label><p>{{ label(d.role) }} · {{ Math.ceil(d.sizeBytes/1024) }} KiB · {{ d.recommended&&d.role!=='INSTRUCTIONS'?'建议提取知识':'需人工决定' }} · {{ label(d.status) }}</p><p>{{ d.reason }}</p><p v-if="!isExtractableDocument(d.path)" class="muted">按学情/其他文件处理，不作为教材文本抽取。</p><small>包内位置：{{ d.path }}</small><button v-if="d.failureMessage" @click="notifyError(d.failureMessage,'文件处理失败')">查看失败原因</button><p v-if="d.jobId" class="muted">已有任务将继续保留，不重复创建。失败时可进入任务重试。</p><button v-if="d.jobId" type="button" :disabled="busy" @click="emit('graph',d.jobId)">{{ d.status==='FAILED'?'打开任务并重试':'查看候选图谱与审核' }}</button></article>
      <div class="kg-form"><label>来源说明<input v-model="sourceLabel" required maxlength="200" :disabled="busy||processing" /></label><UiSelect v-model="rights" :disabled="busy||processing" label="所选文件使用权" :options="[{value:&#x27;CONFIRMED&#x27;,label:&#x27;已确认使用权&#x27;},{value:&#x27;AUTHORIZED&#x27;,label:&#x27;已获授权&#x27;},{value:&#x27;PUBLIC_DOMAIN&#x27;,label:&#x27;公共领域&#x27;},{value:&#x27;UNKNOWN&#x27;,label:&#x27;尚不明确（来源受限）&#x27;}]"/></div><label class="checkbox"><input v-model="acknowledged" type="checkbox" :disabled="busy||processing" />我已核对来源使用权、文件选择及提示，确认启动自动处理。</label><div class="button-row"><button class="primary" :disabled="busy||!acknowledged||!sourceLabel.trim()||processing">一键分析并构图</button></div><p class="muted">可不选文档，仅分析学情。模型输出不自动发布；候选到达后，在本系统内批量审核，无需手动处理资料。</p>
    </form>
  </template>
  <p v-else-if="!busy&&!packages.length" class="muted">还没有资源包。选择 ZIP 开始，识别结果会在这里展示。</p>
</section>
</template>
