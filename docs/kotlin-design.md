# Kotlin 版 Claude Agent SDK 代码生成 Web 服务设计方案 v1

## 1. 调研结论

如果用 Kotlin 实现，本项目不建议强行把 Claude Agent SDK 重写成 Kotlin/JVM 版本。更稳的方案是：

```text
Kotlin 主服务
  -> 负责 API、校验、数据库、队列、SSE、审批、产物下载
Python 或 TypeScript Claude Worker Sidecar
  -> 负责真正调用 Claude Agent SDK
```

原因：

- Claude Agent SDK 官方可编程 SDK 是 Python 和 TypeScript；官方文档说明 SDK 提供 Python/TypeScript 用法，并通过 `query()` 启动 agent loop。
- Agent SDK 托管模型不是无状态 API。它会启动 `claude` CLI 子进程，并绑定工作目录、本地 session 文件和进程状态。
- Kotlin/JVM 可以很适合做长任务 Web 后台、队列消费、SSE 和 Postgres 持久化，但不应该在 MVP 阶段自行复刻 SDK 的子进程协议和消息模型。

因此 Kotlin 版推荐采用 **Ktor + kotlinx.serialization + Exposed + Flyway + Redis Streams + Lettuce + Python/TypeScript SDK Sidecar Worker**。

## 2. 总体架构

```text
Frontend
  | REST + SSE
  v
Kotlin API Server (Ktor)
  | validate request
  | create job
  | write Postgres records
  | XADD job command
  v
Postgres <-> Redis Streams
  ^              |
  | event replay | XREADGROUP
  |              v
Kotlin Worker Orchestrator
  | create workspace
  | create CLAUDE_CONFIG_DIR
  | spawn sidecar container/process
  | collect sidecar events
  v
Claude SDK Sidecar (Python or TypeScript)
  | calls Claude Agent SDK
  | writes generated files
  | streams structured events
  v
workspace + storage artifacts
```

### 核心原则

- Kotlin 主服务不直接调用 Claude Agent SDK。
- 每个 job 独立 workspace。
- 每个 job 独立 `CLAUDE_CONFIG_DIR`。
- job command 走 Redis Stream。
- job event 先落 Postgres，再发布到 Redis。
- SSE 从 Postgres 回放历史，再接 Redis 新事件。
- MVP 默认提供 `MockAgentRunner`，没有 Claude 凭证也能完整跑通。

## 3. 技术选型

### 后端主服务

- Kotlin 2.x
- Ktor 3.x
- kotlinx.serialization
- Ktor ContentNegotiation
- Ktor SSE
- Ktor StatusPages
- Exposed
- Flyway
- PostgreSQL
- Redis Streams
- Lettuce
- kotlinx.coroutines
- Gradle Kotlin DSL

### Worker

MVP 推荐两个层级：

- Kotlin Worker Orchestrator：消费 Redis Streams，管理 job 状态、workspace、事件、取消和审批。
- Claude SDK Sidecar：Python 或 TypeScript 小进程，真正调用 Claude Agent SDK。

Sidecar 优先级：

- 第一选择：Python sidecar，因为原始设计已基于 Python SDK，结构化输出可用 Pydantic 生成 JSON Schema。
- 第二选择：TypeScript sidecar，因为 TypeScript SDK 官方支持，并且 Zod 生成 JSON Schema 方便。

### 前端

- React + Vite + TypeScript
- 继续沿用原方案，不必因为后端换 Kotlin 而调整。

## 4. Kotlin 对应原 Python 设计的替代关系

| 原 Python 设计 | Kotlin 设计 |
|---|---|
| FastAPI | Ktor |
| Pydantic request/response schema | kotlinx.serialization data class + 手动/轻量 validator |
| SQLAlchemy ORM | Exposed DSL/DAO |
| Alembic migration | Flyway |
| arq/RQ/Celery | Redis Streams + Kotlin Worker |
| Python async generator | Kotlin Flow / Channel |
| FastAPI SSE | Ktor SSE plugin |
| Python Claude SDK | Python sidecar 或 TypeScript sidecar |

## 5. 模块设计

