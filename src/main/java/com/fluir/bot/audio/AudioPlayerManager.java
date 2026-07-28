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
import java.util.Locale;
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
        // 1. SoundCloud (Kesintisiz, IP engelsiz müzik kaynağı)
        try {
            playerManager.registerSourceManager(SoundCloudAudioSourceManager.createDefault());
            logger.info("✅ SoundCloud kaynağı kayıt edildi.");
        } catch (Exception e) {
            logger.error("❌ SoundCloud kaynağı başlatılamadı: {}", e.getMessage());
        }

        // 2. YouTube (Music + Web client)
        try {
            YoutubeAudioSourceManager youtube = new YoutubeAudioSourceManager(
                    true,
                    new Music(),
                    new Web()
            );
            playerManager.registerSourceManager(youtube);
            logger.info("✅ YouTube kaynağı kayıt edildi (Music + Web).");
        } catch (Exception e) {
            logger.warn("⚠️ YouTube kaynağı yüklenemedi: {}", e.getMessage());
        }

        // 3. Diğer platformlar
        try { playerManager.registerSourceManager(new BandcampAudioSourceManager()); } catch (Exception ignored) {}
        try { playerManager.registerSourceManager(new VimeoAudioSourceManager()); } catch (Exception ignored) {}
        try { playerManager.registerSourceManager(new TwitchStreamAudioSourceManager()); } catch (Exception ignored) {}
        try { playerManager.registerSourceManager(new HttpAudioSourceManager()); } catch (Exception ignored) {}
        try { playerManager.registerSourceManager(new LocalAudioSourceManager()); } catch (Exception ignored) {}

        logger.info("🎵 Tüm ses kaynakları hazırlandı.");
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
     * Film, dizi, fragman, teaser, tanıtım gibi içerikleri tespit edip filtreler.
     */
    public static boolean isUnwantedMedia(String title) {
        if (title == null || title.isBlank()) return false;
        String lower = title.toLowerCase(Locale.ROOT);
        return lower.contains("fragman") ||
               lower.contains("trailer") ||
               lower.contains("teaser") ||
               lower.contains("tanıtım") ||
               lower.contains("tanitim") ||
               lower.contains("dizi") ||
               lower.contains("film") ||
               lower.contains("sinema") ||
               lower.contains("bölüm") ||
               lower.contains("bolum") ||
               lower.contains("official trailer") ||
               lower.contains("movie");
    }

    public void loadAndPlay(Guild guild, TextChannel textChannel, VoiceChannel voiceChannel, String trackUrl) {
        // Spotify URL kontrolü
        if (trackUrl.contains("spotify.com")) {
            String resolvedQuery = SpotifyResolver.resolveSpotifyUrl(trackUrl);
            if (resolvedQuery != null) {
                trackUrl = "scsearch:" + resolvedQuery;
            }
        }

        GuildAudioManager manager = getGuildAudioManager(guild);
        manager.scheduler.setAnnouncementChannel(textChannel);

        AudioManager audioManager = guild.getAudioManager();
        if (!audioManager.isConnected()) {
            audioManager.openAudioConnection(voiceChannel);
        }

        final String finalTrackUrl = trackUrl;

        playerManager.loadItemOrdered(manager, finalTrackUrl, new AudioLoadResultHandler() {
            @Override
            public void trackLoaded(AudioTrack track) {
                if (isUnwantedMedia(track.getInfo().title)) {
                    textChannel.sendMessage("⚠️ **\"" + track.getInfo().title + "\"** (film/dizi/fragman) filtrelendi ve engellendi.").queue();
                    disconnectIfIdle(guild, manager);
                    return;
                }
                manager.scheduler.queue(track);
                textChannel.sendMessage("✅ **" + track.getInfo().title + "** kuyruğa eklendi.").queue();
            }

            @Override
            public void playlistLoaded(AudioPlaylist playlist) {
                if (playlist.isSearchResult()) {
                    AudioTrack selected = null;
                    for (AudioTrack t : playlist.getTracks()) {
                        if (!isUnwantedMedia(t.getInfo().title)) {
                            selected = t;
                            break;
                        }
                    }
                    if (selected != null) {
                        manager.scheduler.queue(selected);
                        textChannel.sendMessage("🎵 Çalınıyor: **" + selected.getInfo().title + "**").queue();
                    } else {
                        textChannel.sendMessage("⚠️ Arama sonuçlarındaki tüm içerikler film/dizi/fragman olduğu için filtrelendi.").queue();
                        disconnectIfIdle(guild, manager);
                    }
                } else {
                    int added = 0;
                    for (AudioTrack t : playlist.getTracks()) {
                        if (!isUnwantedMedia(t.getInfo().title)) {
                            manager.scheduler.queue(t);
                            added++;
                        }
                    }
                    textChannel.sendMessage("📃 **" + playlist.getName() + "** — `" + added + "` müzik parçası eklendi.").queue();
                }
            }

            @Override
            public void noMatches() {
                if (finalTrackUrl.startsWith("ytsearch:")) {
                    String scQuery = "scsearch:" + finalTrackUrl.substring(9);
                    logger.info("🔄 YouTube sonuç vermedi, SoundCloud ile deneniyor: {}", scQuery);
                    loadAndPlay(guild, textChannel, voiceChannel, scQuery);
                    return;
                }
                textChannel.sendMessage("❌ Sonuç bulunamadı: **" + finalTrackUrl.replaceAll("^(ytsearch:|scsearch:|spsearch:)", "") + "**").queue();
                disconnectIfIdle(guild, manager);
            }

            @Override
            public void loadFailed(FriendlyException e) {
                logger.error("loadFailed [{}]: {}", finalTrackUrl, e.getMessage());
                if (finalTrackUrl.startsWith("ytsearch:")) {
                    String scQuery = "scsearch:" + finalTrackUrl.substring(9);
                    logger.info("🔄 YouTube yükleme hatası. SoundCloud fallback başlatılıyor: {}", scQuery);
                    loadAndPlay(guild, textChannel, voiceChannel, scQuery);
                    return;
                }
                textChannel.sendMessage("❌ **Yükleme başarısız:** `" + e.getMessage() + "`\n💡 Lütfen Spotify veya SoundCloud linki deneyin.").queue();
                disconnectIfIdle(guild, manager);
            }
        });
    }

    public void disconnectIfIdle(Guild guild, GuildAudioManager manager) {
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
