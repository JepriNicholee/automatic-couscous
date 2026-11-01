package com.build.mythos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamSmootherTest {
    private fun drainMs(len: Int): Long {
        val pacer = StreamSmoother.Pacer()
        val target = "x".repeat(len)
        var shown = 0
        var ms = 0L
        while (shown < len && ms < 60000) {
            shown = StreamSmoother.nextShown(pacer, target, shown)
            ms += StreamSmoother.FRAME_MS
        }
        assertEquals(len, shown)
        return ms
    }

    @Test fun noBacklogMeansNoStep() = assertEquals(0, StreamSmoother.Pacer().step(0))

    @Test fun neverExceedsBacklog() {
        val p = StreamSmoother.Pacer()
        for (b in 1..50) assertTrue(p.step(b) <= b)
    }

    @Test fun shortSentenceTakesAboutASecond() {
        val ms = drainMs(80)
        assertTrue("terlalu cepat: $ms ms", ms >= 600L)
        assertTrue("terlalu lambat: $ms ms", ms <= 1800L)
    }

    @Test fun longTextCatchesUpWithinSeconds() {
        assertTrue(drainMs(1500) <= 8000L)
    }

    @Test fun largerBacklogIsFasterPerSecond() {
        val small = StreamSmoother.Pacer(); val big = StreamSmoother.Pacer()
        var a = 0; var b = 0
        repeat(62) { a += small.step(40); b += big.step(2000) }
        assertTrue(b > a)
    }

    @Test fun doesNotSplitSurrogatePair() {
        val target = "ab\uD83D\uDE00cd"
        val pacer = StreamSmoother.Pacer()
        for (s in 0..target.length) {
            val n = StreamSmoother.nextShown(pacer, target, s)
            assertTrue(!(n in 1 until target.length && Character.isHighSurrogate(target[n - 1]) && Character.isLowSurrogate(target[n])))
        }
    }
}

class StreamFadeTest {
    @Test fun fadeStartsFaintAndEndsOpaque() {
        assertTrue(StreamSmoother.fadeAlpha(0) < 60)
        assertEquals(255, StreamSmoother.fadeAlpha(StreamSmoother.FADE_MS))
        assertEquals(255, StreamSmoother.fadeAlpha(10_000))
    }

    @Test fun fadeNeverDecreasesWithAge() {
        var prev = -1
        for (ms in 0L..300L step 10L) {
            val a = StreamSmoother.fadeAlpha(ms)
            assertTrue(a >= prev)
            prev = a
        }
    }

    @Test fun rateChangesGraduallyNotInJumps() {
        val p = StreamSmoother.Pacer()
        var first = 0
        repeat(3) { first += p.step(2000) }   // 3 frame pertama pada backlog besar tetap pelan (belum 260 cps)
        assertTrue("terlalu melonjak: $first", first <= 5)
    }
}
