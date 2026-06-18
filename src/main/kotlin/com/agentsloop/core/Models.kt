package com.agentsloop.core

import java.time.Instant
import java.util.UUID

fun nowIso(): String = Instant.now().toString()

fun newId(prefix: String): String = "$prefix_${UUID.randomUUID().toString().replace("-", "").take(12)}"

enum class TaskStatus(val wire: String) {
    BACKLOG("backlog"),
    READY("ready"),
    RUNNING("running"),
    BLOCKED("blocked"),
    REVIEW("review"),
    DONE("done"),
    FAILED("failed");

    companion object {
        fun fromWire(value: String): TaskStatus =
            entries.firstOrNull { it.wire == value } ?: error("Unknown task status: $value")
    }
}

enum class EventType(val wire: String) {
    TASK_CREATED("task-created"),
    TASK_STARTED("task-started"),
    AGENT_MESSAGE("agent-message"),
    TOOL_CALL_START("tool-call-start"),
    TOOL_CALL_END("tool-call-end"),
    FILE_WRITTEN("file-written"),
    APPROVAL_NEEDED("approval-needed"),
    ARTIFACT_CREATED("artifact-created"),
    REVIEW_REQUESTED("review-requested"),
    TASK_COMPLETED("task-completed"),
    TASK_FAILED("task-failed"),
    TASK_BLOCKED("task-blocked"),
    PLAN_IMPORTED("plan-imported"),
    PLAN_CONFIRMED("plan-confirmed"),
    APPROVAL_DECIDED("approval-decided"),
    TASK_DONE("task-done"),
    TASK_CANCELED("task-canceled");

    companion object {
        fun fromWire(value: String): EventType =
            entries.firstOrNull { it.wire == value } ?: error("Unknown event type: $value")
    }
}

enum class ApprovalStatus(val wire: String) {
    PENDING("pending"),
    APPROVED("approved"),
    DENIED("denied");

    companion object {
        fun fromWire(value: String): ApprovalStatus =
            entries.firstOrNull { it.wire == value } ?: error("Unknown approval status: $value")
    }
}

val boardColumns = listOf(
    TaskStatus.BACKLOG,
    TaskStatus.READY,
    TaskStatus.RUNNING,
    TaskStatus.BLOCKED,
    TaskStatus.REVIEW,
    TaskStatus.DONE,
    TaskStatus.FAILED,
)

val dangerousApprovalKinds = setOf("shell", "delete", "install", "merge", "deploy")

data class Artifact(
    val id: String = newId("art"),
    val taskId: String,
    val kind: String,
    val path: String? = null,
    val summary: String = "",
    val metadata: Map<String, Any?> = emptyMap(),
    val createdAt: String = nowIso(),
)

data class Approval(
    val id: String = newId("apr"),
    val taskId: String,
    val kind: String,
    val message: String,
    val status: ApprovalStatus = ApprovalStatus.PENDING,
    val decision: String? = null,
    val createdAt: String = nowIso(),
    val decidedAt: String? = null,
)

data class Event(
    val id: String = newId("evt"),
    val projectId: String,
    val taskId: String? = null,
    val type: EventType,
    val sequence: Long = 0,
    val timestamp: String = nowIso(),
    val role: String? = null,
    val agentId: String? = null,
    val payload: Map<String, Any?> = emptyMap(),
)

data class Task(
    val id: String = newId("task"),
    val projectId: String,
    val title: String,
    val role: String,
    val description: String,
    val status: TaskStatus = TaskStatus.BACKLOG,
    val dependencies: List<String> = emptyList(),
    val workspace: String? = null,
    val assignedWorker: String = "mock",
    val latestEvent: String? = null,
    val artifactIds: List<String> = emptyList(),
    val approvalIds: List<String> = emptyList(),
    val reviewStatus: String = "not_requested",
    val acceptanceCriteria: List<String> = emptyList(),
    val createdAt: String = nowIso(),
    val updatedAt: String = nowIso(),
)

data class Project(
    val id: String = newId("proj"),
    val title: String,
    val requirements: String,
    val design: String,
    val planConfirmed: Boolean = false,
    val taskIds: List<String> = emptyList(),
    val createdAt: String = nowIso(),
    val updatedAt: String = nowIso(),
)

data class PlannerTask(
    val id: String?,
    val title: String,
    val role: String,
    val description: String,
    val dependencies: List<String> = emptyList(),
    val assignedWorker: String = "mock",
    val acceptanceCriteria: List<String> = emptyList(),
)

data class PlannerBundle(
    val title: String,
    val requirements: String,
    val design: String,
    val tasks: List<PlannerTask>,
)

data class IncomingEvent(
    val externalEventId: String?,
    val projectId: String?,
    val taskId: String?,
    val type: String,
    val role: String?,
    val agentId: String?,
    val payload: Map<String, Any?>,
)

data class LoopState(
    val projects: Map<String, Project> = emptyMap(),
    val tasks: Map<String, Task> = emptyMap(),
    val events: List<Event> = emptyList(),
    val artifacts: Map<String, Artifact> = emptyMap(),
    val approvals: Map<String, Approval> = emptyMap(),
    val seenExternalEventIds: Set<String> = emptySet(),
) {
    val nextSequence: Long get() = (events.maxOfOrNull { it.sequence } ?: 0L) + 1L
}
