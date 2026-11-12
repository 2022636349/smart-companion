<script setup lang="ts">
import { notifyError } from '../notification';
import {ref,computed,watch,onBeforeUnmount,onMounted} from 'vue';
import ChartView from '../ChartView.vue';
import ExperienceList from './ExperienceList.vue';
import UiSelect from './UiSelect.vue';
import UiCheckbox from './UiCheckbox.vue';
import {experienceRequest,type Workspace,type Row} from '../experienceApi';
import {distributionOption,classIndicatorOption,chartTheme,percentText,indicatorLabel,summaryEntries,requestGate} from '../experienceDisplay';
import {className,measuredIndicatorIds,comparisonRows,groupSummary,matrixColumnCount,matrixColumns,setChoice} from '../dashboardDisplay';
const props=defineProps<{workspace:Workspace;nodeId:string;refresh:number;motion:boolean}>();
const emit=defineEmits<{select:[row:Row]}>();
const all=ref<Row|null>(null),distribution=ref<Row|null>(null),allLoading=ref(false),distributionLoading=ref(false),allError=ref(''),distributionError=ref('');
const distributionClass=ref(''),matrixClass=ref(''),selectedClasses=ref<string[]>([]),selectedIndicators=ref<string[]>([]),columnPage=ref(1),columns=ref(4),showAbilities=ref(false);
const allGate=requestGate(),distributionGate=requestGate();let defaultScope:string|undefined;
const classIds=computed(()=>props.workspace.classes.map(c=>typeof c==='string'?c:String(c.id)));
const classOptions=computed(()=>[{value:'',label:'全部班级'},...classIds.value.map(id=>({value:id,label:className(id)}))]);
watch(classIds,(ids,old)=>{selectedClasses.value=old?selectedClasses.value.filter(id=>ids.includes(id)):ids.slice();},{immediate:true});
const comparison=computed(()=>comparisonRows(all.value?.indicatorSummaries||[],selectedClasses.value));
const distributionSummary=computed(()=>groupSummary(distribution.value?.groupSummaries||[],distributionClass.value,props.nodeId));
const comparisonIndicators=computed(()=>props.workspace.indicators.filter(i=>measured.value.includes(i.id)));
const measured=computed(()=>measuredIndicatorIds(props.workspace.indicators,all.value?.indicatorSummaries||[]));
const groups=computed(()=>[...new Set(props.workspace.indicators.map(i=>i.topicName||'学习能力'))].map(name=>({name,items:props.workspace.indicators.filter(i=>(i.topicName||'学习能力')===name)})));
const columnSet=computed(()=>matrixColumns(props.workspace.indicators,selectedIndicators.value,columnPage.value,columns.value));
const matrixFilters=computed(()=>({nodeId:props.nodeId,classId:matrixClass.value}));
const matrixElement=ref<HTMLElement>();let observer:ResizeObserver|undefined;
onMounted(()=>{observer=new ResizeObserver(entries=>{columns.value=matrixColumnCount(entries[0].contentRect.width);});if(matrixElement.value)observer.observe(matrixElement.value);});
onBeforeUnmount(()=>{allGate.next();distributionGate.next();observer?.disconnect();});
watch(()=>[columnSet.value.pages,columns.value,selectedIndicators.value.join('|')],()=>{columnPage.value=Math.min(columnPage.value,columnSet.value.pages);});
async function fetchOverview(classId:string){return experienceRequest('/overview?'+new URLSearchParams({nodeId:props.nodeId,classId}));}
async function loadAll(){const turn=allGate.next();allLoading.value=true;allError.value='';all.value=null;if(!distributionClass.value){distributionGate.next();distribution.value=null;distributionLoading.value=true;distributionError.value='';}
 try{const value=await fetchOverview('');if(!allGate.current(turn))return;all.value=value;if(!distributionClass.value){distribution.value=value;distributionLoading.value=false;}if(defaultScope!==props.nodeId){selectedIndicators.value=measuredIndicatorIds(props.workspace.indicators,value.indicatorSummaries||[]);columnPage.value=1;defaultScope=props.nodeId;}}
 catch(e){if(allGate.current(turn)){allError.value=(e as Error).message;notifyError(e);if(!distributionClass.value){distributionError.value=allError.value;distributionLoading.value=false;}}}finally{if(allGate.current(turn))allLoading.value=false;}}
