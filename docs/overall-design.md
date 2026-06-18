# Claude Agent SDK 代码生成 Web 服务总设计方案 v1

## 1. 摘要

本项目要构建一个面向用户的代码生成 Web 服务。用户在前端提交需求，后端创建异步任务，Worker 在隔离工作区内调用 Claude Agent SDK 或 mock adapter 生成代码，前端实时展示任务进度、工具调用、文件结果、审批请求和下载入口。

MVP 先验证一条完整主链路：

```text
用户提交需求
  -> API 创建异步 job
  -> Worker 创建隔离 workspace
  -> Claude adapter 生成文件
  -> 事件实时推送到前端
  -> 产物打包为 zip
  -> 用户预览并下载
```

核心设计原则：不要把 Claude Agent SDK 当成无状态 HTTP API。每个生成任务都必须拥有独立的 `cwd`、`CLAUDE_CONFIG_DIR`、进程状态、事件记录和产物目录。

## 2. 目标与非目标

### 目标

- 用户可以通过 Web UI 创建代码生成任务。
- 每个任务通过队列异步执行，不阻塞 HTTP 请求。
- 每个任务有独立 workspace 和独立 `CLAUDE_CONFIG_DIR`。
- 前端通过 SSE 实时查看任务进度。
- 事件必须持久化，刷新页面后可以恢复进度。
- 生成结果包含文件树、代码预览、摘要、运行说明和 zip 下载。
- MVP 默认使用 `MockClaudeAdapter`，没有 Claude 凭证也能完整跑通。
- 真实 Claude Agent SDK 通过 adapter 接入，不影响 API 和 Worker 主流程。
- 支持最小审批链路，用于危险操作或澄清请求。
- 默认安全策略必须限制工具、预算、turn 数、路径访问和产物打包范围。

### 非目标

- MVP 不做用户登录、计费、配额和团队权限。
- MVP 不自动部署、不 push Git、不修改用户真实仓库。
- MVP 不承诺生产级多租户沙箱隔离。
- MVP 不做长会话恢复和 `SessionStore`。
- MVP 不允许无审批 shell 执行。
- 禁止使用 `bypassPermissions`。

## 3. 总体架构

```text
Frontend
  |  POST /api/jobs
  |  GET /api/jobs/{id}
  |  GET /api/jobs/{id}/events
  |  POST /api/jobs/{id}/approval
  |  POST /api/jobs/{id}/cancel
  |  GET /api/jobs/{id}/download
  v
FastAPI API Server
  | 写入 job / event / approval / artifact
  | 投递任务到队列
  v
Postgres <-> Redis Queue / PubSub
  ^                 |
  | 写入事件         | 拉取任务
  |                 v
Worker Process
  | 创建 workspace
  | 调用 MockClaudeAdapter 或 ClaudeAgentSDKAdapter
  | 写入生成文件
  | 打包 zip
  v
Local Storage / Future Object Storage
```

### 组件职责

- 前端：需求输入、模式选择、任务状态、事件时间线、审批弹窗、文件树、代码预览、下载 zip、历史任务。
- API Server：负责参数校验、任务创建、状态查询、SSE 推送、审批回调、取消任务、下载产物。
- Queue：Redis + arq，负责异步任务投递、Worker 执行和事件通知。
- Database：Postgres，保存任务元数据、事件历史、产物记录和审批记录。
- Worker：创建隔离工作区，调用 Claude adapter，写入文件，记录事件，打包产物。
- Claude Adapter：隔离真实 SDK 和 mock 实现，避免业务层直接依赖 SDK 细节。
- Storage：MVP 使用本地 `storage/`，后续可替换为 S3、R2、MinIO 或其他对象存储。
- Sandbox：MVP 使用独立 workspace + 容器 Worker 边界；生产建议升级到 gVisor、Firecracker 或托管沙箱。

## 4. 技术选型

- 后端运行时：Python 3.10+
- API 框架：FastAPI
- 请求和响应校验：Pydantic
- ORM：SQLAlchemy
- 数据库迁移：Alembic
- 数据库：Postgres
- 队列和 Worker：Redis + arq
- 实时反馈：SSE
- 前端：React + Vite + TypeScript
- 测试：pytest、httpx、pytest-asyncio
- 本地依赖：Docker Compose 启动 Postgres 和 Redis

