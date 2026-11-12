<script setup lang="ts">
import { notifyError } from './notification';
import {selectedStudentClass,type PlanScope} from './teachingPlan';
import PlanWorkbench from './components/PlanWorkbench.vue';
import KnowledgeStructure from './components/KnowledgeStructure.vue';
import type { StructurePayload } from './structureData';
import MaterialContent from './components/MaterialContent.vue';
import StudentDiagnosis from './components/StudentDiagnosis.vue';
import { diagnosisScope } from './diagnosisCharts';
import {materialTitle,materialActionLabel,materialKind} from './materialPresentation';
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import DashboardOverview from './components/DashboardOverview.vue';
import UiSelect from './components/UiSelect.vue';
import {scopeLabel,scopeText,indicatorLabel,distributionOption,classIndicatorOption,chartTheme,percentText,summaryEntries,materialLabel,batchStatusLabels} from './experienceDisplay';
import BrandMark from './components/brand/BrandMark.vue';
import StudentPicker from './components/StudentPicker.vue';

import { navigate, readRoute } from './routing';
import ExperienceList from './components/ExperienceList.vue';
import { planSaveInput, experience, experienceRequest as request, clearExperience, type ExperienceSession, type Workspace, type Row, type Plan, type StudentDetail } from './experienceApi';
const session=ref<ExperienceSession|null>(null),workspace=ref<Workspace|null>(null),tab=ref('overview'),nodeId=ref(''),classId=ref(''),studentId=ref(''),busy=ref(''),error=ref(''),notice=ref(''),refresh=ref(0);
const structure=ref<StructurePayload|null>(null);
const student=ref<StudentDetail|null>(null),plan=ref<Plan|null>(null),batch=ref<Row|null>(null),deletion=ref<Row|null>(null),part=ref('LEARNING'),ack=ref(false),sourceLabel=ref(''),file=ref<File|null>(null),uploadKey=ref(''),dataTab=ref('batches'),assistant=ref<Row|null>(null),card=ref<Row|null>(null),material=ref<Row|null>(null),purpose=ref('EXPLAIN'),indicatorId=ref('');
const contentDialog=ref<HTMLDialogElement>();const contentLoading=ref(false),contentError=ref('');let contentOpener:HTMLElement|null=null,contentGeneration=0;
const showRemovedBatches=ref(false);
function closeContent(){contentGeneration++;contentDialog.value?.close();card.value=null;material.value=null;void nextTick(()=>{if(contentOpener?.isConnected)contentOpener.focus();});}
async function openContent(kind:'learning'|'material',id:string){if(busy.value)return;const turn=generation,contentTurn=++contentGeneration;contentOpener=document.activeElement instanceof HTMLElement?document.activeElement:null;card.value=null;material.value=null;contentError.value='';contentLoading.value=true;await nextTick();if(turn!==generation||contentTurn!==contentGeneration)return;contentDialog.value?.showModal();try{const value=kind==='learning'?await request('/learning/'+encodeURIComponent(id)):(await experience.material(id)).material;if(turn===generation&&contentTurn===contentGeneration){if(kind==='learning')card.value=value;else material.value=value;}}catch(e){if(turn===generation&&contentTurn===contentGeneration){contentError.value=(e as Error).message;notifyError(e,'学习内容读取失败');}}finally{if(turn===generation&&contentTurn===contentGeneration)contentLoading.value=false;}}
const theme='dark';
const media=window.matchMedia('(prefers-reduced-motion: reduce)'),reduced=ref(media.matches);
function motion(e:MediaQueryListEvent){reduced.value=e.matches;}
media.addEventListener('change',motion);
const tabs=[['overview','班级学情'],['students','学生诊断'],['structure','学习内容与要求'],['plans','学习建议编排'],['data','数据管理']];
const initial=readRoute();tab.value=initial.page;studentId.value=initial.studentId;nodeId.value=initial.nodeId;
const planScope=ref<PlanScope>('STUDENT');
const individualScope=computed(()=>['students','plans'].includes(tab.value));
const scopeOptions=computed(() => [...(individualScope.value?[]:[{value:'',label:'全部范围'}]),...(workspace.value?.nodes||[]).map(n=>({value:n.id,label:String(scopeLabel(n,workspace.value?.indicators)),description:scopeText(n,workspace.value?.indicators)}))]);
watch([tab,studentId,nodeId,workspace],()=>{
 const scope=diagnosisScope(tab.value,nodeId.value,workspace.value?.nodes||[]);
 if(scope!==nodeId.value)nodeId.value=scope;
 navigate('teacher',tab.value,studentId.value,nodeId.value);
});
async function routeChanged(){const r=readRoute();if(r.role!=='teacher')return;if(session.value&&r.studentId&&r.studentId!==studentId.value){const routeHash=window.location.hash;try{const selected=await experience.page('students',{selector:true,studentId:r.studentId,page:1,pageSize:1});if(disposed||window.location.hash!==routeHash)return;classId.value=selectedStudentClass(selected.items,r.studentId);}catch(e){if(!disposed&&window.location.hash===routeHash)notifyError(e,'学生身份读取失败');return;}}tab.value=r.page;studentId.value=r.studentId;nodeId.value=r.nodeId;}
window.addEventListener('hashchange',routeChanged);
onBeforeUnmount(()=>{disposed=true;generation++;window.removeEventListener('hashchange',routeChanged);media.removeEventListener('change',motion);});
onMounted(enter);
const filters=computed(()=>({nodeId:nodeId.value,classId:classId.value}));
const dataFilters=computed(()=>({...filters.value,...(dataTab.value==='batches'&&showRemovedBatches.value?{status:'ALL'}:{})}));
let disposed=false;let generation=0;async function run(label:string,action:()=>Promise<void>){if(busy.value)return;const route=[tab.value,studentId.value,nodeId.value].join('|');const errorOpener=document.activeElement instanceof HTMLElement?document.activeElement:null;busy.value=label;error.value='';notice.value='';try{await action();}catch(e){if(!disposed&&route===[tab.value,studentId.value,nodeId.value].join('|')){error.value=(e as Error).message;notifyError(e,undefined,errorOpener);}}finally{busy.value='';}}
async function sync(){const turn=generation;const value=await experience.workspace();if(turn!==generation)return;if(studentId.value){const selected=await experience.page('students',{selector:true,studentId:studentId.value,page:1,pageSize:1});if(turn!==generation)return;classId.value=selectedStudentClass(selected.items,studentId.value);}else if(!classId.value){const first=value.classes[0];classId.value=String(typeof first==='string'?first:first?.id||'');}workspace.value=value;if(!value.nodes.some(n=>n.id===nodeId.value))nodeId.value=value.nodes[0]?.id||'';refresh.value++;await context();}
async function enter(){await run('正在进入教师工作台',async()=>{session.value=await experience.session();await sync();});}
async function context(){const turn=++generation;closeContent();student.value=null;plan.value=null;assistant.value=null;card.value=null;material.value=null;try{if(!session.value)return;if(tab.value==='structure'){const value=await experience.structure(nodeId.value);if(turn===generation)structure.value=value;return;}if(tab.value!=='students')return;const scope=diagnosisScope(tab.value,nodeId.value,workspace.value?.nodes||[]);if(!scope)return;if(scope!==nodeId.value){nodeId.value=scope;return;}const id=studentId.value;if(id){const value=await experience.student(id,nodeId.value);if(turn===generation){student.value=value;plan.value=value.plan?structuredClone(value.plan):null;}}}catch(e){if(turn===generation){error.value=(e as Error).message;notifyError(e);}}}
watch([nodeId,studentId,tab],()=>{void context();});
function leave(){generation++;clearExperience();navigate('home');}
function selectStudent(row:Row){if(row.classId)classId.value=String(row.classId);studentId.value=String(row.id);if(tab.value!=='plans')tab.value='students';}
async function openBatch(row:Row){await run('正在读取批次',async()=>{batch.value=await request('/batches/'+encodeURIComponent(row.id));deletion.value=null;ack.value=false;});}
function pick(event:Event){file.value=(event.target as HTMLInputElement).files?.[0]||null;uploadKey.value=crypto.randomUUID();}
async function upload(){if(!file.value)return;await run('正在自动识别上传内容',async()=>{batch.value=await experience.upload(file.value!,sourceLabel.value,uploadKey.value);deletion.value=null;ack.value=false;refresh.value++;});}
async function confirmBatch(){await run('正在确认并合并',async()=>{await request('/batches/'+batch.value!.summary.id+'/confirm','POST',{expectedRevision:workspace.value!.revision,acknowledgeWarnings:ack.value});batch.value=await request('/batches/'+batch.value!.summary.id);await sync();notice.value='批次已确认，统计已按完整有效范围更新。';});}
async function previewDelete(){await run('正在检查删除依赖',async()=>{deletion.value=await request('/batches/'+batch.value!.summary.id+'/deletion-preview','POST',{part:part.value});});}
async function remove(){await run('正在撤销批次来源',async()=>{await request('/batches/'+batch.value!.summary.id+'?'+new URLSearchParams({part:part.value,expectedRevision:String(deletion.value!.revision)}),'DELETE');batch.value=await request('/batches/'+batch.value!.summary.id);deletion.value=null;await sync();notice.value='已撤销选定批次来源，共享数据继续保留；这不代表原文件已物理擦除。';});}
function learning(id:string){return openContent('learning',id);}
function question(id:string){return openContent('material',id);}
function changeClass(id:string){if(id!==classId.value){generation++;student.value=null;plan.value=null;closeContent();studentId.value='';classId.value=id;}}
const names:Record<string,string>={studentCount:'学生',materialCount:'题目与资料',recordCount:'作答记录',batchCount:'批次',READY:'当前证据表现较好',DEVELOPING:'仍需巩固',NOT_ASSESSED:'本次未测量',NEEDS_PRACTICE:'需要练习',NO_EVIDENCE:'尚无证据',INSUFFICIENT:'证据不足',MASTERED:'达到要求',DRAFT:'草稿',CONFIRMED:'教师已确认',NEEDS_REVIEW:'数据已变化，待教师复核'};
function label(id:string){return indicatorLabel(id,workspace.value?.indicators);}function display(v:any):string{if(v==null)return '暂无';if(typeof v==='number')return Number.isInteger(v)?String(v):v.toFixed(1);if(typeof v==='object')return Object.entries(v).map(([k,n])=>`${names[k]||k}：${display(n)}`).join(' · ');return names[String(v)]||String(v);}
</script>
<template>
<div class="platform">
<aside class="sidebar">
<a class="brand brand-home" href="#/">
<BrandMark/>
</a>
<small class="space-caption">教师工作台</small>
<nav aria-label="教师导航">
<button v-for="item in tabs" :key="item[0]" :class="{active:tab===item[0]}" :aria-current="tab===item[0]?'page':undefined" @click="tab=item[0]">{{item[1]}}</button>
</nav>
</aside>
<div class="flow-main">
<header class="topbar">
<a href="#/" @click="leave">← 返回首页</a>
<span>教师工作台</span>
<button :disabled="!!busy" @click="run('正在刷新',sync)">刷新</button>
</header>
<main class="workspace">
<h1>{{tabs.find(t=>t[0]===tab)?.[1]||'教师工作台'}}</h1>
<p v-if="busy" role="status">{{busy}}</p>

