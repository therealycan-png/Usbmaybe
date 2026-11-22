package app.pixelferry.core

import java.io.File

class FakeStorage(private val root: File, var capacity: Long = Long.MAX_VALUE / 4) : PixelStorage {
    val published = mutableListOf<File>()
    override fun rootDir() = root
    override fun freeBytes(): Long = capacity - root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    override fun publish(file: File, mime: String, dateTakenMs: Long) { published += file }
}

class FakeClock(var now: Long = 1_000_000L) { fun advance(ms: Long) { now += ms } }

fun tempDir(): File = kotlin.io.path.createTempDirectory("pf").toFile().also { it.deleteOnExit() }
