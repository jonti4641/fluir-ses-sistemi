package com.fluir.bot.audio;

import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.components.selections.StringSelectMenu;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Slash, Prefix ve Panel işlemlerinin tümünün kullandığı merkezi oynatma servisi.
 */
public class MusicPlaybackService {

    private static final Logger logger = LoggerFactory.getLogger(MusicPlaybackService.class);
    private static final Color BOT_COLOR = new Color(88, 101, 242);
    public static final int MAX_PLAYLIST_TRACKS = 100;

    private final AudioPlayerManager audioPlayerManager;

    public MusicPlaybackService(AudioPlayerManager audioPlayerManager) {
        this.audioPlayerManager = audioPlayerManager;
    }

    /**
     * Slash veya doğrudan yanıt ortamı için müzik arama ve oynatma.
     */
    public void processPlayRequest(Guild guild, AudioChannel targetChannel, GuildMessageChannel messageChannel, InteractionHook hook, String rawQuery, boolean isFallback) {
        String processedQuery = rawQuery.trim();

        // 1. Spotify URL kontrolü
        if (processedQuery.contains("spotify.com")) {
            String resolved = SpotifyResolver.resolveSpotifyUrl(processedQuery);
            if (resolved != null) {
                processedQuery = "scsearch:" + resolved;
            }
        }

        // 2. Eğer URL değilse arama ön eki ekle (önce SoundCloud)
        final boolean isUrl = processedQuery.startsWith("http://") || processedQuery.startsWith("https://");
        if (!isUrl && !processedQuery.startsWith("scsearch:") && !processedQuery.startsWith("ytsearch:")) {
            processedQuery = "scsearch:" + processedQuery;
        }

        final String finalQuery = processedQuery;
        GuildAudioSession session = audioPlayerManager.getOrCreateSession(guild);
        if (messageChannel != null) {
            session.setLastMessageChannel(messageChannel);
        }

        // ÖNEMLİ: Kanala HIZLICA doğrudan BAĞLANMA! Önce parçayı güvenle yükle!
        audioPlayerManager.getPlayerManager().loadItemOrdered(session, finalQuery, new AudioLoadResultHandler() {

            @Override
            public void trackLoaded(AudioTrack track) {
                if (AudioPlayerManager.isUnwantedMedia(track.getInfo().title)) {
                    sendResponse(hook, messageChannel, "⚠️ **\"" + track.getInfo().title + "\"** (film/dizi/fragman) filtrelendi ve engellendi.");
                    return;
                }

                // Parça hazır! Şimdi güvenli biçimde bağlan
                GuildAudioSession.ConnectionResult connResult = session.ensureConnected(guild, targetChannel, messageChannel);
                if (!connResult.success()) {
                    sendResponse(hook, messageChannel, connResult.message());
                    return;
                }

                session.getScheduler().queue(track);
                sendResponse(hook, messageChannel, "✅ Kuyruğa eklendi: **" + track.getInfo().title + "**\n" +
                        "👤 Sanatçı: `" + track.getInfo().author + "` | ⏱️ Süre: `" + TrackScheduler.formatDuration(track.getDuration()) + "`");
            }

            @Override
            public void playlistLoaded(AudioPlaylist playlist) {
                if (playlist.isSearchResult()) {
                    List<AudioTrack> validTracks = new ArrayList<>();
                    for (AudioTrack t : playlist.getTracks()) {
                        if (!AudioPlayerManager.isUnwantedMedia(t.getInfo().title)) {
                            validTracks.add(t);
                        }
                        if (validTracks.size() >= 5) break;
                    }

                    if (validTracks.isEmpty()) {
                        sendResponse(hook, messageChannel, "⚠️ Arama sonuçlarındaki tüm içerikler film/dizi/fragman olduğu için filtrelendi.");
                        return;
                    }

                    if (validTracks.size() == 1 || isUrl) {
                        AudioTrack singleTrack = validTracks.get(0);
                        GuildAudioSession.ConnectionResult connResult = session.ensureConnected(guild, targetChannel, messageChannel);
                        if (!connResult.success()) {
                            sendResponse(hook, messageChannel, connResult.message());
                            return;
                        }
                        session.getScheduler().queue(singleTrack);
                        sendResponse(hook, messageChannel, "🎵 Çalınıyor: **" + singleTrack.getInfo().title + "**");
                        return;
                    }

                    // Arama Paneli Sun
                    long channelId = messageChannel != null ? messageChannel.getIdLong() : 0L;
                    long userId = hook != null ? hook.getInteraction().getUser().getIdLong() : 0L;
                    sendSearchPanel(hook, messageChannel, rawQuery, validTracks, userId, guild.getIdLong(), channelId);

                } else {
                    // Normal Playlist (Max 100 parça sınırı)
                    GuildAudioSession.ConnectionResult connResult = session.ensureConnected(guild, targetChannel, messageChannel);
                    if (!connResult.success()) {
                        sendResponse(hook, messageChannel, connResult.message());
                        return;
                    }

                    int addedCount = 0;
                    for (AudioTrack t : playlist.getTracks()) {
                        if (addedCount >= MAX_PLAYLIST_TRACKS) break;
                        if (!AudioPlayerManager.isUnwantedMedia(t.getInfo().title)) {
                            session.getScheduler().queue(t);
                            addedCount++;
                        }
                    }

                    sendResponse(hook, messageChannel, "📃 **" + playlist.getName() + "** — `" + addedCount + "` parça eklendi.");
                }
            }

            @Override
            public void noMatches() {
                // Fallback (Tek Seferlik: SoundCloud -> YouTube)
                if (!isFallback && finalQuery.startsWith("scsearch:")) {
                    String fallbackQuery = "ytsearch:" + rawQuery;
                    logger.info("🔄 [Guild: {}] SoundCloud eşleşmedi. YouTube fallback deneniyor: {}", guild.getId(), fallbackQuery);
                    processPlayRequest(guild, targetChannel, messageChannel, hook, fallbackQuery, true);
                    return;
                }

                sendResponse(hook, messageChannel, "❌ **\"" + rawQuery + "\"** için sonuç bulunamadı.");
            }

            @Override
            public void loadFailed(FriendlyException exception) {
                logger.error("❌ [Guild: {}] Yükleme hatası [{}]: {}", guild.getId(), finalQuery, exception.getMessage());
                // Fallback (Tek Seferlik: SoundCloud -> YouTube)
                if (!isFallback && finalQuery.startsWith("scsearch:")) {
                    String fallbackQuery = "ytsearch:" + rawQuery;
                    logger.info("🔄 [Guild: {}] SoundCloud hatası. YouTube fallback deneniyor: {}", guild.getId(), fallbackQuery);
                    processPlayRequest(guild, targetChannel, messageChannel, hook, fallbackQuery, true);
                    return;
                }

                sendResponse(hook, messageChannel, "❌ **Yükleme başarısız:** `" + exception.getMessage() + "`");
            }
        });
    }

