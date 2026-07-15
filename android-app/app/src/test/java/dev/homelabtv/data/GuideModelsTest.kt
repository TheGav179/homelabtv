package dev.homelabtv.data

import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class GuideModelsTest {

    @Before
    fun forceUtc() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    @Test
    fun `parses xmltv time with timezone`() {
        // 2026-07-14T12:00:00Z
        assertEquals(1784030400000L, XmltvTime.parse("20260714120000 +0000"))
    }

    @Test
    fun `parses xmltv time without timezone as local time`() {
        assertEquals(1784030400000L, XmltvTime.parse("20260714120000"))
    }

    @Test
    fun `rejects garbage timestamps`() {
        assertNull(XmltvTime.parse(null))
        assertNull(XmltvTime.parse(""))
        assertNull(XmltvTime.parse("not a time"))
    }

    @Test
    fun `normalizes dash and dot channel numbers to match`() {
        assertEquals(normalizeChannelNumber("7-1"), normalizeChannelNumber("7.1"))
    }

    @Test
    fun `channel numbers sort by subchannel count not decimal value`() {
        val numbers = listOf("6.12", "6.6", "20.1", "6.10", "6.7", "13.4", "6.2")
        val sorted = numbers.sortedWith(compareBy({ channelMajor(it) }, { channelMinor(it) }))
        assertEquals(listOf("6.2", "6.6", "6.7", "6.10", "6.12", "13.4", "20.1"), sorted)
    }

    @Test
    fun `buildSlots fills gaps and clips to the window`() {
        val hour = 60 * 60 * 1000L
        val windowStart = 1784030400000L // 12:00Z
        val windowEnd = windowStart + 4 * hour

        val programs =
            listOf(
                // Started before the window, ends 13:00Z -> clipped at window start
                Program("Early Show", "20260714110000 +0000", "20260714130000 +0000", null, null, null),
                // 14:00-15:00Z, leaving a 13:00-14:00Z hole
                Program("Late Show", "20260714140000 +0000", "20260714150000 +0000", null, null, null),
            )

        val slots = buildSlots(programs, windowStart, windowEnd)

        assertEquals(4, slots.size)
        val first = slots[0] as GuideSlot.Show
        assertEquals("Early Show", first.program.title)
        assertEquals(windowStart, first.startMillis)
        assertEquals(windowStart + hour, first.stopMillis)

        val gap = slots[1] as GuideSlot.Gap
        assertEquals(windowStart + hour, gap.startMillis)
        assertEquals(windowStart + 2 * hour, gap.stopMillis)

        val second = slots[2] as GuideSlot.Show
        assertEquals("Late Show", second.program.title)

        val tailGap = slots[3] as GuideSlot.Gap
        assertEquals(windowEnd, tailGap.stopMillis)
    }

    @Test
    fun `programAt and programAfter find current and next shows`() {
        val guide =
            ChannelGuide(
                physical_channel = "7.1",
                xmltv_id = "abc",
                name = "ABC",
                logo_url = null,
                programs =
                    listOf(
                        Program("Now Showing", "20260714120000 +0000", "20260714130000 +0000", null, null, null),
                        Program("Up Next", "20260714130000 +0000", "20260714140000 +0000", null, null, null),
                    ),
            )
        val now = 1784030400000L + 10 * 60 * 1000L // 12:10Z
        assertEquals("Now Showing", guide.programAt(now)?.title)
        assertEquals("Up Next", guide.programAfter(now)?.title)
    }
}
