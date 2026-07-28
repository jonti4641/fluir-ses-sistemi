package com.fluir.bot.audio;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.managers.AudioManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Sunucu bazlı tekil ses oturumunu (Session) ve bağlantı yaşam döngüsünü yöneten sınıf.
 */
public class GuildAudioSession {

    private static final Logger logger = LoggerFactory.getLogger(GuildAudioSession.class);
    private static final ScheduledExecutorService schedulerExecutor = Executors.newScheduledThreadPool(4);

    private final long guildId;
    private final AudioPlayer player;
    private final TrackScheduler scheduler;
    private final AudioPlayerSendHandler sendHandler;
    private final ReentrantLock sessionLock = new ReentrantLock();
    private final AtomicLong playbackGeneration = new AtomicLong(0);
    private final MusicPlaybackService playbackService;

    private volatile VoiceConnectionState connectionState = VoiceConnectionState.DISCONNECTED;
    private volatile AudioChannel currentChannel;
    private volatile GuildMessageChannel lastMessageChannel;
    private volatile ScheduledFuture<?> idleTimerTask;
    private volatile ScheduledFuture<?> aloneTimerTask;
    private volatile boolean isDestroyed = false;

    public GuildAudioSession(long guildId, AudioPlayerManager lavaPlayerManager, MusicPlaybackService playbackService) {
        this.guildId = guildId;
        this.player = lavaPlayerManager.createPlayer();
        this.playbackService = playbackService;
        this.scheduler = new TrackScheduler(this.player, this);
        this.player.addListener(this.scheduler);
        this.sendHandler = new AudioPlayerSendHandler(this.player);
    }

    public ConnectionResult ensureConnected(Guild guild, AudioChannel targetChannel, GuildMessageChannel messageChannel) {
        sessionLock.lock();
        try {
            if (isDestroyed) {
                return new ConnectionResult(false, "Oturum sonlandırılmış durumda.");
            }

            if (messageChannel != null) {
                this.lastMessageChannel = messageChannel;
            }

            if (targetChannel == null) {
                return new ConnectionResult(false, "❌ Lütfen öncelikle bir ses kanalına katılın!");
            }

            if ((this.connectionState == VoiceConnectionState.CONNECTED || this.connectionState == VoiceConnectionState.CONNECTING)
                    && this.currentChannel != null && this.currentChannel.getIdLong() == targetChannel.getIdLong()) {
                cancelIdleTimer();
                cancelAloneTimer();
                return new ConnectionResult(true, "Zaten bağlı.");
            }

            Member selfMember = guild.getSelfMember();
            if (!selfMember.hasPermission(targetChannel, Permission.VIEW_CHANNEL)) {
                return new ConnectionResult(false, "❌ Kanalı görme iznim (`VIEW_CHANNEL`) yok: " + targetChannel.getName());
            }
            if (!selfMember.hasPermission(targetChannel, Permission.VOICE_CONNECT)) {
                return new ConnectionResult(false, "❌ Kanala bağlanma iznim (`VOICE_CONNECT`) yok: " + targetChannel.getName());
            }
            if (!selfMember.hasPermission(targetChannel, Permission.VOICE_SPEAK)) {
                return new ConnectionResult(false, "❌ Kanalda konuşma iznim (`VOICE_SPEAK`) yok: " + targetChannel.getName());
            }

            AudioManager audioManager = guild.getAudioManager();
            AudioChannel connectedChannel = audioManager.getConnectedChannel();

            if (connectedChannel != null && connectedChannel.getIdLong() == targetChannel.getIdLong()) {
                this.currentChannel = targetChannel;
                this.connectionState = VoiceConnectionState.CONNECTED;
                cancelIdleTimer();
                cancelAloneTimer();
                return new ConnectionResult(true, "Zaten bağlı.");
            }

            if (connectedChannel != null && player.getPlayingTrack() != null) {
                return new ConnectionResult(false, "❌ Bot şu an `" + connectedChannel.getName() + "` kanalında aktif olarak müzik çalıyor!");
            }

            this.connectionState = VoiceConnectionState.CONNECTING;
            this.currentChannel = targetChannel;

            audioManager.setSendingHandler(sendHandler);
            audioManager.openAudioConnection(targetChannel);

            this.connectionState = VoiceConnectionState.CONNECTED;

            cancelIdleTimer();
            cancelAloneTimer();

            logger.info("📢 [Guild: {}] Ses kanalına bağlandı: {}", guildId, targetChannel.getName());
            return new ConnectionResult(true, "Başarıyla bağlandı.");

        } catch (Exception e) {
            this.connectionState = VoiceConnectionState.DISCONNECTED;
            this.currentChannel = null;
            logger.error("❌ [Guild: {}] Ses kanalına bağlanırken hata: {}", guildId, e.getMessage(), e);
            return new ConnectionResult(false, "❌ Ses kanalına bağlanılamadı: " + e.getMessage());
        } finally {
            sessionLock.unlock();
        }
    }

