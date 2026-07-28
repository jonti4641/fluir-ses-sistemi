package com.fluir.bot.audio;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

/**
 * Her bir AudioTrack için kullanıcı, sorgu, denenmiş kaynaklar ve istemciler bilgisini saklayan context modeli.
 * AudioTrack.setUserData() üzerinden parçaya iliştirilir.
 */
public record TrackContext(
        String originalQuery,
        String title,
        String author,
        PlaybackSource source,
        EnumSet<PlaybackSource> attemptedSources,
        Set<String> attemptedClients,
        int fallbackAttempt,
        long requestedBy,
        long messageChannelId
) {
    public TrackContext {
        if (attemptedSources == null) {
            attemptedSources = EnumSet.noneOf(PlaybackSource.class);
        }
        if (attemptedClients == null) {
            attemptedClients = new HashSet<>();
        }
    }

    public static TrackContext create(String originalQuery, String title, String author, PlaybackSource source, long requestedBy, long messageChannelId) {
        EnumSet<PlaybackSource> set = EnumSet.noneOf(PlaybackSource.class);
        if (source != null) {
            set.add(source);
        }
        Set<String> clients = new HashSet<>();
        return new TrackContext(originalQuery, title, author, source, set, clients, 0, requestedBy, messageChannelId);
    }

    public TrackContext withAttempt(PlaybackSource newSource) {
        EnumSet<PlaybackSource> newSet = EnumSet.copyOf(attemptedSources);
        if (newSource != null) {
            newSet.add(newSource);
        }
        Set<String> newClients = new HashSet<>(attemptedClients);
        return new TrackContext(originalQuery, title, author, newSource, newSet, newClients, fallbackAttempt + 1, requestedBy, messageChannelId);
    }

    public TrackContext withAttemptedClient(String clientName) {
        Set<String> newClients = new HashSet<>(attemptedClients);
        if (clientName != null) {
            newClients.add(clientName);
        }
        return new TrackContext(originalQuery, title, author, source, attemptedSources, newClients, fallbackAttempt, requestedBy, messageChannelId);
    }
}
