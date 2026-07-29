package com.fluir.bot;

import com.fluir.bot.audio.GuildAudioSession;
import com.fluir.bot.audio.TrackScheduler;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TrackSchedulerTest {

    @Mock private AudioPlayer mockPlayer;
    @Mock private GuildAudioSession mockSession;
    @Mock private AudioTrack track1;
    @Mock private AudioTrack track2;
    @Mock private AudioTrack track3;

    private TrackScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new TrackScheduler(mockPlayer, mockSession);

        lenient().when(track1.getInfo()).thenReturn(new AudioTrackInfo("Şarkı 1", "Sanatçı 1", 180000, "1", false, "http://1"));
        lenient().when(track2.getInfo()).thenReturn(new AudioTrackInfo("Şarkı 2", "Sanatçı 2", 200000, "2", false, "http://2"));
        lenient().when(track3.getInfo()).thenReturn(new AudioTrackInfo("Şarkı 3", "Sanatçı 3", 220000, "3", false, "http://3"));

        lenient().when(track1.makeClone()).thenReturn(track1);
        lenient().when(track2.makeClone()).thenReturn(track2);
    }

    @Test
    @DisplayName("Track Exception sonrasında onTrackEnd çağrılırsa kuyruk iki kez ilerlememelidir")
    void testTrackExceptionFollowedByOnTrackEndDoesNotDoubleAdvance() {
        when(mockPlayer.startTrack(any(), eq(true))).thenReturn(false);

        scheduler.queue(track1);
        scheduler.queue(track2);
        scheduler.queue(track3);

        assertEquals(3, scheduler.getQueue().size());

        // Exception meydana geliyor
        scheduler.onTrackException(mockPlayer, track1, new FriendlyException("Stream hatası", FriendlyException.Severity.COMMON, null));

        // Exception sonrası ilk eleman (track1) alındı
        assertEquals(2, scheduler.getQueue().size());

        // Arka arkaya tetiklenen onTrackEnd
        scheduler.onTrackEnd(mockPlayer, track1, AudioTrackEndReason.LOAD_FAILED);

        // Kuyruktan ikinci şarkı (track2) eksilmemiş olmalıdır
        assertEquals(2, scheduler.getQueue().size());
    }

    @Test
    @DisplayName("Skip işlemi yalnızca bir şarkı atlamalıdır")
    void testSkipAdvancesOnlyOneTrack() {
        when(mockPlayer.startTrack(any(), eq(true))).thenReturn(false);

        scheduler.queue(track1);
        scheduler.queue(track2);
        scheduler.queue(track3);

        assertEquals(3, scheduler.getQueue().size());

        scheduler.nextTrack(); // track1 başlar
        assertEquals(2, scheduler.getQueue().size());

        scheduler.nextTrack(); // track2 başlar
        assertEquals(1, scheduler.getQueue().size());
    }

    @Test
    @DisplayName("Stop sonrasında gelen eski track end olayları yeni şarkı başlatmamalıdır")
    void testStopPreventsStaleTrackEndEventsFromStartingNewTracks() {
        when(mockPlayer.startTrack(any(), eq(true))).thenReturn(false);

        scheduler.queue(track1);
        scheduler.queue(track2);

        scheduler.stop();
        assertNull(scheduler.getCurrentTrack());
        assertEquals(0, scheduler.getQueue().size());

        scheduler.onTrackEnd(mockPlayer, track1, AudioTrackEndReason.FINISHED);
        verify(mockPlayer, never()).startTrack(track2, false);
    }

    @Test
    @DisplayName("Döngü yalnızca FINISHED yanıtında çalışmalıdır")
    void testLoopTriggersOnlyOnFinishedReason() {
        scheduler.setLoop(true);

        // REPLACED nedenli bitiş
        scheduler.onTrackEnd(mockPlayer, track1, AudioTrackEndReason.REPLACED);
        verify(mockPlayer, never()).startTrack(any(), eq(false));
    }

    @Test
    @DisplayName("Fallback başladıktan sonra eski parçanın gecikmiş LOAD_FAILED olayı kuyruğu ilerletmemelidir")
    void testStaleFailedTrackEndDoesNotReplaceFallback() {
        when(mockPlayer.startTrack(track1, true)).thenReturn(true);
        when(mockPlayer.startTrack(track2, true)).thenReturn(false);

        scheduler.queue(track1);
        scheduler.queue(track2);
        scheduler.startFallbackTrack(track3);

        scheduler.onTrackEnd(mockPlayer, track1, AudioTrackEndReason.LOAD_FAILED);

        assertSame(track3, scheduler.getCurrentTrack());
        assertEquals(1, scheduler.getQueue().size());
        verify(mockPlayer, never()).startTrack(track2, false);
    }
}
