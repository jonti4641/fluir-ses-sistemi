package com.fluir.bot.audio;

import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Arama sonuç panellerini (Select Menu) saklayan, TTL ve yetki kontrollü bellek yöneticisi.
 */
public class SearchPanelCache {

    private static final Logger logger = LoggerFactory.getLogger(SearchPanelCache.class);
    private static final long DEFAULT_TTL_MS = TimeUnit.MINUTES.toMillis(3); // 3 dakika TTL
    private static final int MAX_CAPACITY = 100;

    private static final Map<String, SearchPanelEntry> cache = new ConcurrentHashMap<>();
    private static final ScheduledExecutorService cleanupExecutor = Executors.newSingleThreadScheduledExecutor();

    static {
        // Her 1 dakikada bir süresi dolmuş panelleri temizler
        cleanupExecutor.scheduleAtFixedRate(SearchPanelCache::evictExpired, 1, 1, TimeUnit.MINUTES);
    }

    public static void put(String customId, long userId, long guildId, long channelId, List<AudioTrack> tracks) {
        if (cache.size() >= MAX_CAPACITY) {
            evictExpired();
        }
        cache.put(customId, new SearchPanelEntry(userId, guildId, channelId, tracks, System.currentTimeMillis()));
        logger.debug("🔎 Panel cache eklendi [ID: {}, User: {}]", customId, userId);
    }

    public static SearchPanelResult getAndRemove(String customId, long userId) {
        SearchPanelEntry entry = cache.get(customId);
        if (entry == null) {
            return new SearchPanelResult(null, SearchPanelStatus.EXPIRED_OR_NOT_FOUND);
        }

        if (System.currentTimeMillis() - entry.createdAt > DEFAULT_TTL_MS) {
            cache.remove(customId);
            return new SearchPanelResult(null, SearchPanelStatus.EXPIRED_OR_NOT_FOUND);
        }

        if (entry.userId != userId) {
            return new SearchPanelResult(null, SearchPanelStatus.UNAUTHORIZED);
        }

        cache.remove(customId); // Tek kullanımlık
        return new SearchPanelResult(entry.tracks, SearchPanelStatus.SUCCESS);
    }

    public static void evictExpired() {
        long now = System.currentTimeMillis();
        cache.entrySet().removeIf(e -> (now - e.getValue().createdAt) > DEFAULT_TTL_MS);
    }

    public static void clear() {
        cache.clear();
    }

    public static void shutdown() {
        cleanupExecutor.shutdownNow();
        cache.clear();
    }

    public record SearchPanelEntry(long userId, long guildId, long channelId, List<AudioTrack> tracks, long createdAt) {}

    public record SearchPanelResult(List<AudioTrack> tracks, SearchPanelStatus status) {}

    public enum SearchPanelStatus {
        SUCCESS,
        EXPIRED_OR_NOT_FOUND,
        UNAUTHORIZED
    }
}
