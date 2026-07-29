package com.fluir.bot;

import com.fluir.bot.audio.AudioPlayerManager;
import com.fluir.bot.audio.GuildAudioSession;
import net.dv8tion.jda.api.entities.Guild;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FluirBotShutdownTest {

    @Test
    @DisplayName("AudioPlayerManager shutdown tüm oturumları ve kaynakları kapatmalıdır")
    void testShutdownCleansUpAllSessions() {
        AudioPlayerManager manager = new AudioPlayerManager();
        Guild mockGuild = mock(Guild.class);
        when(mockGuild.getIdLong()).thenReturn(555L);

        GuildAudioSession session = manager.getOrCreateSession(mockGuild);
        assertNotNull(session);
        assertFalse(session.isDestroyed());

        manager.shutdown();

        assertTrue(session.isDestroyed());
        assertNull(manager.getSession(555L));
    }
}
