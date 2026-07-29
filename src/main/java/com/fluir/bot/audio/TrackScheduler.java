package com.fluir.bot.audio;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.player.event.AudioEventAdapter;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Parça sıralama ve event yönetimi sınıfı.
 * Race condition (çift parça atlama, takılma, hayalet eventler) engellenmiştir.
 * Runtime fallback ve generation koruması desteklenir.
 */
public class TrackScheduler extends AudioEventAdapter {

    private static final Logger logger = LoggerFactory.getLogger(TrackScheduler.class);

    private final AudioPlayer player;
    private final GuildAudioSession session;
    private final BlockingQueue<AudioTrack> queue;
    private final ReentrantLock schedulerLock = new ReentrantLock();

    private volatile boolean loop = false;
    private volatile AudioTrack currentTrack;
    private final AtomicBoolean isHandlingException = new AtomicBoolean(false);
    private final AtomicBoolean isStopped = new AtomicBoolean(false);

    public TrackScheduler(AudioPlayer player, GuildAudioSession session) {
        this.player = player;
        this.session = session;
        this.queue = new LinkedBlockingQueue<>();
    }

    public boolean queue(AudioTrack track) {
        schedulerLock.lock();
        try {
            if (isStopped.get()) {
                isStopped.set(false);
            }

            session.cancelIdleTimer();

            if (!player.startTrack(track, true)) {
                boolean added = queue.offer(track);
                logger.info("📋 [Guild: {}] Kuyruğa eklendi: {} (Kuyruk boyutu: {})", session.getGuildId(), track.getInfo().title, queue.size());
                return added;
            } else {
                this.currentTrack = track;
                logger.info("🎵 [Guild: {}] Doğrudan çalınmaya başlandı: {}", session.getGuildId(), track.getInfo().title);
                return true;
            }
        } finally {
            schedulerLock.unlock();
        }
    }

    public boolean nextTrack() {
        schedulerLock.lock();
        try {
            session.nextPlaybackGeneration();
            AudioTrack next = queue.poll();

            if (next != null) {
                this.currentTrack = next;
                player.startTrack(next, false);
                logger.info("⏭️ [Guild: {}] Sonraki parçaya geçildi: {}", session.getGuildId(), next.getInfo().title);
                return true;
            } else {
                this.currentTrack = null;
                player.stopTrack();
                logger.info("⏹️ [Guild: {}] Kuyruk bitti, çalma durdu.", session.getGuildId());
                return false;
            }
        } finally {
            schedulerLock.unlock();
        }
    }

    public void stop() {
        schedulerLock.lock();
        try {
            session.nextPlaybackGeneration();
            isStopped.set(true);
            isHandlingException.set(false);
            queue.clear();
            currentTrack = null;
            player.stopTrack();
            logger.info("⏹️ [Guild: {}] Çalma durduruldu ve kuyruk temizlendi.", session.getGuildId());
        } finally {
            schedulerLock.unlock();
        }
    }

    public void startFallbackTrack(AudioTrack fallbackTrack) {
        schedulerLock.lock();
        try {
            if (isStopped.get()) return;
            isHandlingException.set(false);
            this.currentTrack = fallbackTrack;
            player.startTrack(fallbackTrack, false);
            logger.info("🔄 [Guild: {}] Re-resolved SoundCloud parçası başlatıldı: {}", session.getGuildId(), fallbackTrack.getInfo().title);
        } finally {
            schedulerLock.unlock();
        }
    }

    public void advanceQueueAfterException() {
        schedulerLock.lock();
        try {
            boolean hasNext = nextTrack();
            if (!hasNext && queue.isEmpty()) {
                scheduleIdleDisconnect();
            }
        } finally {
            schedulerLock.unlock();
        }
    }

