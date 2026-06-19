package com.agentsloop.worker

import com.agentsloop.core.Task
import com.agentsloop.orchestrator.LoopOrchestrator

interface WorkerAdapter {
    val name: String
    fun run(task: Task, orchestrator: LoopOrchestrator)
    fun cancel(task: Task) {}
}

object WorkerRegistry {
    fun resolve(name: String): WorkerAdapter =
        when (name.lowercase()) {
            "mock" -> MockWorker
            "codex", "codex-cli" -> PlaceholderWorker("codex", "Codex CLI adapter is reserved for the next milestone.")
            "claude", "claude-code" -> PlaceholderWorker("claude-code", "Claude Code adapter is reserved for the next milestone.")
            "hermes" -> PlaceholderWorker("hermes", "Hermes adapter is reserved for the next milestone.")
            "embabel" -> PlaceholderWorker("embabel", "Embabel adapter is reserved for a Java 21 module.")
            else -> error("Unknown worker '$name'")
        }
}
