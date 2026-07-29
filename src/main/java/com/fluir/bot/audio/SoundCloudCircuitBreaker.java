package com.fluir.bot.audio;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/** SoundCloud sorunlarını sunucular arasında yaymadan izler ve bozuk URI'leri geçici engeller. */
public class SoundCloudCircuitBreaker {
    private static final Logger logger = LoggerFactory.getLogger(SoundCloudCircuitBreaker.class);
    private static final int FAILURE_THRESHOLD = 3;
    private static final long TIMEFRAME_MS = 120_000;
    private static final long OPEN_DURATION_MS = 120_000;
    private static final long URI_BLOCK_MS = 600_000;
    private static final Map<Long, State> states = new ConcurrentHashMap<>();
    private static final Map<String, Long> blockedUris = new ConcurrentHashMap<>();

    public static void recordFailure(long guildId, String uri) {
        long now = System.currentTimeMillis();
        State state = states.computeIfAbsent(guildId, ignored -> new State());
        state.failures.add(now);
        state.failures.removeIf(timestamp -> now - timestamp > TIMEFRAME_MS);
        if (uri != null && !uri.isBlank()) blockedUris.put(key(guildId, uri), now + URI_BLOCK_MS);
        if (state.failures.size() >= FAILURE_THRESHOLD) {
            state.openUntil.set(now + OPEN_DURATION_MS);
            state.failures.clear();
            logger.warn("SoundCloud devresi guild={} için 120 saniye açıldı", guildId);
        }
        cleanup(now);
    }

    /** Eski test/istemciler için guild=0 uyumluluğu. */
    public static void recordFailure() { recordFailure(0, null); }
    public static boolean isOpen() { return isOpen(0); }
    public static void reset() { states.clear(); blockedUris.clear(); }

    public static boolean isOpen(long guildId) {
        State state = states.get(guildId);
        return state != null && System.currentTimeMillis() < state.openUntil.get();
    }

    public static boolean isBlacklisted(long guildId, String uri) {
        if (uri == null) return false;
        Long until = blockedUris.get(key(guildId, uri));
        return until != null && System.currentTimeMillis() < until;
    }

    public static void recordSuccess(long guildId, String uri) {
        State state = states.get(guildId);
        if (state != null) state.failures.clear();
        if (uri != null) blockedUris.remove(key(guildId, uri));
    }

    public static String status(long guildId) {
        return isOpen(guildId) ? "AÇIK (koruma aktif)" : "Kapalı / sağlıklı";
    }

    public static void reset(long guildId) {
        states.remove(guildId);
        blockedUris.keySet().removeIf(key -> key.startsWith(guildId + ":"));
    }

    private static String key(long guildId,String uri){return guildId+":"+MusicPlaybackService.sanitizeUri(uri).toLowerCase();}
    private static void cleanup(long now){if(blockedUris.size()>10_000)blockedUris.entrySet().removeIf(e->e.getValue()<now);}
    private static final class State { final Queue<Long> failures=new ConcurrentLinkedQueue<>(); final AtomicLong openUntil=new AtomicLong(); }
}
