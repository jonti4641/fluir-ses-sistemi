package com.fluir.bot.audio;

import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;

import java.awt.Color;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Discord sınırları içinde canlı güncellenen ayrıntılı müzik konsolu. */
public final class NowPlayingPanel {
    private static final Color PLAYING_COLOR = new Color(0, 210, 190);
    private static final Color PAUSED_COLOR = new Color(255, 184, 77);
    private static final int PROGRESS_SEGMENTS = 26;

    private NowPlayingPanel() {}

    public static EmbedBuilder embed(AudioTrack track, GuildAudioSession session) {
        long duration = track.getDuration();
        long position = Math.max(0, track.getPosition());
        long remaining = duration > 0 && duration != Long.MAX_VALUE ? Math.max(0, duration - position) : 0;
        double percent = duration > 0 && duration != Long.MAX_VALUE ? Math.min(100, position * 100.0 / duration) : 0;
        TrackContext context = track.getUserData() instanceof TrackContext tc ? tc : null;

        StringBuilder description = new StringBuilder()
                .append("### ").append(session.getPlayer().isPaused() ? "⏸️ Duraklatıldı" : "▶️ Şu An Çalıyor").append("\n")
                .append("**").append(safe(track.getInfo().title, 180)).append("**\n\n")
                .append(progressBar(position, duration, PROGRESS_SEGMENTS)).append("\n")
                .append(formatClock(position)).append(" / ").append(formatClock(duration))
                .append("  •  **%").append(String.format(Locale.ROOT, "%.1f", percent)).append("**");
        if (remaining > 0) description.append("  •  -").append(formatClock(remaining));

        EmbedBuilder embed = new EmbedBuilder()
                .setAuthor("FLUIR • CANLI MÜZİK KONSOLU")
                .setTitle("🎧 Music Panel • " + (session.getPlayer().isPaused() ? "PAUSED" : "LIVE"))
                .setDescription(description)
                .addField("🎙️ Sanatçı", safe(track.getInfo().author, 100), true)
                .addField("🙋 İsteyen", requester(context), true)
                .addField("⏱️ Toplam Süre", TrackScheduler.formatDuration(duration), true)
                .addField("🔊 Ses", progressMeter(session.getPlayer().getVolume(), 150, 10) + "  **" + session.getPlayer().getVolume() + "%**", true)
                .addField("📋 Kuyruk", "**" + session.getScheduler().getQueue().size() + "** parça bekliyor", true)
                .addField("💿 Kaynak", source(context), true)
                .addField("🎛️ Oynatma Modları", modes(session), false)
                .addField("⏭️ Sıradaki Parçalar", queuePreview(session), false)
                .setColor(session.getPlayer().isPaused() ? PAUSED_COLOR : PLAYING_COLOR)
                .setTimestamp(Instant.now())
                .setFooter("10 sn'de bir yenilenir • " + session.getConnectionState() + " • Guild " + session.getGuildId());

        if (MusicPlaybackService.isSafePublicUri(track.getInfo().uri)) embed.setUrl(track.getInfo().uri);
        if (isSafeArtwork(track.getInfo().artworkUrl)) embed.setThumbnail(track.getInfo().artworkUrl);
        return embed;
    }

    public static List<ActionRow> components(GuildAudioSession session) {
        return List.of(primaryControls(session), secondaryControls());
    }

    public static ActionRow primaryControls(GuildAudioSession session) {
        return ActionRow.of(
                session.getPlayer().isPaused() ? Button.success("music:pause", "▶ Devam") : Button.secondary("music:pause", "⏸ Duraklat"),
                Button.primary("music:skip", "⏭ Atla"),
                Button.danger("music:stop", "⏹ Durdur"),
                session.getScheduler().isLoop() ? Button.success("music:loop", "🔁 Döngü Açık") : Button.secondary("music:loop", "🔁 Döngü"),
                Button.secondary("music:favorite", "❤ Favori")
        );
    }

    public static ActionRow secondaryControls() {
        return ActionRow.of(
                Button.secondary("music:volume_down", "🔉 -10"),
                Button.secondary("music:rewind", "⏪ 10 sn"),
                Button.secondary("music:shuffle", "🔀 Karıştır"),
                Button.secondary("music:forward", "10 sn ⏩"),
                Button.secondary("music:volume_up", "+10 🔊")
        );
    }

    public static String progressBar(long position, long duration, int segments) {
        int safeSegments = Math.max(8, Math.min(40, segments));
        if (duration <= 0 || duration == Long.MAX_VALUE) return "●" + "━".repeat(safeSegments - 1) + "  🔴 **CANLI**";
        double ratio = Math.max(0, Math.min(1, (double) position / duration));
        int marker = Math.min(safeSegments - 1, (int) Math.round(ratio * (safeSegments - 1)));
        return "━".repeat(marker) + "●" + "━".repeat(safeSegments - marker - 1);
    }

    public static String formatClock(long millis) {
        if (millis == Long.MAX_VALUE) return "CANLI";
        long totalSeconds = Math.max(0, millis / 1000);
        long seconds = totalSeconds % 60;
        long minutes = (totalSeconds / 60) % 60;
        long hours = totalSeconds / 3600;
        return hours > 0 ? String.format(Locale.ROOT, "%02d:%02d:%02d", hours, minutes, seconds)
                : String.format(Locale.ROOT, "%02d:%02d", minutes, seconds);
    }

    private static String requester(TrackContext context) {
        return context != null && context.requestedBy() > 0 ? "<@" + context.requestedBy() + ">" : "Otomatik sistem";
    }

    private static String source(TrackContext context) {
        return context == null || context.source() == null ? "SoundCloud" : context.source().name();
    }

    private static String modes(GuildAudioSession session) {
        return (session.getScheduler().isLoop() ? "🔁 Döngü **Açık**" : "➡️ Döngü **Kapalı**")
                + "  •  " + (session.settings().autoplay() ? "♾️ Otomatik **Açık**" : "⏹️ Otomatik **Kapalı**")
                + "  •  " + (session.getPlayer().isPaused() ? "⏸️ Duraklatıldı" : "▶️ Oynatılıyor");
    }

    private static String queuePreview(GuildAudioSession session) {
        if (session.getScheduler().getQueue().isEmpty()) return "*Kuyrukta başka parça yok.*";
        List<String> titles = new ArrayList<>();
        int index = 1;
        for (AudioTrack queued : session.getScheduler().getQueue()) {
            titles.add(index++ + ". " + safe(queued.getInfo().title, 70) + " • " + formatClock(queued.getDuration()));
            if (titles.size() == 3) break;
        }
        int remaining = session.getScheduler().getQueue().size() - titles.size();
        if (remaining > 0) titles.add("*…ve " + remaining + " parça daha*");
        return String.join("\n", titles);
    }

    private static String progressMeter(int value, int max, int segments) {
        int filled = Math.max(0, Math.min(segments, (int) Math.round((double) value / max * segments)));
        return "▰".repeat(filled) + "▱".repeat(segments - filled);
    }

    private static boolean isSafeArtwork(String raw) {
        if (raw == null || raw.isBlank()) return false;
        try {
            URI uri = URI.create(raw);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            return "https".equalsIgnoreCase(uri.getScheme())
                    && (host.equals("sndcdn.com") || host.endsWith(".sndcdn.com"));
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static String safe(String value, int max) {
        if (value == null || value.isBlank()) return "Bilinmiyor";
        String sanitized = value.replaceAll("[\\r\\n\\p{Cntrl}]", " ");
        return sanitized.length() > max ? sanitized.substring(0, max - 1) + "…" : sanitized;
    }
}
