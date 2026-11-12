<script setup lang="ts">
import {notifyError} from '../notification';
import UiSelect from './UiSelect.vue';
import {computed,ref,watch,onBeforeUnmount} from 'vue';
import {experience,type Row,type Page} from '../experienceApi';
import {selectorFilters} from '../teachingPlan';
const props=withDefaults(defineProps<{modelValue:string;refresh?:number;disabled?:boolean;classes?:(string|Row)[];classId?:string;studentRequired?:boolean}>(),{studentRequired:true});
const emit=defineEmits<{'update:modelValue':[id:string];'update:classId':[id:string]}>();
const items=ref<Row[]>([]),page=ref(1),q=ref(''),search=ref(''),total=ref(0),error=ref(''),loading=ref(false);let generation=0;
const teacher=computed(()=>props.classes!==undefined);
// Vue casts an absent Boolean prop to false, so the default must be declared above: the student
// space never passes it and still has to show the roster.
const showStudents=computed(()=>props.studentRequired);
const classOptions=computed(()=>(props.classes||[]).map(c=>({value:String(typeof c==='string'?c:c.id),label:String(typeof c==='string'?c:c.name||c.id)})));
const options=computed(()=>[{value:'',label:'请选择学生'},...(props.modelValue&&!items.value.some(s=>s.id===props.modelValue)?[{value:props.modelValue,label:'当前学生 '+props.modelValue}]:[]),...items.value.map(s=>({value:String(s.id),label:s.id+' · '+s.classId+' · '+s.grade}))]);
onBeforeUnmount(()=>generation++);
async function load(){const turn=++generation;items.value=[];total.value=0;if(!showStudents.value||(teacher.value&&!props.classId)){loading.value=false;return;}loading.value=true;error.value='';try{const result:Page=await experience.page('students',selectorFilters(props.classId||'',page.value,search.value));if(turn===generation){items.value=result.items;total.value=result.total;}}catch(e){if(turn===generation){error.value=(e as Error).message;notifyError(e);}}finally{if(turn===generation)loading.value=false;}}
watch(()=>[page.value,search.value,props.refresh],load,{immediate:true});
watch(()=>[props.classId,props.studentRequired],()=>{generation++;page.value=1;q.value='';search.value='';void load();});
function find(){page.value=1;search.value=q.value.trim();void load();}
function changeClass(id:string){if(id!==props.classId){emit('update:modelValue','');emit('update:classId',id);}}
</script>
<template>
<section class="card student-picker teaching-selector" data-testid="student-picker">
<h2>{{teacher?'选择学习对象':'选择学生'}}</h2>
<div class="selector-fields">
<UiSelect v-if="teacher" :model-value="classId||''" :disabled="disabled" label="先选班级" :options="classOptions" @change="changeClass"/>
<UiSelect v-if="showStudents" :model-value="modelValue" :disabled="loading||disabled||(teacher&&!classId)" @change="emit('update:modelValue',$event)" label="再选学生" :options="options" data-testid="student-select"/>
<form v-if="showStudents" class="selector-search" @submit.prevent="find"><label>查找学生<input v-model="q" :disabled="disabled" placeholder="输入学生标识"/></label><button :disabled="disabled">查找</button></form>
</div>
<p v-if="loading" role="status">正在读取学生名单…</p>
<p v-if="error">读取失败，可重试。 <button @click="load">重试</button></p>
<p v-if="showStudents&&!loading&&!error&&!total">当前范围没有可选学生，请检查班级或搜索条件。</p>
<nav v-if="showStudents" class="button-row selector-pagination" aria-label="学生名单分页"><button :disabled="loading||disabled||page<=1" @click="page--">上一页</button><span>第 {{page}} / {{Math.max(1,Math.ceil(total/20))}} 页 · 共 {{total}} 人</span><button :disabled="loading||disabled||page*20>=total" @click="page++">下一页</button></nav>
</section>
</template>
<style scoped>
.teaching-selector h2{font-size:15px;margin-bottom:12px}.selector-fields{display:grid;grid-template-columns:repeat(auto-fit,minmax(180px,1fr));gap:16px;align-items:end}.selector-search{display:flex;gap:8px;align-items:end;margin:0}.selector-search label{flex:1;min-width:0;margin:0}.selector-pagination{margin-top:12px;font-size:12px}.selector-pagination button{min-height:32px;padding:5px 12px}@media(max-width:550px){.selector-fields{grid-template-columns:1fr}.teaching-selector{padding:16px}}
</style>
