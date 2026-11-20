package shop.terramoor.github.io

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import shop.terramoor.github.io.diagnostics.DiagnosticStore
import shop.terramoor.github.io.diagnostics.diagnosticFailure
import shop.terramoor.github.io.diagnostics.diagnosticLine
import shop.terramoor.github.io.diagnostics.diagnosticStack
import shop.terramoor.github.io.systemui.NativeContract

class DiagnosticLogTest {
    @Test fun exportsKnownLookupAndStackLocationsWithoutExceptionMessages() {
        val secret = "private song metadata"
        val failure = NativeContract.Mismatch("resource oplus:id/content", IllegalArgumentException(secret))
        val entry = diagnosticLine(diagnosticFailure("Plugin contract", failure))
        assertTrue(entry.startsWith("Plugin contract | Native contract: resource oplus:id/content"))
        assertTrue(entry.contains("DiagnosticLogTest.exportsKnownLookupAndStackLocationsWithoutExceptionMessages"))
        assertFalse(entry.contains(secret))
    }

    @Test fun removesControlCharactersAndBoundsEachRecord() {
        val entry = diagnosticLine("first\nsecond\t" + "a".repeat(DiagnosticStore.MAX_LINE_LENGTH))
        assertEquals(DiagnosticStore.MAX_LINE_LENGTH, entry.length)
        assertTrue(entry.startsWith("first second "))
        assertFalse(entry.any { it.isISOControl() })
    }

    @Test fun capturesEveryFrameAndCauseWithoutExceptionMessages() {
        val cause = IllegalStateException("private lyric")
        cause.stackTrace = Array(12) { StackTraceElement("example.Provider", "frame$it", "Provider.kt", it + 1) }
        val failure = IllegalArgumentException("private title", cause)
        val lines = diagnosticStack("test", failure)
        assertTrue(lines.any { it.contains("cause=1 type=java.lang.IllegalStateException") })
        assertTrue(lines.any { it.contains("cause=1 frame=11 example.Provider.frame11(Provider.kt:12)") })
        assertFalse(lines.any { it.contains("private lyric") || it.contains("private title") })
    }
}
