package shop.terramoor.github.io

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import shop.terramoor.github.io.systemui.MediaCard
import shop.terramoor.github.io.systemui.MediaCardRoute
import shop.terramoor.github.io.systemui.selectMediaCard

class MediaCardSelectionTest {
    private val first = MediaCard("first", "owner.first")
    private val second = MediaCard("second", "owner.second")

    @Test fun findsTheActivePlayerAmongMultipleCards() {
        val selected = selectMediaCard(second.id, listOf(first, second))
        assertEquals(MediaCardRoute.ACTIVE_PLAYER_ID, selected.route)
        assertEquals(second, selected.card)
    }

    @Test fun usesOnlyAnUnambiguousCardWhenTheFirmwareLacksAnActiveId() {
        assertEquals(first, selectMediaCard(null, listOf(first)).card)
        assertEquals(MediaCardRoute.AMBIGUOUS, selectMediaCard(null, listOf(first, second)).route)
        assertNull(selectMediaCard(null, listOf(first, second)).card)
        assertNull(selectMediaCard(null, listOf(first, MediaCard(null, null))).card)
    }

    @Test fun doesNotFallBackWhenTheControllerNamesADifferentOrDuplicatePlayer() {
        assertNull(selectMediaCard(second.id, listOf(first)).card)
        assertNull(selectMediaCard(first.id, listOf(first, first.copy(owner = second.owner))).card)
    }
}
