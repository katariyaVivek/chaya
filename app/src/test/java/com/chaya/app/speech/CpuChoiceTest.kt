package com.chaya.app.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which whisper.cpp build and how many threads, from what phones report. */
class CpuChoiceTest {
    /** /proc/cpuinfo's Features line on a Cortex-A55 or A78 (a Dimensity 900, a Snapdragon 8 Gen 1). */
    private val v82 = "fp asimd evtstrm aes pmull sha1 sha2 crc32 atomics fphp asimdhp cpuid asimdrdm lrcpc dcpop asimddp"

    /** And on a Cortex-A53 (a Snapdragon 450): neither half precision nor dot products. */
    private val v80 = "fp asimd evtstrm aes pmull sha1 sha2 crc32 cpuid"

    private fun cpuinfo(vararg features: String) =
        features.mapIndexed { i, f -> "processor\t: $i\nBogoMIPS\t: 26.00\nFeatures\t: $f\nCPU part\t: 0xd05\n" }.joinToString("\n")

    @Test
    fun `the fast build runs where every core does half precision and dot products`() {
        assertTrue(CpuChoice.fastBuild(cpuinfo(v82, v82, v82, v82), "arm64-v8a"))
        assertFalse("a Cortex-A53 phone gets the baseline", CpuChoice.fastBuild(cpuinfo(v80, v80), "arm64-v8a"))
        assertFalse("one core without them is enough to keep it off", CpuChoice.fastBuild(cpuinfo(v82, v80), "arm64-v8a"))
        assertFalse("an emulator's x86_64", CpuChoice.fastBuild(cpuinfo(v82), "x86_64"))
        assertFalse("nothing read", CpuChoice.fastBuild("", "arm64-v8a"))
    }

    @Test
    fun `threads follow the fast cores, at least four, never a thread for every slow core`() {
        // Dimensity 900 (OnePlus Nord CE 2): two A78 at 2.4 GHz, six A55 at 2.0 GHz
        assertEquals(4, CpuChoice.threads(listOf(2_000_000L, 2_000_000, 2_000_000, 2_000_000, 2_000_000, 2_000_000, 2_400_000, 2_400_000), 8))
        // Snapdragon 8 Gen 1: one X2, three A710, four A510
        assertEquals(4, CpuChoice.threads(listOf(1_800_000L, 1_800_000, 1_800_000, 1_800_000, 2_500_000, 2_500_000, 2_500_000, 3_000_000), 8))
        // Six fast cores of eight
        assertEquals(6, CpuChoice.threads(List(2) { 1_700_000L } + List(6) { 2_600_000L }, 8))
        // All alike: every core
        assertEquals(4, CpuChoice.threads(List(4) { 1_800_000L }, 4))
        // Speeds unreadable
        assertEquals(4, CpuChoice.threads(emptyList(), 8))
        assertEquals(2, CpuChoice.threads(emptyList(), 2))
    }
}
