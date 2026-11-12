# 智学引擎 Spring Boot 业务后端

Spring Boot 3.2.6 + Java 17。本服务由 B 负责，是浏览器唯一允许访问的业务 API；A 不得直接调用 FastAPI、Neo4j、ChromaDB 或 Ollama。

权威合同：

- 人工说明：`C:\Project\EducationAgent\docs\API_CONTRACT.md`
- FE-BE 公共合同：`C:\Project\EducationAgent\docs\openapi\education-public-api-v2.yaml`
- BE-GS-AI 内部合同：`C:\Project\EducationAgent\docs\openapi\education-internal-api-v2.yaml`

## 已实现

| 方法与路径 | operationId | 状态 |
|---|---|---|
| `GET /api/system/readiness` | `getReadiness` | 已实现 |
| `GET /api/system/ai-health` | `getAiHealth` | 已实现，探测 8000/8001 |
| `POST /api/auth/register/student` | `registerStudent` | 已实现 |
| `POST /api/auth/login` | `login` | 已实现 |
| `POST /api/auth/refresh` | `refreshToken` | 已实现并轮换刷新令牌 |
| `POST /api/auth/logout` | `logout` | 已实现 |
| `GET /api/auth/me` | `getCurrentUser` | 已实现 |
| `PUT /api/auth/password` | `changePassword` | 已实现 |
| `GET /api/classes` | `listClasses` | 已实现角色/班级过滤 |
| `GET /api/classes/{classId}` | `getClass` | 已实现对象级权限 |
| `GET /api/knowledge/graph` | `getKnowledgeGraph` | 已与 C 的 8001 子图接口真实联调 |
| `GET /api/knowledge/search` | `searchKnowledge` | 已与 C 的 8001 搜索接口真实联调 |
| `POST/GET/DELETE /api/files/**` | Files | 受控上传、鉴权下载、引用保护和软删除 |
| `GET/POST/PUT/DELETE /api/resources/**` | Resources | 教学资源元数据、审核可见性和知识点绑定 |
| `GET/POST /api/tutor/sessions/**` | Tutor | 会话创建/恢复/关闭、结构化消息和已审核来源 |
| `GET/POST/PUT/DELETE /api/assessments/**` | Assessments | 教师测评列表、草稿、五类题、复制和发布不可变版本 |
| `POST /api/assessments/{id}/imports` | Assessment import | 受控源文件上传、异步 AI 候选解析和任务轮询 |
| `POST /api/assessments/{id}/publish` | Assessment publish | 幂等发布、严格图谱绑定和版本快照 |

这些接口使用统一 `ApiResponse`、`ErrorResponse`、`X-Request-Id`、JWT、BCrypt、无状态 Spring Security 和 MyBatis-Plus。其余公共合同接口仍需后续周实现。

## 数据库

- `V1__education_core.sql`：测评、提交、批改、资源、导师等首版核心表。
- `V2__platform_foundation.sql`：课程、刷新令牌、AI 任务、Outbox、审计、学习事件、长期记忆、资源 chunk、通知、反馈等地基。
- `V3__cycle_one_tutor_foundation.sql`：导师知识点快照、入口追踪、请求/模型/提示词版本和索引。
- `V4__cycle_two_assessment_authoring.sql`：测评草稿、五类题明细、AI 导入任务、发布版本与索引。
- 不得修改已经执行的迁移文件；后续从 `V5__...sql` 继续。

V1+V2+V3+V4 已在 D 盘隔离的 MySQL 8.0.39 实例依次执行通过，共 38 张业务表；加上 `flyway_schema_history` 共 39 张。验证实例已停止，没有修改本机已有 `xiaozhi` 数据库。

## 首次运行

先创建空数据库：

```powershell
& 'D:\DevTools\EducationAgent\mysql-portable\bin\mysql.exe' -u root -p -e "CREATE DATABASE IF NOT EXISTS xiaozhi CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;"
```

然后从项目根目录运行：

```powershell
Set-Location C:\Project\EducationAgent
.\scripts\start-backend.ps1 `
  -MySqlUsername root `
  -MySqlPassword '你的MySQL密码' `
  -EnableFlyway `
  -EnableDemoData
```

只有首次建库或确实要执行新迁移时才使用 `-EnableFlyway`。JWT 密钥、数据库密码和内部服务令牌应通过环境变量提供，不能提交进代码。

演示数据开关启用后会幂等准备：

| 身份 | 用户名 | 密码 |
|---|---|---|
| 管理员 | `admin_demo` | `Admin@123` |
| 教师 | `teacher_demo` | `Teacher@123` |
| 学生 | `student_demo` | `Student@123` |

