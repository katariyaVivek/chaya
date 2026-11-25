package com.chaya.app.ui.theme

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Holds the sources to DESIGN.md's shape and elevation rules. Corners had drifted to nineteen radii (a pill set by
 * hand as 25, 27 or 31dp, a card at 20 beside one at 16) and floating bars to 12dp shadows, which read as an app put
 * together from parts. Reading the sources catches the next one before it ships.
 */
class ShapeScaleTest {

    /** DESIGN.md › Shape. Anything rounded to half its height is a pill: CircleShape, never a number. */
    private val scale = setOf(6, 10, 12, 14, 16, 18, 24, 28)

    /** DESIGN.md › Elevation: flat first, at most 2dp of shadow. */
    private val maxShadow = 2.0

    private val radius = Regex("""RoundedCornerShape\((\d+(?:\.\d+)?)\.dp\)""")
    private val shadow = Regex("""(?:shadowElevation\s*=\s*|\.shadow\()(\d+(?:\.\d+)?)\.dp""")

    private fun sources(): List<File> =
        File("src/main/java").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
            .also { assertTrue("No sources found from ${File(".").absolutePath}", it.isNotEmpty()) }

    private fun matches(pattern: Regex, bad: (Double) -> Boolean): List<String> = sources().flatMap { file ->
        file.readLines().withIndex()
            .filter { (_, line) -> !line.trimStart().startsWith("*") && !line.trimStart().startsWith("//") }
            .flatMap { (i, line) ->
                pattern.findAll(line).map { it.groupValues[1].toDouble() }.filter(bad).map { "${file.path}:${i + 1} ($it)" }
            }
    }

    @Test
    fun `every corner radius is on the scale`() {
        val found = matches(radius) { it != Math.floor(it) || it.toInt() !in scale }
        assertTrue("Corner radii off DESIGN.md's scale $scale (a pill is CircleShape): $found", found.isEmpty())
    }

    @Test
    fun `no shadow is deeper than 2dp`() {
        val found = matches(shadow) { it > maxShadow }
        assertTrue("Shadows deeper than ${maxShadow}dp; use a hairline border or a container step: $found", found.isEmpty())
    }
}
