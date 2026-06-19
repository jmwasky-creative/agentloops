package com.agentsloop.store

import com.agentsloop.core.Approval
import com.agentsloop.core.ApprovalStatus
import com.agentsloop.core.Artifact
import com.agentsloop.core.Event
import com.agentsloop.core.EventType
import com.agentsloop.core.IncomingEvent
import com.agentsloop.core.LoopState
import com.agentsloop.core.PlannerBundle
import com.agentsloop.core.PlannerTask
import com.agentsloop.core.Project
import com.agentsloop.core.Task
import com.agentsloop.core.TaskStatus

object StateCodec {
    fun stateToMap(state: LoopState): Map<String, Any?> = mapOf(
        "projects" to state.projects.values.map(::projectToMap),
        "tasks" to state.tasks.values.map(::taskToMap),
        "events" to state.events.sortedBy { it.sequence }.map(::eventToMap),
        "artifacts" to state.artifacts.values.map(::artifactToMap),
        "approvals" to state.approvals.values.map(::approvalToMap),
        "seen_external_event_ids" to state.seenExternalEventIds.sorted(),
    )

    fun stateFromMap(map: Map<String, Any?>): LoopState {
        val projects = listOfObjects(map["projects"]).map(::projectFromMap).associateBy { it.id }
        val tasks = listOfObjects(map["tasks"]).map(::taskFromMap).associateBy { it.id }
        val events = listOfObjects(map["events"]).map(::eventFromMap).sortedBy { it.sequence }
        val artifacts = listOfObjects(map["artifacts"]).map(::artifactFromMap).associateBy { it.id }
        val approvals = listOfObjects(map["approvals"]).map(::approvalFromMap).associateBy { it.id }
        val seen = stringList(map["seen_external_event_ids"]).toSet()
        return LoopState(projects, tasks, events, artifacts, approvals, seen)
    }

    fun bundleFromMap(map: Map<String, Any?>): PlannerBundle {
        val tasks = listOfObjects(map["tasks"]).mapIndexed { index, task ->
            PlannerTask(
                id = task["id"] as? String,
                title = requiredString(task, "title", "task[$index]"),
                role = requiredString(task, "role", "task[$index]"),
                description = requiredString(task, "description", "task[$index]"),
                dependencies = stringList(task["dependencies"]),
                assignedWorker = (task["assigned_worker"] as? String)
                    ?: (task["assignedWorker"] as? String)
                    ?: "mock",
                acceptanceCriteria = stringList(task["acceptance_criteria"] ?: task["acceptanceCriteria"]),
            )
        }
        require(tasks.isNotEmpty()) { "planner bundle requires at least one task" }
        return PlannerBundle(
            title = (map["title"] as? String) ?: "Untitled Loop Project",
            requirements = (map["requirements"] as? String) ?: "",
            design = (map["design"] as? String) ?: "",
            tasks = tasks,
        )
    }

    fun incomingEventFromMap(map: Map<String, Any?>): IncomingEvent =
        IncomingEvent(
            externalEventId = map["external_event_id"] as? String
                ?: map["externalEventId"] as? String
                ?: map["id"] as? String,
            projectId = map["project_id"] as? String ?: map["projectId"] as? String,
            taskId = map["task_id"] as? String ?: map["taskId"] as? String,
            type = requiredString(map, "type", "event"),
            role = map["role"] as? String,
            agentId = map["agent_id"] as? String ?: map["agentId"] as? String,
            payload = objectMap(map["payload"]),
        )

    fun projectToMap(project: Project): Map<String, Any?> = mapOf(
        "id" to project.id,
        "title" to project.title,
        "requirements" to project.requirements,
        "design" to project.design,
        "plan_confirmed" to project.planConfirmed,
        "task_ids" to project.taskIds,
        "created_at" to project.createdAt,
        "updated_at" to project.updatedAt,
    )

    fun taskToMap(task: Task): Map<String, Any?> = mapOf(
        "id" to task.id,
        "project_id" to task.projectId,
        "title" to task.title,
        "role" to task.role,
        "description" to task.description,
        "status" to task.status.wire,
        "dependencies" to task.dependencies,
        "workspace" to task.workspace,
        "assigned_worker" to task.assignedWorker,
        "latest_event" to task.latestEvent,
        "artifact_ids" to task.artifactIds,
        "approval_ids" to task.approvalIds,
        "review_status" to task.reviewStatus,
        "acceptance_criteria" to task.acceptanceCriteria,
        "created_at" to task.createdAt,
        "updated_at" to task.updatedAt,
    )

