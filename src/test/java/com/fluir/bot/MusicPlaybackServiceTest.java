package com.fluir.bot;

import com.fluir.bot.audio.AudioPlayerManager;
import com.fluir.bot.audio.MusicPlaybackService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MusicPlaybackServiceTest {

    @Test
    @DisplayName("Film, dizi, fragman ve teaser başlıkları filtrelenmelidir")
    void testIsUnwantedMedia() {
        assertTrue(AudioPlayerManager.isUnwantedMedia("Kurtlar Vadisi 1. Bölüm Fragman"));
        assertTrue(AudioPlayerManager.isUnwantedMedia("Inception Official Trailer HD"));
        assertTrue(AudioPlayerManager.isUnwantedMedia("Yüzüklerin Efendisi Teaser"));
        assertTrue(AudioPlayerManager.isUnwantedMedia("GORA Film Sahnesi"));
        assertTrue(AudioPlayerManager.isUnwantedMedia("Marvel Movie Clip"));

        assertFalse(AudioPlayerManager.isUnwantedMedia("Sezen Aksu - Firuze (Official Audio)"));
        assertFalse(AudioPlayerManager.isUnwantedMedia("Daft Punk - Get Lucky"));
    }

    @Test
    @DisplayName("Çalma listesi üst sınırı 100 parça olmalıdır")
    void testMaxPlaylistTracksLimit() {
        assertEquals(100, MusicPlaybackService.MAX_PLAYLIST_TRACKS);
    }
}
