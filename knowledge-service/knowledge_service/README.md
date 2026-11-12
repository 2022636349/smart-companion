# Python package

这是 `knowledge-service` 的 Python 包目录。启动、导入、质量报告、不可变快照、隔离恢复/回滚、Neo4j dry-run 保护和测试说明统一维护在上一级 `C:\Project\EducationAgent\knowledge-service\README.md`，请从服务根目录执行命令，不要从历史 `new` 目录启动。

## 周期 7 建图草稿边界

- 生产草稿使用 `Neo4jGraphDraftRepository`，只写 `GraphDraftBatch` 标签；候选永远不会获得 `KnowledgeNode` 或 `GraphSource` 标签。
- JSON 本地模式使用 D 盘 `FileGraphDraftRepository`；测试注入隔离的内存 Repository。请求不会原地修改仓库 `knowledge_graph_v2.json`。
- 发布先在草稿 Repository 原子认领批次，认领后任何人工决策都会被拒绝；再生成并校验 D 盘不可变 release，以 release 专属标签完整暂存。正式切换受 provider 侧全局激活租约保护，Neo4j 在同一事务内比较当前版本并移动正式读取标签，回滚复用同一 compare-and-activate 机制。
- 激活前失败会释放认领供读取最新 revision 后重试；激活已提交但批次最终化失败时不会反向补偿，而会保留有期限的认领/租约，相同 `request_id` 重试只补齐镜像与 `PUBLISHED` 最终化。
- 所有候选都必须有人工 `APPROVED/REJECTED` 决策；只有 `APPROVED` 候选在发布时转成正式 `APPROVED`，`PENDING/REJECTED` 不进入新版本。
- 未拒绝节点与来源的 `rights_status` 必须是 `ALLOWED`；节点的 `PENDING/RESTRICTED` 或来源的 `RESTRICTED` 会产生 `GRAPH_RIGHTS_NOT_ALLOWED` ERROR，即使管理员选择 APPROVE 也不能发布。受限来源必须提交带审核说明的 `edited_source.rights_status=ALLOWED` 完成授权确认，发布构造阶段还会再次 fail-closed 校验。
- decision 的 `request_id` 与规范化请求 SHA-256 会随批次原子保存最小回执；同请求重放成功、同 key 异载荷冲突。回执不含候选正文且每批最多 2048 条，满额后仅允许已有 key 重放，不淘汰旧回执。
- rollback 在释放激活租约前保存 provider 侧 operation receipt；同请求可精确重放原结果。每条结果只允许 4 个标量字段且规范 JSON 最多 4 KiB，长期保留以维持幂等，不存教材或候选内容。
- 生产建议配置 `GRAPH_BACKEND=neo4j`、`GRAPH_DRAFT_BACKEND=neo4j`、`GRAPH_INGESTION_ROOT=D:\CodexEnvs\EducationAgent\graph-ingestion`。`GRAPH_DRAFT_BACKEND=memory` 只有同时显式设置 `GRAPH_ALLOW_IN_MEMORY_DRAFTS=true` 才可启用。
