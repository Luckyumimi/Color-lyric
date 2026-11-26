package shop.terramoor.github.io

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import shop.terramoor.github.io.lyrics.LyricPayload

class CapsuleLyricSourceTest {
    private val privateKey = LyricPayload.metadataKey(superLyricOnly = true)
    private val sharedKey = LyricPayload.KEY

    @Test
    fun superLyricUsesOnlyItsOwnMetadataWhenBothProvidersPublish() {
        val metadata = mapOf(privateKey to "SuperLyric", sharedKey to "Universal Player")
        assertEquals("SuperLyric", LyricPayload.selectMetadata(true, metadata::get))
        assertEquals("Universal Player", LyricPayload.selectMetadata(false, metadata::get))
    }

    @Test
    fun superLyricWaitsForItsOwnLyricsWithoutReadingSharedMetadata() {
        val reads = mutableListOf<String>()
        val result = LyricPayload.selectMetadata(superLyricOnly = true) { key ->
            reads += key
            if (key == sharedKey) "Universal Player" else null
        }
        assertNull(result)
        assertEquals(listOf(privateKey), reads)
    }

    @Test
    fun removingSuperLyricLyricsKeepsSharedLyricsAvailable() {
        val metadata = mapOf(privateKey to "", sharedKey to "Universal Player")
        assertNull(LyricPayload.selectMetadata(true, metadata::get))
        assertEquals("Universal Player", LyricPayload.selectMetadata(false, metadata::get))
    }
}
