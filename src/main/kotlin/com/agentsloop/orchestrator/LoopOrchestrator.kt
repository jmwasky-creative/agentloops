package com.agentsloop.orchestrator

import com.agentsloop.core.Approval
import com.agentsloop.core.ApprovalStatus
import com.agentsloop.core.Artifact
import com.agentsloop.core.Event
import com.agentsloop.core.EventType
import com.agentsloop.core.IncomingEvent
import com.agentsloop.core.LoopState
import com.agentsloop.core.PlannerBundle
import com.agentsloop.core.Project
import com.agentsloop.core.Task
import com.agentsloop.core.TaskStatus
import com.agentsloop.core.dangerousApprovalKinds
import com.agentsloop.core.newId
import com.agentsloop.core.nowIso
import com.agentsloop.store.JsonStateStore
import java.nio.file.Path
import kotlin.io.path.createDirectories

class LoopOrchestrator(
    private val store: JsonStateStore,
    private val workspacesRoot: Path = Path.of(".agentsloop", "workspaces"),
) {
    fun importPlan(bundle: PlannerBundle): Project =
        store.update { state ->
            val project = Project(
                title = bundle.title,
                requirements = bundle.requirements,
                design = bundle.design,
            )
            val tasks = bundle.tasks.map { plannerTask ->
                Task(
                    id = plannerTask.id ?: newId("task"),
                    projectId = project.id,
                    title = plannerTask.title,
                    role = plannerTask.role,
                    description = plannerTask.description,
                    dependencies = plannerTask.dependencies,
                    assignedWorker = plannerTask.assignedWorker,
                    acceptanceCriteria = plannerTask.acceptanceCriteria,
                )
            }
            var nextState = state.copy(
                projects = state.projects + (project.id to project.copy(taskIds = tasks.map { it.id })),
                tasks = state.tasks + tasks.associateBy { it.id },
            )
            nextState = appendEvent(
                nextState,
                project.id,
                null,
                EventType.PLAN_IMPORTED,
                payload = mapOf("task_count" to tasks.size),
            )
            tasks.forEach { task ->
                nextState = appendEvent(
                    nextState,
                    project.id,
                    task.id,
                    EventType.TASK_CREATED,
                    role = task.role,
                    agentId = task.assignedWorker,
                    payload = mapOf("title" to task.title),
                )
            }
            nextState
        }.projects.values.maxBy { it.createdAt }

    fun confirmPlan(projectId: String? = null): Int =
        store.update { state ->
            val project = selectProject(state, projectId)
            val updatedProject = project.copy(planConfirmed = true, updatedAt = nowIso())
            var nextState = state.copy(projects = state.projects + (project.id to updatedProject))
            nextState = appendEvent(nextState, project.id, null, EventType.PLAN_CONFIRMED)
            releaseReadyTasks(nextState, project.id)
        }.tasks.values.count { it.projectId == selectProject(store.load(), projectId).id && it.status == TaskStatus.READY }

    fun readyTasks(workerName: String? = null): List<Task> =
        store.load().tasks.values
            .filter { it.status == TaskStatus.READY }
            .filter { workerName == null || it.assignedWorker == workerName || workerName == "mock" }
            .sortedBy { it.createdAt }

    fun startTask(taskId: String): Task =
        store.update { state ->
            val task = state.task(taskId)
            require(task.status == TaskStatus.READY) { "task ${task.id} is ${task.status.wire}, not ready" }
            val workspace = workspacesRoot.resolve(task.id).normalize()
            workspace.createDirectories()
            val updated = task.copy(
                status = TaskStatus.RUNNING,
                workspace = workspace.toString(),
                updatedAt = nowIso(),
            )
            appendEvent(
                state.copy(tasks = state.tasks + (task.id to updated)),
                task.projectId,
                task.id,
                EventType.TASK_STARTED,
                role = task.role,
                agentId = task.assignedWorker,
                payload = mapOf("workspace" to workspace.toString()),
            )
        }.task(taskId)

    fun handleIncoming(event: IncomingEvent): Boolean =
        store.update { state ->
            if (event.externalEventId != null && event.externalEventId in state.seenExternalEventIds) return@update state
            val task = event.taskId?.let { state.tasks[it] }
            val projectId = event.projectId ?: task?.projectId ?: selectProject(state, null).id
            val eventType = EventType.fromWire(event.type)
            var nextState = state
            if (event.externalEventId != null) {
                nextState = nextState.copy(seenExternalEventIds = nextState.seenExternalEventIds + event.externalEventId)
            }
            nextState = applyEventSideEffects(nextState, task, eventType, event.payload)
            appendEvent(
                nextState,
                projectId,
                event.taskId,
                eventType,
                role = event.role ?: task?.role,
                agentId = event.agentId ?: task?.assignedWorker,
                payload = event.payload + mapOf("external_event_id" to event.externalEventId),
            )
        }.let { true }

    fun requestApproval(taskId: String, kind: String, message: String): Approval =
        store.update { state ->
            val task = state.task(taskId)
            require(kind in dangerousApprovalKinds) { "approval kind '$kind' is not configured as dangerous" }
            val approval = Approval(taskId = task.id, kind = kind, message = message)
            val updatedTask = task.copy(
                status = TaskStatus.BLOCKED,
                approvalIds = task.approvalIds + approval.id,
                updatedAt = nowIso(),
            )
            appendEvent(
                state.copy(
                    tasks = state.tasks + (task.id to updatedTask),
                    approvals = state.approvals + (approval.id to approval),
                ),
                task.projectId,
                task.id,
                EventType.APPROVAL_NEEDED,
                role = task.role,
                agentId = task.assignedWorker,
                payload = mapOf("approval_id" to approval.id, "kind" to kind, "message" to message),
            )
        }.approvals.values.maxBy { it.createdAt }

    fun decideApproval(approvalId: String, approve: Boolean, decision: String): Approval =
        store.update { state ->
            val approval = state.approvals[approvalId] ?: error("Unknown approval $approvalId")
            val task = state.task(approval.taskId)
            val updatedApproval = approval.copy(
                status = if (approve) ApprovalStatus.APPROVED else ApprovalStatus.DENIED,
                decision = decision,
                decidedAt = nowIso(),
            )
            val updatedTask = task.copy(
                status = if (approve) TaskStatus.READY else TaskStatus.FAILED,
                updatedAt = nowIso(),
            )
            appendEvent(
                state.copy(
                    approvals = state.approvals + (approval.id to updatedApproval),
                    tasks = state.tasks + (task.id to updatedTask),
                ),
                task.projectId,
                task.id,
                EventType.APPROVAL_DECIDED,
                role = task.role,
                agentId = task.assignedWorker,
                payload = mapOf("approval_id" to approval.id, "approved" to approve, "decision" to decision),
            )
        }.approvals[approvalId] ?: error("Unknown approval $approvalId")

    fun markDone(taskId: String): Task =
        store.update { state ->
            val task = state.task(taskId)
            require(task.status == TaskStatus.REVIEW) { "task ${task.id} is ${task.status.wire}, not in review" }
            val updated = task.copy(status = TaskStatus.DONE, reviewStatus = "accepted", updatedAt = nowIso())
            releaseReadyTasks(
                appendEvent(
                    state.copy(tasks = state.tasks + (task.id to updated)),
                    task.projectId,
                    task.id,
                    EventType.TASK_DONE,
                    role = task.role,
                    agentId = task.assignedWorker,
                ),
                task.projectId,
            )
        }.task(taskId)

    fun state(): LoopState = store.load()

    private fun applyEventSideEffects(
        state: LoopState,
        task: Task?,
        eventType: EventType,
        payload: Map<String, Any?>,
    ): LoopState {
        if (task == null) return state
        return when (eventType) {
            EventType.ARTIFACT_CREATED -> {
                val artifactPath = guardedArtifactPath(task, payload["path"] as? String)
                val artifact = Artifact(
                    taskId = task.id,
                    kind = (payload["kind"] as? String) ?: "generic",
                    path = artifactPath,
                    summary = (payload["summary"] as? String) ?: "",
                    metadata = payload + mapOf("path" to artifactPath),
                )
                val updated = task.copy(artifactIds = task.artifactIds + artifact.id, updatedAt = nowIso())
                state.copy(
                    tasks = state.tasks + (task.id to updated),
                    artifacts = state.artifacts + (artifact.id to artifact),
                )
            }
            EventType.REVIEW_REQUESTED, EventType.TASK_COMPLETED -> {
                val updated = task.copy(status = TaskStatus.REVIEW, reviewStatus = "requested", updatedAt = nowIso())
                state.copy(tasks = state.tasks + (task.id to updated))
            }
            EventType.TASK_FAILED -> {
                val updated = task.copy(status = TaskStatus.FAILED, updatedAt = nowIso())
                state.copy(tasks = state.tasks + (task.id to updated))
            }
            EventType.TASK_BLOCKED, EventType.APPROVAL_NEEDED -> {
                val updated = task.copy(status = TaskStatus.BLOCKED, updatedAt = nowIso())
                state.copy(tasks = state.tasks + (task.id to updated))
            }
            else -> state.copy(tasks = state.tasks + (task.id to task.copy(updatedAt = nowIso())))
        }
    }

    private fun releaseReadyTasks(state: LoopState, projectId: String): LoopState {
        val project = state.projects[projectId] ?: error("Unknown project $projectId")
        if (!project.planConfirmed) return state
        val done = state.tasks.values.filter { it.status == TaskStatus.DONE }.map { it.id }.toSet()
        val updates = state.tasks.values
            .filter { it.projectId == projectId && it.status == TaskStatus.BACKLOG }
            .filter { task -> task.dependencies.all { it in done } }
            .associate { it.id to it.copy(status = TaskStatus.READY, updatedAt = nowIso()) }
        return if (updates.isEmpty()) state else state.copy(tasks = state.tasks + updates)
    }

    private fun appendEvent(
        state: LoopState,
        projectId: String,
        taskId: String?,
        type: EventType,
        role: String? = null,
        agentId: String? = null,
        payload: Map<String, Any?> = emptyMap(),
    ): LoopState {
        val event = Event(
            projectId = projectId,
            taskId = taskId,
            type = type,
            sequence = state.nextSequence,
            role = role,
            agentId = agentId,
            payload = payload,
        )
        val taskUpdates = if (taskId != null && state.tasks.containsKey(taskId)) {
            val task = state.tasks.getValue(taskId)
            mapOf(taskId to task.copy(latestEvent = event.id, updatedAt = nowIso()))
        } else {
            emptyMap()
        }
        return state.copy(
            tasks = state.tasks + taskUpdates,
            events = state.events + event,
        )
    }

    private fun selectProject(state: LoopState, projectId: String?): Project {
        if (projectId != null) return state.projects[projectId] ?: error("Unknown project $projectId")
        return state.projects.values.maxByOrNull { it.createdAt } ?: error("No project has been imported")
    }

    private fun guardedArtifactPath(task: Task, path: String?): String? {
        if (path == null) return null
        val workspace = task.workspace ?: throw IllegalArgumentException("task ${task.id} has no workspace for artifact")
        val workspacePath = Path.of(workspace).toAbsolutePath().normalize()
        val candidate = Path.of(path).let {
            if (it.isAbsolute) it.normalize() else workspacePath.resolve(it).normalize()
        }.toAbsolutePath().normalize()
        require(candidate.startsWith(workspacePath)) {
            "artifact path escapes task workspace: $path"
        }
        return candidate.toString()
    }

    private fun LoopState.task(taskId: String): Task = tasks[taskId] ?: error("Unknown task $taskId")
}
