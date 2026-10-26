package dev.aidos.aidin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiscoveryTest {
    private val day = 24L * 60 * 60 * 1000
    private val now = 1_800_000_000_000L
    private val group = GroupAuthority(SigningKey.generate()).groupId

    @Test
    fun advertRoundTripsThroughTxt() {
        val advert = DiscoveryAdvert(SigningKey.generate().nodeId, 47100, setOf(GroupTags.tag(group, now)))
        assertEquals(advert, DiscoveryAdvert.fromTxt(advert.toTxt(), 47100))
    }

    @Test
    fun advertCarriesNoNamesOrModels() {
        val txt = DiscoveryAdvert(SigningKey.generate().nodeId, 47100, setOf(GroupTags.tag(group, now))).toTxt()
        assertEquals(setOf("v", "n", "g"), txt.keys)
    }

    @Test
    fun foreignOrMalformedTxtIsIgnored() {
        val id = SigningKey.generate().nodeId.value
        assertNull(DiscoveryAdvert.fromTxt(mapOf("v" to "2", "n" to id), 1))
        assertNull(DiscoveryAdvert.fromTxt(mapOf("v" to "1", "n" to "not-an-id"), 1))
        assertNull(DiscoveryAdvert.fromTxt(mapOf("v" to "1", "n" to id), 0))
        assertNull(DiscoveryAdvert.fromTxt(mapOf("n" to id), 1))
    }

    @Test
    fun groupTagsRotateDailyAndMatchTodayOrYesterday() {
        val today = GroupTags.tag(group, now)
        val tomorrow = GroupTags.tag(group, now + day)
        assertNotEquals(today, tomorrow)
        assertNotEquals(today, GroupTags.tag(GroupAuthority(SigningKey.generate()).groupId, now))

        val advert = DiscoveryAdvert(SigningKey.generate().nodeId, 1, setOf(today))
        assertEquals(setOf(group), GroupTags.matching(advert, listOf(group), now))
        // A peer whose clock is still on yesterday's tag is recognised after midnight.
        assertEquals(setOf(group), GroupTags.matching(advert, listOf(group), now + day))
        assertTrue(GroupTags.matching(advert, listOf(group), now + 2 * day).isEmpty())
    }
}
