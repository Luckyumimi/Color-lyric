package shop.terramoor.github.io.systemui

import org.junit.Assert.assertEquals
import org.junit.Test

class CapsuleCloseGeometryTest {
    @Test fun `left-biased collapse keeps the native shrinking width around the camera`() {
        val frames = listOf(120 to 340, 121 to 250, 155 to 218)
        val centered = frames.map { (left, right) ->
            val offset = capsuleCloseOffset(230f, (left + right) / 2f, 1f)
            left + offset to right + offset
        }
        assertEquals(listOf(120 to 340, 166 to 295, 199 to 262), centered)
    }

    @Test fun `scaled parent converts the screen offset to layout coordinates`() {
        assertEquals(90, capsuleCloseOffset(230f, 185f, 0.5f))
    }
}
