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
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import net.dv8tion.jda.api.managers.AudioManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Tüm sunucuların ses yöneticilerini yöneten sınıf.
 *
 * ÖNEMLI: registerRemoteSources() KULLANILMADI çünkü eski kırık YouTube
 * source'u da ekler ve çakışmaya neden olur. Kaynaklar tek tek kaydedilir.
 */
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
            // ✅ YouTube — dev.lavalink.youtube:v2 (güncel, aktif bakımlı)
            YoutubeAudioSourceManager youtube = new YoutubeAudioSourceManager(true);
            playerManager.registerSourceManager(youtube);
            logger.info("✅ YouTube kaynağı kayıt edildi (v2).");
        } catch (Exception e) {
            logger.error("❌ YouTube kaynağı başlatılamadı: {}", e.getMessage(), e);
        }

        try {
            // ✅ SoundCloud
            playerManager.registerSourceManager(SoundCloudAudioSourceManager.createDefault());
            logger.info("✅ SoundCloud kaynağı kayıt edildi.");
        } catch (Exception e) {
            logger.error("❌ SoundCloud kaynağı başlatılamadı: {}", e.getMessage());
        }

        try {
            // ✅ Bandcamp
            playerManager.registerSourceManager(new BandcampAudioSourceManager());
            logger.info("✅ Bandcamp kaynağı kayıt edildi.");
        } catch (Exception e) {
            logger.error("❌ Bandcamp kaynağı başlatılamadı: {}", e.getMessage());
        }

        try {
            // ✅ Vimeo
            playerManager.registerSourceManager(new VimeoAudioSourceManager());
            logger.info("✅ Vimeo kaynağı kayıt edildi.");
        } catch (Exception e) {
            logger.error("❌ Vimeo kaynağı başlatılamadı: {}", e.getMessage());
        }

        try {
            // ✅ Twitch
            playerManager.registerSourceManager(new TwitchStreamAudioSourceManager());
            logger.info("✅ Twitch kaynağı kayıt edildi.");
        } catch (Exception e) {
            logger.error("❌ Twitch kaynağı başlatılamadı: {}", e.getMessage());
        }

        try {
            // ✅ HTTP (direkt ses URL'leri)
            playerManager.registerSourceManager(new HttpAudioSourceManager());
            logger.info("✅ HTTP kaynağı kayıt edildi.");
        } catch (Exception e) {
            logger.error("❌ HTTP kaynağı başlatılamadı: {}", e.getMessage());
        }

        try {
            // ✅ Yerel dosyalar
            playerManager.registerSourceManager(new LocalAudioSourceManager());
            logger.info("✅ Yerel dosya kaynağı kayıt edildi.");
        } catch (Exception e) {
            logger.error("❌ Yerel dosya kaynağı başlatılamadı: {}", e.getMessage());
        }

        logger.info("🎵 Tüm ses kaynakları kayıt işlemi tamamlandı.");
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
            }
            @Override
            public void loadFailed(FriendlyException e) {
                textChannel.sendMessage("❌ Yükleme hatası: `" + e.getMessage() + "`").queue();
                logger.error("loadFailed [{}]: {}", trackUrl, e.getMessage());
            }
        });
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
