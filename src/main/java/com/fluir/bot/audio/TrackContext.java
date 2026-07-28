package com.fluir.bot.audio;

import java.util.EnumSet;

/**
 * Her bir AudioTrack için kullanıcı, sorgu ve denenmiş kaynak bilgilerini saklayan context modeli.
 * AudioTrack.setUserData() üzerinden parçaya iliştirilir.
 */
public record TrackContext(
        String originalQuery,
        String title,
        String author,
        PlaybackSource source,
        EnumSet<PlaybackSource> attemptedSources,
        int fallbackAttempt,
        long requestedBy,
        long messageChannelId
) {
    public TrackContext {
        if (attemptedSources == null) {
            attemptedSources = EnumSet.noneOf(PlaybackSource.class);
        }
    }

    public static TrackContext create(String originalQuery, String title, String author, PlaybackSource source, long requestedBy, long messageChannelId) {
        EnumSet<PlaybackSource> set = EnumSet.noneOf(PlaybackSource.class);
        if (source != null) {
            set.add(source);
        }
        return new TrackContext(originalQuery, title, author, source, set, 0, requestedBy, messageChannelId);
    }

    public TrackContext withAttempt(PlaybackSource newSource) {
        EnumSet<PlaybackSource> newSet = EnumSet.copyOf(attemptedSources);
        if (newSource != null) {
            newSet.add(newSource);
        }
        return new TrackContext(originalQuery, title, author, newSource, newSet, fallbackAttempt + 1, requestedBy, messageChannelId);
    }
}
