package com.agentsloop.http

import com.agentsloop.orchestrator.LoopOrchestrator
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress

class LoopHttpServer(
    private val orchestrator: LoopOrchestrator,
    private val port: Int,
) {
    fun start() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", port), 0)
        server.createContext("/") { exchange ->
            val body = BoardRenderer.html(orchestrator.state()).toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.createContext("/events") { exchange ->
            val body = orchestrator.state().events
                .sortedBy { it.sequence }
                .joinToString("\n") { "${it.sequence} ${it.type.wire} ${it.taskId ?: "-"} ${it.payload}" }
                .toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "text/plain; charset=utf-8")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        println("AgentLoops board running at http://127.0.0.1:$port")
    }
}