```text
agentsloop/
  backend-kotlin/
    build.gradle.kts
    settings.gradle.kts
    src/main/kotlin/com/agentsloop/
      App.kt
      config/
        AppConfig.kt
      api/
        JobRoutes.kt
        EventRoutes.kt
        ApprovalRoutes.kt
      domain/
        Job.kt
        JobEvent.kt
        Artifact.kt
        Approval.kt
        JobStatus.kt
      dto/
        JobDtos.kt
        EventDtos.kt
        ApprovalDtos.kt
      db/
        DatabaseFactory.kt
        tables/
          JobsTable.kt
          JobEventsTable.kt
          JobArtifactsTable.kt
          ApprovalsTable.kt
        repositories/
          JobRepository.kt
          EventRepository.kt
          ArtifactRepository.kt
          ApprovalRepository.kt
      services/
        JobService.kt
        EventService.kt
        ApprovalService.kt
        ArtifactService.kt
        ZipService.kt
      queue/
        RedisClientFactory.kt
        JobQueue.kt
        EventBus.kt
      worker/
        WorkerMain.kt
        JobWorker.kt
        WorkspaceService.kt
        AgentRunner.kt
        MockAgentRunner.kt
        SidecarAgentRunner.kt
      security/
        PathGuard.kt
        ArtifactFilter.kt
        PromptPolicy.kt
      storage/
        LocalStorage.kt
    src/main/resources/db/migration/
      V1__create_jobs.sql
      V2__create_job_events.sql
      V3__create_artifacts_and_approvals.sql
    src/test/kotlin/
      unit/
      integration/
      security/
  sidecars/
    claude-python/
      main.py
      pyproject.toml
    claude-typescript/
      src/main.ts
      package.json
  frontend/
  docs/
```

## 6. API 设计

API 与原设计保持一致，避免前端和产品语义变化。

### `POST /api/jobs`

创建任务。

Kotlin DTO：

```kotlin
@Serializable
data class CreateJobRequest(
    val prompt: String,
    val language: String? = null,
    val framework: String? = null,
    val mode: JobMode = JobMode.Generate,
    val constraints: String? = null,
    val enableTests: Boolean = false,
)
```

行为：

- 校验 prompt 非空和最大长度。
- 创建 `jobs` 记录，状态为 `queued`。
- 写入 `queued` event。
- 向 Redis Stream `jobs:commands` 写入 job command。

### `GET /api/jobs/{jobId}`

查询任务状态、摘要、错误、产物路径和成本估算。

### `GET /api/jobs/{jobId}/events`

Ktor SSE endpoint。

行为：

- 先从 Postgres 读取 `job_events` 并按 `sequence` 回放。
- 再订阅 Redis event stream 或本地 event bridge。
- 任务终态后发送最终事件并关闭连接。

### `POST /api/jobs/{jobId}/approval`

写入审批结果。

行为：

- 更新 `approvals`。
- 写入 `approval_decided` event。
- Worker 或 sidecar 通过数据库/Redis 观察审批结果。

### `POST /api/jobs/{jobId}/cancel`

请求取消任务。

行为：

- 设置 `cancel_requested=true`。
- 写入取消事件。
- Worker 检查到后停止 sidecar 进程或标记停止。

### `GET /api/jobs/{jobId}/download`

下载 zip。

行为：

- 只允许 `completed` job 下载。
- zip 必须位于 artifact storage 下。
- 路径必须通过 `PathGuard` 校验。

## 7. 数据模型

### `jobs`

- `id UUID primary key`
- `user_id UUID null`
- `status varchar not null`
- `prompt text not null`
- `language varchar null`
- `framework varchar null`
- `mode varchar not null`
- `constraints text null`
- `enable_tests boolean not null default false`
- `workspace_path text not null`
- `claude_config_path text not null`
- `artifact_path text null`
- `cost_estimate numeric null`
- `error text null`
- `cancel_requested boolean not null default false`
- `created_at timestamptz not null`
- `updated_at timestamptz not null`

### `job_events`

- `id UUID primary key`
- `job_id UUID not null references jobs(id)`
- `sequence bigint not null`
- `type varchar not null`
- `payload jsonb not null`
- `created_at timestamptz not null`

约束：

- `(job_id, sequence)` 唯一。

### `job_artifacts`

- `id UUID primary key`
- `job_id UUID not null references jobs(id)`
- `file_tree jsonb not null`
- `summary text null`
- `run_instructions text null`
- `tests jsonb null`
- `warnings jsonb null`
- `zip_path text not null`
- `created_at timestamptz not null`