<p v-if="notice" role="status">{{notice}}</p>
<template v-if="workspace">
<div class="flow-filters">
<UiSelect v-model="nodeId" :disabled="!!busy" label="测验范围" :options="scopeOptions"/>
<UiSelect v-if="tab==='data'" v-model="classId" :disabled="!!busy" label="班级" :options="[{value:&#x27;&#x27;,label:&#x27;全部班级&#x27;},...(workspace?.classes||[]).map(c=&gt;({value:typeof c===&#x27;string&#x27;?c:c.id,label:String(typeof c===&#x27;string&#x27;?c:c.id)}))]"/>
</div>
<DashboardOverview v-if="tab==='overview'" :workspace="workspace" :node-id="nodeId" :refresh="refresh" :motion="!reduced" @select="selectStudent"/>
<KnowledgeStructure v-else-if="tab==='structure'" :payload="structure" :busy="!!busy" :motion="!reduced" role="teacher" @learning="learning" @material="question" @diagnose="tab='students'"/>
<template v-else-if="tab==='data'">
<section class="card">
<h2>上传数据</h2>
<p class="muted">自动识别学情、题目与材料或组合内容，核对后确认；已有来源增量合并。</p>
<label>来源名称<input v-model="sourceLabel"/>
</label>
<label class="file-picker">选择 XLSX / ZIP<input type="file" accept=".xlsx,.zip" @change="pick"/>
</label>
<p class="muted">{{file?.name||'尚未选择文件'}}</p>
<button :disabled="!file||!!busy" @click="upload">上传并识别</button>
<button :disabled="!!busy" @click="run('正在导出完整范围',experience.download)">导出完整报告</button>
</section>
<div class="button-row">
<button v-for="c in [['batches','导入批次'],['materials','题目与材料'],['records','作答'],['quality','异常']]" :key="c[0]" @click="dataTab=c[0]">{{c[1]}}</button>
</div>
<label v-if="dataTab==='batches'" class="flow-history-toggle">
<input v-model="showRemovedBatches" type="checkbox" />显示已移除批次</label>
<ExperienceList :collection="dataTab" :size="dataTab==='batches'?10:20" :filters="dataFilters" :refresh="refresh" @select="row=>dataTab==='batches'?openBatch(row):dataTab==='materials'?question(row.id):undefined">
<template #default="{row,select}">
<button v-if="['batches','materials'].includes(dataTab)" @click="select">{{row.fileName||(dataTab==='materials'?materialTitle(row):row.id)}}</button>
<span v-if="dataTab==='materials'" class="status-badge">{{materialKind(row)==='question'?(row.questionType||'题目'):(row.type||'学习材料')}}</span>
<span>{{dataTab==='records'?`${row.id} · ${row.studentId} · ${row.score??'待评'}/${row.maxScore}`:dataTab==='quality'?`${row.recordId} · ${row.disposition} · ${(row.reasons||[]).join('；')}`:(row.body||batchStatusLabels[row.status]||row.status||'').slice(0,100)}}</span>
<details v-if="dataTab=== 'records'">
<summary>展开原作答与过程</summary>
<p>{{row.answer}}</p>
<p class="flow-pre">{{row.process}}</p>
</details>
<details v-if="dataTab=== 'materials'">
<summary>{{materialKind(row)==='question'?'展开题目摘要':'展开学习材料摘要'}}</summary>
<p class="flow-pre">{{row.body}}</p>
</details>
</template>
</ExperienceList>
<section v-if="batch" class="card">
<h2>{{batch.summary.fileName}}</h2>
<p>{{batchStatusLabels[batch.summary.status]||batch.summary.status}}</p>
<h3>识别结果</h3>
<dl class="flow-summary">
<div v-for="entry in summaryEntries(batch.preview.counts)" :key="entry.label">
<dt>{{entry.label}}</dt>
<dd>{{entry.value}}</dd>
</div>
</dl>
<p v-for="(issue,i) in batch.preview.issues" :key="i">{{issue.severity==='ERROR'?'需处理':'提示'}} · {{issue.message}}</p>
<label>
<input v-model="ack" type="checkbox"/>我已核对识别结果及警告</label>
<button :disabled="!!busy||!batch.preview.canConfirm||!ack||batch.summary.status!=='PREVIEW'" @click="confirmBatch">确认增量合并</button>
<hr/>
<h3>删除批次来源</h3>
<p>撤销该批次的有效来源，其他批次共享的数据继续保留。普通删除不代表原文件物理擦除。</p>
<UiSelect v-model="part" @change="deletion=null" label="删除范围" :options="[{value:&#x27;LEARNING&#x27;,label:&#x27;仅学情&#x27;},{value:&#x27;QUESTION_BANK&#x27;,label:&#x27;仅题目与材料&#x27;},{value:&#x27;ALL&#x27;,label:&#x27;学情与题目材料&#x27;}]"/>
<button :disabled="!!busy" @click="previewDelete">预览影响与依赖</button>
<template v-if="deletion">
<p>{{deletion.message}}</p>
<p>移除 {{deletion.removedRecords}} 条作答、{{deletion.removedMaterials}} 项题目与材料；保留共享作答 {{deletion.retainedSharedRecords}}、共享资料 {{deletion.retainedSharedMaterials}}</p>
<p v-for="d in deletion.dependencies" :key="d.materialId">{{materialActionLabel(d.materialId)}} · {{d.recordCount}} 条关联 · {{d.message}}</p>
<button :disabled="!!busy||!deletion.canDelete" @click="remove">确认删除所选来源</button>
</template>
</section>
</template>
<template v-else>
<div v-if="tab==='plans'" class="plan-mode button-row" aria-label="方案对象"><button :class="{primary:planScope==='STUDENT'}" :aria-pressed="planScope==='STUDENT'" @click="planScope='STUDENT'">个人定制计划</button><button :class="{primary:planScope==='CLASS'}" :aria-pressed="planScope==='CLASS'" @click="planScope='CLASS'">班级整体计划</button></div>
<StudentPicker v-model="studentId" :class-id="classId" :classes="workspace.classes" :student-required="tab!=='plans'||planScope==='STUDENT'" :refresh="refresh" :disabled="!!busy" @update:class-id="changeClass"/>
<PlanWorkbench v-if="tab==='plans'" :scope-type="planScope" :class-id="classId" :student-id="studentId" :node-id="nodeId" :refresh="refresh" :indicators="workspace.indicators" @learning="learning" @question="question"/>
<template v-else>
<p v-if="!student" class="muted">先选学生与测验范围，再查看证据或编排方案。</p>
<p v-if="!student" class="card">选择学生查看诊断与方案。</p>
<template v-if="student">
<details v-if="tab==='plans'" class="card plan-context"><summary>{{student.student.id}} · 学习概况与诊断依据</summary><dl class="flow-summary"><div v-for="entry in summaryEntries(student.summary,{nodes:workspace?.nodes||[],indicators:workspace?.indicators})" :key="entry.label"><dt>{{entry.label}}</dt><dd>{{entry.value}}</dd></div></dl><p class="muted">判断基于已有评分，缺少证据不等于未掌握。</p></details>
<section v-else class="card">
<h2>{{student.student.id}} · 学习概况</h2>
<dl class="flow-summary">
<div v-for="entry in summaryEntries(Object.fromEntries(['scorePercent','scoredCount','availableQuestionCount','coveragePercent'].map(key=>[key,student.summary[key]])))" :key="entry.label">
<dt>{{entry.label}}</dt>
<dd>{{entry.value}}</dd>
</div>
</dl>
<details class="diagnosis-more-stats"><summary>更多统计</summary><dl class="flow-summary"><div v-for="entry in summaryEntries(Object.fromEntries(Object.entries(student.summary).filter(([key])=>!['scorePercent','scoredCount','availableQuestionCount','coveragePercent','studentId','nodeId'].includes(key))))" :key="entry.label"><dt>{{entry.label}}</dt><dd>{{entry.value}}</dd></div></dl></details>
</section>
<StudentDiagnosis v-if="tab==='students'" :key="student.student.id+'|'+nodeId" :detail="student" :indicators="workspace.indicators" :nodes="workspace.nodes" :node-id="nodeId" :busy="!!busy" :motion="!reduced" @learning="learning" @plan="tab='plans'"/>


