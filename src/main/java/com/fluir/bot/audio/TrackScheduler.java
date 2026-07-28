package com.fluir.bot.audio;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.player.event.AudioEventAdapter;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Her sunucu (Guild) için ayrı bir TrackScheduler oluşturulur.
 * Kuyruk yönetimini ve çalma sıralamasını yönetir.
 */
public class TrackScheduler extends AudioEventAdapter {

    private static final Logger logger = LoggerFactory.getLogger(TrackScheduler.class);

    private final AudioPlayer player;
    private final BlockingQueue<AudioTrack> queue;
    private TextChannel announcementChannel;
    private boolean loop = false;
    private AudioTrack currentTrack;

    public TrackScheduler(AudioPlayer player) {
        this.player = player;
        this.queue = new LinkedBlockingQueue<>();
    }

    /**
     * Parçayı kuyruğa ekler. Eğer şu an bir şey çalmıyorsa direkt çalar.
     */
    public void queue(AudioTrack track) {
        if (!player.startTrack(track, true)) {
            queue.offer(track);
        } else {
            currentTrack = track;
        }
    }

    /**
     * Bir sonraki parçaya geçer.
     */
    public void nextTrack() {
        AudioTrack next = queue.poll();
        if (next != null) {
            player.startTrack(next, false);
            currentTrack = next;
        } else {
            player.stopTrack();
            currentTrack = null;
        }
    }

    @Override
    public void onTrackEnd(AudioPlayer player, AudioTrack track, AudioTrackEndReason endReason) {
        if (loop && currentTrack != null) {
            // Döngü açıksa aynı parçayı tekrar çal
            player.startTrack(track.makeClone(), false);
            return;
        }

        if (endReason.mayStartNext) {
            nextTrack();
        }
    }

    @Override
    public void onTrackStart(AudioPlayer player, AudioTrack track) {
        currentTrack = track;
        logger.info("🎵 Çalıyor: {}", track.getInfo().title);
        if (announcementChannel != null) {
            announcementChannel.sendMessage(
                "🎵 **Şimdi çalıyor:** `" + track.getInfo().title + "`\n" +
                "👤 Kanal: " + track.getInfo().author + " | ⏱️ Süre: " + formatDuration(track.getDuration())
            ).queue();
        }
    }

    @Override
    public void onTrackException(AudioPlayer player, AudioTrack track, com.sedmelluq.discord.lavaplayer.tools.FriendlyException exception) {
        logger.error("❌ Parça çalınırken hata: {} - {}", track.getInfo().title, exception.getMessage());
        if (announcementChannel != null) {
            announcementChannel.sendMessage("❌ `" + track.getInfo().title + "` çalınırken bir hata oluştu!").queue();
        }
        nextTrack();
    }

    /**
     * Milisaniyeyi MM:SS formatına çevirir.
     */
    public static String formatDuration(long durationMs) {
        if (durationMs == Long.MAX_VALUE) return "🔴 CANLI";
        long seconds = durationMs / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;
        seconds %= 60;
        minutes %= 60;
        if (hours > 0) {
            return String.format("%02d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format("%02d:%02d", minutes, seconds);
    }

    // Getter ve Setter'lar
    public BlockingQueue<AudioTrack> getQueue() { return queue; }
    public boolean isLoop() { return loop; }
    public void setLoop(boolean loop) { this.loop = loop; }
    public AudioTrack getCurrentTrack() { return currentTrack; }
    public void setAnnouncementChannel(TextChannel channel) { this.announcementChannel = channel; }
    public AudioPlayer getPlayer() { return player; }
}
