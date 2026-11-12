# AI service scoped instructions

修改本目录前必须阅读根 `AGENTS.md`、`docs/agent-context/PROJECT_TRUTH.md` 和 `docs/agent-context/workstreams/ai-service.md`。

- 当前先实现DeepSeek生成Provider，后期添加Ollama，不把厂商调用写进路由。
- Graph-RAG只查询C服务的已审核图谱；不接收、切分或向量化教材。
- ChromaDB只索引伴学记忆，且每次查询强制学生隔离。
- 不直接访问业务MySQL；不保存或公布最终业务结论。
- 新依赖、虚拟环境、缓存和模型必须位于D盘。
- 完成任务后更新 `docs/agent-context/handoffs/ai-service.md`。
