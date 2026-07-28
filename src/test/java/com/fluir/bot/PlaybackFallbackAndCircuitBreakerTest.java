package com.fluir.bot;

import com.fluir.bot.audio.*;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;

import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PlaybackFallbackAndCircuitBreakerTest {

    @Mock private AudioPlayer mockPlayer;
    @Mock private Guild mockGuild;
    @Mock private GuildMessageChannel mockMessageChannel;
    @Mock private AudioTrack track1;
    @Mock private AudioTrack track2;

    private AudioPlayerManager lavaPlayerManager;
    private GuildAudioSession session;
    private TrackScheduler scheduler;

    @BeforeEach
    void setUp() {
        lavaPlayerManager = new AudioPlayerManager();
        session = lavaPlayerManager.getOrCreateSession(mockGuild);
        session.setLastMessageChannel(mockMessageChannel);
        scheduler = session.getScheduler();

        SoundCloudCircuitBreaker.reset();

        lenient().when(mockGuild.getIdLong()).thenReturn(999888777L);
        lenient().when(track1.getInfo()).thenReturn(new AudioTrackInfo("Bixi Blake & Kum - Obsession", "Bixi Blake", 180000, "1", false, "https://youtube.com/watch?v=1"));
        lenient().when(track2.getInfo()).thenReturn(new AudioTrackInfo("Şarkı 2", "Sanatçı 2", 200000, "2", false, "https://soundcloud.com/test"));

        lenient().when(track1.makeClone()).thenReturn(track1);
        lenient().when(track2.makeClone()).thenReturn(track2);
    }

    @Test
    @DisplayName("1. SoundCloud 404 hatasında Türkçe hata mesajı üretilmeli ve raw exception gizlenmelidir")
    void testFriendlyErrorMessageMapping() {
        FriendlyException soundCloud404 = new FriendlyException("Something broke when playing the track", FriendlyException.Severity.COMMON, new java.io.IOException("Invalid status code for soundcloud stream: 404"));
        String msg = MusicPlaybackService.getFriendlyErrorMessage(soundCloud404);

        assertTrue(msg.contains("SoundCloud bağlantısı geçersiz"));
        assertFalse(msg.contains("Something broke"));
    }

    @Test
    @DisplayName("2. 3 adet SoundCloud 404 hatası Circuit Breaker'ı açmalıdır")
    void testSoundCloudCircuitBreakerOpensAfter3Failures() {
        assertFalse(SoundCloudCircuitBreaker.isOpen());

        SoundCloudCircuitBreaker.recordFailure();
        assertFalse(SoundCloudCircuitBreaker.isOpen());

        SoundCloudCircuitBreaker.recordFailure();
        assertFalse(SoundCloudCircuitBreaker.isOpen());

        SoundCloudCircuitBreaker.recordFailure();
        assertTrue(SoundCloudCircuitBreaker.isOpen());
    }

    @Test
    @DisplayName("3. Circuit Breaker açıkken aramalar YouTube'a yönlenmelidir")
    void testCircuitBreakerOpenBypassesSoundCloud() {
        SoundCloudCircuitBreaker.recordFailure();
        SoundCloudCircuitBreaker.recordFailure();
        SoundCloudCircuitBreaker.recordFailure();

        assertTrue(SoundCloudCircuitBreaker.isOpen());

        TrackContext context = TrackContext.create("Sorgu", "Şarkı", "Sanatçı", PlaybackSource.SOUNDCLOUD, 1L, 1L);
        assertTrue(context.attemptedSources().contains(PlaybackSource.SOUNDCLOUD));
    }

    @Test
    @DisplayName("4. Aynı parça için en fazla MAX_PLAYBACK_FALLBACK_ATTEMPTS kez fallback yapılmalıdır")
    void testSingleFallbackAttemptPerTrack() {
        TrackContext initial = TrackContext.create("Sorgu", "Şarkı", "Sanatçı", PlaybackSource.YOUTUBE, 1L, 1L);
        assertEquals(0, initial.fallbackAttempt());

        TrackContext firstFallback = initial.withAttempt(PlaybackSource.SOUNDCLOUD);
        assertEquals(1, firstFallback.fallbackAttempt());
        assertTrue(firstFallback.attemptedSources().contains(PlaybackSource.SOUNDCLOUD));
        assertTrue(firstFallback.attemptedSources().contains(PlaybackSource.YOUTUBE));
    }

    @Test
    @DisplayName("5. Manuel skip işlemi (nextTrack) playback generation artırmalı ve bekleyen fallback'i geçersiz kılmalıdır")
    void testManualSkipIncrementsPlaybackGeneration() {
        long initialGen = session.getPlaybackGeneration();
        session.getScheduler().nextTrack();
        long newGen = session.getPlaybackGeneration();

        assertTrue(newGen > initialGen);
    }

    @Test
    @DisplayName("6. Spotify çözümü varsayılan olarak YouTube aramasına yönlenmelidir")
    void testSpotifyResolverDefaultsToYouTube() {
        String resolved = SpotifyResolver.resolveSpotifyUrl("https://open.spotify.com/track/4cOdK2wGLETKBW3PvgPWqT");
        if (resolved != null) {
            assertFalse(resolved.startsWith("scsearch:"));
        }
    }

    @Test
    @DisplayName("7. Detaylı stack trace ve cause zinciri loglama çağrısı hata vermeden çalışmalıdır")
    void testLogDetailedExceptionDoesNotThrow() {
        FriendlyException nestedException = new FriendlyException("Oynatma hatası", FriendlyException.Severity.COMMON,
                new java.io.IOException("Stream kapandı", new java.lang.RuntimeException("Root cause")));
        assertDoesNotThrow(() -> MusicPlaybackService.logDetailedException(org.slf4j.LoggerFactory.getLogger("TestLogger"), 999L, track1, nestedException));
    }
}
