package com.fluir.bot.audio;

import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.source.bandcamp.BandcampAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.source.http.HttpAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.source.local.LocalAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.source.soundcloud.SoundCloudAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.source.twitch.TwitchStreamAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.source.vimeo.VimeoAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import dev.lavalink.youtube.YoutubeAudioSourceManager;
import dev.lavalink.youtube.clients.Music;
import dev.lavalink.youtube.clients.Web;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import net.dv8tion.jda.api.managers.AudioManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

public class AudioPlayerManager {

    private static final Logger logger = LoggerFactory.getLogger(AudioPlayerManager.class);

    private final DefaultAudioPlayerManager playerManager;
    private final Map<Long, GuildAudioManager> guildAudioManagers;

    public AudioPlayerManager() {
        this.playerManager = new DefaultAudioPlayerManager();
        this.guildAudioManagers = new HashMap<>();
        registerSources();
    }

    private void registerSources() {
        try {
            // ✅ YouTube — Music + Web client kullan (AndroidLite bu versiyonda yok)
            // Music: YouTube Music API kullanır, cipher gerektirmez
            // Web: Standart YouTube, fallback
            YoutubeAudioSourceManager youtube = new YoutubeAudioSourceManager(
                    true,
                    new Music(),
                    new Web()
            );
            playerManager.registerSourceManager(youtube);
            logger.info("✅ YouTube kaynağı kayıt edildi (AndroidLite + Music + Web).");
        } catch (Exception e) {
            logger.error("❌ YouTube kaynağı başlatılamadı: {}", e.getMessage(), e);
        }

        try {
            playerManager.registerSourceManager(SoundCloudAudioSourceManager.createDefault());
            logger.info("✅ SoundCloud kaynağı kayıt edildi.");
        } catch (Exception e) {
            logger.error("❌ SoundCloud başlatılamadı: {}", e.getMessage());
        }

        try {
            playerManager.registerSourceManager(new BandcampAudioSourceManager());
            logger.info("✅ Bandcamp kaynağı kayıt edildi.");
        } catch (Exception e) {
            logger.error("❌ Bandcamp başlatılamadı: {}", e.getMessage());
        }

        try {
            playerManager.registerSourceManager(new VimeoAudioSourceManager());
            logger.info("✅ Vimeo kaynağı kayıt edildi.");
        } catch (Exception e) {
            logger.error("❌ Vimeo başlatılamadı: {}", e.getMessage());
        }

        try {
            playerManager.registerSourceManager(new TwitchStreamAudioSourceManager());
            logger.info("✅ Twitch kaynağı kayıt edildi.");
        } catch (Exception e) {
            logger.error("❌ Twitch başlatılamadı: {}", e.getMessage());
        }

        try {
            playerManager.registerSourceManager(new HttpAudioSourceManager());
            logger.info("✅ HTTP kaynağı kayıt edildi.");
        } catch (Exception e) {
            logger.error("❌ HTTP başlatılamadı: {}", e.getMessage());
        }

        try {
            playerManager.registerSourceManager(new LocalAudioSourceManager());
            logger.info("✅ Yerel dosya kaynağı kayıt edildi.");
        } catch (Exception e) {
            logger.error("❌ Yerel dosya başlatılamadı: {}", e.getMessage());
        }

        logger.info("🎵 Tüm ses kaynakları kayıt tamamlandı.");
    }

    public synchronized GuildAudioManager getGuildAudioManager(Guild guild) {
        long guildId = guild.getIdLong();
        GuildAudioManager manager = guildAudioManagers.get(guildId);
        if (manager == null) {
            manager = new GuildAudioManager(playerManager);
            guildAudioManagers.put(guildId, manager);
        }
        guild.getAudioManager().setSendingHandler(manager.getSendHandler());
        return manager;
    }

    /**
     * Parçayı yükle ve çal. Eğer yükleme başarısız olursa ve hiçbir şey
     * çalmıyorsa ses kanalından otomatik ayrıl (sürekli girip çıkmayı engeller).
     */
    public void loadAndPlay(Guild guild, TextChannel textChannel, VoiceChannel voiceChannel, String trackUrl) {
        GuildAudioManager manager = getGuildAudioManager(guild);
        manager.scheduler.setAnnouncementChannel(textChannel);

        AudioManager audioManager = guild.getAudioManager();
        if (!audioManager.isConnected()) {
            audioManager.openAudioConnection(voiceChannel);
        }

        playerManager.loadItemOrdered(manager, trackUrl, new AudioLoadResultHandler() {
            @Override
            public void trackLoaded(AudioTrack track) {
                manager.scheduler.queue(track);
                textChannel.sendMessage("✅ **" + track.getInfo().title + "** kuyruğa eklendi.").queue();
            }

            @Override
            public void playlistLoaded(AudioPlaylist playlist) {
                if (playlist.isSearchResult()) {
                    AudioTrack t = playlist.getTracks().get(0);
                    manager.scheduler.queue(t);
                    textChannel.sendMessage("🎵 Çalınıyor: **" + t.getInfo().title + "**").queue();
                } else {
                    for (AudioTrack t : playlist.getTracks()) manager.scheduler.queue(t);
                    textChannel.sendMessage("📃 **" + playlist.getName() + "** — `" +
                            playlist.getTracks().size() + "` parça eklendi.").queue();
                }
            }

            @Override
            public void noMatches() {
                textChannel.sendMessage("❌ Sonuç bulunamadı: **" +
                        trackUrl.replace("ytsearch:", "") + "**").queue();
                // Hiçbir şey çalmıyorsa ses kanalından çık
                disconnectIfIdle(guild, manager);
            }

            @Override
            public void loadFailed(FriendlyException e) {
                logger.error("loadFailed [{}]: {}", trackUrl, e.getMessage());
                textChannel.sendMessage("❌ **Yükleme başarısız:** `" + e.getMessage() + "`\n" +
                        "💡 Bu video yüklenemedi. SoundCloud URL veya farklı bir parça dene.").queue();
                // Hiçbir şey çalmıyorsa ses kanalından çık
                disconnectIfIdle(guild, manager);
            }
        });
    }

    /**
     * Eğer bot ses kanalında bağlı ama hiçbir şey çalmıyorsa bağlantıyı kes.
     * "Sürekli girip çıkma" sorununu engeller.
     */
    private void disconnectIfIdle(Guild guild, GuildAudioManager manager) {
        if (manager.player.getPlayingTrack() == null && manager.scheduler.getQueue().isEmpty()) {
            guild.getAudioManager().closeAudioConnection();
            logger.info("🔇 Boşta kaldı, {} sunucusundan ayrıldı.", guild.getName());
        }
    }

    public void disconnect(Guild guild) {
        GuildAudioManager manager = guildAudioManagers.get(guild.getIdLong());
        if (manager != null) {
            manager.player.stopTrack();
            manager.scheduler.getQueue().clear();
        }
        guild.getAudioManager().closeAudioConnection();
        logger.info("🔇 {} kanalından ayrıldı.", guild.getName());
    }

    public DefaultAudioPlayerManager getPlayerManager() {
        return playerManager;
    }
}
