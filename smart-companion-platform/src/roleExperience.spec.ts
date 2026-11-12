import {describe,it,expect,vi,beforeEach} from 'vitest';
import {readRoute} from './routing';
import {moveStepUp,removeStepAt} from './planSteps';
import {summaryEntries,scopeText,scopeLabel,indicatorLabel,shortIndicatorLabel,classIndicatorOption} from './experienceDisplay';
import {clearExperience,experience} from './experienceApi';
describe('独立角色路由与全局步骤',()=>{
 it('restores role, page, student and scope from deep links',()=>{expect(readRoute('#/student/tasks?student=D17&scope=N02')).toEqual({role:'student',page:'tasks',studentId:'D17',nodeId:'N02'});expect(readRoute('#/teacher/plans?student=D01').page).toBe('plans');});
 it('rejects a page from the other role',()=>{expect(readRoute('#/student/data').page).toBe('learning');expect(readRoute('#/teacher/tasks').page).toBe('overview');expect(readRoute('#/unknown').role).toBe('home');});
 it('moves the first item on a later page across the page boundary without dropping steps',()=>{const steps=['a','b','c','d','e','f'];expect(moveStepUp(steps,2,0)).toBe(1);expect(steps).toEqual(['a','b','d','c','e','f']);expect(removeStepAt(steps,2,1)).toBe(2);expect(steps).toEqual(['a','b','d','c','f']);});
 it('clamps the page after deleting its final step',()=>{const steps=['a','b','c','d'];expect(removeStepAt(steps,2,0)).toBe(1);expect(steps).toEqual(['a','b','c']);});
});
describe('可读能力与图表语义',()=>{
 it('uses reviewed Chinese names and adapts unknown indicators from server labels',()=>{expect(indicatorLabel('T01I1')).not.toContain('T01I1');expect(indicatorLabel('future',[{id:'future',label:'新学科能力'}])).toBe('新学科能力');expect(shortIndicatorLabel('future',[{id:'future',label:'非常长但依旧可以在图轴上读出的能力名称'}]).length).toBeLessThanOrEqual(11);});
 it('renders readable axes and bounded numbers without losing source evidence',()=>{const base={classId:'班级甲',nodeId:'N1',indicatorId:'T01I1',studentCount:2,coveredStudentCount:1,scoredCount:2,needsPracticeCount:1,insufficientCount:0,noEvidenceCount:0,notAssessedCount:0};const chart=classIndicatorOption([{...base,scorePercent:66.666666666666}],[{id:'T01I1',label:'表示数量关系'}],[{id:'N1',description:'阶段测验'}]);expect((chart.xAxis as any).data).toEqual(['表示数量关系']);expect((chart.series as any[])[0].data[0].value).toBe(66.7);expect((chart.tooltip as any).formatter({data:(chart.series as any[])[0].data[0]})).toContain('阶段测验');});
});
describe('入口会话与迟到响应隔离',()=>{
 beforeEach(()=>{clearExperience();vi.unstubAllGlobals();});
 it('ignores a session creation response after leaving its role',async()=>{let resolve!:(x:any)=>void;vi.stubGlobal('fetch',vi.fn(()=>new Promise(r=>resolve=r)));const pending=experience.session();clearExperience();resolve({ok:true,json:async()=>({data:{token:'old-teacher'}})});await expect(pending).rejects.toThrow('页面已离开');});
 it('requests only the minimal selector before student projection',async()=>{const fetch=vi.fn().mockResolvedValue({ok:true,json:async()=>({data:{items:[],total:0}})});vi.stubGlobal('fetch',fetch);await experience.page('students',{selector:true,page:2,pageSize:20});expect(fetch.mock.calls[0][0]).toContain('selector=true');expect(fetch.mock.calls[0][0]).toContain('page=2');});
});
it('keeps the new student token when an older teacher response arrives last',async()=>{
 clearExperience();let resolveOld!:(x:any)=>void;
 const response=(data:any)=>({ok:true,json:async()=>({data})});
 const fetch=vi.fn().mockImplementationOnce(()=>new Promise(r=>resolveOld=r)).mockResolvedValueOnce(response({token:'new-session',view:'TEACHER'})).mockResolvedValueOnce(response({token:'student-session',view:'STUDENT',studentId:'D01'})).mockResolvedValueOnce(response({revision:1}));
 vi.stubGlobal('fetch',fetch);const old=experience.session();clearExperience();await experience.session();await experience.session({view:'STUDENT',studentId:'D01'});resolveOld(response({token:'old-teacher',view:'TEACHER'}));await expect(old).rejects.toThrow('页面已离开');await experience.workspace();expect(fetch.mock.calls[3][1].headers.get('X-Companion-Session')).toBe('student-session');
});

it('names measurement scope topics from server metadata and keeps a short selector name',()=>{
 const node={id:'N01',description:'七上期中测验（覆盖T01、T02）',scope:'T01,T02'};
 const indicators=[{topicId:'T01',topicName:'整式'},{topicId:'T02',topicName:'方程'}];
 expect(scopeLabel(node,indicators)).toBe('七上期中测验');expect(scopeText(node,indicators)).toContain('整式');expect(scopeText(node,indicators)).toContain('方程');expect(scopeText(node,indicators)).not.toMatch(/T0[12]/);
 expect(scopeText({id:'new',description:'新测验覆盖T99'})).not.toContain('T99');
});

it('names the progress summary measurement scope and keeps unknown scopes readable',()=>{
 const nodes=[{id:'N01',description:'七上期中测验（覆盖T01）',scope:'T01'}];const indicators=[{topicId:'T01',topicName:'整式'}];
 expect(summaryEntries({nodeId:'N01',scorePercent:66.66},{nodes,indicators})).toEqual([{label:'测验范围',value:'七上期中测验'},{label:'得分率',value:'66.7%'}]);
 expect(summaryEntries({nodeId:'unknown'},{nodes,indicators})).toEqual([{label:'测验范围',value:'当前测验范围'}]);
});
