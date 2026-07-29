package com.fluir.bot.security;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Kullanıcı+sunucu bazlı kayan pencereli basit kötüye kullanım koruması. */
public final class CommandRateLimiter {
    private final int capacity;
    private final long windowMs;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public CommandRateLimiter(int capacity, long windowMs) {
        this.capacity = capacity;
        this.windowMs = windowMs;
    }

    public boolean allow(long guildId, long userId) {
        long now = System.currentTimeMillis();
        String key = guildId + ":" + userId;
        Window window = windows.compute(key, (ignored, old) -> {
            if (old == null || now - old.startedAt >= windowMs) return new Window(now, 1);
            return new Window(old.startedAt, old.count + 1);
        });
        if (windows.size() > 20_000) windows.entrySet().removeIf(e -> now - e.getValue().startedAt >= windowMs);
        return window.count <= capacity;
    }

    private record Window(long startedAt, int count) {}
}
