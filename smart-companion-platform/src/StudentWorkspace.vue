<script setup lang="ts">
import { notifyError } from './notification';
import { assistantInput, assistantObservations } from './assistantRequest';
import MaterialContent from './components/MaterialContent.vue';
import KnowledgeStructure from './components/KnowledgeStructure.vue';
import type { StructurePayload } from './structureData';
import {materialTitle,materialActionLabel} from './materialPresentation';
import UiSelect from './components/UiSelect.vue';

import {ref,computed,onMounted,onBeforeUnmount,watch,nextTick} from 'vue';
import BrandMark from './components/brand/BrandMark.vue';
import StudentPicker from './components/StudentPicker.vue';
import StudentProgress from './components/StudentProgress.vue';
import {experience,experienceRequest as request,type Workspace,type StudentDetail,type Row} from './experienceApi';
import {readRoute,navigate} from './routing';
import {scopeLabel,scopeText,indicatorLabel} from './experienceDisplay';
const initial=readRoute(),tab=ref(initial.page),studentId=ref(initial.studentId),nodeId=ref(initial.nodeId);
const ready=ref(false),sessionReady=ref(false),busy=ref(''),error=ref(''),notice=ref(''),workspace=ref<Workspace|null>(null),student=ref<StudentDetail|null>(null),assistant=ref<Row|null>(null),structure=ref<StructurePayload|null>(null),structureBusy=ref(false);
const planPage=ref(1),purpose=ref('PLAN'),indicatorId=ref('');let generation=0;let structureTurn=0;
const tabs=[['learning','我的学情'],['structure','学习内容与要求'],['assistant','学习建议'],['tasks','学习任务']];
const classPage=ref(1);
const classPlan=computed(()=>{const p=student.value?.classPlan;return p&&p.scopeType==='CLASS'&&p.classId===student.value?.student.classId&&p.nodeId===nodeId.value&&p.status==='CONFIRMED'&&p.dataRevision===student.value?.revision?p:null;});
const classPages=computed(()=>Math.max(1,Math.ceil((classPlan.value?.steps.length||0)/3)));
const classSteps=computed(()=>classPlan.value?.steps.slice((classPage.value-1)*3,classPage.value*3)||[]);
const plan=computed(()=>student.value?.plan);
const visibleSteps=computed(()=>plan.value?.steps.slice((planPage.value-1)*3,planPage.value*3)||[]);
const pages=computed(()=>Math.max(1,Math.ceil((plan.value?.steps.length||0)/3)));
const status:Record<string,string>={READY:'当前表现较好',DEVELOPING:'继续巩固',INSUFFICIENT:'还需要更多练习证据',NO_EVIDENCE:'尚无学习证据',NOT_ASSESSED:'本次未测量',NEEDS_PRACTICE:'优先练习',MASTERED:'达到要求'};
function label(id:string){return indicatorLabel(id,workspace.value?.indicators);}
async function run(text:string,action:()=>Promise<void>){if(busy.value)return;const turn=generation,view=tab.value;const errorOpener=document.activeElement instanceof HTMLElement?document.activeElement:null;busy.value=text;error.value='';notice.value='';try{await action();}catch(e){if(turn===generation&&view===tab.value){error.value=(e as Error).message;notifyError(e,undefined,errorOpener);}}finally{busy.value='';}}
onMounted(()=>run('正在准备学习空间',async()=>{await experience.session();sessionReady.value=true;if(studentId.value)await selectStudent(studentId.value);}));
onBeforeUnmount(()=>{generation++;window.removeEventListener('hashchange',routeChanged);});
async function selectStudent(id:string){if(!id||busy.value==='正在读取我的学习')return;const turn=++generation;const errorOpener=document.activeElement instanceof HTMLElement?document.activeElement:null;busy.value='正在读取我的学习';error.value='';try{await experience.session({view:'STUDENT',studentId:id});if(turn!==generation)return;studentId.value=id;structure.value=null;structureTurn++;workspace.value=await experience.workspace();if(turn!==generation)return;if(!workspace.value.nodes.some(n=>n.id===nodeId.value))nodeId.value=workspace.value.nodes[0]?.id||'';ready.value=true;await load();navigate('student',tab.value,id,nodeId.value);}catch(e){if(turn===generation){error.value=(e as Error).message;notifyError(e,undefined,errorOpener);}}finally{busy.value='';}}
async function load(){const turn=++generation;close();student.value=null;assistant.value=null;planPage.value=1;classPage.value=1;try{const value=await experience.student(studentId.value,nodeId.value);if(turn===generation)student.value=value;}catch(e){if(turn===generation){error.value=(e as Error).message;notifyError(e);}}}
async function loadStructure(){const requested=nodeId.value;const turn=++structureTurn;structureBusy.value=true;try{const value=await experience.structure(requested);if(turn===structureTurn)structure.value=value;}catch(e){if(turn===structureTurn){error.value=(e as Error).message;notifyError(e,'学习内容读取失败');}}finally{if(turn===structureTurn)structureBusy.value=false;}}
function suggest(id:string){purpose.value='PLAN';indicatorId.value=id;tab.value='assistant';}
function routeChanged(){const r=readRoute();if(r.role!=='student')return;tab.value=r.page;if(ready.value&&r.studentId&&r.studentId!==studentId.value){navigate('student',r.page,studentId.value,nodeId.value);return;}if(ready.value&&r.nodeId!==nodeId.value){nodeId.value=r.nodeId;void load();}}
window.addEventListener('hashchange',routeChanged);
watch(tab,()=>{close();navigate('student',tab.value,studentId.value,nodeId.value);});
watch([tab,nodeId,ready],()=>{if(!ready.value||tab.value!=='structure')return;if(structure.value&&structure.value.nodeId===(nodeId.value||'')&&!busy.value)return;void loadStructure();});
async function scopeChanged(){navigate('student',tab.value,studentId.value,nodeId.value);await load();}
async function feedback(type:string,taskId:string,planScope:'STUDENT'|'CLASS'='STUDENT'){await run('正在保存学习反馈',async()=>{await request('/feedback','POST',{studentId:studentId.value,nodeId:nodeId.value,taskId,type,planScope});notice.value='反馈已保存。学习进度由真实作答与学习记录共同呈现。';});}
async function ask(){if(busy.value||!assistantReady.value)return;const turn=generation,assistantTurn=++assistantGeneration;const input=assistantInput(student.value,studentId.value,nodeId.value,indicatorId.value,purpose.value);const errorOpener=document.activeElement instanceof HTMLElement?document.activeElement:null;busy.value='正在根据证据生成建议';error.value='';assistant.value=null;try{const value=await request('/assistant','POST',input);if(turn===generation&&assistantTurn===assistantGeneration&&student.value?.revision===input.expectedRevision)assistant.value=value;}catch(e){if(turn===generation&&assistantTurn===assistantGeneration){error.value=(e as Error).message;notifyError(e,'建议生成失败',errorOpener);}}finally{busy.value='';}}
const assistantOptions=computed(()=>assistantObservations(student.value,studentId.value,nodeId.value).map(o=>({value:o.indicatorId,label:String(label(o.indicatorId))})));
const weakPicks=computed(()=>[...(student.value?.observations||[])].filter(o=>['NEEDS_PRACTICE','DEVELOPING','INSUFFICIENT'].includes(o.status)).sort((a,b)=>(a.scorePercent??101)-(b.scorePercent??101)).slice(0,4));
const assistantReady=computed(()=>!!student.value&&assistantOptions.value.some(o=>o.value===indicatorId.value));
const assistantAction=computed(()=>purpose.value==='PLAN'?'生成学习步骤':purpose.value==='HINT'?'生成练习提示':'生成学情解释');
let assistantGeneration=0;
watch([student,studentId,nodeId,tab,purpose,indicatorId],()=>{assistantGeneration++;assistant.value=null;const options=assistantOptions.value;if(!options.some(o=>o.value===indicatorId.value))indicatorId.value=options[0]?.value||'';},{immediate:true,flush:'sync'});
const dialog=ref<HTMLDialogElement>(),card=ref<Row|null>(null),material=ref<Row|null>(null),contentError=ref(''),contentLoading=ref(false);let opener:HTMLElement|null=null,contentGeneration=0;
async function content(kind:'learning'|'material',id:string){const contentTurn=++contentGeneration;opener=document.activeElement as HTMLElement;card.value=null;material.value=null;contentError.value='';contentLoading.value=true;const turn=generation;await nextTick();if(turn!==generation||contentTurn!==contentGeneration)return;dialog.value?.showModal();try{const value=kind==='learning'?await request('/learning/'+encodeURIComponent(id)):(await experience.material(id)).material;if(turn===generation&&contentTurn===contentGeneration){if(kind==='learning')card.value=value;else material.value=value;}}catch(e){if(turn===generation&&contentTurn===contentGeneration){contentError.value=(e as Error).message;notifyError(e,'学习内容读取失败');}}finally{if(turn===generation&&contentTurn===contentGeneration)contentLoading.value=false;}}
function close(){contentGeneration++;dialog.value?.close();card.value=null;material.value=null;opener?.focus();}
</script>
<template>
<div class="platform student-platform">
<aside class="sidebar">
<a class="brand brand-home" href="#/">
<BrandMark/>
</a>
<small class="space-caption">学生学习空间</small>
<nav aria-label="学生导航">
<button v-for="item in tabs" :key="item[0]" :disabled="!ready" :class="{active:tab===item[0]}" :aria-current="tab===item[0]?'page':undefined" @click="tab=item[0]">{{item[1]}}</button>
</nav>
</aside>
<div class="flow-main">
<header class="topbar">
<a href="#/">← 返回首页</a>
<span>学生学习空间<span v-if="ready"> · {{studentId}}</span>
</span>
</header>
<main class="workspace">
<h1>{{ready?tabs.find(t=>t[0]===tab)?.[1]:'从你的学习开始'}}</h1>
<p v-if="busy" role="status">{{busy}}</p>