async function loadDistribution(){const turn=distributionGate.next();distributionError.value='';distribution.value=null;if(!distributionClass.value&&all.value){distribution.value=all.value;distributionLoading.value=false;return;}distributionLoading.value=true;try{const value=await fetchOverview(distributionClass.value);if(distributionGate.current(turn))distribution.value=value;}catch(e){if(distributionGate.current(turn)){distributionError.value=(e as Error).message;notifyError(e);}}finally{if(distributionGate.current(turn))distributionLoading.value=false;}}
watch(()=>[props.nodeId,props.refresh],()=>{void loadAll();if(distributionClass.value)void loadDistribution();},{immediate:true});
watch(distributionClass,loadDistribution);
function chooseIndicator(id:string,checked:boolean){selectedIndicators.value=setChoice(selectedIndicators.value,id,checked);columnPage.value=1;}
</script>
<template>
<div class="flow-metrics"><section v-for="(value,key) in all?.counts" :key="key" class="card"><small>{{({studentCount:'学生',materialCount:'题目与资料',recordCount:'作答记录',batchCount:'批次'} as Record<string,string>)[key]||key}}</small><h2>{{value}}</h2></section></div>
<div class="dashboard-chart-grid">
<section class="card dashboard-chart" data-testid="score-distribution"><div class="section-heading"><h2>得分分布</h2><UiSelect v-model="distributionClass" label="分布班级" :options="classOptions"/></div><p class="muted">{{className(distributionClass)}} · 完整有效范围</p><p v-if="distributionLoading" role="status">正在读取分布…</p><p v-else-if="distributionError">读取失败，可重试。 <button @click="loadDistribution">重试</button></p><template v-else-if="distribution"><div class="distribution-summary"><p v-for="(g,i) in distributionSummary" :key="i">{{className(distributionClass)}} · 得分率 {{percentText(g.scorePercent)}} · 已评分 {{g.scoredCount}} 条</p></div><ChartView v-if="distribution.distribution?.length" :option="chartTheme(distributionOption(distribution.distribution),'dark')" :label="className(distributionClass)+'学生得分分布'" :height="300" :motion="motion"/><p v-else class="empty-state">当前范围暂无得分分布。</p><details><summary>整体导入质量</summary><p class="muted">此处为全部导入数据的整体质量统计，不随班级筛选变化。</p><dl class="flow-summary"><div v-for="entry in summaryEntries(distribution.quality)" :key="entry.label"><dt>{{entry.label}}</dt><dd>{{entry.value}}</dd></div></dl></details></template>
</section>
<section class="card dashboard-chart" data-testid="class-comparison"><h2>班级能力比较</h2><p class="muted">同一测验的各班完整证据，独立于分布与学生列表的筛选。</p><div class="comparison-choices"><UiCheckbox v-for="id in classIds" :key="id" :model-value="selectedClasses.includes(id)" :label="className(id)" @update:model-value="checked=>selectedClasses=setChoice(selectedClasses,id,checked)"/></div><p v-if="allLoading" role="status">正在读取班级比较…</p><p v-else-if="allError">读取失败，可重试。 <button @click="loadAll">重试</button></p><p v-else-if="!selectedClasses.length" class="empty-state">请选择至少一个班级进行比较。</p><ChartView v-else-if="comparison.length" :option="chartTheme(classIndicatorOption(comparison,comparisonIndicators,workspace.nodes,true),'dark')" label="所选班级能力得分率比较" :height="Math.max(340,comparisonIndicators.length*65+95)" :motion="motion"/><p v-else class="empty-state">当前范围暂无班级能力证据。</p>
</section>
</div>
<section ref="matrixElement" class="dashboard-matrix" data-testid="student-matrix"><div class="section-heading"><div><h2>学生能力矩阵</h2><p class="muted">每页10名学生；切换知识点列不会改变当前学生页。</p></div><div class="button-row"><UiSelect v-model="matrixClass" label="矩阵班级" :options="classOptions"/><button :aria-expanded="showAbilities" aria-controls="ability-filter-panel" @click="showAbilities=!showAbilities">筛选知识点 · 已选 {{selectedIndicators.length}} 项</button></div></div>
<section v-if="showAbilities" id="ability-filter-panel" class="card ability-filter"><div class="section-heading"><h3>选择关注的知识能力</h3><div class="button-row"><button @click="selectedIndicators=workspace.indicators.map(i=>i.id);columnPage=1">全选</button><button @click="selectedIndicators=[];columnPage=1">清空</button><button @click="showAbilities=false">关闭</button></div></div><fieldset v-for="group in groups" :key="group.name"><legend>{{group.name}}</legend><UiCheckbox v-for="i in group.items" :key="i.id" :label="indicatorLabel(i.id,workspace.indicators)+(measured.includes(i.id)?'':'（本次未测量）')" :model-value="selectedIndicators.includes(i.id)" @update:model-value="checked=>chooseIndicator(i.id,checked)"/></fieldset></section>
<nav v-if="columnSet.pages>1" class="button-row matrix-column-pages" aria-label="知识点列分页"><button :disabled="columnSet.page<=1" @click="columnPage--">上一组知识点</button><span>第 {{columnSet.page}} / {{columnSet.pages}} 组 · 已选 {{columnSet.total}} 项</span><button :disabled="columnSet.page>=columnSet.pages" @click="columnPage++">下一组知识点</button></nav>
<ExperienceList collection="students" :size="10" :filters="matrixFilters" :refresh="refresh" :matrix="columnSet.items" @select="row=>emit('select',row)"/>
</section>
</template>
