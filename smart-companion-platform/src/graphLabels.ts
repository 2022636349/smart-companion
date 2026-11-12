export const graphLabels: Record<string,string> = {
  CONTAINS:'包含',PREREQUISITE:'先修于',REQUIRES:'先修于',RELATED:'相关联',SUPPORTED_BY:'由来源支持',
  CREATED:'等待抽取',EXTRACTING:'模型正在抽取',REVIEWING:'模型正在复核',VALIDATING:'校验候选内容',WAITING_HUMAN_REVIEW:'等待人工审核',READY_TO_PUBLISH:'可发布',PUBLISHING:'正在发布',PUBLISHED:'已发布',FAILED:'处理失败',CANCELLED:'已取消',PARTIALLY_COMPLETED:'部分完成，待核对',
  PENDING:'待审核',APPROVED:'人工已批准',REJECTED:'人工已拒绝',FAST_TRACK:'建议优先核对',STANDARD:'常规复核',REJECT_RECOMMENDED:'模型建议拒绝',ALLOWED:'允许使用',RESTRICTED:'使用受限',
  DRAFT:'教师复核草稿',SUCCEEDED:'模型生成成功',INSUFFICIENT:'证据不足',NO_EVIDENCE:'暂无证据',NEEDS_PRACTICE:'需要练习',READY:'表现达标',CHECK_REQUIRED:'需要核查',INFO:'提示',WARNING:'警告',ERROR:'错误',course:'课程',module:'模块',chapter:'章节',section:'小节',knowledge_point:'知识点'
};
export const graphLabel = (value: string) => graphLabels[value] || value;
