package com.fluir.bot.audio;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;

/**
 * Bir sunucunun (Guild) tüm ses bileşenlerini bir arada tutan sınıf.
 */
public class GuildAudioManager {

    public final AudioPlayer player;
    public final TrackScheduler scheduler;
    private final AudioPlayerSendHandler sendHandler;

    public GuildAudioManager(com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager manager) {
        this.player = manager.createPlayer();
        this.scheduler = new TrackScheduler(this.player);
        this.player.addListener(this.scheduler);
        this.sendHandler = new AudioPlayerSendHandler(this.player);
    }

    public AudioPlayerSendHandler getSendHandler() {
        return sendHandler;
    }
}