### `approvals`

- `id UUID primary key`
- `job_id UUID not null references jobs(id)`
- `kind varchar not null`
- `message text not null`
- `status varchar not null`
- `decision varchar null`
- `created_at timestamptz not null`
- `decided_at timestamptz null`

## 8. 队列与事件设计

### Job Command Stream

Redis Stream：

```text
jobs:commands
```

消息字段：

```json
{
  "job_id": "...",
  "command": "run"
}
```

Worker 使用 consumer group：

```text
group: codegen-workers
consumer: worker-{instance-id}
```

处理规则：

- API 使用 `XADD jobs:commands * job_id ... command run`。
- Worker 使用 `XREADGROUP GROUP codegen-workers worker-1 BLOCK 2000 COUNT 10 STREAMS jobs:commands >`。
- 成功处理后 `XACK`。
- Worker 启动时先处理 pending entries，再处理新消息。

### Event Stream

事件以 Postgres 为事实来源。

写入顺序：

```text
insert job_events
  -> publish lightweight notification to Redis
  -> SSE route pulls event detail from Postgres or payload
```

这样可以避免 Redis pub/sub 丢事件导致页面刷新后进度缺失。

## 9. Worker 与 Sidecar 设计

### Kotlin Worker Orchestrator

职责：

- 消费 Redis job command。
- 获取 job 配置。
- 创建 workspace。
- 创建 `CLAUDE_CONFIG_DIR`。
- 启动 `MockAgentRunner` 或 `SidecarAgentRunner`。
- 接收 agent event。
- 写入 job event。
- 生成 artifact manifest。
- 打包 zip。
- 更新 job 状态。

### AgentRunner 接口

```kotlin
interface AgentRunner {
    fun run(request: AgentRunRequest): Flow<AgentEvent>
    suspend fun cancel(jobId: UUID)
}
```

### MockAgentRunner

MVP 默认实现：

- 不需要外部 API key。
- 生成稳定的示例项目。
- 产生 `running`、`token`、`tool_call`、`file_written`、`completed` 事件。
- 可模拟 `approval_needed` 和失败。

### SidecarAgentRunner

调用 Python/TypeScript sidecar。

推荐协议：

```text
Kotlin -> sidecar stdin: JSON line request
sidecar -> Kotlin stdout: JSON line event stream
sidecar -> workspace: generated files
```

请求示例：

```json
{
  "job_id": "...",
  "prompt": "...",
  "cwd": "/workspaces/job-id",
  "claude_config_dir": "/workspaces/job-id-claude-config",
  "allowed_tools": ["Read", "Write", "Edit", "Glob", "Grep"],
  "disallowed_tools": ["Bash"],
  "permission_mode": "dontAsk",
  "max_turns": 20,
  "max_budget_usd": 2.0,
  "output_schema": {}
}
```

事件示例：

```json
{"type":"running","payload":{}}
{"type":"token","payload":{"text":"Creating files..."}}
{"type":"file_written","payload":{"path":"src/App.tsx"}}
{"type":"completed","payload":{"summary":"Generated React app"}}
```

Sidecar 退出码：

- `0`：成功。
- `10`：需要审批但未批准。
- `20`：结构化输出失败。
- `30`：SDK 执行失败。
- `130`：取消。

## 10. Claude Agent SDK 接入策略

由于官方 SDK 当前是 Python/TypeScript，Kotlin 方案中真实 SDK 接入放在 sidecar 内。

Sidecar 必须设置：

- `cwd`
- `CLAUDE_CONFIG_DIR`
- `allowed_tools`
- `disallowed_tools`
- `permission_mode="dontAsk"`
- `max_turns`
- `max_budget_usd`
- `output_format`

默认工具：

```text
allowed_tools = Read, Write, Edit, Glob, Grep
disallowed_tools = Bash
```

如果未来要允许测试命令：

- 不要把 `Bash` 放入默认 allow。
- 先产生 `approval_needed`。
- 用户审批后只允许白名单命令，例如 `npm test`、`pytest`。
- 审批记录必须写入 `approvals` 和 `job_events`。

## 11. 安全设计

### MVP 必做