Pydantic 负责 API 合同和数据校验，SQLAlchemy 负责数据库持久化。Service 层负责把 Pydantic 入参转换成 SQLAlchemy 数据操作，并组织业务状态流转。

## 5. 核心数据模型

### Job 状态

```text
queued
running
approval_needed
completed
failed
canceled
```

### 事件类型

```text
queued
running
tool_call
token
file_written
approval_needed
completed
failed
canceled
```

### `jobs`

保存任务生命周期和用户输入。

- `id`
- `user_id`
- `status`
- `prompt`
- `language`
- `framework`
- `mode`
- `constraints`
- `enable_tests`
- `workspace_path`
- `artifact_path`
- `cost_estimate`
- `error`
- `cancel_requested`
- `created_at`
- `updated_at`

MVP 可以让 `user_id` 为空，但字段应保留，方便后续加鉴权和多用户隔离。

### `job_events`

保存所有可回放事件。

- `id`
- `job_id`
- `sequence`
- `type`
- `payload`
- `created_at`

事件必须先写入 Postgres，再发布到 Redis。这样前端刷新或 SSE 重连时不会丢进度。

### `job_artifacts`

保存最终产物元数据。

- `id`
- `job_id`
- `file_tree`
- `summary`
- `run_instructions`
- `tests`
- `warnings`
- `zip_path`
- `created_at`

### `approvals`

保存审批请求和用户决策。

- `id`
- `job_id`
- `kind`
- `message`
- `status`
- `decision`
- `created_at`
- `decided_at`

MVP 审批可以先做最小闭环：Worker 发出 `approval_needed`，前端展示审批，用户批准或拒绝，Worker 观察到结果后恢复或终止。

## 6. API 设计

### `POST /api/jobs`

创建生成任务。

请求示例：

```json
{
  "prompt": "Build a small todo app",
  "language": "typescript",
  "framework": "react",
  "mode": "generate",
  "constraints": "Use local state only",
  "enable_tests": true
}
```

响应示例：

```json
{
  "job_id": "job_123",
  "status": "queued"
}
```

行为：

- 校验 prompt 长度和必填字段。
- 创建 `jobs` 记录。
- 写入 `queued` 事件。
- 投递 Redis 队列。

### `GET /api/jobs/{job_id}`

查询任务状态、摘要、产物、错误和成本估算。

### `GET /api/jobs/{job_id}/events`

通过 SSE 推送任务事件。

行为：

- 先按 `sequence` 回放 `job_events` 中已有事件。
- 再持续推送 Redis 中的新事件。
- 任务进入 `completed`、`failed` 或 `canceled` 后结束流。

### `POST /api/jobs/{job_id}/approval`

提交审批决策。

请求示例：

```json
{
  "approval_id": "approval_123",
  "decision": "approved",
  "comment": "Allow the mock test step"
}
```

行为：

- 更新 `approvals`。
- 写入审批事件。
- Worker 轮询或订阅到结果后继续执行或终止。

### `POST /api/jobs/{job_id}/cancel`

请求取消任务。

行为：

- 设置 `cancel_requested=true`。
- 写入取消事件。
- Worker 在主要步骤之间检查取消标记并安全退出。

### `GET /api/jobs/{job_id}/download`

下载生成代码 zip。

行为：

- 仅允许 `completed` 且存在 artifact 的任务下载。
- zip 只能包含当前 job workspace 内的文件。
- 排除 `.env`、`.ssh`、`.npmrc`、`.git` 等敏感文件。

## 7. Worker 设计

### 执行流程

```text
拉取 job
  -> 标记 running
  -> 创建 workspace
  -> 创建独立 CLAUDE_CONFIG_DIR
  -> 选择 Claude adapter
  -> 流式写入 adapter 事件
  -> 写入生成文件
  -> 校验结构化输出
  -> 打包 zip
  -> 写入 artifact
  -> 标记 completed
```

