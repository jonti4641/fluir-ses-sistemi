package com.fluir.bot.persistence;

import com.fluir.bot.audio.TrackContext;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;

public record StoredTrack(String uri, String title, String author, long durationMs,
                          String originalQuery, long requestedBy, long messageChannelId) {
    public static StoredTrack from(AudioTrack track) {
        TrackContext context = track.getUserData() instanceof TrackContext tc ? tc : null;
        return new StoredTrack(track.getInfo().uri, track.getInfo().title, track.getInfo().author,
                track.getDuration(), context == null ? track.getInfo().title : context.originalQuery(),
                context == null ? 0 : context.requestedBy(), context == null ? 0 : context.messageChannelId());
    }
}
