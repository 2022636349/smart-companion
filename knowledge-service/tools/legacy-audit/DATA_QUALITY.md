# 知识图谱数据质量基线

> 历史说明：本文记录第一周原始输入的审计结论，不是当前 V2 发布状态。当前事实以 `knowledge_graph_v2.json`、`content-progress.json` 和 `docs/agent-context/handoffs/graph-content.md` 为准。

## 当前可用部分

- 实际节点数：150。
- 实际显式先修边：119。
- 1 个根节点、8 个一级节点、36 个二级节点、105 个三级节点。
- 所有非根节点的 `parent_id` 均能找到父节点。
- 当前没有悬空边、自环、重复边和有向先修环。

## 当前不能直接用于正式引用的部分

- JSON 元数据中的节点数和边数已过期，仍写为 119/112。
- `knowledge_graph_with_sources.json` 只有 47 个节点含来源，覆盖率约 31.3%。
- 现有来源匹配出现大量明显语义错误，`high_confidence.csv` 不能作为人工审核结论。
- 现有来源缺少页码、相似度、审核人和审核状态。
- 节点只有 ID、名称、层级和父节点，缺少描述、别名、难度、目标、常见误区和示例。

## C 的第一阶段规则

1. 不把现有 `sources` 返回给学生端或 AI 导师作为可靠引用。
2. 先建立 100～200 条人工标注金标准。
3. 使用适合中文的模型，并用章节、节、小节完整上下文重新匹配。
4. 每条结果输出 Top-K 候选，人工确认后才进入正式图谱。
5. Neo4j 替换 JSON 仓储时保持 FastAPI `/internal/graph/**` 响应结构稳定。

## 运行结构审计

```powershell
Set-Location C:\Project\EducationAgent\knowledge-service
& 'D:\CodexEnvs\EducationAgent\knowledge-service\Scripts\python.exe' .\tools\audit_knowledge_graph.py .\knowledge_graph.json
& 'D:\CodexEnvs\EducationAgent\knowledge-service\Scripts\python.exe' .\tools\audit_knowledge_graph.py .\knowledge_graph_with_sources.json
```

脚本只读，不会修改数据。结构错误返回非零退出码；元数据过期和来源覆盖不足会作为警告输出。