失败流程：

```text
捕获异常
  -> 写入 failed 事件
  -> 标记 job failed
```

取消流程：

```text
检测 cancel_requested
  -> 停止 adapter 或跳过后续步骤
  -> 写入 canceled 事件
  -> 标记 job canceled
```

### Claude Adapter 接口

Worker 不直接耦合 Claude Agent SDK，而是调用统一接口。

```python
class ClaudeAdapter:
    async def run(self, request: GenerationRequest) -> AsyncIterator[GenerationEvent]:
        ...
```

`MockClaudeAdapter`：

- 默认启用。
- 产生稳定的 token、tool_call、file_written、completed 事件。
- 写出一个小型示例项目。
- 用于本地开发、CI 和端到端验收。

`ClaudeAgentSDKAdapter`：

- 仅在配置开启时使用。
- 使用独立 `cwd` 和 `CLAUDE_CONFIG_DIR`。
- 设置 `max_turns`、`max_budget_usd`、`allowed_tools`、`disallowed_tools`。
- 设置 `permission_mode="dontAsk"`。
- 使用结构化 `output_format` 约束最终结果。

MVP 默认工具白名单：

```text
Read
Write
Edit
Glob
Grep
```

shell 命令、测试命令、安装依赖等操作默认产生审批请求，不直接执行。

## 8. 前端设计

前端是操作控制台，不是营销落地页。

### 核心页面

- 任务创建区：输入 prompt、language、framework、mode、constraints、enable tests。
- 任务详情区：展示状态、摘要、错误、成本估算占位。
- 事件时间线：展示 SSE 实时事件和历史事件。
- 审批面板：展示待审批项，支持批准或拒绝。
- 文件树：展示生成文件结构。
- 代码预览：展示选中文件内容。
- 产物操作：下载 zip。
- 历史任务：展示最近任务。

### 体验要求

- 首屏直接可创建任务。
- 页面刷新后能恢复历史事件。
- 完成任务后清晰展示运行说明和下载入口。
- 失败任务展示最后关键事件和错误信息。
- 取消任务后停止事件流，但仍可查看历史。

## 9. 存储与产物

MVP 使用本地目录：

```text
storage/
  artifacts/
    {job_id}/
      output.zip
      manifest.json
workspaces/
  {job_id}/
    generated files
  {job_id}-claude-config/
```

产物规则：

- 只打包 `workspaces/{job_id}` 下的文件。
- 排除 `.env`、`.ssh`、`.npmrc`、`.git`、secret 类文件。
- 所有路径必须 normalize，拒绝路径穿越。
- 生成 `manifest.json`，包含文件树、摘要、警告和运行说明。

## 10. 安全设计

MVP 不等于生产级隔离，但必须守住最关键边界。

### MVP 必做控制

- 用户 prompt、生成代码、workspace 文件都视为不可信。
- 每个 job 独立 workspace。
- 每个 job 独立 `CLAUDE_CONFIG_DIR`。
- Worker workspace 不挂载服务端 `.env`、SSH key、云凭证、包管理凭证。
- 禁止 `bypassPermissions`。
- shell 命令必须审批后才可执行。
- 防止路径穿越。
- 防止 job 之间互相读取 workspace。
- 每个工具调用、审批、错误和产物路径都要有事件或记录。

### 生产前补充

- 用户鉴权和授权。
- 速率限制、预算限制、配额限制。
- gVisor、Firecracker 或托管沙箱。
- Egress Proxy 限制 Worker 出网。
- OpenTelemetry 追踪。
- 审计日志长期留存。
- Prompt injection 和供应链风险测试。

## 11. 测试计划

### 单元测试

- Pydantic 请求校验。
- Job 状态流转。
- Event sequence 生成。
- Artifact manifest 生成。
- zip 打包和敏感文件排除。
- Approval 状态流转。
- Cancel 标记行为。
- Claude adapter 输出 schema 校验。

### 集成测试

- `POST /api/jobs -> Worker -> completed -> download zip` 完整链路。
- SSE 能回放连接前事件。
- SSE 能收到连接后新事件。
- Worker 异常后 job 变为 `failed` 并记录错误。
- mock 审批流程可以暂停和恢复。
- cancel 请求可以停止 Worker 并记录取消状态。

