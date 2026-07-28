package com.fluir.bot.audio;

import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.source.bandcamp.BandcampAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.source.http.HttpAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.source.local.LocalAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.source.soundcloud.SoundCloudAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.source.twitch.TwitchStreamAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.source.vimeo.VimeoAudioSourceManager;
import dev.lavalink.youtube.YoutubeAudioSourceManager;
import dev.lavalink.youtube.clients.Music;
import dev.lavalink.youtube.clients.Web;
import net.dv8tion.jda.api.entities.Guild;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sunucu ses oturumlarını ve LavaPlayer kaynaklarını yöneten sınıf.
 */
public class AudioPlayerManager {

    private static final Logger logger = LoggerFactory.getLogger(AudioPlayerManager.class);

    private final DefaultAudioPlayerManager playerManager;
    private final Map<Long, GuildAudioSession> sessions;
    private final MusicPlaybackService playbackService;

    public AudioPlayerManager() {
        this.playerManager = new DefaultAudioPlayerManager();
        this.sessions = new ConcurrentHashMap<>();
        this.playbackService = new MusicPlaybackService(this);
        registerSources();
    }

    private void registerSources() {
        // 1. Birincil Kaynak: YouTube (Music + Web client)
        try {
            YoutubeAudioSourceManager youtube = new YoutubeAudioSourceManager(
                    true,
                    new Music(),
                    new Web()
            );
            playerManager.registerSourceManager(youtube);
            logger.info("✅ YouTube kaynağı birincil olarak kayıt edildi (Music + Web).");
        } catch (Exception e) {
            logger.warn("⚠️ YouTube kaynağı yüklenemedi: {}", e.getMessage());
        }

        // 2. İkincil Kaynak: SoundCloud
        try {
            playerManager.registerSourceManager(SoundCloudAudioSourceManager.createDefault());
            logger.info("✅ SoundCloud kaynağı ikincil olarak kayıt edildi.");
        } catch (Exception e) {
            logger.error("❌ SoundCloud kaynağı başlatılamadı: {}", e.getMessage(), e);
        }

        // 3. Diğer kaynaklar
        try { playerManager.registerSourceManager(new BandcampAudioSourceManager()); } catch (Exception e) { logger.warn("Bandcamp kaydı atlandı: {}", e.getMessage()); }
        try { playerManager.registerSourceManager(new VimeoAudioSourceManager()); } catch (Exception e) { logger.warn("Vimeo kaydı atlandı: {}", e.getMessage()); }
        try { playerManager.registerSourceManager(new TwitchStreamAudioSourceManager()); } catch (Exception e) { logger.warn("Twitch kaydı atlandı: {}", e.getMessage()); }
        try { playerManager.registerSourceManager(new HttpAudioSourceManager()); } catch (Exception e) { logger.warn("HTTP kaydı atlandı: {}", e.getMessage()); }
        try { playerManager.registerSourceManager(new LocalAudioSourceManager()); } catch (Exception e) { logger.warn("Local kaydı atlandı: {}", e.getMessage()); }

        logger.info("🎵 Ses kaynakları kayıt tamamlandı.");
    }

    public GuildAudioSession getOrCreateSession(Guild guild) {
        return sessions.computeIfAbsent(guild.getIdLong(), id -> new GuildAudioSession(id, playerManager, playbackService));
    }

    public GuildAudioSession getSession(long guildId) {
        return sessions.get(guildId);
    }

    public GuildAudioManager getGuildAudioManager(Guild guild) {
        return new GuildAudioManager(getOrCreateSession(guild));
    }

    public static boolean isUnwantedMedia(String title) {
        if (title == null || title.isBlank()) return false;
        String lower = title.toLowerCase(Locale.ROOT);
        return lower.contains("fragman") ||
               lower.contains("trailer") ||
               lower.contains("teaser") ||
               lower.contains("tanıtım") ||
               lower.contains("tanitim") ||
               lower.contains("dizi") ||
               lower.contains("film") ||
               lower.contains("sinema") ||
               lower.contains("bölüm") ||
               lower.contains("bolum") ||
               lower.contains("official trailer") ||
               lower.contains("movie");
    }

    public void disconnect(Guild guild) {
        GuildAudioSession session = sessions.get(guild.getIdLong());
        if (session != null) {
            session.disconnect(guild);
        }
    }

    public void shutdown() {
        logger.info("🧹 AudioPlayerManager kapatılıyor...");
        SearchPanelCache.shutdown();
        for (GuildAudioSession session : sessions.values()) {
            try {
                session.destroy(null);
            } catch (Exception e) {
                logger.warn("Oturum kapatılırken hata [Guild: {}]: {}", session.getGuildId(), e.getMessage());
            }
        }
        sessions.clear();
        playerManager.shutdown();
        logger.info("✅ AudioPlayerManager başarıyla kapatıldı.");
    }

    public DefaultAudioPlayerManager getPlayerManager() { return playerManager; }
    public MusicPlaybackService getPlaybackService() { return playbackService; }
}