- prompt、生成代码、workspace 文件都视为不可信。
- 每个 job 独立 workspace。
- 每个 job 独立 `CLAUDE_CONFIG_DIR`。
- Worker 不挂载服务端 `.env`、SSH key、云凭证、`.npmrc`。
- 禁止 `bypassPermissions`。
- 禁止默认 Bash。
- zip 打包前路径必须 normalize。
- zip 只能包含当前 job workspace 内文件。
- 排除 `.env`、`.ssh`、`.npmrc`、`.git`、secret 类文件。
- job 之间不能互相读取 workspace。

### 生产前加强

- Sidecar 容器化。
- 每个 job 一个临时容器或强隔离沙箱。
- gVisor 或 Firecracker。
- Egress Proxy，只允许访问 Anthropic API 和必要包源。
- API key 由代理注入，不进入 workspace。
- OpenTelemetry 和审计日志。

## 12. 测试计划

### 单元测试

- DTO 校验。
- Job 状态机。
- Redis command 序列化。
- Event sequence 生成。
- PathGuard 路径穿越拦截。
- ArtifactFilter 敏感文件排除。
- Approval 状态转换。
- ZipService 打包逻辑。

### 集成测试

- Ktor `testApplication` 测 API route。
- Testcontainers 启动 Postgres 和 Redis。
- `POST /api/jobs -> Redis Stream -> Worker -> completed -> download zip`。
- SSE 先回放历史事件，再接收新事件。
- Worker failure 写入 `failed`。
- Cancel 停止 Worker。
- Mock approval flow 暂停和恢复。

### Sidecar 契约测试

- Kotlin 向 sidecar 发送 JSON request。
- sidecar 输出 JSONL event。
- 非法 JSON event 被标记为 failed。
- sidecar 超时被终止。
- sidecar 退出码映射正确。

### 安全测试

- 不能读取其他 job workspace。
- zip 不包含敏感文件。
- `../` 路径写入被拒绝。
- 未审批 Bash 不执行。
- 空 prompt 和超长 prompt 被拒绝。

## 13. 实施阶段

### Phase 1：Kotlin 后端骨架

- 创建 `backend-kotlin` Gradle 项目。
- 添加 Ktor、serialization、StatusPages、SSE。
- 添加 health endpoint。
- 添加 Flyway 和 Postgres 连接。
- 添加 Redis Lettuce 连接。

### Phase 2：数据库和 API

- 创建 Flyway migrations。
- 实现 Exposed table 和 repository。
- 实现 `POST /api/jobs`。
- 实现 `GET /api/jobs/{jobId}`。
- 实现 event 写入服务。

### Phase 3：Redis Streams 和 Worker

- 实现 `JobQueue`。
- 实现 `JobWorker`。
- 实现 `MockAgentRunner`。
- 跑通 job 从 queued 到 completed。

### Phase 4：SSE、下载、审批、取消

- 实现 `GET /events`。
- 实现 zip 打包和下载。
- 实现 approval endpoint。
- 实现 cancel endpoint。

### Phase 5：Sidecar SDK 接入

- 新增 Python sidecar。
- 定义 JSONL 协议。
- Kotlin Worker 通过进程或容器启动 sidecar。
- sidecar 调用 Claude Agent SDK。
- 保留 mock 作为默认。

### Phase 6：前端接入

- 沿用 React/Vite 控制台。
- 对接 Kotlin API。
- 展示 SSE 事件、文件树、代码预览、审批和下载。

## 14. 关键风险

- 官方没有 Kotlin Claude Agent SDK，硬接 CLI 协议会增加维护风险。
- Exposed JDBC transaction 是同步阻塞的，Ktor coroutine 中要用专门 dispatcher 或评估 Exposed R2DBC。
- Redis pub/sub 不是事件事实来源，必须用 Postgres 保存 `job_events`。
- Sidecar 进程如果不限制超时、cwd 和环境变量，会成为安全边界薄弱点。
- 真实 SDK 会启动长期子进程，Worker 必须能取消、超时、清理 workspace 和记录失败。

## 15. 推荐默认决策

- Kotlin 负责主服务和 Worker 编排。
- Claude SDK 执行放到 Python sidecar。
- MVP 默认使用 `MockAgentRunner`。
- 真实 SDK 通过环境变量开启。
- Redis Streams 做 job queue。
- Postgres 保存 job 和事件历史。
- SSE 做实时事件。
- Flyway 做 migrations。
- Exposed 做数据库访问。
- 不默认启用 Bash。
- 不启用 `bypassPermissions`。
