# 多 Agent 协作开发系统调研总结

## 1. 结论摘要

你想做的不是再造一个普通代码生成器，而是给用户多一种“合作开发方式”：把一个需求拆成不同职责的 agent 或任务单元，让它们并行推进、相互交接、让人类在关键节点审阅和拍板。

调研后可以把可借鉴系统分成五类：

| 类型 | 代表 | 核心价值 |
|---|---|---|
| Agent 看板 | Minions / Hermes、Cline Kanban | 把长任务变成可视化卡片，让人类知道谁在做、做到哪、需不需要审 |
| 远程 agent 控制台 | CCPark / AgentDock | 把本地终端 agent 变成可远程观察、控制、审批的进程 |
| 多 agent swarm | Zoink | 用声明式配置定义 PM、Coder、Researcher、DevOps 等角色，容器隔离并协作 |
| 规格驱动开发 | Kiro Specs | 先把需求、设计、任务结构化，再按依赖并行执行 |
| 全链路产品工作台 | CodeBuddy、扣子/Coze | 把需求、设计、编码、部署、知识库、工作流包成低门槛产品体验 |

对我们这个 Claude Agent SDK 代码生成 Web 服务来说，最值得借鉴的是：

- 用看板表达协作，不要只用聊天窗口。
- 每个任务卡有明确职责、状态、workspace、日志、产物和审批点。
- 允许 PM/Planner agent 拆任务，但让人类确认拆分结果。
- 允许并行，但必须用独立 workspace 或 worktree 隔离。
- 事件和产物要比原始工具日志更重要，要给用户看“可验证证据”。
- 危险操作、合并、部署、删除、shell 执行必须有人类审批。

## 2. 调研对象

### Minions / Hermes Agent Kanban

资料来源：npm 包 `minionsai`，描述为 “Mission Control for Hermes Agent — a Kanban board for autonomous agent tasks”。README 说明它提供任务看板、自动执行、自动进入 review queue、实时流、human-in-the-loop、per-task model control、scheduled tasks、file browser、本地 SQLite。

核心模式：

- 每个任务是一个持久 Hermes root session。
- 看板展示 `in progress`、`in review`、`done`。
- agent 完成后不直接 done，而是进入 review。
- 用户确认后才关闭任务。
- 任务元数据存在本地 SQLite，聊天 transcript 存 Hermes session database。

优点：

- 非常贴近“多个长期任务要有人管”的场景。
- 看板天然降低认知负担，比多个终端窗口容易管理。
- `ready for review` 是很好的安全闸口，适合代码生成。
- local-first 方案简单，适合 MVP。
- per-task model/reasoning effort 对成本和质量控制有价值。

缺点：

- 公开 README 表示 launch 是 Hermes-only，适配其他 agent 还在后面。
- 更像任务监督台，不是完整工程交付系统。
- 没看到它强调 git worktree 隔离、diff review、自动 merge 这些代码协作细节。
- 对多角色分工的表达较弱，更多是“多个任务”而不是“一个团队”。

可借鉴：

- 我们可以采用 `Todo -> Running -> Review -> Done` 的主看板。
- 每个 job 完成后默认进入 `review`，不能自动标记 done。
- 卡片上展示最近事件、待审批项、产物和下载入口。
- 支持每个任务独立模型、预算、turn 限制和工具权限。

## 3. CCPark / AgentDock

资料来源：npm 包 `ccpark`、`agentdock`、`@agentdock/daemon`、`@agentdock/sdk`、`@agentdock/wire`。

公开 npm 元数据：

- `ccpark`：`CCPark CLI — remote console for terminal AI agents`。
- `agentdock`：`Terminal AI Agent remote console — shorthand for @agentdock/daemon`。
- `@agentdock/daemon`：PC daemon + CLI，负责 Agent 子进程管理、ACP 抽象、事件转换、Socket.IO 上报、本地 HTTP 控制服务器。
- `@agentdock/sdk`：Socket.IO 连接管理、类型安全 RPC、离线队列、事件流。
- `@agentdock/wire`：Zod 协议定义、会话事件、信封、RPC、同步协议、权限模式、CLI 类型。

核心模式：

- 在用户 PC 上运行 daemon。
- daemon 启动/停止 Claude Code 等终端 agent 子进程。
- daemon 解析 agent 原始 JSONL，统一转成 wire 协议事件。
- 前端或 server 通过 Socket.IO / RPC 控制 daemon。
- 支持 approve、deny、answer、abort、stop 这类交互。