    private void sendSearchPanel(InteractionHook hook, GuildMessageChannel messageChannel, String query, List<AudioTrack> tracks, long userId, long guildId, long channelId) {
        String customId = "song_select:" + UUID.randomUUID().toString().substring(0, 8);
        SearchPanelCache.put(customId, userId, guildId, channelId, tracks);

        EmbedBuilder eb = new EmbedBuilder()
                .setTitle("🔎 Arama Sonuçları Paneli")
                .setDescription("💡 **\"" + query + "\"** için bulunan parçalar:\n\nAşağıdaki açılır menüden çalmak istediğin şarkıyı seç:")
                .setColor(BOT_COLOR);

        StringSelectMenu.Builder menuBuilder = StringSelectMenu.create(customId)
                .setPlaceholder("🎵 Çalınacak şarkıyı seçin...");

        for (int i = 0; i < tracks.size(); i++) {
            AudioTrack t = tracks.get(i);
            String title = t.getInfo().title;
            if (title.length() > 60) title = title.substring(0, 57) + "...";
            String author = t.getInfo().author;
            if (author.length() > 30) author = author.substring(0, 27) + "...";

            eb.addField((i + 1) + ". " + title, "👤 " + author + " • ⏱️ " + TrackScheduler.formatDuration(t.getDuration()), false);
            menuBuilder.addOption((i + 1) + ". " + title, String.valueOf(i), author + " (" + TrackScheduler.formatDuration(t.getDuration()) + ")");
        }
        menuBuilder.addOption("❌ İptal Et", "cancel", "Aramayı kapatır");

        if (hook != null) {
            hook.sendMessageEmbeds(eb.build()).addActionRow(menuBuilder.build()).queue();
        } else if (messageChannel != null) {
            messageChannel.sendMessageEmbeds(eb.build()).setActionRow(menuBuilder.build()).queue();
        }
    }

    private void sendResponse(InteractionHook hook, GuildMessageChannel messageChannel, String message) {
        if (hook != null) {
            hook.sendMessage(message).queue(null, err -> logger.warn("Hook mesaj hatası: {}", err.getMessage()));
        } else if (messageChannel != null) {
            messageChannel.sendMessage(message).queue(null, err -> logger.warn("MessageChannel mesaj hatası: {}", err.getMessage()));
        }
    }
}
