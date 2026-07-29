package com.fluir.bot.audio;

import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.source.bandcamp.BandcampAudioSourceManager;
import com.fluir.bot.config.BotConfig;
import com.fluir.bot.monitoring.SecureWebhookNotifier;
import com.fluir.bot.persistence.PersistentStore;
import com.fluir.bot.watch.WatchPartyService;
import com.sedmelluq.discord.lavaplayer.source.soundcloud.SoundCloudAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.source.twitch.TwitchStreamAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.source.vimeo.VimeoAudioSourceManager;
import net.dv8tion.jda.api.entities.Guild;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sunucu ses oturumlarını ve LavaPlayer kaynaklarını yöneten sınıf.
 * Bu müzik botunda birincil ses kaynağı SoundCloud olarak yapılandırılmıştır.
 */
public class AudioPlayerManager {

    private static final Logger logger = LoggerFactory.getLogger(AudioPlayerManager.class);

    private final DefaultAudioPlayerManager playerManager;
    private final Map<Long, GuildAudioSession> sessions;
    private final MusicPlaybackService playbackService;
    private final PersistentStore store;
    private final BotConfig config;
    private final SecureWebhookNotifier notifier;
    private final WatchPartyService watchPartyService;

    public AudioPlayerManager() {
        this(BotConfig.load(), new PersistentStore(BotConfig.load().dataDirectory()), new SecureWebhookNotifier(""));
    }

    public AudioPlayerManager(BotConfig config, PersistentStore store, SecureWebhookNotifier notifier) {
        this.playerManager = new DefaultAudioPlayerManager();
        this.sessions = new ConcurrentHashMap<>();
        this.store = store;
        this.config = config;
        this.notifier = notifier;
        this.watchPartyService = new WatchPartyService(config.publicBaseUrl(), config.discordToken());
        this.playbackService = new MusicPlaybackService(this, store, config, notifier);
        registerSources();
    }

    private void registerSources() {
        // 1. Birincil Kaynak: SoundCloud
        try {
            playerManager.registerSourceManager(SoundCloudAudioSourceManager.builder()
                    .withAllowSearch(true)
                    .withFilterOutPreviewTracks(true)
                    .build());
            logger.info("✅ SoundCloud birincil ses kaynağı olarak kayıt edildi.");
            logger.info("ℹ️ SoundCloud Sağlık Durumu: [Search: AKTİF | Resolve: AKTİF | Preview Filter: AKTİF]");
        } catch (Exception e) {
            logger.error("❌ SoundCloud kaynağı başlatılamadı: {}", e.getMessage(), e);
        }

        // Güvenlik: genel HTTP ve yerel dosya kaynakları özellikle kaydedilmez (SSRF/LFI koruması).
        try { playerManager.registerSourceManager(new BandcampAudioSourceManager()); } catch (Exception e) { logger.warn("Bandcamp kaydı atlandı: {}", e.getMessage()); }
        try { playerManager.registerSourceManager(new VimeoAudioSourceManager()); } catch (Exception e) { logger.warn("Vimeo kaydı atlandı: {}", e.getMessage()); }
        try { playerManager.registerSourceManager(new TwitchStreamAudioSourceManager()); } catch (Exception e) { logger.warn("Twitch kaydı atlandı: {}", e.getMessage()); }

        logger.info("🎵 Ses kaynakları kayıt işlemi tamamlandı.");
    }

    public GuildAudioSession getOrCreateSession(Guild guild) {
        return sessions.computeIfAbsent(guild.getIdLong(), id -> new GuildAudioSession(id, playerManager, playbackService, store, notifier));
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
    public PersistentStore getStore() { return store; }
    public BotConfig getConfig() { return config; }
    public SecureWebhookNotifier getNotifier() { return notifier; }
    public int getSessionCount() { return sessions.size(); }
    public WatchPartyService getWatchPartyService() { return watchPartyService; }
}
