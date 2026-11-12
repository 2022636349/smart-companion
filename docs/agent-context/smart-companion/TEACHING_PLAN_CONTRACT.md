# 教学方案合同 · public2.14.0 / internal2.12.0

冻结版本2026-11-12，本合同由root协调；机器合同由scripts/update_companion_teaching_contract.py生成。旧assistant/个人plans保持兼容。浏览器仅Spring/api，AI只内网ModelProvider，成绩不重判。

## 公共接口

`GET /api/companion-experience/teaching-plans?scopeType=STUDENT|CLASS&studentId=...|classId=...&nodeId=...`：仅教师。scopeType与对应subject必填；单一真实测验，STUDENT的classId可省略但若传必须匹配。返回`{revision,knowledgeVersion,plan,summary}`，revision为当前数据版本；summary为该目标全量有效记录统计与indicatorSummaries。班级不能用单一学生代替。

summary=`{studentCount,recordCount,scoredCount,earnedScore,maxScore,scorePercent,indicatorSummaries}`；其中TeachingIndicatorSummary沿ExperienceIndicatorSummary并额外必填availableQuestionCount，按本测验可用题与指标映射的不同题目数量，不能以已评分数代替；前端据此禁选无题指标。旧overview schema不改。

Plan：`{scopeType,studentId:null|string,classId:string,nodeId,revision,dataRevision,status,updatedAt:null|string,constraints,steps}`。status=DRAFT/CONFIRMED/NEEDS_REVIEW；constraints=`{objective:string(0..500),durationMinutes:int(10..180),maxSteps:int(1..12)}`，默认空目标/45分钟/6步。个人plan与旧state.plans同一事实；CLASS独立state.classPlans，用classId:nodeId定位，原快照无字段时懒建兼容。

Step沿用旧字段`id,title,action,reason,indicatorId,materialIds,evidenceIds,hints`；新增可选`durationMinutes`(1..180)、`knowledgePointIds`、`sourceIds`。数组step最多30，AI候选不超过constraints.maxSteps。人工原步骤可缺新字段；新AI步骤引用必须在目标范围/已审核来源白名单内。

材料按整份方案选定知识点的相关材料集合校验，允许一个教学步骤综合使用多个选定知识点的材料；题目仍须为当前测验可用版本，L/B仍须关联选定范围。作答证据严格按该步骤知识点校验，不因跨知识点资源组合而扩大证据或学生权限。保存与学生任务材料投影采用同一规则。

`POST /api/companion-experience/teaching-plans/generate`：仅教师，body=`{scopeType,studentId?:string,classId?:string,nodeId,indicatorIds:string[1..9],constraints,expectedRevision,expectedPlanRevision}`。expectedRevision=当前数据版本，expectedPlanRevision=目标计划修订。知识点必须属于当前学习基线且在本测验有可用题，允许已有记录不足但明示补观察。通过Spring统计、审核卡/来源后单次内部planning调用（含独立复核）。不持锁等模型，不改计划/成绩；返回前再次核数据、知识版本与计划修订。

返回Proposal=`{scopeType,studentId:null|string,classId,nodeId,dataRevision,basePlanRevision,knowledgeVersion,status:'DRAFT',provider,model,explanation,cautions:string[],constraints,steps:Step[],sources:[{id,title,location,excerpt}]}`。候选id服务端生成唯一；预览/逐条采用在前端进行，重新生成不覆盖现有编辑。AI新增步骤有durationMinutes，合计不超过约束时长。

STATE032前端采用操作改为明确调用既有PUT保存DRAFT，成功才标采用。未采用候选保留，只有本次自身成功保存才推进其basePlanRevision；外部修订/data/K变化不做静默rebase。未保存手工编辑为本页面会话缓存与离开提示，不作为MySQL业务事实；接口字段/版本不变。

`PUT /api/companion-experience/teaching-plans`：仅教师，body=`{scopeType,studentId?,classId?,nodeId,expectedRevision,expectedDataRevision,status:'DRAFT'|'CONFIRMED',constraints,steps}`，expectedRevision=计划修订。Spring事务验证身份、节点、步骤/题版本/证据与来源、时长及乐观锁，再写同一JSON事实；返回Plan，plan.revision+1，数据revision不加。总时长按有时长步骤合计，旧无时长步骤不造值。数据改变时两类计划都NEEDS_REVIEW，保留原步骤。

`ExperienceStudentDetail`新增可选`classPlan: Plan|null`：仅自己的班级/当前节点CONFIRMED且dataRevision当前的班级共同任务；学生不能读其他班或未确认class计划。个人计划仍沿旧字段，班级计划不覆写个人。

学生个人plan也仅当前CONFIRMED且dataRevision当前时投影步骤，其他状态返回同Plan形状但steps=[]；教师继续读取完整草稿。旧个人GET遵循同一门禁。个人与班级的学生反馈均须目标计划已确认且仍有效，不把保存草稿当作生效。

Feedback body新增可选`planScope:'STUDENT'|'CLASS'`默认STUDENT；CLASS按学生真实班级查当前已确认有效计划，taskId必须存在；仅记录学习反馈、不改分。export新增可选classPlans数组，不含已无有效班级/节点的孤儿项。

`data/students?selector=true&classId=...`：先班级/学生筛选再最小字段投影和分页；默认诊断列表与学生身份入口行为兼容，不能只过滤当前页。前端教师选择区用workspace.classes，进入深链学生由服务端最小单学生查询确定班级，切班清旧学生后只列新班名单。

## 内部接口

`POST /internal/ai/smart-companion/plan`，operationId=internalSmartCompanionPlan，内部头沿用安全合同。输入snake_case：

```
scope_type: STUDENT|CLASS
course_id: int>0
node_id: string
subject_id: string
graph_version: string
constraints: {objective, duration_minutes, max_steps}
summary: {student_count,record_count,scored_count,earned_score,max_score,score_percent:null|number}
focuses: [{indicator_id,name,knowledge_point_id,scored_count,available_question_count,
          covered_student_count,score_percent:null|number,
          status_counts:{needs_practice,developing,ready,insufficient,no_evidence,not_assessed},
          evidence_ids:string[0..100]}] // 1..9
knowledge_points: [{id,name,description}] //1..30，含必要待核查前置
sources: [{source_id,knowledge_point_id,title,locator,page_from:null|int,page_to:null|int,excerpt:null|string}] //<=80
materials: [{material_id,type,body,purpose}] //<=60，无参考答案/学生答案/过程/评分点
```

Provider输入剥离subject_id及任何身份，不给学生原作答/过程或按学生分行的记录；focus仅确定性统计/状态人数和可溯源ID，CLASS以班级整体/差异分组建议措辞。只有审核知识事实与来源，前置无证据只作核查，不把班级平均值推广到每个人。

输出`{provider,model,explanation,steps:[{title,action,rationale,indicator_id,duration_minutes,knowledge_point_ids,source_ids,evidence_ids,material_ids,hints}],cautions}`，step1..12且不超max_steps，ID均来自该请求白名单，至少覆盖每个选定indicator的一个步骤，duration合计<=duration_minutes。独立复核与生成均完整结构校验；不能改统计或虚构材料。只允许草稿；Provider不可用/无效输出明确503/502，不静默规则回退。

首稿先作结构与模型metadata校验，再由独立复核纠正引用、覆盖和时间预算；对外返回前的复核结果执行全部语义门禁，失败明确拒绝。内部故障日志仅记录requestId/阶段/静态类别，不记原始输入或模型正文。