同时创建“Python程序设计演示班”，并把演示学生加入班级。演示密码只能用于本机开发。

## 快速验证

就绪检查：

```powershell
Invoke-RestMethod http://localhost:8080/api/system/readiness
```

登录并查询当前用户：

```powershell
$login = Invoke-RestMethod `
  -Method Post `
  -Uri http://localhost:8080/api/auth/login `
  -ContentType 'application/json' `
  -Body '{"username":"student_demo","password":"Student@123","expectedRole":"STUDENT"}'

$token = $login.data.tokens.accessToken
Invoke-RestMethod `
  -Uri http://localhost:8080/api/auth/me `
  -Headers @{ Authorization = "Bearer $token" }
```

查询学生可见班级：

```powershell
Invoke-RestMethod `
  -Uri http://localhost:8080/api/classes `
  -Headers @{ Authorization = "Bearer $token" }
```

## API 页面

后端启动后：

- Swagger UI：`http://localhost:8080/swagger-ui/index.html`
- Knife4j：`http://localhost:8080/doc.html`
- 已实现路由 JSON：`http://localhost:8080/v3/api-docs`

页面展示的是当前代码已实现接口；完整目标仍以仓库里的公共 OpenAPI V2 为准。

## 测试

```powershell
Set-Location C:\Project\EducationAgent\business-service
& 'D:\DevTools\EducationAgent\maven-portable\bin\mvn.cmd' '-Dmaven.repo.local=D:\PackageCache\maven' clean test
```

测试使用 H2 的 MySQL 兼容模式，不依赖个人数据库，覆盖：

- 统一响应和请求 ID。
- 学生真实注册、BCrypt 哈希、登录、JWT、当前用户和刷新令牌轮换。
- 角色错误和未登录错误。
- 学生班级数据隔离和管理员接口越权。
- B 调用 C 时 `snake_case -> lowerCamelCase` 的图谱 DTO 映射。
- 文件/教学资源权限、审核可见性和引用保护。
- 上传 MIME 伪造/HTML 主动内容防护、附件响应和 `nosniff`。
- 教学资源批准期间绑定并发变化的行锁、快照和 409 防护。
- 导师成功持久化、24 小时幂等重放、关闭状态、AI 429/503 降级，以及来源双字段审核证明。

周期 1 基线：6 个测试套件、17 项测试全部通过。

## 内部服务配置

```text
KNOWLEDGE_SERVICE_URL=http://localhost:8001
AI_SERVICE_URL=http://localhost:8000
INTERNAL_SERVICE_TOKEN=双方一致的内部令牌
```

B 在内部请求中透传公共请求的 `X-Request-Id`。C/D 不可用时返回稳定的服务不可用错误，不能静默伪造业务数据。

## 周期 6 运维配置

报告、共享限流和保留期维护均以 MySQL 为事实源，不需要单机内存状态。可通过环境变量映射下列 Spring 属性；未配置时使用括号中的保守默认值：

- `education.security.trust-forwarded-for`：是否信任反向代理提供的 `X-Forwarded-For`（`false`）。只有部署在受控代理之后才能开启。
- `education.rate-limit.auth-per-minute`（30）、`feedback-per-minute`（20）、`notification-publish-per-minute`（20）、`report-per-minute`（30）、`ai-per-minute`（60）、`code-per-minute`（60）。
- `education.report.max-rows`（10000）、`max-cells`（150000）、`max-cell-chars`（4096）、`max-total-chars`（2000000）、`max-bytes`（10485760）。任一报告预算超限都会返回 422，不会截断或返回部分文件；配置本身还受 100000 行、1000000 单元格、65536 单元格字符、20000000 累计字符和 50 MiB 的绝对安全上限约束。
- `education.maintenance.scan-ms`（3600000）、`initial-delay-ms`（300000）、`lease-seconds`（120）和可选的唯一 `instance-id`。
- `education.maintenance.audit-retention-days`（365）、`telemetry-retention-days`（365）、`notification-retention-days`（365）、`terminal-retention-days`（30）；四项都必须在 1–36500 天闭区间内，非法值会使应用启动失败而不会执行清理。

限流表只保存哈希化主体和固定窗口计数，不保存请求正文、密码、refresh token 或原始 IP。保留期任务在同一事务中锁定并验证数据库租约，持有 lease 行锁覆盖全部删除及成功终态写入；`PENDING` outbox、有效 refresh token、成绩/学习证据以及仍被引用的文件不会被清理。