    fun eventToMap(event: Event): Map<String, Any?> = mapOf(
        "id" to event.id,
        "project_id" to event.projectId,
        "task_id" to event.taskId,
        "type" to event.type.wire,
        "sequence" to event.sequence,
        "timestamp" to event.timestamp,
        "role" to event.role,
        "agent_id" to event.agentId,
        "payload" to event.payload,
    )

    fun artifactToMap(artifact: Artifact): Map<String, Any?> = mapOf(
        "id" to artifact.id,
        "task_id" to artifact.taskId,
        "kind" to artifact.kind,
        "path" to artifact.path,
        "summary" to artifact.summary,
        "metadata" to artifact.metadata,
        "created_at" to artifact.createdAt,
    )

    fun approvalToMap(approval: Approval): Map<String, Any?> = mapOf(
        "id" to approval.id,
        "task_id" to approval.taskId,
        "kind" to approval.kind,
        "message" to approval.message,
        "status" to approval.status.wire,
        "decision" to approval.decision,
        "created_at" to approval.createdAt,
        "decided_at" to approval.decidedAt,
    )

    private fun projectFromMap(map: Map<String, Any?>): Project = Project(
        id = requiredString(map, "id", "project"),
        title = requiredString(map, "title", "project"),
        requirements = (map["requirements"] as? String) ?: "",
        design = (map["design"] as? String) ?: "",
        planConfirmed = map["plan_confirmed"] as? Boolean ?: false,
        taskIds = stringList(map["task_ids"]),
        createdAt = requiredString(map, "created_at", "project"),
        updatedAt = requiredString(map, "updated_at", "project"),
    )

    private fun taskFromMap(map: Map<String, Any?>): Task = Task(
        id = requiredString(map, "id", "task"),
        projectId = requiredString(map, "project_id", "task"),
        title = requiredString(map, "title", "task"),
        role = requiredString(map, "role", "task"),
        description = requiredString(map, "description", "task"),
        status = TaskStatus.fromWire(requiredString(map, "status", "task")),
        dependencies = stringList(map["dependencies"]),
        workspace = map["workspace"] as? String,
        assignedWorker = (map["assigned_worker"] as? String) ?: "mock",
        latestEvent = map["latest_event"] as? String,
        artifactIds = stringList(map["artifact_ids"]),
        approvalIds = stringList(map["approval_ids"]),
        reviewStatus = (map["review_status"] as? String) ?: "not_requested",
        acceptanceCriteria = stringList(map["acceptance_criteria"]),
        createdAt = requiredString(map, "created_at", "task"),
        updatedAt = requiredString(map, "updated_at", "task"),
    )

    private fun eventFromMap(map: Map<String, Any?>): Event = Event(
        id = requiredString(map, "id", "event"),
        projectId = requiredString(map, "project_id", "event"),
        taskId = map["task_id"] as? String,
        type = EventType.fromWire(requiredString(map, "type", "event")),
        sequence = (map["sequence"] as Number).toLong(),
        timestamp = requiredString(map, "timestamp", "event"),
        role = map["role"] as? String,
        agentId = map["agent_id"] as? String,
        payload = objectMap(map["payload"]),
    )

    private fun artifactFromMap(map: Map<String, Any?>): Artifact = Artifact(
        id = requiredString(map, "id", "artifact"),
        taskId = requiredString(map, "task_id", "artifact"),
        kind = requiredString(map, "kind", "artifact"),
        path = map["path"] as? String,
        summary = (map["summary"] as? String) ?: "",
        metadata = objectMap(map["metadata"]),
        createdAt = requiredString(map, "created_at", "artifact"),
    )

    private fun approvalFromMap(map: Map<String, Any?>): Approval = Approval(
        id = requiredString(map, "id", "approval"),
        taskId = requiredString(map, "task_id", "approval"),
        kind = requiredString(map, "kind", "approval"),
        message = requiredString(map, "message", "approval"),
        status = ApprovalStatus.fromWire(requiredString(map, "status", "approval")),
        decision = map["decision"] as? String,
        createdAt = requiredString(map, "created_at", "approval"),
        decidedAt = map["decided_at"] as? String,
    )

    private fun requiredString(map: Map<String, Any?>, key: String, owner: String): String =
        map[key] as? String ?: error("$owner missing $key")

    @Suppress("UNCHECKED_CAST")
    private fun listOfObjects(value: Any?): List<Map<String, Any?>> =
        (value as? List<*>)?.map { it as? Map<String, Any?> ?: error("Expected object in list") } ?: emptyList()

    @Suppress("UNCHECKED_CAST")
    private fun objectMap(value: Any?): Map<String, Any?> =
        (value as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value } ?: emptyMap()

    private fun stringList(value: Any?): List<String> =
        (value as? List<*>)?.map { it.toString() } ?: emptyList()
}
