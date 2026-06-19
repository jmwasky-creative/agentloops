package com.agentsloop.http

import com.agentsloop.orchestrator.LoopOrchestrator
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

class LoopHttpServer(
    private val orchestrator: LoopOrchestrator,
    private val port: Int,
    private val host: String = "127.0.0.1",
) {
    fun start() {
        val server = ServerSocket(port, 50, InetAddress.getByName(host))
        thread(name = "agentloops-http-$port", isDaemon = false) {
            while (!server.isClosed) {
                val socket = server.accept()
                thread(name = "agentloops-http-client", isDaemon = true) {
                    socket.use { handle(it) }
                }
            }
        }
        println("AgentLoops board running at http://$host:$port")
    }

    private fun handle(socket: Socket) {
        val requestLine = socket.getInputStream().bufferedReader().readLine().orEmpty()
        val path = requestLine.split(" ").getOrNull(1) ?: "/"
        val contentType: String
        val body: String
        if (path == "/events") {
            contentType = "text/plain; charset=utf-8"
            body = orchestrator.state().events
                .sortedBy { it.sequence }
                .joinToString("\n") { "${it.sequence} ${it.type.wire} ${it.taskId ?: "-"} ${it.payload}" }
        } else {
            contentType = "text/html; charset=utf-8"
            body = BoardRenderer.html(orchestrator.state())
        }
        val bytes = body.toByteArray(Charsets.UTF_8)
        val header = listOf(
            "HTTP/1.1 200 OK",
            "Content-Type: $contentType",
            "Content-Length: ${bytes.size}",
            "Connection: close",
            "",
            "",
        ).joinToString("\r\n")
        socket.getOutputStream().use { out ->
            out.write(header.toByteArray(Charsets.UTF_8))
            out.write(bytes)
        }
    }
}
