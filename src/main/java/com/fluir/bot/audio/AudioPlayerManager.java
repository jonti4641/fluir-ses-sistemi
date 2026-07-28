package com.fluir.bot.audio;

import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManagers;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import dev.lavalink.youtube.YoutubeAudioSourceManager;
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
 * YouTube için dev.lavalink.youtube:v2 kullanır (LavaPlayer'ın dahili YT desteği bozuk).
 */
public class AudioPlayerManager {

    private static final Logger logger = LoggerFactory.getLogger(AudioPlayerManager.class);

    private final DefaultAudioPlayerManager playerManager;
    private final Map<Long, GuildAudioManager> guildAudioManagers;

    public AudioPlayerManager() {
        this.playerManager = new DefaultAudioPlayerManager();
        this.guildAudioManagers = new HashMap<>();

        // ✅ Güncel YouTube kaynağı — dahili YT desteği YERİNE bunu kullan
        YoutubeAudioSourceManager ytSourceManager = new YoutubeAudioSourceManager();
        playerManager.registerSourceManager(ytSourceManager);

        // Diğer kaynaklar: SoundCloud, Twitch, Vimeo, Bandcamp, HTTP...
        // YouTube'u manuel kaydettiğimiz için registerRemoteSources'tan çıkarıyoruz
        AudioSourceManagers.registerRemoteSources(playerManager,
                com.sedmelluq.discord.lavaplayer.source.youtube.YoutubeAudioSourceManager.class);

        // Yerel dosya desteği
        AudioSourceManagers.registerLocalSource(playerManager);

        logger.info("🎵 AudioPlayerManager başlatıldı — YouTube v2 kaynağı aktif.");
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
     * Parçayı yükler ve çalar (TextChannel mesaj versiyonu — prefix komutlar için).
     */
    public void loadAndPlay(Guild guild, TextChannel textChannel, VoiceChannel voiceChannel, String trackUrl) {
        GuildAudioManager manager = getGuildAudioManager(guild);
        manager.scheduler.setAnnouncementChannel(textChannel);

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
                    AudioTrack track = playlist.getTracks().get(0);
                    logger.info("🔍 Arama sonucu: {}", track.getInfo().title);
                    textChannel.sendMessage("🎵 Çalınıyor: **" + track.getInfo().title + "**").queue();
                    manager.scheduler.queue(track);
                } else {
                    textChannel.sendMessage("📃 **" + playlist.getName() + "** — `" +
                            playlist.getTracks().size() + "` parça eklendi!").queue();
                    for (AudioTrack track : playlist.getTracks()) {
                        manager.scheduler.queue(track);
                    }
                }
            }

            @Override
            public void noMatches() {
                textChannel.sendMessage("❌ **\"" + trackUrl.replace("ytsearch:", "") + "\"** için sonuç bulunamadı!").queue();
                logger.warn("Sonuç bulunamadı: {}", trackUrl);
            }

            @Override
            public void loadFailed(FriendlyException exception) {
                textChannel.sendMessage("❌ Yükleme hatası: **" + exception.getMessage() + "**").queue();
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