优点：

- 进程管理抽象很强，适合接入多种 CLI agent。
- wire 层设计值得学：事件、信封、RPC、权限、CLI 类型都 schema 化。
- SDK 的离线队列和自动重连适合长任务控制台。
- 本地 daemon 控制模型适合“用户自己的机器上跑 agent”的产品形态。
- 它把“agent 原始输出”转成统一协议，这正是我们需要的 adapter 层能力。

缺点：

- 更偏底层远程控制台，不是完整任务看板和项目管理产品。
- npm 包 license 显示 `UNLICENSED`，直接复用要谨慎。
- 需要用户安装本地 daemon，使用门槛高于纯 Web 服务。
- 远程控制本地终端 agent 会引入配对、鉴权、密钥、离线队列和安全边界复杂度。

可借鉴：

- 设计统一 `AgentEventEnvelope`，不要让前端直接吃 Claude SDK 原始输出。
- 设计 `approve/deny/answer/abort/stop` 这类 RPC 方法。
- 把 agent runtime 做成 adapter：Claude SDK、Claude Code CLI、Codex CLI、Gemini CLI 都可以走统一协议。
- 事件流里保留 turn 边界、tool call start/end、question、permission request。

## 4. Cline Kanban

资料来源：npm 包 `kanban`，README 称它是 “A replacement for your IDE better suited for running many agents in parallel and reviewing diffs”。它的 task card 拥有自己的 terminal 和 worktree，支持 auto-commit、依赖链、diff review、PR。

核心模式：

- 每张卡一个任务。
- 每张卡有独立 terminal 和 ephemeral worktree。
- 可以让 agent 拆任务、链接任务、自动启动依赖任务。
- agent 工作时用 hooks 把最新消息或工具调用显示在卡片上。
- 用户点开卡片可以看 TUI 和 worktree diff。
- 可以 commit、open PR，或启用 auto-commit / auto-PR。

优点：

- 非常适合代码开发并行化。
- worktree 隔离很好，能减少并行 agent 的 merge 冲突。
- 卡片依赖链是大任务拆解的关键能力。
- diff review 是代码生成产品必须做的体验。
- “最新消息/工具调用显示在卡片上”能让用户扫一眼知道进展。

缺点：

- README 明确是 research preview，并使用实验特性，包括 bypass permissions 和 runtime hooks。
- auto-commit / auto-PR 很强，但默认用于 MVP 风险偏高。
- 依赖 git repo 和 worktree，对于我们“从零生成 zip”的第一版不完全匹配。
- 权限自动化太激进时，容易踩安全坑。

可借鉴：

- 每个任务卡绑定一个独立 workspace。
- 对代码类任务提供 diff review，而不是只给 zip。
- 支持任务依赖图：某卡完成并通过审阅后，自动启动下游卡。
- 支持“任务拆解 agent”生成多个卡片，但人类确认后才执行。

## 5. Zoink

资料来源：npm 包 `@zoink-dev/zoink-cli`。README 描述它是 multi-framework AI agent swarm orchestration，支持 OpenClaw 和 Hermes agent side-by-side，声明式 `swarm.yaml`，Docker 容器隔离，内置 Kanban、TUI、Web dashboard、SSE board events、PM-driven delegation、human-in-the-loop approval。

核心模式：

- 用 `swarm.yaml` 定义 agent 团队。
- 每个 agent 有 name、role、runtime、model、provider、workspace、资源限制。
- PM agent 可以把目标拆成任务并分配给 coder、researcher、devops。
- worker 可以自动 pickup 看板卡片。
- 每个 agent 跑在独立 Docker 容器里。
- Dashboard 有 Overview、Kanban、Logs、Agents、Chat 五个标签。

优点：

- “角色化团队”最贴近你说的“各自有不同职责”。
- 声明式团队配置非常适合复用和模板化。
- Docker 隔离、资源限制、健康检查、自动恢复是生产化方向。
- PM-driven delegation 和 autoPickup 是从单任务到多 agent 协作的关键。
- TUI + Web dashboard 两种界面都照顾到了不同用户。

缺点：

- 体系很重，MVP 如果照搬会过度设计。
- 多容器、多 runtime、多 provider 会增加本地安装和调试成本。
- 自动 pickup + 自动 Done 如果没有 review gate，容易把错误自动推进。
- 真实协作效果强依赖 PM agent 的拆解能力和任务边界质量。