    @Override
    public void onTrackStart(AudioPlayer player, AudioTrack track) {
        schedulerLock.lock();
        try {
            this.currentTrack = track;
            isHandlingException.set(false);
            session.cancelIdleTimer();
            logger.info("▶️ [Guild: {}] Çalıyor: {}", session.getGuildId(), track.getInfo().title);

            GuildMessageChannel announcementChannel = session.getLastMessageChannel();
            if (announcementChannel != null) {
                announcementChannel.sendMessage(
                        "⏳ **Parça hazırlanıyor:** `" + track.getInfo().title + "`\n" +
                        "👤 Sanatçı: `" + track.getInfo().author + "` | ⏱️ Süre: `" + formatDuration(track.getDuration()) + "`"
                ).queue(null, err -> logger.warn("Duyuru mesajı gönderilemedi: {}", err.getMessage()));
            }
        } finally {
            schedulerLock.unlock();
        }
    }

    @Override
    public void onTrackEnd(AudioPlayer player, AudioTrack track, AudioTrackEndReason endReason) {
        schedulerLock.lock();
        try {
            logger.info("🏁 [Guild: {}] Track Bitti [Reason: {}]: {}", session.getGuildId(), endReason, track.getInfo().title);

            if (isStopped.get()) {
                return;
            }

            // Fallback/skip yeni bir parça başlattıysa eski parçanın gecikmiş end olayı
            // yeni kuyruğu ilerletmemelidir.
            if (track != currentTrack) {
                logger.debug("[Guild: {}] Eski track end olayı yok sayıldı: {} ({})",
                        session.getGuildId(), track.getInfo().title, endReason);
                return;
            }

            // Exception handler tarafından hallediliyorsa es geç
            if (isHandlingException.getAndSet(false)) {
                logger.debug("Exception handler tarafından işleniyor, onTrackEnd es geçildi.");
                return;
            }

            // Döngü kontrolü (Yalnızca FINISHED olan şarkılar için)
            if (loop && endReason == AudioTrackEndReason.FINISHED && currentTrack != null) {
                logger.info("🔁 [Guild: {}] Döngü aktif, tekrar çalınıyor: {}", session.getGuildId(), track.getInfo().title);
                AudioTrack cloned = track.makeClone();
                cloned.setUserData(track.getUserData());
                player.startTrack(cloned, false);
                return;
            }

            if (endReason.mayStartNext) {
                boolean hasNext = nextTrack();
                if (!hasNext && queue.isEmpty()) {
                    scheduleIdleDisconnect();
                }
            }
        } finally {
            schedulerLock.unlock();
        }
    }

    private void scheduleIdleDisconnect() {
        GuildMessageChannel messageChannel = session.getLastMessageChannel();
        Guild guild = messageChannel != null ? messageChannel.getGuild() : null;
        session.scheduleIdleTimer(guild, 90);
    }

    @Override
    public void onTrackException(AudioPlayer player, AudioTrack track, FriendlyException exception) {
        schedulerLock.lock();
        try {
            logger.error("❌ [Guild: {}] Parça yürütme hatası [{}]: {}", session.getGuildId(), track.getInfo().title, exception.getMessage());

            if (isHandlingException.getAndSet(true)) {
                logger.debug("Zaten exception işleniyor, mükerrer çağrı es geçildi.");
                return;
            }
        } finally {
            schedulerLock.unlock();
        }

        // Asenkron runtime fallback çağrısı (Kilit dışında yapılır ki deadlock olmasın!)
        if (session != null && session.getPlaybackService() != null) {
            session.getPlaybackService().handleRuntimePlaybackFallback(session, track, exception);
        } else {
            advanceQueueAfterException();
        }
    }

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

    public BlockingQueue<AudioTrack> getQueue() { return queue; }
    public boolean isLoop() { return loop; }
    public void setLoop(boolean loop) { this.loop = loop; }
    public AudioTrack getCurrentTrack() { return currentTrack; }
    public AudioPlayer getPlayer() { return player; }
    public GuildAudioSession getSession() { return session; }
}