<p v-if="notice" role="status">{{notice}}</p>
<template v-if="!ready">
<p class="muted">选择模拟学生身份，查看自己的进度与学习任务。更换身份请先返回首页。</p>
<StudentPicker v-if="sessionReady" :model-value="studentId" :disabled="!!busy" @update:model-value="selectStudent"/>
</template>
<template v-else>
<div class="flow-filters">
<UiSelect v-model="nodeId" :disabled="!!busy" @change="scopeChanged" label="我的测验范围" :options="[...(workspace?.nodes||[]).map(n=&gt;({value:n.id,label:String(scopeLabel(n,workspace?.indicators)),description:scopeText(n,workspace?.indicators)}))]"/>
</div>
<KnowledgeStructure v-if="tab==='structure'" :payload="structure" :busy="structureBusy" :motion="!reduced" role="student" @learning="content('learning',$event)" @material="content('material',$event)" @suggest="suggest"/>
<p v-else-if="!student&&!busy&&!error" class="card">当前范围还没有个人学习证据。</p>
<template v-else-if="student">
<template v-if="tab==='learning'">
<section class="card next-action">
<small>下一步学习</small>
<h2>{{plan?.steps[0]?.title||'从学习卡开始，了解当前知识要求'}}</h2>
<p>{{plan?.steps[0]?.action||'先看下方图表里的能力分布，学习后再完成相应练习。'}}</p>
<button class="primary" @click="tab='tasks'">查看我的学习任务</button>
</section>
<StudentProgress :key="studentId+'|'+nodeId" :detail="student" :indicators="workspace?.indicators||[]" :nodes="workspace?.nodes||[]" :node-id="nodeId" :refresh="refresh" :busy="!!busy" :motion="!reduced" @learning="content('learning',$event)" @material="content('material',$event)"/>
</template>
<section v-if="tab==='tasks'" class="card">
<div class="section-heading">
<h2>按自己的节奏完成</h2>
<span class="status-badge">{{plan?.status==='CONFIRMED'?'教师已确认':plan?.status==='NEEDS_REVIEW'?'学习安排更新中':'等待教师确认'}}</span>
</div>
<p v-if="!plan?.steps.length">{{plan?.status==='CONFIRMED'?'教师已确认个人方案，目前暂无学习安排，可以先从学习卡开始。':'个人方案等待教师确认，确认后会显示学习任务。你可以先查看学习卡，或使用学习助手。'}}</p>
<article v-for="(s,i) in visibleSteps" :key="s.id" class="flow-row">
<small>第 {{(planPage-1)*3+i+1}} 步 · {{label(s.indicatorId)}}</small>
<h3>{{s.title}}</h3>
<p>{{s.action}}</p>
<details>
<summary>为什么这样学</summary>
<p>{{s.reason}}</p>
</details>
<div class="button-row">
<button v-if="s.indicatorId" @click="content('learning',s.indicatorId)">开始学习</button>
<button v-for="m in s.materialIds" :key="m" @click="content('material',m)">{{materialActionLabel(m)}}</button>
</div>
<details v-if="s.hints?.length">
<summary>查看提示</summary>
<p v-for="h in s.hints" :key="h">{{h}}</p>
</details>
<div class="button-row">
<button :disabled="!!busy" @click="feedback('READ',s.id)">已阅读</button>
<button :disabled="!!busy" @click="feedback('NEED_HELP',s.id)">需要帮助</button>
<button :disabled="!!busy" @click="feedback('COMPLETED',s.id)">完成自评</button>
</div>
</article>
<nav v-if="pages>1" class="button-row" aria-label="学习任务分页">
<button :disabled="planPage<=1" @click="planPage--">上一组</button>
<span>第 {{planPage}} / {{pages}} 组 · 共 {{plan?.steps.length}} 步</span>
<button :disabled="planPage>=pages" @click="planPage++">下一组</button>
</nav>
</section>
<section v-if="tab==='tasks'&&classPlan" class="card" data-testid="class-tasks"><div class="section-heading"><h2>班级共同任务</h2><span class="status-badge">教师已确认</span></div><p class="muted">本班本次测验的共同学习安排，与个人任务分别保留。</p><article v-for="(s,i) in classSteps" :key="s.id" class="flow-row"><small>第 {{(classPage-1)*3+i+1}} 步 · {{s.durationMinutes?s.durationMinutes+'分钟':''}}</small><h3>{{s.title}}</h3><p>{{s.action}}</p><details><summary>为什么这样学</summary><p>{{s.reason}}</p></details><div class="button-row"><button v-if="s.indicatorId" @click="content('learning',s.indicatorId)">开始学习</button><button v-for="m in s.materialIds" :key="m" @click="content('material',m)">{{materialActionLabel(m)}}</button></div><details v-if="s.hints?.length"><summary>查看提示</summary><p v-for="h in s.hints" :key="h">{{h}}</p></details><div class="button-row"><button :disabled="!!busy" @click="feedback('READ',s.id,'CLASS')">已阅读</button><button :disabled="!!busy" @click="feedback('NEED_HELP',s.id,'CLASS')">需要帮助</button><button :disabled="!!busy" @click="feedback('COMPLETED',s.id,'CLASS')">完成自评</button></div></article><nav v-if="classPages>1" class="button-row" aria-label="班级共同任务分页"><button :disabled="classPage<=1" @click="classPage--">上一组</button><span>第 {{classPage}} / {{classPages}} 组 · 共 {{classPlan.steps.length}} 步</span><button :disabled="classPage>=classPages" @click="classPage++">下一组</button></nav></section>
<section v-if="tab==='assistant'" class="card assistant-card" data-testid="student-assistant">
<div class="section-heading"><div><span class="eyebrow">个性化学习建议</span><h2>结合我的学情和内容要求</h2></div><span class="status-badge">{{plan?.status==='CONFIRMED'?'教师已确认学习安排':plan?.status==='NEEDS_REVIEW'?'学习安排待更新':'等待教师确认'}}</span></div>
<p class="muted">建议由 AI 依据当前学情与已审核的学习内容生成，只作学习参考；教师可在教师工作台查看并调整，确认后才成为学习任务。</p>
<div v-if="weakPicks.length" class="assistant-picks"><small>优先关注</small><button v-for="o in weakPicks" :key="o.indicatorId" :aria-pressed="indicatorId===o.indicatorId" @click="indicatorId=o.indicatorId">{{label(o.indicatorId)}} · {{status[o.status]||'查看依据'}}</button></div>
<div class="flow-filters">
<UiSelect v-model="purpose" :disabled="!!busy" label="我想要" :options="[{value:&#x27;PLAN&#x27;,label:&#x27;具体学习建议&#x27;},{value:&#x27;HINT&#x27;,label:&#x27;练习提示&#x27;},{value:&#x27;EXPLAIN&#x27;,label:&#x27;理解学习依据&#x27;}]"/>
<UiSelect v-model="indicatorId" label="关注能力" :disabled="!!busy||!assistantOptions.length" :options="assistantOptions"/>
</div>
<div class="assistant-action" data-testid="assistant-action"><button class="primary" :disabled="!!busy||!assistantReady" @click="ask">{{busy==='正在根据证据生成建议'?'正在生成，请稍候…':assistantAction}}</button><p v-if="!assistantReady" class="muted">当前没有可用能力，请选择有学习依据的测验范围。</p><button v-if="plan?.steps.length" class="learning-link" @click="tab='tasks'">查看教师确认的学习任务 →</button></div>
<template v-if="assistant">
<p>{{assistant.explanation}}</p>
<article v-for="(s,i) in assistant.steps||[]" :key="i" class="flow-row">
<h3>{{s.title}}</h3>
<p>{{s.action}}</p>
<details>
<summary>学习依据</summary>
<p>{{s.rationale}}</p>
</details>
</article>
<details>
<summary>参考来源</summary>
<p v-for="(s,i) in assistant.sources||[]" :key="i">{{s.title||s.sourceName}} · {{s.location}}</p>
</details>
</template>
</section>
</template>
</template>
<dialog ref="dialog" class="evidence-dialog flow-content-dialog" @cancel.prevent="close" aria-labelledby="student-content-title">
<header class="dialog-header">
<h2 id="student-content-title">{{card?.name||(material?materialTitle(material):'学习内容')}}</h2>
<button autofocus @click="close">关闭</button>
</header>
<div class="dialog-body">
<p v-if="contentLoading" role="status">正在读取学习内容…</p>
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
<summary>已审核来源</summary>
<blockquote v-for="s in card.sources" :key="s.id">
<p>{{s.title}} · {{s.location}}</p>
<p>{{s.excerpt}}</p>
</blockquote>
</details>
</template>
<MaterialContent v-if="material" :material="material" :show-teacher-reference="false"/>
</div>
</dialog>
</main>
</div>
</div>
</template>
<style scoped>
.assistant-picks { display:flex; flex-wrap:wrap; align-items:center; gap:8px; margin:18px 0 4px; }
.assistant-picks small { color:var(--muted); font-size:12px; letter-spacing:.04em; }
.assistant-picks button { padding:7px 11px; font-size:12px; }
.assistant-picks button[aria-pressed="true"] { border-color:var(--blue); background:var(--blue-soft); color:var(--blue); }
.assistant-action { display:flex; flex-wrap:wrap; align-items:center; gap:14px; margin-top:16px; }
</style>