</template>
</template>
</template>
<dialog ref="contentDialog" class="evidence-dialog flow-content-dialog" aria-labelledby="content-dialog-title" @cancel.prevent="closeContent">
<header class="dialog-header">
<h2 id="content-dialog-title">{{card?.name||(material?materialTitle(material):'学习内容')}}</h2>
<button autofocus aria-label="关闭学习内容" @click="closeContent">关闭</button>
</header>
<div class="dialog-body">
<p v-if="contentLoading" role="status">正在读取，请稍候…</p>
<p v-if="contentError" class="muted">学习内容未能读取，请关闭后重试。</p>
<template v-if="card">
<p>{{card.summary}}</p>
<p class="flow-pre">{{card.content}}</p>
<ol>
<li v-for="s in card.learningSteps" :key="s">{{s}}</li>
</ol>
<h3>前置学习</h3>
<p v-for="p in card.prerequisites" :key="p.id">{{p.label}} · {{p.reason}}</p>
<details>
<summary>已审核来源 · {{card.version}}</summary>
<blockquote v-for="s in card.sources" :key="s.id">
<p>{{s.title}} · {{s.location}}</p>
<p>{{s.excerpt}}</p>
</blockquote>
</details>
</template>
<MaterialContent v-if="material" :material="material" :show-teacher-reference="true"/>
</div>
</dialog>
<details class="card method-notes">
<summary>统计方法与使用边界</summary>
<ul><li v-for="m in workspace?.method" :key="m">{{m}}</li><li v-for="m in workspace?.limitations" :key="m">{{m}}</li></ul>
</details>
</template>
</main>
</div>
</div>
</template>











<style scoped>
.diagnosis-more-stats { margin-top:12px; color:var(--muted); font-size:12px; }
.diagnosis-more-stats summary { cursor:pointer; }
</style>
