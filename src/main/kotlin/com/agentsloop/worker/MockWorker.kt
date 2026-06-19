package com.agentsloop.worker

import com.agentsloop.core.IncomingEvent
import com.agentsloop.core.Task
import com.agentsloop.orchestrator.LoopOrchestrator
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

object MockWorker : WorkerAdapter {
    override val name: String = "mock"

    override fun run(task: Task, orchestrator: LoopOrchestrator) {
        val running = orchestrator.startTask(task.id)
        val workspace = Path.of(running.workspace ?: error("Task ${task.id} has no workspace"))
        workspace.createDirectories()

        emit(orchestrator, running, "agent-message", mapOf("message" to "Mock worker started ${running.title}"))
        emit(orchestrator, running, "tool-call-start", mapOf("tool" to "mock-write-artifact"))

        val artifactPath = workspace.resolve("artifact-${running.id}.md").normalize()
        artifactPath.writeText(
            """
            # ${running.title}

            Role: ${running.role}

            ${running.description}

            ## Acceptance Criteria

            ${running.acceptanceCriteria.joinToString("\n") { "- $it" }}
            """.trimIndent() + "\n",
        )

        emit(orchestrator, running, "file-written", mapOf("path" to artifactPath.toString()))
        emit(orchestrator, running, "tool-call-end", mapOf("tool" to "mock-write-artifact", "ok" to true))
        emit(
            orchestrator,
            running,
            "artifact-created",
            mapOf(
                "kind" to "mock-review-artifact",
                "path" to artifactPath.toString(),
                "summary" to "Mock artifact for ${running.title}",
            ),
        )
        emit(orchestrator, running, "review-requested", mapOf("summary" to "Mock worker completed; human review required."))
    }

    private fun emit(orchestrator: LoopOrchestrator, task: Task, type: String, payload: Map<String, Any?>) {
        orchestrator.handleIncoming(
            IncomingEvent(
                externalEventId = "mock-${task.id}-$type-${payload.hashCode()}",
                projectId = task.projectId,
                taskId = task.id,
                type = type,
                role = task.role,
                agentId = name,
                payload = payload,
            ),
        )
    }
}

class PlaceholderWorker(
    override val name: String,
    private val message: String,
) : WorkerAdapter {
    override fun run(task: Task, orchestrator: LoopOrchestrator) {
        orchestrator.startTask(task.id)
        orchestrator.handleIncoming(
            IncomingEvent(
                externalEventId = "$name-${task.id}-placeholder",
                projectId = task.projectId,
                taskId = task.id,
                type = "task-failed",
                role = task.role,
                agentId = name,
                payload = mapOf("error" to message),
            ),
        )
    }
}