    public void scheduleIdleTimer(Guild guild, long delaySeconds) {
        sessionLock.lock();
        try {
            cancelIdleTimer();
            if (isDestroyed) return;

            logger.info("⏳ [Guild: {}] Idle timer başlatıldı ({}s)", guildId, delaySeconds);
            idleTimerTask = schedulerExecutor.schedule(() -> {
                sessionLock.lock();
                try {
                    if (!isDestroyed && player.getPlayingTrack() == null && scheduler.getQueue().isEmpty()) {
                        logger.info("🔇 [Guild: {}] Boşta kalma süresi doldu, kanaldan ayrılınıyor.", guildId);
                        disconnect(guild);
                    }
                } finally {
                    sessionLock.unlock();
                }
            }, delaySeconds, TimeUnit.SECONDS);

        } finally {
            sessionLock.unlock();
        }
    }

    public void cancelIdleTimer() {
        sessionLock.lock();
        try {
            if (idleTimerTask != null && !idleTimerTask.isDone()) {
                idleTimerTask.cancel(false);
                idleTimerTask = null;
                logger.debug("⏱️ [Guild: {}] Idle timer iptal edildi.", guildId);
            }
        } finally {
            sessionLock.unlock();
        }
    }

    public void scheduleAloneTimer(Guild guild, long delaySeconds) {
        sessionLock.lock();
        try {
            cancelAloneTimer();
            if (isDestroyed) return;

            logger.info("⏳ [Guild: {}] Yalnız kalma timer'ı başlatıldı ({}s)", guildId, delaySeconds);
            aloneTimerTask = schedulerExecutor.schedule(() -> {
                sessionLock.lock();
                try {
                    if (!isDestroyed) {
                        logger.info("🔇 [Guild: {}] Kanalda üye kalmadığı için ayrılınıyor.", guildId);
                        disconnect(guild);
                    }
                } finally {
                    sessionLock.unlock();
                }
            }, delaySeconds, TimeUnit.SECONDS);

        } finally {
            sessionLock.unlock();
        }
    }

    public void cancelAloneTimer() {
        sessionLock.lock();
        try {
            if (aloneTimerTask != null && !aloneTimerTask.isDone()) {
                aloneTimerTask.cancel(false);
                aloneTimerTask = null;
                logger.debug("⏱️ [Guild: {}] Yalnız kalma timer'ı iptal edildi.", guildId);
            }
        } finally {
            sessionLock.unlock();
        }
    }

    public void disconnect(Guild guild) {
        sessionLock.lock();
        try {
            nextPlaybackGeneration();
            this.connectionState = VoiceConnectionState.DISCONNECTING;
            cancelIdleTimer();
            cancelAloneTimer();

            scheduler.stop();

            if (guild != null && guild.getAudioManager().isConnected()) {
                guild.getAudioManager().closeAudioConnection();
            }

            this.currentChannel = null;
            this.connectionState = VoiceConnectionState.DISCONNECTED;
            logger.info("🔇 [Guild: {}] Bağlantı kapatıldı.", guildId);
        } finally {
            sessionLock.unlock();
        }
    }

    public void destroy(Guild guild) {
        sessionLock.lock();
        try {
            if (isDestroyed) return;
            isDestroyed = true;

            disconnect(guild);
            player.destroy();
            logger.info("🧹 [Guild: {}] Oturum temizlendi.", guildId);
        } finally {
            sessionLock.unlock();
        }
    }

    public long getGuildId() { return guildId; }
    public AudioPlayer getPlayer() { return player; }
    public TrackScheduler getScheduler() { return scheduler; }
    public AudioPlayerSendHandler getSendHandler() { return sendHandler; }
    public VoiceConnectionState getConnectionState() { return connectionState; }
    public AudioChannel getCurrentChannel() { return currentChannel; }
    public GuildMessageChannel getLastMessageChannel() { return lastMessageChannel; }
    public void setLastMessageChannel(GuildMessageChannel messageChannel) { this.lastMessageChannel = messageChannel; }
    public boolean isDestroyed() { return isDestroyed; }

    public long getPlaybackGeneration() { return playbackGeneration.get(); }
    public long nextPlaybackGeneration() { return playbackGeneration.incrementAndGet(); }
    public MusicPlaybackService getPlaybackService() { return playbackService; }

    public record ConnectionResult(boolean success, String message) {}
}
