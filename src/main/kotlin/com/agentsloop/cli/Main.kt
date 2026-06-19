package com.agentsloop.cli

import com.agentsloop.http.BoardRenderer
import com.agentsloop.http.LoopHttpServer
import com.agentsloop.ingest.SpoolIngestor
import com.agentsloop.json.SimpleJson
import com.agentsloop.orchestrator.LoopOrchestrator
import com.agentsloop.store.JsonStateStore
import com.agentsloop.store.StateCodec
import com.agentsloop.worker.WorkerRegistry
import java.nio.file.Path
import kotlin.io.path.readText

fun main(args: Array<String>) {
    if (args.isEmpty()) return usage()
    val parsed = Args(args.toList())
    val statePath = Path.of(parsed.option("--state") ?: ".agentsloop/state.json")
    val orchestrator = LoopOrchestrator(JsonStateStore(statePath))

    when (val command = parsed.command) {
        "import-plan" -> {
            val file = parsed.argument(0) ?: error("import-plan requires a tasks.json path")
            val bundle = StateCodec.bundleFromMap(SimpleJson.parseObject(Path.of(file).readText()))
            val project = orchestrator.importPlan(bundle)
            println("Imported project ${project.id}: ${project.title}")
        }
        "confirm-plan" -> {
            val ready = orchestrator.confirmPlan(parsed.option("--project"))
            println("Plan confirmed. Ready tasks: $ready")
        }
        "run-ready" -> {
            val workerName = parsed.option("--worker") ?: "mock"
            val worker = WorkerRegistry.resolve(workerName)
            val tasks = orchestrator.readyTasks(workerName)
            tasks.forEach { worker.run(it, orchestrator) }
            println("Ran ${tasks.size} ready task(s) with ${worker.name}")
        }
        "board" -> println(BoardRenderer.text(orchestrator.state()))
        "serve" -> {
            val port = parsed.option("--port")?.toIntOrNull() ?: 8787
            val host = parsed.option("--host") ?: "127.0.0.1"
            LoopHttpServer(orchestrator, port, host).start()
            Thread.currentThread().join()
        }
        "ingest-spool" -> {
            val report = SpoolIngestor(Path.of(parsed.option("--spool") ?: ".agentsloop/spool")).ingest(orchestrator)
            println("Processed ${report.processed}, rejected ${report.rejected}")
        }
        "approval" -> {
            val taskId = parsed.option("--task") ?: error("approval requires --task")
            val kind = parsed.option("--kind") ?: error("approval requires --kind")
            val message = parsed.option("--message") ?: "Approval requested"
            val approval = orchestrator.requestApproval(taskId, kind, message)
            println("Created approval ${approval.id}")
        }
        "approve", "deny" -> {
            val approvalId = parsed.argument(0) ?: error("$command requires approval id")
            val decision = parsed.option("--decision") ?: command
            val approval = orchestrator.decideApproval(approvalId, approve = command == "approve", decision = decision)
            println("${approval.id} -> ${approval.status.wire}")
        }
        "mark-done" -> {
            val taskId = parsed.argument(0) ?: error("mark-done requires task id")
            val task = orchestrator.markDone(taskId)
            println("${task.id} -> ${task.status.wire}")
        }
        else -> usage()
    }
}

private class Args(private val args: List<String>) {
    val command: String = args.first()

    fun option(name: String): String? {
        val index = args.indexOf(name)
        return if (index >= 0 && index + 1 < args.size) args[index + 1] else null
    }

    fun argument(offset: Int): String? =
        args.drop(1).filterNot { it.startsWith("--") }.getOrNull(offset)
}

private fun usage() {
    println(
        """
        AgentLoops commands:
          import-plan <tasks.json> [--state path]
          confirm-plan [--project id] [--state path]
          run-ready [--worker mock] [--state path]
          board [--state path]
          serve [--host 127.0.0.1] [--port 8787] [--state path]
          ingest-spool [--spool path] [--state path]
          approval --task taskId --kind shell --message text [--state path]
          approve <approvalId> [--decision text] [--state path]
          deny <approvalId> [--decision text] [--state path]
          mark-done <taskId> [--state path]
        """.trimIndent(),
    )
}
