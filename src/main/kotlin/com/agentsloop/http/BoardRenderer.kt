package com.agentsloop.http

import com.agentsloop.core.LoopState
import com.agentsloop.core.Task
import com.agentsloop.core.boardColumns

object BoardRenderer {
    fun text(state: LoopState): String = buildString {
        appendLine("AgentLoops Board")
        appendLine("================")
        boardColumns.forEach { column ->
            val tasks = state.tasks.values.filter { it.status == column }.sortedBy { it.createdAt }
            appendLine()
            appendLine("${column.wire.uppercase()} (${tasks.size})")
            appendLine("-".repeat(column.wire.length + 4))
            if (tasks.isEmpty()) appendLine("  (empty)")
            tasks.forEach { task ->
                appendLine("  ${task.id} | ${task.role} | ${task.title}")
                appendLine("    worker=${task.assignedWorker} deps=${task.dependencies.joinToString(",").ifBlank { "-" }}")
                appendLine("    review=${task.reviewStatus} artifacts=${task.artifactIds.size} approvals=${task.approvalIds.size}")
            }
        }
    }

    fun html(state: LoopState): String = """
        <!doctype html>
        <html>
        <head>
          <meta charset="utf-8">
          <title>AgentLoops Board</title>
          <style>
            body { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif; margin: 0; background: #f5f5f3; color: #20201f; }
            header { padding: 16px 20px; background: #1f2933; color: white; }
            main { display: grid; grid-template-columns: repeat(7, minmax(180px, 1fr)); gap: 12px; padding: 16px; overflow-x: auto; }
            section { background: #ffffff; border: 1px solid #ddd9d0; border-radius: 8px; min-height: 70vh; }
            h2 { font-size: 14px; margin: 0; padding: 10px 12px; border-bottom: 1px solid #ece8df; }
            article { margin: 10px; padding: 10px; border: 1px solid #dedbd2; border-radius: 8px; background: #fbfaf7; }
            .meta { color: #62615d; font-size: 12px; line-height: 1.45; }
            .title { font-weight: 650; margin-bottom: 6px; }
            code { font-size: 12px; }
          </style>
        </head>
        <body>
          <header><h1>AgentLoops Board</h1></header>
          <main>
            ${boardColumns.joinToString("\n") { column ->
                val tasks = state.tasks.values.filter { it.status == column }.sortedBy { task -> task.createdAt }
                """
                <section>
                  <h2>${escape(column.wire)} (${tasks.size})</h2>
                  ${tasks.joinToString("\n") { card(it) }}
                </section>
                """.trimIndent()
            }}
          </main>
        </body>
        </html>
    """.trimIndent()

    private fun card(task: Task): String = """
        <article>
          <div class="title">${escape(task.title)}</div>
          <div class="meta"><code>${escape(task.id)}</code></div>
          <div class="meta">role=${escape(task.role)} worker=${escape(task.assignedWorker)}</div>
          <div class="meta">deps=${escape(task.dependencies.joinToString(",").ifBlank { "-" })}</div>
          <div class="meta">review=${escape(task.reviewStatus)} artifacts=${task.artifactIds.size} approvals=${task.approvalIds.size}</div>
        </article>
    """.trimIndent()

    private fun escape(value: String): String =
        value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
