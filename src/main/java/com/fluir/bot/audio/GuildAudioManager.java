package com.fluir.bot.audio;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;

/**
 * Bir sunucunun (Guild) ses oturumunu temsil eden uyumluluk sınıfı.
 */
public class GuildAudioManager {

    public final GuildAudioSession session;
    public final AudioPlayer player;
    public final TrackScheduler scheduler;

    public GuildAudioManager(GuildAudioSession session) {
        this.session = session;
        this.player = session.getPlayer();
        this.scheduler = session.getScheduler();
    }

    public AudioPlayerSendHandler getSendHandler() {
        return session.getSendHandler();
    }
}
