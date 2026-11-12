<script setup lang="ts">
import {materialActionLabel} from '../materialPresentation';
import {ref,computed} from 'vue';
import type {Step,Plan} from '../experienceApi';
import type {TeachingPlan} from '../teachingPlan';
import {indicatorLabel,materialLabel} from '../experienceDisplay';
import {moveStepUp,removeStepAt} from '../planSteps';
const props=defineProps<{plan:Plan|TeachingPlan;busy:boolean;indicators:{id:string;label:string}[]}>();
const emit=defineEmits<{save:[status:string];learning:[id:string];question:[id:string]}>();
const page=ref(1),pages=computed(()=>Math.max(1,Math.ceil(props.plan.steps.length/3)));
const visible=computed(()=>props.plan.steps.slice((page.value-1)*3,page.value*3));
const status=computed(()=>props.plan.status==='CONFIRMED'?'教师已确认':props.plan.status==='NEEDS_REVIEW'?'数据已变化，待复核':'建议草稿');
function move(index:number){page.value=moveStepUp(props.plan.steps,page.value,index);}
function normalizeDuration(step:Step){if((step.durationMinutes as unknown)==='')delete step.durationMinutes;}
function remove(index:number){page.value=removeStepAt(props.plan.steps,page.value,index);}
</script>
<template>
<section class="card plan-editor">
<div class="section-heading">
<h2>我的教学方案</h2>
<span class="status-badge">{{status}}</span>
</div>
<p class="muted">这里是最终方案编辑区：已采用的建议与教师调整会合并为此方案。手动修改后请保存草稿；教师确认后，学生才会收到学习任务。</p>
<div class="button-row plan-toolbar">
<button :disabled="busy" @click="emit('save','DRAFT')">保存草稿</button>
<button class="primary" :disabled="busy" @click="emit('save','CONFIRMED')">教师确认方案</button>
</div>
<p v-if="!plan.steps.length">当前范围暂无学习步骤。</p>
<article v-for="(s,i) in visible" :key="s.id" class="flow-row">
<h3>第 {{(page-1)*3+i+1}} 步 · {{indicatorLabel(s.indicatorId,indicators)}}</h3>
<label>步骤标题<input v-model="s.title" :disabled="busy"/>
</label>
<label>学习行动<textarea v-model="s.action" :disabled="busy"/>
</label>
<label>诊断依据<textarea v-model="s.reason" :disabled="busy"/>
</label>
<label>步骤时长（分钟，可留空）<input v-model.number="s.durationMinutes" @change="normalizeDuration(s)" type="number" min="1" max="180" :disabled="busy"/></label>
<div class="button-row">
<button :disabled="busy||(page-1)*3+i===0" @click="move(i)">上移</button>
<button :disabled="busy" @click="remove(i)">移除步骤</button>
<button v-if="s.indicatorId" @click="emit('learning',s.indicatorId)">查看学习内容</button>
<button v-for="m in s.materialIds" :key="m" @click="emit('question',m)">{{materialActionLabel(m)}}</button>
</div>
<details>
<summary>原始证据引用</summary>
<p>{{s.evidenceIds.join('、')||'暂无直接证据'}}</p>
</details>
</article>
<nav v-if="pages>1" class="button-row" aria-label="方案步骤分页">
<button :disabled="busy||page<=1" @click="page--">上一组步骤</button>
<span>第 {{page}} / {{pages}} 组 · 共 {{plan.steps.length}} 步</span>
<button :disabled="busy||page>=pages" @click="page++">下一组步骤</button>
</nav>
</section>
</template>
