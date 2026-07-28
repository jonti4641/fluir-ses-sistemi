package com.fluir.bot.audio;

import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel;
import net.dv8tion.jda.api.events.guild.voice.GuildVoiceUpdateEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JDA ses güncellemelerini dinleyerek kanalda yalnız kalınma durumlarını yöneten dinleyici.
 */
public class VoiceUpdateListener extends ListenerAdapter {

    private static final Logger logger = LoggerFactory.getLogger(VoiceUpdateListener.class);
    private final AudioPlayerManager audioPlayerManager;

    public VoiceUpdateListener(AudioPlayerManager audioPlayerManager) {
        this.audioPlayerManager = audioPlayerManager;
    }

    @Override
    public void onGuildVoiceUpdate(GuildVoiceUpdateEvent event) {
        // Botun kendi olaylarını es geç (döngü oluşmaması için)
        if (event.getMember().getUser().getIdLong() == event.getJDA().getSelfUser().getIdLong()) {
            return;
        }

        Guild guild = event.getGuild();
        GuildAudioSession session = audioPlayerManager.getSession(guild.getIdLong());
        if (session == null || session.getConnectionState() == VoiceConnectionState.DISCONNECTED) {
            return;
        }

        AudioChannel botChannel = guild.getAudioManager().getConnectedChannel();
        if (botChannel == null) {
            return;
        }

        // Kanaldaki bot harici insan sayısı
        long humanCount = botChannel.getMembers().stream()
                .filter(m -> !m.getUser().isBot())
                .count();

        if (humanCount == 0) {
            logger.info("👥 [Guild: {}] Kanalda insan üye kalmadı, yalnız kalma timer'ı başlatılıyor.", guild.getId());
            session.scheduleAloneTimer(guild, 60);
        } else {
            logger.debug("👥 [Guild: {}] Kanalda {} insan üye var, yalnız kalma timer'ı iptal ediliyor.", guild.getId(), humanCount);
            session.cancelAloneTimer();
        }
    }
}
