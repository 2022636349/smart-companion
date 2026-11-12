# 知识图谱服务（C 组）

本目录是 C 组唯一正式交付目录，包含知识图谱 V2 草案、教材对齐/审核数据、FastAPI 服务、JSON/Neo4j 双仓储、导入器和测试。两位成员的第一周压缩包已去重合并；未保留 `.venv`、缓存和 IDE 文件。

## 当前已完成

- 150 个知识节点、119 条 `REQUIRES` 先修关系和 149 条由 `parent_id` 生成的 `CONTAINS` 层级关系。
- 完整文档级 `knowledge_graph.schema.json`，可校验 metadata、nodes、edges、books 和 source sections。
- `REQUIRES` 统一为“source 是 target 的前置知识点”，与冻结 API 合同一致。
- JSON 与 Neo4j Repository 对外返回相同结构，子图最大深度为 5。
- 1 个公共健康接口和 9 个内部图谱接口，包含 prerequisites、sources、version 和 Graph-RAG context。
- Neo4j 幂等导入器及 Docker Compose。
- 周期 6 增加不可变发布 manifest、D 盘快照、隔离恢复/回滚、Neo4j dry-run 保护工具、质量报告和查询性能基线。正式 `match`/`recommend-next` 的 Neo4j 候选查询有硬上限，不再无界读取整图。
- 周期 6 全量为 62 个 JSON/合同/Repository/内容/导入/运维自动测试，以及 1 个可选 Neo4j 实库测试。

周期 7 已增加隔离草稿、逐项人工决策、版权与学习事实质量门禁、不可变发布、稳定 receipt、全局激活租约及幂等回滚。最终全量为 `83 passed, 2 skipped`；默认跳过的外部项已分别通过独立 Neo4j Repository live `2/2` 和真实 DeepSeek 建图发布/回滚门禁。成功验收后 active graph 已回到正式基线 `2.4.0-c5-gc.1`。

周期 9 将图谱命名空间升级为显式 `course_id`。每门课程拥有独立草稿、候选游标、不可变发布物、active pointer、锁、lease、receipt 和缓存；课程 1 继续使用既有 Python 图谱，其他课程从确定性空版本 `0.0.0` 开始，必须经教师人工决策才能首次发布。两门课程可以使用相同 node ID 与 graph version 并独立回滚。最终离线门禁为 `90 passed, 2 skipped`；真实 JSON 后端双课程发布/回滚已由周期 9 跨服务门禁验证，Neo4j live 本周期未启动。

周期 5 最终发布物为 `2.4.0-c5-gc.1`：150 个节点、119 条 `REQUIRES`、147 个 APPROVED 节点/来源（102 个 knowledge_point + 45 个结构祖先），3 个证据不足知识点保持 PENDING。正式查询、Graph-RAG、match 和 recommend 只返回 APPROVED 内容，REJECTED/PENDING 永不泄漏。

`POST /internal/graph/match` 用于作业题、资源或导师文本的知识点候选匹配。它在 JSON 与 Neo4j 仓储使用相同的确定性评分，只返回 `APPROVED + knowledge_point`，并携带命中方式、证据和 `requires_review`；调用方仍须由教师确认绑定结果。

## 最快启动：JSON 模式

从项目根目录执行：

```powershell
Set-Location C:\Project\EducationAgent
.\scripts\start-knowledge-service.ps1
```

首次启动会在 `D:\CodexEnvs\EducationAgent\knowledge-service` 创建虚拟环境并把缓存放到 D 盘。默认使用 JSON，无需 Docker。`-AllowPendingDraft` 只供内容审核人员查看草案，学生/AI 联调不要使用。

- 健康检查：<http://localhost:8001/health>
- Swagger：<http://localhost:8001/docs>

`/internal/**` 必须同时携带：

```text
X-Internal-Token: local-development-internal-token-2026
X-Request-Id: 任意非空请求标识
```

这个令牌仅用于本机联调，部署时必须通过 `INTERNAL_SERVICE_TOKEN` 更换，并与 Spring Boot 保持一致。

除 `/health` 和 `/internal/graph/health` 外，所有图谱操作还必须携带正整数 `course_id`；服务不会把缺失课程静默映射到 Python 课程。

## Neo4j 模式

启动 Docker Desktop，然后：

