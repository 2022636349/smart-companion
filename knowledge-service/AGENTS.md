# Knowledge graph scoped instructions

修改本目录前必须阅读根 `AGENTS.md`、`PROJECT_TRUTH.md`，并根据身份选择 `graph-content.md` 或 `graph-service.md`。

- GC 内容智能体和 GS 服务智能体不得同时修改同一文件。
- GC 负责事实、来源、关系和审核，不修改 `knowledge_service/app/**`。
- GS 负责API/Repository/导入/测试，不自行把PENDING内容改成APPROVED。
- 教材仅在本工作流离线使用，不向AI服务或ChromaDB输出chunk。
- 正式接口只返回APPROVED内容；演示开关不得成为生产默认值。
- 分别更新 `handoffs/graph-content.md` 或 `handoffs/graph-service.md`。
