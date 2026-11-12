# 智学引擎 AI 服务（AI 工作流）

本目录负责模型Provider、Graph-RAG智能体、memory-only ChromaDB、AI批改与代码沙箱。
知识图谱已经迁移到根目录的 `knowledge-service/`，这里不再提供任何
`/internal/graph/**` 接口。浏览器不得直连本服务，所有调用由 Spring Boot 编排。

DeepSeek 与 Ollama 均通过同一 `ModelProvider` 生成接口，使用 `AI_MODEL_PROVIDER=deepseek|ollama` 切换且既有业务 DTO 不变。教材和教学资源不在本服务切分或向量化；导师按 `knowledgePointId` 调用knowledge-service取得已审核Graph-RAG上下文。ChromaDB唯一允许的集合是 `learning_memory`，embedding只处理伴学记忆。

当前跨服务实现以冻结的 `education-internal-api-v2` `2.8.1` 为准。

动画支持独立的 OpenAI-compatible API，使用 `TUTOR_ANIMATION_*` 环境变量；不影响默认 DeepSeek/Ollama 的其他能力。见 [动画模型配置、检查与重启](../docs/TUTOR_ANIMATION_MODEL.md)。

当前已经实现：

- `GET /health`
- `GET /internal/models/health`：DeepSeek/embedding/Chroma 配置健康；未配置生成模型时不发起外网请求
- `POST /internal/ai/tutor/chat`：按 Spring 权威传入的 `course_id` 查询已审核 Graph-RAG，按课程启用的活动能力调用 `ModelProvider`，校验结构化内容和引用后回显课程
- Tutor 支持显式 `FIXED|ROUTER|SHADOW`：Router 最多执行 2 个白名单只读图谱/记忆工具，服务端注入学生范围；失败时明确回退 FIXED，响应只保留脱敏执行摘要，不保存思维链、原始观察或记忆正文
- `POST /internal/ai/graph/extract-review`：从 Spring 活动任务精确绑定的文件瞬时提取文本，经独立 Extractor 与 Reviewer 逐项复核节点、关系和来源证据，返回与 knowledge-service 完全一致的 `PENDING` 草稿、真实模型身份和版本化 Prompt SHA-256
- `DeepSeekProvider`：超时、有限重试、取消、401/429/5xx 和 JSON 损坏的类型化处理
- `POST /internal/ai/assessment/parse`：从 Spring 受控下载 TXT/Markdown、DOCX 或含文本 PDF，在内存中临时提取文字，经 `ModelProvider` 生成五类题目草稿并做题型语义校验
- `POST /internal/ai/subjective/suggest-grade`：严格按 rubric 输出逐项证据、置信度、建议分和教师复核警告
- `POST /internal/ai/attachment/grade`：只从 Spring 活动任务授权的同源 URL 下载最多 5 份附件，在隔离子进程中做有界内存提取后给出建议
- 每道解析题目再按测评 `course_id` 调用 knowledge-service `POST /internal/graph/match`（`scene=QUESTION`），只保留本课程的 `APPROVED` 知识点；图谱不可用时保留题目、候选置空并明确警告
- `POST /internal/code/run`：由 Docker Linux 容器执行 Python 3.11；主 FastAPI 进程不执行学生代码，公开测试返回脱敏结果，隐藏测试只返回序号、状态、分数和资源用量
- `POST /internal/ai/code/explain-result`：仅依据结构化状态生成确定性教学提示，不调用模型，也不回显源码、异常正文或测试数据
- `POST /internal/memory/upsert|search` 与 `DELETE /internal/memory/{id}`：使用独立 `EmbeddingProvider` 和唯一 `learning_memory` Chroma 集合；检索强制同时按学生与课程过滤并逐条复核，执行版本/哈希/同步代次校验、可逆 DEINDEX 屏障、终态 tombstone 与可重建持久化；旧无课程元数据的索引会轮换 epoch 并要求从 MySQL 重放
- `POST /internal/ai/profile/summarize`：最多 50 条、单条 4,000 字符的学习证据生成严格摘要、认知标签、记忆候选和资源类型建议
- Tutor 在 `personalization_enabled=true` 时仅检索同一学生、当前知识点的记忆；索引不可用时保留 Graph-RAG 并返回空 `memory_ids_used`

周期 7 建图不会复用伴学记忆 Chroma：教材/教学资源只在一次请求的内存与隔离提取 worker 中存在，Provider 不接收 job/file/download URL 或内部令牌，响应只保留短证据与行定位。Extractor 输出必须由 Reviewer 对每个 NODE、EDGE、SOURCE 恰好复核一次；任何伪造摘录、候选引用漂移、未覆盖审查、非法关系或非结构化输出都会 fail-closed。所有候选保持 `review_status/decision_status=PENDING`，最终人工决策与不可变发布仍由 Spring 和 knowledge-service 负责。

