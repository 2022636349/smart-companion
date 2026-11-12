# Backend scoped instructions

修改本目录前必须阅读根 `AGENTS.md`、`docs/agent-context/PROJECT_TRUTH.md` 和 `docs/agent-context/workstreams/backend.md`。

- 本服务拥有公共API、业务状态和MySQL，不拥有图谱、ChromaDB或模型。
- 对 C/D 只使用带 `X-Internal-Token`、`X-Request-Id` 的内部HTTP调用。
- AI输出必须校验后再保存；禁止AI直接改变最终成绩或发布状态。
- 已执行 Flyway 迁移不可回写；新结构从V3开始追加。
- 完成任务后更新 `docs/agent-context/handoffs/backend.md`。
