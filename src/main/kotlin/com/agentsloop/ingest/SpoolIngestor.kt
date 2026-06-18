package com.agentsloop.ingest

import com.agentsloop.json.SimpleJson
import com.agentsloop.orchestrator.LoopOrchestrator
import com.agentsloop.store.StateCodec
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readText

data class IngestReport(
    val processed: Int,
    val rejected: Int,
)

class SpoolIngestor(
    private val root: Path = Path.of(".agentsloop", "spool"),
) {
    fun ingest(orchestrator: LoopOrchestrator): IngestReport {
        val inbox = root.resolve("inbox")
        val processed = root.resolve("processed")
        val rejected = root.resolve("rejected")
        inbox.createDirectories()
        processed.createDirectories()
        rejected.createDirectories()

        var ok = 0
        var bad = 0
        val stream = Files.list(inbox)
        try {
            stream
                .filter { it.isRegularFile() && it.name.endsWith(".json") }
                .sorted()
                .forEach { file ->
                    try {
                        val map = SimpleJson.parseObject(file.readText())
                        val incoming = StateCodec.incomingEventFromMap(map)
                        orchestrator.handleIncoming(incoming)
                        move(file, processed)
                        ok++
                    } catch (ex: Exception) {
                        val target = rejected.resolve(file.name)
                        Files.writeString(rejected.resolve("${file.name}.error.txt"), ex.message ?: ex.toString())
                        Files.move(file, target, StandardCopyOption.REPLACE_EXISTING)
                        bad++
                    }
                }
        } finally {
            stream.close()
        }
        return IngestReport(ok, bad)
    }

    private fun move(file: Path, targetDir: Path) {
        var target = targetDir.resolve(file.name)
        if (target.exists()) {
            target = targetDir.resolve("${System.currentTimeMillis()}-${file.name}")
        }
        Files.move(file, target, StandardCopyOption.REPLACE_EXISTING)
    }
}