可借鉴：

- 引入 `TeamTemplate`：PM、Architect、Coder、Reviewer、Tester、Researcher。
- 引入 `role`、`workspace`、`model`、`budget`、`tools`、`approvalPolicy` 这些 agent 配置。
- Dashboard 信息架构可以参考：Overview、Board、Events/Logs、Agents、Artifacts。
- 不照搬多容器 swarm，先在单 Worker 里模拟多角色，再逐步拆成 sidecar/容器。

## 6. Kiro Specs / Hooks / Steering

资料来源：Kiro 官方文档。

Kiro Specs 的核心是把开发过程结构化成：

- `requirements.md`
- `design.md`
- `tasks.md`

文档说明 Specs 可以把高层想法转成有验收标准、设计文档和可跟踪任务的实施计划；任务执行界面会实时更新状态；Run all Tasks 会分析任务依赖，把独立任务并发执行。

Kiro Hooks 的核心是事件触发自动动作，例如保存文件、提交 prompt、agent turn 完成、工具调用前后、spec task 执行前后。动作可以是 agent prompt 或 shell command。

Kiro Steering 的核心是通过 markdown 文件给 agent 持久项目知识，包括产品、技术栈、目录结构、团队标准，并支持 workspace/global/team 作用域和不同 inclusion modes。

优点：

- Specs 解决“先规划再执行”的质量问题。
- 任务依赖图和 wave 并发执行很适合多 agent 调度。
- Hooks 适合做自动质量门禁，例如保存后 lint、任务完成后 review。
- Steering 适合让不同职责 agent 共享项目规则。

缺点：

- 偏 IDE 内工作流，不是 Web 任务平台。
- 如果所有工作都要求 specs，会降低探索型任务速度。
- Hooks 如果允许 shell command，必须有权限边界和审计。

可借鉴：

- 我们的任务卡可以内置三个文档区：需求、设计、任务。
- 多 agent 执行时先生成任务依赖图，再按 wave 并发。
- 引入 `Project Steering`：产品目标、技术栈、安全规则、代码规范。
- 引入事件 hook，但 MVP 只做安全 hooks：完成后 review、zip 前敏感文件扫描、shell 前审批。

## 7. CodeBuddy / 扣子 Coze

资料来源：CodeBuddy 官网和公开百科资料、Coze 官网。公开页面对“扣子 3.0”的细节可检索内容有限，所以这一节只做产品模式参考，不作为精确功能还原。

### CodeBuddy

公开资料显示 CodeBuddy 是腾讯云 AI 编程工具，覆盖产品构思、设计、研发、部署链路，并有 PlanAgent、DesignAgent、CodingAgent、DeployAgent 等专业智能体分工。

可借鉴：

- 用用户能理解的角色命名，而不是技术内部名。
- 把“从想法到上线”拆成 Plan、Design、Code、Deploy。
- 初期可以保留 DeployAgent 入口，但不自动部署，只生成部署计划。

风险：

- 全链路产品很容易把 MVP 做大。
- 设计稿、部署、数据库、鉴权都接入后，权限和成本控制会很复杂。

### Coze / 扣子

Coze 的整体模式是低代码/可视化 agent 平台，通常围绕工作流、插件、知识库、发布渠道组织能力。

可借鉴：

- 用可视化 workflow 表达 agent 节点，而不是只展示聊天。
- 把工具、知识库、审批、发布作为节点能力。
- 面向非工程用户时，角色和流程比底层日志更重要。

风险：

- 工作流平台会偏“通用自动化”，我们的核心还是代码生成与协作开发。
- 如果过早做可视化 workflow builder，会拖慢核心链路。

## 8. 横向对比

| 系统 | 最强点 | 最大风险 | 最值得借鉴 |
|---|---|---|---|
| Minions / Hermes | 简单看板 + human review | Hermes-only、工程 diff 较弱 | Review queue、local-first、实时流 |
| CCPark / AgentDock | 远程控制终端 agent、协议层 | 不像完整产品、daemon 安全复杂 | wire protocol、RPC、进程管理、event envelope |
| Cline Kanban | 并行 coding agent + worktree + diff review | 实验性强、权限自动化风险 | 每卡 workspace、依赖链、diff review |
| Zoink | 多角色 swarm + Docker 隔离 | 过重、部署复杂 | TeamTemplate、角色配置、PM 分解任务 |
| Kiro | Specs、依赖任务、hooks、steering | 偏 IDE、流程可能变慢 | 需求-设计-任务三件套、wave 并发、项目规则 |
| CodeBuddy | Plan/Design/Code/Deploy 全链路 | 范围巨大 | 用户可理解的专业角色 |
| Coze/扣子 | 可视化 workflow、工具、知识库 | 容易变通用工作流平台 | 节点化协作和低门槛配置 |