Router 工具固定为 `graph.rag_context`、`graph.search`、`graph.prerequisites`、`graph.recommend_next` 和 `memory.search`。图谱结果在客户端 DTO 和工具边界两次检查 `APPROVED`；课程范围与推荐/记忆的学生范围只能由 Spring 请求注入，模型提供 `course_id`、`student_id` 或 `user_id` 会被拒绝，记忆响应和每条 hit 还会再次核对学生与课程。单工具和全链路分别受超时限制；即使第二步规划超时，也会保留第一步已经发生的脱敏调用摘要并显式 FIXED fallback。

导师生成的 DeepSeek 单请求/Provider 总期限默认分别为 60/110 秒；最多两次结构修正共享 `TUTOR_GENERATION_TOTAL_TIMEOUT_SECONDS=110` 的答案总期限，不会把两次 Provider 预算简单相加。Router 仍在答案生成前执行并受独立 30 秒上限约束，工具不会因答案修正而重跑。Spring、SSE 和浏览器默认期限依次为 150/155/160 秒。

Ollama 生成使用 `/api/chat`，只允许 `OLLAMA_ALLOWED_HOSTS` 明确列出的地址，并且客户端禁用环境代理。每次模型调用向 Spring 的 `/api/internal/ai/model-invocations` 最佳努力上报不含正文或身份的摘要；上报失败绝不改变 AI 主响应。`MODEL_*MICROUNITS_PER_MILLION` 和 `MODEL_PRICING_VERSION` 是部署定价配置。AI 评分和画像输出仅是建议；最终逐题分、整卷成绩、记忆正文和公布始终由 Spring 与教师决定。

所有 `/internal/**` 均要求 `X-Internal-Token` 和 `X-Request-Id`。本地启动脚本默认使用与 B/C 相同的开发令牌；部署时必须替换。

启动：

```powershell
Set-Location C:\Project\EducationAgent
.\scripts\start-ai-service.ps1
```

真实 DeepSeek 生成需要仅在本机环境设置：

```powershell
$env:DEEPSEEK_API_KEY = '你的密钥'
$env:DEEPSEEK_CHAT_MODEL = 'deepseek-chat'
.\scripts\start-ai-service.ps1
```

密钥可保存在本机已忽略的 `ai-service/.env`，由 `scripts/start-all-services.ps1` 仅加载到子进程环境；不得输出、写入日志或提交。没有密钥时服务仍可启动并展示降级健康状态，导师请求会类型化失败，不会伪造来源或回答。

伴学记忆默认关闭。启用本地 Ollama embedding 时设置
`MEMORY_EMBEDDING_PROVIDER=ollama`、`MEMORY_EMBEDDING_MODEL=<本地模型>`，并把
`CHROMA_PERSIST_DIR` 保持在 D 盘。embedding 只接收伴学记忆正文和查询，不接收教材、
教学资源、用户 ID 或身份字段。Chroma 只保存可由 MySQL ACTIVE 记录重放的索引；删除
必须显式携带同步 `generation`：`DEINDEX` 写入可由更高代次 ACTIVE 重建的屏障，
`TERMINAL_DELETE` 写入任何后续 UPSERT 都不可复活的终态墓碑。索引损坏会保留隔离
副本并重建空索引等待 Outbox 重放。索引状态还持久记录 embedding provider/model；
本地 embedding 配置变化、旧状态格式或无法证明身份的旧索引都会换 epoch、清空派生
向量并要求 Spring 从 MySQL 全量重放，避免不同向量空间静默混用。

DeepSeek 超时与重试通过环境变量配置：`DEEPSEEK_TIMEOUT_SECONDS` 是单次 HTTP
操作超时（默认 30 秒），`DEEPSEEK_TOTAL_TIMEOUT_SECONDS` 是一次 `generate`
包含所有重试和退避的总截止时间（默认 50 秒，最大 60 秒），
`DEEPSEEK_MAX_RETRIES` 默认 2。总截止时间到达时统一返回类型化模型超时。

测评解析只允许从 `BUSINESS_SERVICE_INTERNAL_BASE_URL` 同源的
`/api/files/{source_file_id}/content` 下载，携带共享内部令牌和请求 ID，且禁止重定向。
默认文件上限 50 MiB、提取文本上限 60,000 字符、PDF 上限 200 页；旧式 `.doc`、
加密文件、纯扫描且没有可提取文字的 PDF 会显式失败。解析全链路总截止时间默认 75 秒、
硬上限 80 秒，Spring 调用方 read timeout 应至少为 85 秒（当前建议 90 秒）。
TXT/Markdown、DOCX 与 PDF 都在最多 2 个并发的一次性隔离子进程中提取；请求取消或
总超时会终止并回收对应进程，进程槽位只在操作系统确认回收后释放。文件与提取文本不会
写入本服务数据库、ChromaDB 或持久化 chunk；模型候选中的知识点 ID
会被丢弃并由图谱匹配结果替换。编程运行配置和附件提交策略由 Spring 注入安全默认值，
随后必须由教师确认或修改。