### 安全测试

- workspace 路径穿越失败。
- zip 不包含 `.env`、`.ssh`、`.npmrc` 或 workspace 外文件。
- 一个 job 不能读取另一个 job 的 workspace。
- 未审批 shell 命令不会执行。
- 空 prompt 和超长 prompt 被拒绝。

### 验收标准

- 用户能在前端提交 prompt。
- job 从 `queued` 进入 `running`，最终进入 `completed`。
- 前端能看到实时事件。
- 生成文件能以文件树和代码预览展示。
- zip 可以下载。
- 刷新页面后不丢历史事件。
- 没有 Claude 凭证时仍可通过 `MockClaudeAdapter` 跑通全链路。

## 12. 建议目录结构

```text
agentsloop/
  backend/
    app/
      main.py
      config.py
      api/
        jobs.py
        events.py
      core/
        job_state.py
        paths.py
        security.py
      db/
        models.py
        session.py
        migrations/
      schemas/
        jobs.py
        events.py
        artifacts.py
      services/
        jobs.py
        events.py
        artifacts.py
        approvals.py
        zipper.py
      worker/
        queue.py
        runner.py
        workspace.py
      claude/
        base.py
        mock_adapter.py
        sdk_adapter.py
      storage/
        base.py
        local_storage.py
    tests/
      unit/
      integration/
      security/
    pyproject.toml
  frontend/
    src/
      api/
      components/
      pages/
    package.json
  docs/
    overall-design.md
  storage/
  workspaces/
  docker-compose.yml
  README.md
```

## 13. 实施阶段

### Phase 1：后端骨架

- 创建 FastAPI 项目。
- 添加配置、健康检查、数据库连接、Redis 连接。
- 添加 Docker Compose 启动 Postgres 和 Redis。
- 添加 pytest 基线。

### Phase 2：Job 生命周期

- 添加 `jobs`、`job_events`、`job_artifacts`、`approvals` 模型。
- 实现创建任务 API。
- 实现事件写入服务。
- 实现任务状态查询 API。

### Phase 3：Worker 和 mock 生成

- 添加 arq Worker。
- 添加 workspace 服务。
- 添加 `MockClaudeAdapter`。
- 生成稳定示例文件。
- 打包 zip 并写入 artifact。

### Phase 4：事件流、下载、审批、取消

- 实现 SSE endpoint 和事件回放。
- 实现下载 endpoint。
- 实现取消 endpoint。
- 实现最小审批 endpoint。

### Phase 5：前端控制台

- 创建任务表单。
- 事件时间线。
- 任务详情。
- 文件树和代码预览。
- 审批 UI。
- 下载入口。

### Phase 6：真实 SDK 接入

- 添加可选 `ClaudeAgentSDKAdapter`。
- 通过环境变量选择 adapter。
- 设置安全默认值：工具白名单、预算、turn 限制、权限模式、结构化输出。
- 保留 mock adapter 作为本地开发和测试默认值。

## 14. 关键风险

- SDK 生命周期风险：如果把 Claude Agent SDK 当无状态 API，会导致 HTTP 长时间阻塞、子进程失控、cwd 状态丢失。
- 隔离风险：一旦真实 agent 能读写文件，弱 workspace 边界可能泄露服务端密钥或其他任务文件。
- 事件一致性风险：如果事件只放 Redis pub/sub，刷新和重连会丢进度。
- 范围膨胀风险：过早加入登录、计费、部署、Git 集成和生产沙箱，会拖慢 MVP 主链路验证。

## 15. MVP 默认决策

- 默认使用 `MockClaudeAdapter`。
- 默认使用本地 storage。
- 使用 Redis + arq 做队列。
- 使用 Postgres 保存任务元数据和事件历史。
- 第一版使用 SSE，不使用 WebSocket。
- `user_id` 字段保留但允许为空。
- shell 命令未审批不执行。
- 不支持修改已有仓库。
- 不启用 `bypassPermissions`。
