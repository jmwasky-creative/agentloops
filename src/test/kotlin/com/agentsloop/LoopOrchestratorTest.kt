package com.agentsloop

import com.agentsloop.core.IncomingEvent
import com.agentsloop.core.PlannerBundle
import com.agentsloop.core.PlannerTask
import com.agentsloop.core.TaskStatus
import com.agentsloop.ingest.SpoolIngestor
import com.agentsloop.json.SimpleJson
import com.agentsloop.orchestrator.LoopOrchestrator
import com.agentsloop.store.JsonStateStore
import com.agentsloop.worker.MockWorker
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LoopOrchestratorTest {
    @Test
    fun `imported tasks start in backlog and confirmation releases only unblocked tasks`() {
        val harness = Harness()
        val project = harness.orchestrator.importPlan(sampleBundle())

        val imported = harness.orchestrator.state()
        assertEquals(false, imported.projects.getValue(project.id).planConfirmed)
        assertEquals(TaskStatus.BACKLOG, imported.tasks.getValue("task_a").status)
        assertEquals(TaskStatus.BACKLOG, imported.tasks.getValue("task_b").status)

        harness.orchestrator.confirmPlan(project.id)

        val confirmed = harness.orchestrator.state()
        assertEquals(true, confirmed.projects.getValue(project.id).planConfirmed)
        assertEquals(TaskStatus.READY, confirmed.tasks.getValue("task_a").status)
        assertEquals(TaskStatus.BACKLOG, confirmed.tasks.getValue("task_b").status)
    }

    @Test
    fun `dependency task becomes ready only after upstream review is manually marked done`() {
        val harness = Harness()
        harness.orchestrator.importPlan(sampleBundle())
        harness.orchestrator.confirmPlan()

        MockWorker.run(harness.orchestrator.state().tasks.getValue("task_a"), harness.orchestrator)
        assertEquals(TaskStatus.REVIEW, harness.orchestrator.state().tasks.getValue("task_a").status)
        assertEquals(TaskStatus.BACKLOG, harness.orchestrator.state().tasks.getValue("task_b").status)

        harness.orchestrator.markDone("task_a")

        val state = harness.orchestrator.state()
        assertEquals(TaskStatus.DONE, state.tasks.getValue("task_a").status)
        assertEquals(TaskStatus.READY, state.tasks.getValue("task_b").status)
    }

    @Test
    fun `mock worker writes artifact inside workspace and never auto marks done`() {
        val harness = Harness()
        harness.orchestrator.importPlan(sampleBundle())
        harness.orchestrator.confirmPlan()

        MockWorker.run(harness.orchestrator.state().tasks.getValue("task_a"), harness.orchestrator)

        val state = harness.orchestrator.state()
        val task = state.tasks.getValue("task_a")
        assertEquals(TaskStatus.REVIEW, task.status)
        assertEquals("requested", task.reviewStatus)
        assertEquals(1, task.artifactIds.size)
        val artifact = state.artifacts.getValue(task.artifactIds.single())
        assertTrue(Path.of(artifact.path!!).normalize().startsWith(harness.workspacesRoot.resolve(task.id).normalize()))
        assertTrue(Path.of(artifact.path!!).exists())
    }

    @Test
    fun `dangerous approval blocks task and approval decision controls next state`() {
        val harness = Harness()
        harness.orchestrator.importPlan(sampleBundle())
        harness.orchestrator.confirmPlan()

        val approval = harness.orchestrator.requestApproval("task_a", "shell", "Run build command")
        assertEquals(TaskStatus.BLOCKED, harness.orchestrator.state().tasks.getValue("task_a").status)

        harness.orchestrator.decideApproval(approval.id, approve = true, decision = "allowed for test")
        assertEquals(TaskStatus.READY, harness.orchestrator.state().tasks.getValue("task_a").status)

        val second = harness.orchestrator.requestApproval("task_a", "install", "Install dependency")
        harness.orchestrator.decideApproval(second.id, approve = false, decision = "not allowed")
        assertEquals(TaskStatus.FAILED, harness.orchestrator.state().tasks.getValue("task_a").status)
    }

    @Test
    fun `spool ingestor processes valid events rejects invalid files and ignores duplicate external ids`() {
        val harness = Harness()
        harness.orchestrator.importPlan(sampleBundle())
        harness.orchestrator.confirmPlan()
        harness.orchestrator.startTask("task_a")

        val inbox = harness.temp.resolve("spool/inbox")
        inbox.createDirectories()
        val eventJson = SimpleJson.stringify(
            mapOf(
                "external_event_id" to "evt-1",
                "task_id" to "task_a",
                "type" to "agent-message",
                "payload" to mapOf("message" to "hello"),
            ),
        )
        inbox.resolve("valid.json").writeText(eventJson)
        inbox.resolve("invalid.json").writeText("{ nope")

        val first = SpoolIngestor(harness.temp.resolve("spool")).ingest(harness.orchestrator)
        assertEquals(1, first.processed)
        assertEquals(1, first.rejected)
        val eventCountAfterFirst = harness.orchestrator.state().events.size

        inbox.resolve("duplicate.json").writeText(eventJson)
        val second = SpoolIngestor(harness.temp.resolve("spool")).ingest(harness.orchestrator)
        assertEquals(1, second.processed)
        assertEquals(0, second.rejected)
        assertEquals(eventCountAfterFirst, harness.orchestrator.state().events.size)
        assertTrue(harness.temp.resolve("spool/rejected/invalid.json").exists())
    }

    @Test
    fun `artifact event outside task workspace is rejected`() {
        val harness = Harness()
        harness.orchestrator.importPlan(sampleBundle())
        harness.orchestrator.confirmPlan()
        harness.orchestrator.startTask("task_a")

        assertFailsWith<IllegalArgumentException> {
            harness.orchestrator.handleIncoming(
                IncomingEvent(
                    externalEventId = "escape-artifact",
                    projectId = null,
                    taskId = "task_a",
                    type = "artifact-created",
                    role = "coder",
                    agentId = "mock",
                    payload = mapOf(
                        "kind" to "bad",
                        "path" to harness.temp.resolve("../escape.md").toString(),
                        "summary" to "bad path",
                    ),
                ),
            )
        }
        assertTrue(harness.orchestrator.state().artifacts.isEmpty())
    }

    @Test
    fun `json state store round trips imported project`() {
        val harness = Harness()
        val project = harness.orchestrator.importPlan(sampleBundle())

        val reloaded = JsonStateStore(harness.statePath).load()
        assertEquals(project.title, reloaded.projects.getValue(project.id).title)
        assertFalse(harness.statePath.readText().isBlank())
    }

    private fun sampleBundle(): PlannerBundle =
        PlannerBundle(
            title = "TDD sample",
            requirements = "# Requirements",
            design = "# Design",
            tasks = listOf(
                PlannerTask(
                    id = "task_a",
                    title = "Plan UI",
                    role = "architect",
                    description = "Plan the UI",
                    assignedWorker = "mock",
                    acceptanceCriteria = listOf("Plan exists"),
                ),
                PlannerTask(
                    id = "task_b",
                    title = "Build UI",
                    role = "coder",
                    description = "Build the UI",
                    dependencies = listOf("task_a"),
                    assignedWorker = "mock",
                    acceptanceCriteria = listOf("Artifact exists"),
                ),
            ),
        )

    private class Harness {
        val temp: Path = Files.createTempDirectory("agentloops-test-")
        val statePath: Path = temp.resolve("state.json")
        val workspacesRoot: Path = temp.resolve("workspaces")
        val orchestrator: LoopOrchestrator = LoopOrchestrator(JsonStateStore(statePath), workspacesRoot)
    }
}
