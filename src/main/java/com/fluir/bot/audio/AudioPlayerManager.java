package com.fluir.bot.audio;

import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManagers;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import net.dv8tion.jda.api.managers.AudioManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Tüm sunucuların ses yöneticilerini ve LavaPlayer'ı yöneten ana sınıf.
 */
public class AudioPlayerManager {

    private static final Logger logger = LoggerFactory.getLogger(AudioPlayerManager.class);

    private final DefaultAudioPlayerManager playerManager;
    private final Map<Long, GuildAudioManager> guildAudioManagers;

    public AudioPlayerManager() {
        this.playerManager = new DefaultAudioPlayerManager();
        this.guildAudioManagers = new HashMap<>();

        // Tüm ses kaynaklarını etkinleştir (YouTube, SoundCloud, Twitch, vb.)
        AudioSourceManagers.registerRemoteSources(playerManager);
        AudioSourceManagers.registerLocalSource(playerManager);

        logger.info("🎵 AudioPlayerManager başlatıldı - Tüm kaynaklar aktif.");
    }

    /**
     * Bir sunucunun ses yöneticisini döndürür veya oluşturur.
     */
    public synchronized GuildAudioManager getGuildAudioManager(Guild guild) {
        long guildId = guild.getIdLong();
        GuildAudioManager manager = guildAudioManagers.get(guildId);

        if (manager == null) {
            manager = new GuildAudioManager(playerManager);
            guildAudioManagers.put(guildId, manager);
        }

        guild.getAudioManager().setSendingHandler(manager.getSendHandler());
        return manager;
    }

    /**
     * Ses kanalına bağlanır ve parçayı yükler/çalar.
     */
    public void loadAndPlay(Guild guild, TextChannel textChannel, VoiceChannel voiceChannel, String trackUrl) {
        GuildAudioManager manager = getGuildAudioManager(guild);
        manager.scheduler.setAnnouncementChannel(textChannel);

        // Ses kanalına bağlan
        AudioManager audioManager = guild.getAudioManager();
        if (!audioManager.isConnected()) {
            audioManager.openAudioConnection(voiceChannel);
            logger.info("📢 {} kanalına bağlanıldı.", voiceChannel.getName());
        }

        playerManager.loadItemOrdered(manager, trackUrl, new AudioLoadResultHandler() {

            @Override
            public void trackLoaded(AudioTrack track) {
                logger.info("✅ Parça yüklendi: {}", track.getInfo().title);
                textChannel.sendMessage("✅ Kuyruğa eklendi: **" + track.getInfo().title + "**").queue();
                manager.scheduler.queue(track);
            }

            @Override
            public void playlistLoaded(AudioPlaylist playlist) {
                if (playlist.isSearchResult()) {
                    // Arama sonucu - ilk parçayı al
                    AudioTrack track = playlist.getTracks().get(0);
                    logger.info("🔍 Arama sonucu: {}", track.getInfo().title);
                    textChannel.sendMessage("🔍 Bulunan parça: **" + track.getInfo().title + "**").queue();
                    manager.scheduler.queue(track);
                } else {
                    // Çalma listesi
                    textChannel.sendMessage("📃 **" + playlist.getName() + "** çalma listesi yükleniyor! (" + playlist.getTracks().size() + " parça)").queue();
                    for (AudioTrack track : playlist.getTracks()) {
                        manager.scheduler.queue(track);
                    }
                }
            }

            @Override
            public void noMatches() {
                textChannel.sendMessage("❌ **\"" + trackUrl + "\"** için sonuç bulunamadı!").queue();
                logger.warn("Sonuç bulunamadı: {}", trackUrl);
            }

            @Override
            public void loadFailed(FriendlyException exception) {
                textChannel.sendMessage("❌ Parça yüklenirken hata: **" + exception.getMessage() + "**").queue();
                logger.error("Parça yükleme hatası: {}", exception.getMessage());
            }
        });
    }

    /**
     * Sunucunun ses bağlantısını keser ve yöneticiyi temizler.
     */
    public void disconnect(Guild guild) {
        GuildAudioManager manager = guildAudioManagers.get(guild.getIdLong());
        if (manager != null) {
            manager.player.stopTrack();
            manager.scheduler.getQueue().clear();
        }
        guild.getAudioManager().closeAudioConnection();
        logger.info("🔇 {} sunucusundan ayrıldı.", guild.getName());
    }

    public DefaultAudioPlayerManager getPlayerManager() {
        return playerManager;
    }
}