```powershell
Set-Location C:\Project\EducationAgent\knowledge-service
docker compose -f .\docker-compose.neo4j.yml up -d

$env:NEO4J_URI = 'bolt://localhost:17687'
$env:NEO4J_USER = 'neo4j'
$env:NEO4J_PASSWORD = 'change-me-now'
& 'D:\CodexEnvs\EducationAgent\knowledge-service\Scripts\python.exe' -m knowledge_service.ingestion.import_neo4j --course-id 1

Set-Location C:\Project\EducationAgent
.\scripts\start-knowledge-service.ps1 -Backend neo4j
```

导入完成应显示 150 个节点和 119 条 `REQUIRES`；数据库中还会生成 149 条 `CONTAINS`。Neo4j Browser 为 <http://localhost:17474/browser/>，Bolt 地址为 `bolt://localhost:17687`。

`--clear-first` 是破坏性迁移选项，不是备份手段；日常导入不得使用。任何清理或恢复必须先创建快照，并只对名称为 `education-graph-isolated-*` 的隔离目标执行。

Neo4j 连接默认显式配置 3 秒查询超时、20 连接池、5 秒建连/取连接超时、500 个候选上限和 250ms 慢查询日志阈值。`subgraph`、`prerequisites` 和 `recommend-next` 使用逐层 direct-neighbor BFS，不执行可变长路径展开；每层只展开已受限 frontier，并同时执行单 frontier `LIMIT + 1` 和全层 `LIMIT + 1` 探针。默认累计 500 nodes、每跳 200、最终 2000 edges，context 每类 200、prerequisites 500、sources 1000；任何预算触顶都返回服务不可用，不会返回无法在冻结 DTO 中标注的部分结果，也不会回退到无界 JSON 查询。`GRAPH_BACKEND=auto` 只允许在 Neo4j Repository 初始化失败时选择 JSON；显式 `neo4j` 初始化失败原样抛出，一旦选中 Neo4j，auto/neo4j 的请求期异常都 fail-closed，绝不运行第二次 JSON 查询。所有配置还受代码绝对上限约束，可通过 `GRAPH_QUERY_TIMEOUT_SECONDS`、`GRAPH_NEO4J_POOL_SIZE`、`GRAPH_NEO4J_CONNECTION_TIMEOUT_SECONDS`、`GRAPH_QUERY_CANDIDATE_LIMIT`、`GRAPH_RECOMMEND_MAX_DEPTH`、`GRAPH_SLOW_QUERY_MS`、`GRAPH_CONTEXT_ITEM_LIMIT`、`GRAPH_PREREQUISITE_NODE_LIMIT`、`GRAPH_SOURCE_LIMIT`、`GRAPH_SUBGRAPH_NODE_LIMIT`、`GRAPH_SUBGRAPH_HOP_LIMIT`、`GRAPH_SUBGRAPH_EDGE_LIMIT` 下调；生产不得取消 timeout/limit。导入器同时维护 `KnowledgeNode.parent_id` 与 `GraphSource.node_id` 索引，支撑有界 context/source 查询。

## 质量、快照、恢复和回滚

质量报告包含版本/SHA-256、阈值、来源覆盖率、节点/边/来源 APPROVED/PENDING/REJECTED 计数、孤立、悬挂、重复、自环、REQUIRES 环和发布判定：

```powershell
Set-Location C:\Project\EducationAgent\knowledge-service
& 'D:\CodexEnvs\EducationAgent\knowledge-service\Scripts\python.exe' .\tools\graph_release.py quality .\knowledge_graph_v2.json
& 'D:\CodexEnvs\EducationAgent\knowledge-service\Scripts\python.exe' .\tools\benchmark_graph_queries.py .\knowledge_graph_v2.json
```

创建不可变快照时会先执行质量门禁，默认写入 `D:\CodexEnvs\EducationAgent\backups\graph\<graphVersion>`。同版本目录已存在时拒绝覆盖：

```powershell
& 'D:\CodexEnvs\EducationAgent\knowledge-service\Scripts\python.exe' .\tools\graph_release.py snapshot .\knowledge_graph_v2.json
& 'D:\CodexEnvs\EducationAgent\knowledge-service\Scripts\python.exe' .\tools\graph_release.py verify 'D:\CodexEnvs\EducationAgent\backups\graph\2.4.0-c5-gc.1'
```

JSON 恢复必须显式指定 D 盘、名称含 `isolated` 且尚不存在的目标；checksum、version 和质量 audit 全部先通过才复制。回滚也只接受已验证快照，并以原子 active-release 指针记录切换结果：

