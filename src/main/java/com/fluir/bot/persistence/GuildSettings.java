package com.fluir.bot.persistence;

public record GuildSettings(
        long guildId,
        int defaultVolume,
        int idleSeconds,
        int maxQueueSize,
        boolean autoplay,
        boolean announcements,
        boolean prefixCommands,
        long djRoleId,
        long commandChannelId
) {
    public static GuildSettings defaults(long guildId) {
        return new GuildSettings(guildId, 100, 90, 100, false, true, true, 0, 0);
    }

    public GuildSettings normalized() {
        return new GuildSettings(guildId, clamp(defaultVolume, 0, 150), clamp(idleSeconds, 30, 900),
                clamp(maxQueueSize, 10, 500), autoplay, announcements, prefixCommands,
                Math.max(0, djRoleId), Math.max(0, commandChannelId));
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
