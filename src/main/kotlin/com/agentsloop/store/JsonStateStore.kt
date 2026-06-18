package com.agentsloop.store

import com.agentsloop.core.LoopState
import com.agentsloop.json.SimpleJson
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText

class JsonStateStore(private val path: Path) {
    fun load(): LoopState {
        if (!path.exists()) return LoopState()
        val raw = path.readText()
        if (raw.isBlank()) return LoopState()
        return StateCodec.stateFromMap(SimpleJson.parseObject(raw))
    }

    fun save(state: LoopState) {
        path.parent?.createDirectories()
        Files.writeString(
            path,
            SimpleJson.stringify(StateCodec.stateToMap(state)) + "\n",
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
        )
    }

    fun update(mutator: (LoopState) -> LoopState): LoopState {
        val next = mutator(load())
        save(next)
        return next
    }
}
