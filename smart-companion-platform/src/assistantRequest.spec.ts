import { describe, expect, it } from 'vitest';
import { assistantInput, assistantObservations } from './assistantRequest';
import type { StudentDetail } from './experienceApi';
function detail():StudentDetail{return {revision:43,student:{id:'D18'},observations:[
 {studentId:'D18',nodeId:'N01',indicatorId:'ready',status:'READY'},
 {studentId:'D18',nodeId:'N01',indicatorId:'unmeasured',status:'NOT_ASSESSED'},
 {studentId:'D18',nodeId:'N01',indicatorId:'weak',status:'NEEDS_PRACTICE'},
 {studentId:'D18',nodeId:'N02',indicatorId:'otherScope',status:'NEEDS_PRACTICE'},
 {studentId:'D19',nodeId:'N01',indicatorId:'otherStudent',status:'NEEDS_PRACTICE'}
 ]} as StudentDetail;}
describe('助手请求与当前学习证据一致',()=>{
 it('优先真实范围的薄弱能力，未测量置后且不改变原诊断顺序',()=>{const d=detail();expect(assistantObservations(d,'D18','N01').map(o=>o.indicatorId)).toEqual(['weak','ready','unmeasured']);expect(d.observations[0].indicatorId).toBe('ready');});
 it('使用学生详情的数据版本，提交必填具体能力',()=>{expect(assistantInput(detail(),'D18','N01','weak','PLAN')).toEqual({studentId:'D18',nodeId:'N01',indicatorId:'weak',expectedRevision:43,purpose:'PLAN'});});
 it('拒绝空当前范围能力和其他学生、测验的观察',()=>{for(const [sid,nid,iid] of [['D18','N01',''],['D19','N01','weak'],['D18','N01','otherScope'],['D18','N01','otherStudent'],['D18','','weak']])expect(()=>assistantInput(detail(),sid,nid,iid,'EXPLAIN')).toThrow('请先选择');});
 it('无学生或观察时没有可生成选项',()=>{expect(assistantObservations(null,'D18','N01')).toEqual([]);const d=detail();d.observations=[];expect(()=>assistantInput(d,'D18','N01','weak','HINT')).toThrow();});
 it('拒绝无效版本与未知目的',()=>{const d=detail();d.revision=NaN;expect(()=>assistantInput(d,'D18','N01','weak','PLAN')).toThrow();expect(()=>assistantInput(detail(),'D18','N01','weak','other')).toThrow('目的');});
});
