# Kotlin 外部 Loop 系统 MVP 记录

## 1. 背景

目标是实现一个外部 Loop 工程系统，用 Kotlin 作为主编排层，把 Claude Code、Codex、Hermes、Embabel 等 agent runtime 纳入统一的任务看板、事件协议、审批和产物审查流程。

这个系统不直接替代 Claude Code、Codex 或 Embabel。它负责“工程调度”和“人类可控闭环”，具体 agent 执行能力通过 adapter 接入。

核心链路：

```text
Claude Code Planner
  -> tasks.json / hook event
  -> Loop Ingestor
  -> Loop Orchestrator
  -> Kanban Board
  -> Worker Adapter
  -> Review Gate
```

## 2. 调研结论

- Kotlin/JVM 适合做长期运行的外部编排层，负责任务状态机、事件持久化、审批、SSE/HTTP API、worker 进程管理和看板接口。
- Claude Code 更适合做前期 Planner，产出 `requirements.md`、`design.md`、`tasks.json`，并通过 hook 输出结构化事件。
- Codex 更适合作为 coding/review/test worker，通过 CLI 或 SDK 接入，由外部 Loop 系统翻译事件、管理 workspace 和审查产物。
- Hermes/Minions 证明了 agent Kanban、review queue、human-in-the-loop 的产品形态可行，但第一版不建议直接依赖 Hermes-only 的看板实现。
- Embabel 可以兼容，但更适合放在 `WorkerAdapter` 后面，承担 Planner、Reviewer、Researcher、Policy agent 等内部智能流程，不建议替代外部 Loop Orchestrator。

Embabel 兼容要点：

- Embabel 是 JVM agent framework，核心是 Actions、Goals、Conditions、Domain Model 和动态规划。
- 已确认 `com.embabel.agent:embabel-agent-starter:0.3.4` 可从 Maven Central 获取。
- 官方 Kotlin template 使用 Java 21、Kotlin 2.1.10、Spring Boot 和 Embabel 0.3.4。
- 当前 MVP 核心可先保持 Java 17/Kotlin stdlib 或轻量 Ktor；真实 Embabel adapter 后续单独模块化，并要求 Java 21。

## 3. MVP 边界

MVP 做：

- 导入 Claude Code Planner 产出的 `tasks.json`。
- 把任务转成看板卡片。
- 支持固定列：`Backlog`、`Ready`、`Running`、`Blocked`、`Review`、`Done`、`Failed`。
- 人类确认 plan 后，任务才允许从 `Backlog` 进入 `Ready`。
- 每张任务卡拥有独立 workspace。
- Worker 完成任务后进入 `Review`，不能自动 `Done`。
- 统一事件协议，支持 hook/spool ingest。
- 支持 Mock worker 跑通完整闭环。
- 预留 Codex、Claude Code、Hermes、Embabel worker adapter。

MVP 不做：

- 不自动 merge 到主分支。
- 不自动部署。
- 不默认执行危险 shell。
- 不直接把 Embabel/Spring Boot 放进核心状态机。
- 不做完整 React 前端，先用 CLI 或轻量 HTTP board 验证链路。
- 不引入复杂多容器 swarm。

## 4. 核心模型

### Project

- `id`
- `title`
- `requirements`
- `design`
- `planConfirmed`
- `taskIds`
- `createdAt`
- `updatedAt`

### Task

- `id`
- `projectId`
- `title`
- `role`
- `description`
- `status`
- `dependencies`
- `workspace`
- `assignedWorker`
- `latestEvent`
- `artifactIds`
- `approvalIds`
- `reviewStatus`
- `acceptanceCriteria`
- `createdAt`
- `updatedAt`

### Event

- `id`
- `projectId`
- `taskId`
- `type`
- `sequence`
- `timestamp`
- `role`
- `agentId`
- `payload`

事件类型至少包括：

- `task-created`
- `task-started`
- `agent-message`
- `tool-call-start`
- `tool-call-end`
- `file-written`
- `approval-needed`
- `artifact-created`
- `review-requested`
- `task-completed`
- `task-failed`

### Approval

- `id`
- `taskId`
- `kind`
- `message`
- `status`
- `decision`
- `createdAt`
- `decidedAt`

必须审批的动作：

- `shell`
- `delete`
- `install`
- `merge`
- `deploy`

### Artifact

- `id`
- `taskId`
- `kind`
- `path`
- `summary`
- `metadata`
- `createdAt`

## 5. 推荐实现路线

第一阶段先做无外部依赖或轻依赖的 Kotlin 核心：

```text
src/main/kotlin/com/agentsloop/
  core/
  store/
  orchestrator/
  ingest/
  worker/
  cli/
  http/
```

建议优先级：

1. `core`：状态、事件、审批、artifact 数据模型。
2. `store`：本地 JSON state，默认 `.agentsloop/state.json`。
3. `orchestrator`：导入 plan、确认 plan、状态推进、依赖判断、review gate。
4. `ingest`：读取 `.agentsloop/spool/inbox/*.json`，成功移入 `processed`，失败移入 `rejected`。
5. `worker`：实现 `MockWorker`，预留 `CodexCliWorker`、`ClaudeCodeWorker`、`EmbabelWorker`。
6. `cli`：提供 `import-plan`、`confirm-plan`、`run-ready`、`board`、`approve`、`deny`、`mark-done`。
7. `http`：轻量 board，只展示列、卡片、最新事件、审批和 artifact 摘要。

## 6. WorkerAdapter 合同

统一接口：

```text
startTask(task, workspace)
streamEvents(task)
requestApproval(task, approval)
cancelTask(task)
collectArtifact(task)
```

Adapter 规则：

- Worker 不直接修改看板状态，只输出统一事件。
- Orchestrator 是唯一状态写入者。
- Worker 输出的外部事件必须带 `externalEventId`，用于去重。
- Worker 只能写自己的 workspace。
- Worker 完成后只能触发 `review-requested`，不能直接触发 `task-done`。

## 7. Planner 输入格式

`tasks.json` 示例：

```json
{
  "title": "Sample Loop Project",
  "requirements": "# Requirements\n\nBuild a tiny notes app.",
  "design": "# Design\n\nUse local storage.",
  "tasks": [
    {
      "id": "task_plan_notes_ui",
      "title": "Design notes UI",
      "role": "architect",
      "description": "Define the notes UI structure.",
      "assigned_worker": "mock",
      "acceptance_criteria": ["UI states are named"]
    }
  ]
}
```

## 8. 验收测试

MVP 完成标准：

- Mock worker 能跑通：导入 plan -> 确认 plan -> Ready -> Running -> Review -> Done。
- 依赖任务不会提前进入 Ready。
- Worker 完成后不会自动 Done。
- 危险动作会创建 pending approval。
- 重复 hook event 不会重复写入。
- 非法 hook payload 会进入 rejected。
- 每个任务有独立 workspace。
- artifact path 不能越界。

## 9. 后续升级

- 引入 Ktor + SSE，为前端提供实时事件。
- 引入 Postgres + Flyway，替代 JSON state。
- 引入 Redis Streams，支持多 worker 并发。
- 引入 Codex CLI/SDK adapter。
- 引入 Claude Code CLI/hook adapter。
- 引入 Embabel worker module，用于 Planner、Reviewer、Researcher 和 Policy agent。
- 引入 React 看板前端。
