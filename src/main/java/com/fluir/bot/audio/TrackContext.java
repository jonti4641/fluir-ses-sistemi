package com.fluir.bot.audio;

import java.util.EnumSet;

/**
 * Her bir AudioTrack için kullanıcı, sorgu ve kalıcı SoundCloud URI bilgisini saklayan context modeli.
 * AudioTrack.setUserData() üzerinden parçaya iliştirilir.
 */
public record TrackContext(
        String originalQuery,
        String title,
        String author,
        String permanentUri,
        PlaybackSource source,
        EnumSet<PlaybackSource> attemptedSources,
        boolean isReResolved,
        int fallbackAttempt,
        long requestedBy,
        long messageChannelId
) {
    public TrackContext {
        if (attemptedSources == null) {
            attemptedSources = EnumSet.noneOf(PlaybackSource.class);
        }
    }

    public static TrackContext create(String originalQuery, String title, String author, String permanentUri, PlaybackSource source, long requestedBy, long messageChannelId) {
        EnumSet<PlaybackSource> set = EnumSet.noneOf(PlaybackSource.class);
        if (source != null) {
            set.add(source);
        }
        return new TrackContext(originalQuery, title, author, permanentUri, source, set, false, 0, requestedBy, messageChannelId);
    }

    public TrackContext markReResolved() {
        return new TrackContext(originalQuery, title, author, permanentUri, source, attemptedSources, true, fallbackAttempt + 1, requestedBy, messageChannelId);
    }

    public TrackContext markAlternativeResolved() {
        return new TrackContext(originalQuery, title, author, permanentUri, source, attemptedSources, true, fallbackAttempt + 1, requestedBy, messageChannelId);
    }
}
