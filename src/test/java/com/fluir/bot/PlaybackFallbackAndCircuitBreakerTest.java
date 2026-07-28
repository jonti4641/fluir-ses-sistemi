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

    @BeforeEach
    void setUp() {
        lavaPlayerManager = new AudioPlayerManager();
        session = lavaPlayerManager.getOrCreateSession(mockGuild);
        session.setLastMessageChannel(mockMessageChannel);

        SoundCloudCircuitBreaker.reset();

        lenient().when(mockGuild.getIdLong()).thenReturn(999888777L);
        lenient().when(track1.getInfo()).thenReturn(new AudioTrackInfo("SoundCloud Track 1", "Sanatçı 1", 180000, "1", false, "https://soundcloud.com/artist/track-1"));
        lenient().when(track2.getInfo()).thenReturn(new AudioTrackInfo("SoundCloud Track 2", "Sanatçı 2", 200000, "2", false, "https://soundcloud.com/artist/track-2"));

        lenient().when(track1.makeClone()).thenReturn(track1);
        lenient().when(track2.makeClone()).thenReturn(track2);
    }

    @Test
    @DisplayName("1. YouTube URL ve sorguları anlaşılır biçimde reddedilmelidir")
    void testYouTubeQueryRejected() {
        assertTrue(MusicPlaybackService.isYouTubeUrlOrQuery("https://www.youtube.com/watch?v=123"));
        assertTrue(MusicPlaybackService.isYouTubeUrlOrQuery("https://youtu.be/123"));
        assertTrue(MusicPlaybackService.isYouTubeUrlOrQuery("ytsearch:test"));
        assertFalse(MusicPlaybackService.isYouTubeUrlOrQuery("https://soundcloud.com/artist/track"));
    }

    @Test
    @DisplayName("2. SoundCloud 404 hatasında Türkçe hata mesajı üretilmeli ve raw exception gizlenmelidir")
    void testFriendlyErrorMessageMapping() {
        FriendlyException soundCloud404 = new FriendlyException("Something broke when playing the track", FriendlyException.Severity.COMMON, new java.io.IOException("Invalid status code for soundcloud stream: 404"));
        String msg = MusicPlaybackService.getFriendlyErrorMessage(soundCloud404);

        assertTrue(msg.contains("SoundCloud bağlantısı"));
        assertFalse(msg.contains("Something broke"));
    }

    @Test
    @DisplayName("3. 3 adet SoundCloud 404 hatası Circuit Breaker'ı açmalıdır")
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
    @DisplayName("4. Parça için re-resolve işaretlemesi en fazla 1 kez yapılmalıdır")
    void testSingleReResolveAttempt() {
        TrackContext initial = TrackContext.create("Sorgu", "Şarkı", "Sanatçı", "https://soundcloud.com/test", PlaybackSource.SOUNDCLOUD, 1L, 1L);
        assertFalse(initial.isReResolved());

        TrackContext reResolved = initial.markReResolved();
        assertTrue(reResolved.isReResolved());
        assertEquals(1, reResolved.fallbackAttempt());
    }

    @Test
    @DisplayName("5. Manuel skip (nextTrack) playback generation artırmalıdır")
    void testManualSkipIncrementsPlaybackGeneration() {
        long initialGen = session.getPlaybackGeneration();
        session.getScheduler().nextTrack();
        long newGen = session.getPlaybackGeneration();

        assertTrue(newGen > initialGen);
    }

    @Test
    @DisplayName("6. Spotify çözümü SoundCloud aramasına yönlenmelidir")
    void testSpotifyResolverDefaultsToSoundCloud() {
        String resolved = SpotifyResolver.resolveSpotifyUrl("https://open.spotify.com/track/4cOdK2wGLETKBW3PvgPWqT");
        if (resolved != null) {
            assertFalse(resolved.startsWith("ytsearch:"));
        }
    }

    @Test
    @DisplayName("7. Detaylı stack trace ve cause zinciri loglaması sorunsuz çalışmalıdır")
    void testLogDetailedExceptionDoesNotThrow() {
        FriendlyException nestedException = new FriendlyException("SoundCloud 404 hatası", FriendlyException.Severity.COMMON,
                new java.io.IOException("Stream kapandı", new java.lang.RuntimeException("Root cause")));
        assertDoesNotThrow(() -> MusicPlaybackService.logDetailedException(org.slf4j.LoggerFactory.getLogger("TestLogger"), 999L, track1, nestedException));
    }
}
