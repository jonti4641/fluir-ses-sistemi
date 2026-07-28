package com.fluir.bot;

import com.fluir.bot.audio.AudioPlayerManager;
import com.fluir.bot.audio.GuildAudioSession;
import com.fluir.bot.audio.VoiceConnectionState;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.managers.AudioManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GuildAudioSessionTest {

    @Mock private Guild mockGuild;
    @Mock private AudioChannel mockAudioChannel;
    @Mock private GuildMessageChannel mockMessageChannel;
    @Mock private Member mockSelfMember;
    @Mock private AudioManager mockAudioManager;

    private AudioPlayerManager fluirAudioPlayerManager;
    private GuildAudioSession session;

    @BeforeEach
    void setUp() {
        fluirAudioPlayerManager = new AudioPlayerManager();
        session = fluirAudioPlayerManager.getOrCreateSession(mockGuild);

        lenient().when(mockGuild.getIdLong()).thenReturn(123456789L);
        lenient().when(mockGuild.getSelfMember()).thenReturn(mockSelfMember);
        lenient().when(mockGuild.getAudioManager()).thenReturn(mockAudioManager);

        lenient().when(mockSelfMember.hasPermission(any(AudioChannel.class), eq(Permission.VIEW_CHANNEL))).thenReturn(true);
        lenient().when(mockSelfMember.hasPermission(any(AudioChannel.class), eq(Permission.VOICE_CONNECT))).thenReturn(true);
        lenient().when(mockSelfMember.hasPermission(any(AudioChannel.class), eq(Permission.VOICE_SPEAK))).thenReturn(true);

        lenient().when(mockAudioChannel.getIdLong()).thenReturn(987654321L);
        lenient().when(mockAudioChannel.getName()).thenReturn("Test Voice");
    }

    @Test
    @DisplayName("Gerekli izinler yoksa bağlantı reddedilmelidir")
    void testEnsureConnectedMissingPermissions() {
        when(mockSelfMember.hasPermission(mockAudioChannel, Permission.VOICE_CONNECT)).thenReturn(false);

        GuildAudioSession.ConnectionResult result = session.ensureConnected(mockGuild, mockAudioChannel, mockMessageChannel);

        assertFalse(result.success());
        assertTrue(result.message().contains("VOICE_CONNECT"));
        assertEquals(VoiceConnectionState.DISCONNECTED, session.getConnectionState());
        verify(mockAudioManager, never()).openAudioConnection(any());
    }

    @Test
    @DisplayName("Aynı sunucuda eş zamanlı oynatma istekleri yalnızca bir kez openAudioConnection çağırmalıdır")
    void testConcurrentEnsureConnectedCallsOpenAudioConnectionOnce() throws InterruptedException {
        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    latch.await();
                    GuildAudioSession.ConnectionResult res = session.ensureConnected(mockGuild, mockAudioChannel, mockMessageChannel);
                    if (res.success()) {
                        successCount.incrementAndGet();
                    }
                } catch (InterruptedException ignored) {
                }
            });
        }

        latch.countDown();
        executor.shutdown();
        assertTrue(executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS));

        assertEquals(threadCount, successCount.get());
        assertEquals(VoiceConnectionState.CONNECTED, session.getConnectionState());
        verify(mockAudioManager, times(1)).openAudioConnection(mockAudioChannel);
    }
}