```powershell
& 'D:\CodexEnvs\EducationAgent\knowledge-service\Scripts\python.exe' .\tools\graph_release.py restore 'D:\CodexEnvs\EducationAgent\backups\graph\2.4.0-c5-gc.1' --isolated-target 'D:\CodexEnvs\EducationAgent\restore\isolated-cycle6-check'
& 'D:\CodexEnvs\EducationAgent\knowledge-service\Scripts\python.exe' .\tools\graph_release.py rollback 'D:\CodexEnvs\EducationAgent\backups\graph\2.4.0-c5-gc.1' --isolated-target 'D:\CodexEnvs\EducationAgent\restore\isolated-cycle6-rollback' --state-dir 'D:\CodexEnvs\EducationAgent\graph-state'
```

Neo4j dump/load 默认仅输出 dry-run 命令；实际执行必须先停掉使用同一 data volume 的 Neo4j 服务以释放 store lock，再额外传 `--execute`。工具用一次性 Compose helper 执行 `neo4j-admin`，不会把 `--clear-first` 当备份。恢复目标数据库必须命名为 `education-graph-isolated-*`，工具会先验证 dump checksum、关联 JSON 快照和质量判定，且始终使用 `--overwrite-destination=false`：

```powershell
& 'D:\CodexEnvs\EducationAgent\knowledge-service\Scripts\python.exe' .\tools\neo4j_backup_restore.py backup --release-snapshot 'D:\CodexEnvs\EducationAgent\backups\graph\2.4.0-c5-gc.1'
& 'D:\CodexEnvs\EducationAgent\knowledge-service\Scripts\python.exe' .\tools\neo4j_backup_restore.py restore --backup-dir 'D:\CodexEnvs\EducationAgent\backups\graph\neo4j\2.4.0-c5-gc.1' --target-database education-graph-isolated-cycle6
```

## 内部接口

| 方法与路径 | 作用 |
| --- | --- |
| `GET /health` | 服务进程健康，不需要内部头 |
| `GET /internal/graph/health` | 图谱仓储健康、后端和计数 |
| `GET /internal/graph/nodes/{node_id}` | 知识点详情 |
| `GET /internal/graph/search?q=...` | 知识点搜索 |
| `GET /internal/graph/context/{node_id}` | 前置、后继和父子上下文 |
| `GET /internal/graph/rag-context/{node_id}` | AI 导师的已审核事实、关系、来源和图谱版本 |
| `GET /internal/graph/prerequisites/{node_id}` | 已审核先修链 |
| `GET /internal/graph/sources/{node_id}` | 已审核来源定位 |
| `GET /internal/graph/version` | 发布版本与审核计数 |
| `GET /internal/graph/subgraph?center_id=...&depth=1` | 关系子图，深度 1–5 |

浏览器不得直接调用这些内部接口；正式链路必须由 Spring Boot 转发。

## 数据文件

| 文件 | 用途 |
| --- | --- |
| `knowledge_graph_v2.json` | 当前 V2 图谱草案和唯一运行基线 |
| `knowledge_graph.schema.json` | 整体图谱 JSON Schema |
| `books.csv` | 17 本教材台账和版权审核状态 |
| `knowledge_match_audit.csv` | 100 条人工匹配候选 |
| `prerequisite_audit.csv` | 119 条先修边审核队列 |
| `source_section_audit.csv` | 100 条高置信来源审核候选 |
| `high/medium/low_confidence.csv` | 自动对齐分层结果 |
| `data/` | 14 本教材目录抽取 JSON |

`knowledge_graph.json` 和 `knowledge_graph_with_sources.json` 是历史研究输入，不能覆盖 V2 运行基线。规范化脚本 `tools/normalize_week1_delivery.py` 可重复运行；改数据后必须重新跑测试。

## 测试

```powershell
Set-Location C:\Project\EducationAgent\knowledge-service
$env:GRAPH_BACKEND = 'json'
$env:INTERNAL_SERVICE_TOKEN = 'test-internal-token'
$env:PYTHONDONTWRITEBYTECODE = '1'
$env:PYTEST_ADDOPTS = '-o cache_dir=D:/CodexEnvs/EducationAgent/pytest-cache/knowledge-service'
& 'D:\CodexEnvs\EducationAgent\knowledge-service\Scripts\python.exe' -m pytest -q
```

周期 9 当前预期：`90 passed, 2 skipped`；默认跳过项需要显式 Neo4j 外部环境。本周期默认 JSON 运行链路已完成真实双课程发布、查询和独立回滚；课程 1 基线仍为 `2.4.0-c5-gc.1`（150/119/147 APPROVED nodes/147 sources）。完整跨组检查可运行 `scripts\check-foundation.ps1`。
