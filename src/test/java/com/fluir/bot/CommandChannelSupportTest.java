package com.fluir.bot;

import com.fluir.bot.audio.AudioPlayerManager;
import com.fluir.bot.audio.GuildAudioSession;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.GuildVoiceState;
import net.dv8tion.jda.api.entities.SelfMember;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel;
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CommandChannelSupportTest {

    @Mock private Guild mockGuild;
    @Mock private TextChannel mockTextChannel;
    @Mock private VoiceChannel mockVoiceChannel;
    @Mock private GuildChannel mockUnsupportedChannel;
    @Mock private SelfMember mockMember;
    @Mock private GuildVoiceState mockVoiceState;
    @Mock private AudioChannel mockAudioChannel;

    private AudioPlayerManager audioPlayerManager;
    private GuildAudioSession session;

    @BeforeEach
    void setUp() {
        audioPlayerManager = new AudioPlayerManager();
        session = audioPlayerManager.getOrCreateSession(mockGuild);

        lenient().when(mockGuild.getIdLong()).thenReturn(111222333L);
        lenient().when(mockGuild.getSelfMember()).thenReturn(mockMember);
        lenient().when(mockMember.hasPermission(any(AudioChannel.class), any(Permission.class))).thenReturn(true);
    }

    @Test
    @DisplayName("1. TextChannel (Normal Yazı Kanalı) üzerinden mesaj kanalı ayarlanabilmelidir")
    void testNormalTextChannelSupport() {
        session.setLastMessageChannel(mockTextChannel);
        assertEquals(mockTextChannel, session.getLastMessageChannel());
        assertTrue(mockTextChannel instanceof GuildMessageChannel);
    }

    @Test
    @DisplayName("2. VoiceChannel (Ses Kanalı Yerleşik Sohbeti) üzerinden mesaj kanalı ayarlanabilmelidir ve ClassCastException vermemelidir")
    void testVoiceChannelTextChatSupport() {
        // VoiceChannel, JDA 5'te hem AudioChannel hem de GuildMessageChannel arayüzünü uygular
        session.setLastMessageChannel(mockVoiceChannel);
        assertEquals(mockVoiceChannel, session.getLastMessageChannel());
        assertTrue(mockVoiceChannel instanceof GuildMessageChannel);
        assertDoesNotThrow(() -> {
            GuildMessageChannel channel = session.getLastMessageChannel();
            assertNotNull(channel);
        });
    }

    @Test
    @DisplayName("3. Desteklenmeyen kanal türlerinde GuildMessageChannel cast işlemi güvenle reddedilmelidir")
    void testUnsupportedChannelTypeHandling() {
        assertFalse(mockUnsupportedChannel instanceof GuildMessageChannel);
        GuildMessageChannel resolved = mockUnsupportedChannel instanceof GuildMessageChannel gmc ? gmc : null;
        assertNull(resolved);
    }

    @Test
    @DisplayName("4. Idle timer bir GuildMessageChannel olmadan da (null guard ile) güvenle başlatılabilmelidir")
    void testIdleTimerWithoutTextChannel() {
        session.setLastMessageChannel(null);
        assertNull(session.getLastMessageChannel());
        assertDoesNotThrow(() -> session.scheduleIdleTimer(mockGuild, 60));
        session.cancelIdleTimer();
    }
}
