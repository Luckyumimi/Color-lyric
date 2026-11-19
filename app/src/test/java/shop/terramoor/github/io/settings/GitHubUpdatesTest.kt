package shop.terramoor.github.io.settings

import org.junit.Assert.assertEquals
import org.junit.Test
import shop.terramoor.github.io.settings.GitHubUpdates.Comparison

class GitHubUpdatesTest {
    @Test
    fun releaseTagsUseAndroidVersionCodeWhenPresent() {
        assertEquals(Comparison.NEWER, GitHubUpdates.compare("14-1.9", 13, "2.0"))
        assertEquals(Comparison.UP_TO_DATE, GitHubUpdates.compare("13-2.0", 13, "2.0"))
        assertEquals(Comparison.UP_TO_DATE, GitHubUpdates.compare("12-1.9", 13, "2.0"))
    }

    @Test
    fun versionNamesUseNumericSegmentsAndIgnoreTrailingZeros() {
        assertEquals(Comparison.NEWER, GitHubUpdates.compare("v1.10", 1, "1.2"))
        assertEquals(Comparison.UP_TO_DATE, GitHubUpdates.compare("1.2.0", 1, "1.2"))
    }

    @Test
    fun unrecognizedTagsDoNotClaimTheAppIsUpToDate() {
        assertEquals(Comparison.UNKNOWN, GitHubUpdates.compare("spring-release", 1, "1.0"))
    }
}
