package com.fluir.bot;

import com.fluir.bot.audio.NowPlayingPanel;
import com.fluir.bot.watch.WatchPartyService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PanelAndWatchPartyTest {
    @Test
    void detailedClockSupportsMinutesAndHours() {
        assertEquals("00:00", NowPlayingPanel.formatClock(0));
        assertEquals("03:07", NowPlayingPanel.formatClock(187_000));
        assertEquals("01:02:03", NowPlayingPanel.formatClock(3_723_000));
        assertEquals("CANLI", NowPlayingPanel.formatClock(Long.MAX_VALUE));
    }

    @Test
    void progressBarHasOneMarkerAndFixedWidth() {
        String start = NowPlayingPanel.progressBar(0, 100_000, 20);
        String middle = NowPlayingPanel.progressBar(50_000, 100_000, 20);
        String end = NowPlayingPanel.progressBar(100_000, 100_000, 20);
        assertEquals(20, start.codePointCount(0, start.length()));
        assertEquals(1, start.chars().filter(c -> c == '●').count());
        assertTrue(start.startsWith("●"));
        assertTrue(middle.indexOf('●') >= 9);
        assertTrue(end.endsWith("●"));
    }

    @Test
    void youtubeIdParserAllowsOnlySupportedVideoLinks() {
        assertEquals("dQw4w9WgXcQ", WatchPartyService.extractYouTubeId("https://www.youtube.com/watch?v=dQw4w9WgXcQ"));
        assertEquals("dQw4w9WgXcQ", WatchPartyService.extractYouTubeId("https://youtu.be/dQw4w9WgXcQ?t=30"));
        assertEquals("dQw4w9WgXcQ", WatchPartyService.extractYouTubeId("https://youtube.com/shorts/dQw4w9WgXcQ"));
        assertNull(WatchPartyService.extractYouTubeId("https://example.com/watch?v=dQw4w9WgXcQ"));
        assertNull(WatchPartyService.extractYouTubeId("javascript:alert(1)"));
    }
}
