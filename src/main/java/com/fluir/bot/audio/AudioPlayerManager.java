package com.fluir.bot.audio;

import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManagers;
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

public class AudioPlayerManager {

    private static final Logger logger = LoggerFactory.getLogger(AudioPlayerManager.class);

    private final DefaultAudioPlayerManager playerManager;
    private final Map<Long, GuildAudioManager> guildAudioManagers;

    public AudioPlayerManager() {
        this.playerManager = new DefaultAudioPlayerManager();
        this.guildAudioManagers = new HashMap<>();

        // 1) Önce güncel YouTube kaynağını kaydet (öncelik bu alır)
        YoutubeAudioSourceManager ytManager = new YoutubeAudioSourceManager(true);
        playerManager.registerSourceManager(ytManager);

        // 2) Diğer uzak kaynaklar: SoundCloud, Twitch, Vimeo, Bandcamp, HTTP...
        //    registerRemoteSources eski kırık YouTube'u da ekler ama bizimki önce geldiği için
        //    YouTube URL'lerini o yakalar, diğerleri ikinci sıraya düşer.
        AudioSourceManagers.registerRemoteSources(playerManager);
        AudioSourceManagers.registerLocalSource(playerManager);

        logger.info("✅ AudioPlayerManager hazır — YouTube v2, SoundCloud, Twitch aktif.");
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

    /** Prefix komutlar için */
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
                    AudioTrack track = playlist.getTracks().get(0);
                    manager.scheduler.queue(track);
                    textChannel.sendMessage("🎵 Çalınıyor: **" + track.getInfo().title + "**").queue();
                } else {
                    for (AudioTrack t : playlist.getTracks()) manager.scheduler.queue(t);
                    textChannel.sendMessage("📃 **" + playlist.getName() + "** — `" + playlist.getTracks().size() + "` parça eklendi.").queue();
                }
            }
            @Override
            public void noMatches() {
                textChannel.sendMessage("❌ Sonuç bulunamadı: **" + trackUrl.replace("ytsearch:", "") + "**").queue();
            }
            @Override
            public void loadFailed(FriendlyException exception) {
                textChannel.sendMessage("❌ Yükleme hatası: `" + exception.getMessage() + "`").queue();
                logger.error("loadFailed: {}", exception.getMessage());
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
    }

    public DefaultAudioPlayerManager getPlayerManager() {
        return playerManager;
    }
}
