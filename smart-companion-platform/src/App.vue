<script setup lang="ts">
import { ref, onBeforeUnmount } from 'vue';
import ErrorDialog from './components/ErrorDialog.vue';
import { clearErrors } from './notification';
import BrandMark from './components/brand/BrandMark.vue';
import BrandConstellation from './components/BrandConstellation.vue';
import TeacherWorkspace from './TeacherWorkspace.vue';
import StudentWorkspace from './StudentWorkspace.vue';
import { clearExperience } from './experienceApi';
import { readRoute } from './routing';
const role=ref(readRoute().role);
function routeChanged(){clearErrors();const next=readRoute().role;if(next!==role.value){clearExperience();role.value=next;}}
window.addEventListener('hashchange',routeChanged);
const media=window.matchMedia('(prefers-reduced-motion: reduce)'),reduced=ref(media.matches);
function motion(e:MediaQueryListEvent){reduced.value=e.matches;}
media.addEventListener('change',motion);
onBeforeUnmount(()=>{clearErrors();clearExperience();window.removeEventListener('hashchange',routeChanged);media.removeEventListener('change',motion);});
</script>
<template>
 <ErrorDialog/>
 <TeacherWorkspace v-if="role==='teacher'"/>
 <StudentWorkspace v-else-if="role==='student'"/>
 <div v-else class="login-page">
  <section class="login-intro"><BrandConstellation :enabled="!reduced"/><div class="brand"><BrandMark/></div><p class="eyebrow">以证据理解学习 · 用行动走向进步</p><h1>让每一次学习<br/>都有据可循</h1><p>从真实作答发现学习需求，让教师看清差异，让学生知道下一步。</p><div class="login-features"><span>可信学习依据</span><span>个性学习方案</span><span>清晰学习行动</span></div></section>
  <section class="card login-card entry-card"><small>智学引擎</small><h2>选择你的空间</h2><p class="muted">免账号体验，围绕三项核心任务：学习内容与要求梳理、学生学习情况分析、个性化学习建议。</p><a class="role-entry" href="#/teacher/overview"><span class="entry-number">01 / 教学</span><strong>教师工作台 <span aria-hidden="true">↗</span></strong><span>班级学情 · 学生诊断 · 学习内容与要求 · 学习建议编排 · 数据管理</span></a><a class="role-entry" href="#/student/learning"><span class="entry-number">02 / 学习</span><strong>学生学习空间 <span aria-hidden="true">↗</span></strong><span>我的学情 · 学习内容与要求 · 学习建议 · 学习任务</span></a><p class="muted">学生入口选择模拟学习身份后，仅展示该学生的学习范围；教师可查看并调整给学生的建议。</p></section>
 </div>
</template>