主观/附件建议评分不使用图谱、教材 RAG、ChromaDB 或 embedding。请求只向生成
Provider 披露作答材料、可选参考答案和评分点，不发送答卷 ID、请求 ID、下载 URL、
内部令牌或身份字段。模型输出需通过逐项标签唯一性、rubric 上限、两位精度、逐项合计、
证据确实来自学生材料及总分上限复核；任何异常均 fail-closed 为 422/429/502/503/504，
人工批改仍可继续。附件只允许从配置的 Spring 同源 `/api/files/{id}/content` 下载，
携带同一个内部令牌和请求 ID，禁止重定向；单件默认 20 MiB、合计 50 MiB、提取文字
合计 60,000 字符、PDF 100 页且全链路默认 75 秒。TXT/Markdown、DOCX 和带文本层
PDF 以及作为纯文本读取但绝不执行的 `.py` 可评分；PNG/JPG 等图片（当前无受控 OCR）、
ZIP（当前不安全展开）、旧 DOC、压缩炸弹、加密或纯扫描文件会类型化失败并保留人工批改，
不静默忽略、不落盘、不建立索引。

代码沙箱默认使用 `SANDBOX_PYTHON_IMAGE=python:3.11.15-alpine3.23`。镜像必须由部署者
提前离线准备或在明确授权后拉取；服务调用固定使用 `--pull=never`，不存在镜像、Docker
未运行或强隔离能力不可用时返回 `503 SANDBOX_UNAVAILABLE`，绝不回退到 Windows 本机
Python。容器固定使用 `--network=none`、只读根文件系统、非 root 用户、移除 capabilities、
`no-new-privileges`、禁用 IPC、PID/内存/swap/CPU/临时文件限制。每个请求使用唯一命名
容器；取消或超时会终止客户端并执行 `docker rm --force`。Docker 管理命令单次最多等待
3 秒，短暂卡顿会在 15 秒总清理预算内重试；只有连续确认精确容器名不存在后才释放容量槽。
队列过载、运行时不可用和总超时分别返回 429、503、504。内部请求必须声明
`execution_mode=PUBLIC_RUN|GRADING`；两种模式各自最多运行 1 个容器（总上限仍为 2），
公开试跑不能占用正式判题的保留槽位。Docker 运行时/镜像探测也在对应容量槽内执行，
并发请求不能绕过容量限制制造额外探测进程。当前仓库不携带镜像。

首次准备镜像时，把 Docker Desktop 数据目录配置在 D 盘，并在明确允许联网后单独执行：

```powershell
& 'D:\DevTools\DockerDesktop\resources\bin\docker.exe' pull python:3.11.15-alpine3.23
```

当前开发机已将 Docker 数据目录放在
`D:\CodexEnvs\EducationAgent\docker-desktop-data`，并预置镜像
`python:3.11.15-alpine3.23`（digest
`sha256:f73754c398b259dfbbe482361dca8b464dea57da74efe5214966ca2ee767ee12`）。
真实 Docker 门禁已覆盖正常公开/隐藏、错答、语法/运行时错误、断网、只读根、子进程、
输出/时间/内存限制、并发过载及取消/创建竞态，门禁结束无 `education-sandbox-*` 容器残留。

接口文档：`http://localhost:8000/docs`

测试：

```powershell
Set-Location C:\Project\EducationAgent\ai-service
& 'D:\CodexEnvs\EducationAgent\ai-service\Scripts\python.exe' -m pytest -q
```

周期 7 最终基线：`208 passed, 6 skipped, 43 subtests passed`；6 个 skip 是默认测试命令下需显式启用的 Docker live 用例，独立门禁已实际运行并 `6/6 passed`。隔离集成门已使用真实 DeepSeek 完成图谱 Extractor/Reviewer 与 Tutor Router；真实 Ollama 生成未执行，仅作为可选部署验证保留。

真实 Docker 门禁默认跳过，需要显式启用：

```powershell
$env:RUN_SANDBOX_LIVE_TESTS='1'
$env:SANDBOX_DOCKER_COMMAND='D:\DevTools\DockerDesktop\resources\bin\docker.exe'
$env:SANDBOX_PYTHON_IMAGE='python:3.11.15-alpine3.23'
& 'D:\CodexEnvs\EducationAgent\ai-service\Scripts\python.exe' -m pytest -q -p no:cacheprovider tests/test_sandbox_live.py
```