## 9. 给我们的产品建议

### 9.1 MVP 协作方式

建议第一版新增一种 `Team Mode`，但不要上来做完整 swarm。

Team Mode 流程：

```text
用户提交目标
  -> Planner 拆任务卡
  -> 人类确认任务拆分
  -> Researcher / Architect / Coder / Reviewer / Tester 并行或顺序执行
  -> 每个卡片进入 Review
  -> 人类确认后合并为最终 artifact
```

### 9.2 第一版角色

保留 5 个角色就够：

- Planner：把需求拆成任务、依赖和验收标准。
- Researcher：调研依赖、API、技术选型和风险。
- Coder：写代码和生成文件。
- Reviewer：做代码审查、风险检查、是否偏离需求。
- Tester：生成和执行测试计划，汇总证据。

DeployAgent 暂时不自动部署，只生成部署建议。

### 9.3 看板列

建议列：

```text
Backlog
Ready
Running
Blocked
Review
Done
Failed
```

每张卡必须展示：

- role
- owner agent
- status
- dependencies
- latest event
- workspace
- artifact summary
- pending approval
- cost / turns / duration

### 9.4 任务关系

支持三种关系即可：

- blocks：A 完成后 B 才能开始。
- parallel-with：A 和 B 可同时执行。
- reviews：Reviewer 卡审查 Coder 卡。

后续再做更复杂 DAG。

### 9.5 事件协议

借鉴 AgentDock wire，设计统一事件：

```text
task-created
task-started
agent-message
tool-call-start
tool-call-end
file-written
artifact-created
question
permission-request
review-requested
review-comment
task-completed
task-failed
```

所有事件必须有：

- id
- job_id
- task_id
- role
- agent_id
- sequence
- timestamp
- payload

### 9.6 人类审批点

必须审批：

- Planner 生成的任务拆分。
- shell 命令执行。
- 文件删除或大规模修改。
- 依赖安装。
- 合并多个任务产物。
- 标记最终 Done。
- 部署或外部系统写入。

### 9.7 我们不要照搬的点

- 不照搬 Zoink 的多容器 swarm 复杂度。
- 不照搬 Cline Kanban 的 bypass permissions 和 auto-commit 默认行为。
- 不照搬 Coze 的通用 workflow builder。
- 不照搬本地 daemon 作为第一版必须安装项。
- 不让 agent 自动 Done，必须经过 Review。

## 10. 推荐产品形态

最终建议把我们的产品从单个 codegen job 升级为两个模式：

### Simple Mode

一个 prompt，一个 Worker，一个 artifact。

适合：

- 小代码片段。
- 单文件生成。
- 快速 demo。

### Team Mode

一个目标，多张任务卡，多个职责 agent。

适合：

- 小项目生成。
- 需要调研、设计、编码、测试的任务。
- 用户想看清楚每个 agent 做了什么。

第一版 Team Mode 不需要真实多进程并行，可以先做“逻辑多 agent”：

- 同一个 Worker 内按角色调用 adapter。
- 每个角色生成独立事件和产物。
- 任务卡按依赖顺序推进。
- 后续再升级成多 Worker 并行。

## 11. 参考来源

- Minions npm: https://www.npmjs.com/package/minionsai
- AgentDock daemon npm: https://www.npmjs.com/package/@agentdock/daemon
- AgentDock SDK npm: https://www.npmjs.com/package/@agentdock/sdk
- AgentDock wire npm: https://www.npmjs.com/package/@agentdock/wire
- CCPark npm: https://www.npmjs.com/package/ccpark
- Cline Kanban npm: https://www.npmjs.com/package/kanban
- Zoink CLI npm: https://www.npmjs.com/package/@zoink-dev/zoink-cli
- Kiro Specs: https://kiro.dev/docs/specs/
- Kiro Hooks: https://kiro.dev/docs/hooks/
- Kiro Steering: https://kiro.dev/docs/steering/
- CodeBuddy: https://www.codebuddy.ai/
- Coze: https://www.coze.com/
