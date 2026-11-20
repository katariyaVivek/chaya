package com.chaya.app.speech

/** How whisper.cpp is run on this phone's processor. Plain Kotlin: fed what the phone reports. */
object CpuChoice {
    /**
     * Whether the fast build can run: an arm64 phone whose cores all report half-precision vector arithmetic
     * (`asimdhp`) and dot products (`asimddp`) in /proc/cpuinfo's Features, as every Cortex-A55, A75 and newer does.
     * A phone of only Cortex-A53s has neither, and the fast build would end with an illegal instruction.
     */
    fun fastBuild(cpuinfo: String, abi: String?): Boolean {
        if (abi != "arm64-v8a") return false
        val features = cpuinfo.lines().filter { it.trimStart().startsWith("Features", ignoreCase = true) }
            .map { it.substringAfter(':').trim().split(Regex("\\s+")).toSet() }
        return features.isNotEmpty() && features.all { "asimdhp" in it && "asimddp" in it }
    }

    /**
     * Threads for whisper.cpp: the fast cores (those quicker than the slowest), and at least four where the phone has
     * them, as its matrix work is shared out in small pieces a slow core can still help with, but each step waits for
     * the last thread; every core when all are alike; never more than 8. [speeds] are the cores' top speeds; [cores]
     * how many there are, for when the speeds can't be read.
     */
    fun threads(speeds: List<Long>, cores: Int): Int {
        if (speeds.isEmpty()) return minOf(4, cores).coerceIn(1, 8)
        val slowest = speeds.min()
        val fast = speeds.count { it > slowest }
        return (if (fast == 0) speeds.size else maxOf(fast, 4)).coerceAtMost(speeds.size).coerceIn(1, 8)
    }
}
