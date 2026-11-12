import {describe,it,expect} from 'vitest';
import {className,measuredIndicatorIds,comparisonRows,groupSummary,matrixColumnCount,matrixColumns,setChoice} from './dashboardDisplay';
import {distributionOption,classIndicatorOption,chartTheme,uiFontFamily} from './experienceDisplay';
describe('独立班级范围与测量语义',()=>{
 const indicators=[{id:'algebra',label:'整式运算'},{id:'equation',label:'方程求解'},{id:'inequality',label:'不等式求解'}];
 const rows=[{classId:'DC01',indicatorId:'algebra',studentCount:20,notAssessedCount:0},{classId:'DC02',indicatorId:'equation',studentCount:20,notAssessedCount:1},{classId:'DC01',indicatorId:'inequality',studentCount:20,notAssessedCount:20}];
 it('defaults to abilities actually measured without treating unmeasured as failed',()=>{expect(measuredIndicatorIds(indicators,rows)).toEqual(['algebra','equation']);});
 it('keeps comparison scope independent of distribution and allows no classes',()=>{const whole=[...rows,{classId:'ALL',indicatorId:'algebra',studentCount:40,notAssessedCount:0}];expect(comparisonRows(whole,['DC01','DC02'])).toHaveLength(3);expect(comparisonRows(whole,[])).toEqual([]);const distribution=groupSummary([{classId:'ALL',nodeId:'N01'},{classId:'DC01',nodeId:'N01'},{classId:'ALL',nodeId:'N02'}],'','N01');expect(distribution).toEqual([{classId:'ALL',nodeId:'N01'}]);expect(whole).toHaveLength(4);expect(className('')).toBe('全部班级');expect(className('DC01')).toBe('DC01 班');});
 it('does not mix multiple ALL measurement groups into an all-scope summary',()=>{expect(groupSummary([{classId:'ALL',nodeId:'N01'},{classId:'ALL',nodeId:'N02'}],'','')).toEqual([]);});
});
describe('知识点列分页独立于服务器学生分页',()=>{
 const all=Array.from({length:9},(_,index)=>({id:String(index),label:'能力'+index}));
 it('fits two chosen knowledge columns on a narrow panel in catalogue order',()=>{const selection=['6','1'];const result=matrixColumns(all,selection,1,matrixColumnCount(310));expect(result.items.map(i=>i.id)).toEqual(['1','6']);expect(result.pages).toBe(1);expect(result.total).toBe(2);});
 it('preserves selection across column pages and narrows without inventing a student slice',()=>{const selected=all.map(i=>i.id);expect(matrixColumns(all,selected,2,4).items.map(i=>i.id)).toEqual(['4','5','6','7']);expect(selected).toHaveLength(9);expect(matrixColumnCount(700)).toBe(4);expect(matrixColumnCount(1200)).toBe(6);expect(matrixColumns(all,[],4,2)).toEqual({items:[],pages:1,page:1,total:0});expect(setChoice(['a'],'a',false)).toEqual([]);});
});
it('draws readable horizontal abilities while retaining missing values and full tooltip evidence',()=>{
 const base={classId:'DC01',nodeId:'N01',studentCount:20,coveredStudentCount:10,scoredCount:12,needsPracticeCount:2,insufficientCount:3,noEvidenceCount:7,notAssessedCount:0};
 const option=classIndicatorOption([{...base,indicatorId:'A',scorePercent:null},{...base,indicatorId:'B',scorePercent:66.666}],[{id:'A',label:'完整能力甲名称'},{id:'B',label:'完整能力乙名称'}],[],true);
 expect((option.xAxis as any).type).toBe('value');expect((option.yAxis as any).data).toEqual(['完整能力甲名称','完整能力乙名称']);expect((option.series as any[])[0].data[0].value).toBeNull();expect((option.series as any[])[0].data[1].value).toBe(66.7);expect((chartTheme(option,'dark').textStyle as any).fontFamily).toBe(uiFontFamily);
});

it('keeps every distribution band labelled including long ranges and unscored learners',()=>{
 const labels=['低于60%','60%–84%','85%及以上','暂无评分'];const option=distributionOption(labels.map((label,index)=>({label,count:index+1})));const axis=option.xAxis as any;
 expect(axis.data).toEqual(labels);expect(axis.axisLabel.interval).toBe(0);expect(axis.axisLabel.formatter('60%–84%')).toContain('60%');expect(axis.axisLabel.formatter('暂无评分')).toBe('暂无评分');
});
