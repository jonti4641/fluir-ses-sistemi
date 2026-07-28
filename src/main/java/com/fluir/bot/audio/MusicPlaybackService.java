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
 * Birincil arama SoundCloud (scsearch:) üzerinden yürütülür. YouTube desteklenmez.
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

        // 1. YouTube bağlantısı kontrolü -> Doğrudan reddedilir
        if (isYouTubeUrlOrQuery(processedQuery)) {
            sendResponse(hook, messageChannel, "❌ YouTube bağlantıları bu müzik sisteminde desteklenmiyor.");
            return;
        }

        // 2. Spotify URL kontrolü -> SoundCloud (scsearch:) aramasına dönüştürülür
        if (processedQuery.contains("spotify.com")) {
            String resolved = SpotifyResolver.resolveSpotifyUrl(processedQuery);
            if (resolved != null) {
                processedQuery = "scsearch:" + resolved;
            } else {
                sendResponse(hook, messageChannel, "❌ Spotify bağlantısı çözümlenemedi.");
                return;
            }
        }

        // 3. Varsayılan arama ön eki: SoundCloud (scsearch:)
        final boolean isUrl = processedQuery.startsWith("http://") || processedQuery.startsWith("https://");
        if (!isUrl && !processedQuery.startsWith("scsearch:")) {
            processedQuery = "scsearch:" + processedQuery;
        }

        // SoundCloud Circuit Breaker kontrolü
        if (SoundCloudCircuitBreaker.isOpen()) {
            logger.warn("⚡ SoundCloud circuit açık olduğu için istek reddediliyor.");
            sendResponse(hook, messageChannel, "❌ SoundCloud oynatma hizmeti geçici olarak kullanılamıyor.");
            return;
        }

        final String finalQuery = processedQuery;
        final PlaybackSource source = determineSource(finalQuery);

        GuildAudioSession session = audioPlayerManager.getOrCreateSession(guild);
        if (messageChannel != null) {
            session.setLastMessageChannel(messageChannel);
        }

        long userId = hook != null ? hook.getInteraction().getUser().getIdLong() : 0L;
        long channelId = messageChannel != null ? messageChannel.getIdLong() : 0L;

        // ÖNEMLİ: Kanala HIZLICA doğrudan BAĞLANMA! Önce parçayı güvenle yükle!
        audioPlayerManager.getPlayerManager().loadItemOrdered(session, finalQuery, new AudioLoadResultHandler() {

            @Override
            public void trackLoaded(AudioTrack track) {
                if (AudioPlayerManager.isUnwantedMedia(track.getInfo().title)) {
                    sendResponse(hook, messageChannel, "⚠️ **\"" + track.getInfo().title + "\"** (film/dizi/fragman) filtrelendi ve engellendi.");
                    return;
                }

                // TrackContext ataması (Kalıcı URI kaydedilir)
                TrackContext context = TrackContext.create(rawQuery, track.getInfo().title, track.getInfo().author, track.getInfo().uri, source, userId, channelId);
                track.setUserData(context);

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
                            TrackContext context = TrackContext.create(rawQuery, t.getInfo().title, t.getInfo().author, t.getInfo().uri, source, userId, channelId);
                            t.setUserData(context);
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
                            TrackContext context = TrackContext.create(playlist.getName(), t.getInfo().title, t.getInfo().author, t.getInfo().uri, source, userId, channelId);
                            t.setUserData(context);
                            session.getScheduler().queue(t);
                            addedCount++;
                        }
                    }

                    sendResponse(hook, messageChannel, "📃 **" + playlist.getName() + "** — `" + addedCount + "` parça eklendi.");
                }
            }

            @Override
            public void noMatches() {
                sendResponse(hook, messageChannel, "❌ **\"" + rawQuery + "\"** için SoundCloud üzerinde sonuç bulunamadı.");
            }

            @Override
            public void loadFailed(FriendlyException exception) {
                logDetailedException(logger, guild.getIdLong(), null, exception);
                sendResponse(hook, messageChannel, getFriendlyErrorMessage(exception));
            }
        });
    }

    /**
     * Oynatma esnasında (onTrackException) oluşan SoundCloud 404 hatalarında
     * parçayı kalıcı URI üzerinden yeniden çözümleyip (re-resolve) devam ettiren kurtarma metodu.
     */
    public void handleRuntimePlaybackFallback(GuildAudioSession session, AudioTrack failedTrack, FriendlyException exception) {
        logDetailedException(logger, session.getGuildId(), failedTrack, exception);

        TrackContext context = (TrackContext) failedTrack.getUserData();
        long currentGen = session.getPlaybackGeneration();

        if (context == null) {
            context = TrackContext.create(failedTrack.getInfo().title, failedTrack.getInfo().title, failedTrack.getInfo().author, failedTrack.getInfo().uri, PlaybackSource.SOUNDCLOUD, 0L, 0L);
        }

        // SoundCloud hatasını Circuit Breaker'a bildir
        SoundCloudCircuitBreaker.recordFailure();

        if (SoundCloudCircuitBreaker.isOpen()) {
            logger.warn("⚡ SoundCloud circuit opened after consecutive failures. Queue advancing.");
            sendChannelMessage(session.getLastMessageChannel(), "❌ SoundCloud oynatma hizmeti geçici olarak kullanılamıyor.");
            session.getScheduler().advanceQueueAfterException();
            return;
        }

        // Zaten 1 kez re-resolve edildiyse 2. kez denenmez (sonsuz döngü koruması)
        if (context.isReResolved()) {
            logger.warn("⚠️ [Guild: {}] Parça zaten re-resolve edildi fakat tekrar 404 verdi. Sıradaki parçaya geçiliyor.", session.getGuildId());
            sendChannelMessage(session.getLastMessageChannel(), "❌ SoundCloud bu parçanın ses bağlantısını sağlayamadı. Sıradaki parçaya geçiliyor.");
            session.getScheduler().advanceQueueAfterException();
            return;
        }

        String permanentUri = context.permanentUri() != null ? context.permanentUri() : failedTrack.getInfo().uri;
        if (permanentUri == null || permanentUri.isBlank()) {
            sendChannelMessage(session.getLastMessageChannel(), "❌ SoundCloud bu parçanın ses bağlantısını sağlayamadı. Sıradaki parçaya geçiliyor.");
            session.getScheduler().advanceQueueAfterException();
            return;
        }

        final TrackContext reResolvedContext = context.markReResolved();

        sendChannelMessage(session.getLastMessageChannel(), "⚠️ SoundCloud bağlantısı yenileniyor, parça tekrar deneniyor...");

        // Kalıcı sayfa URI'si üzerinden taze AudioTrack yüklemesi
        audioPlayerManager.getPlayerManager().loadItemOrdered(session, permanentUri, new AudioLoadResultHandler() {
            @Override
            public void trackLoaded(AudioTrack freshTrack) {
                if (session.getPlaybackGeneration() != currentGen || session.isDestroyed()) {
                    logger.info("ℹ️ [Guild: {}] Manuel skip/stop yapıldığı için re-resolve iptal edildi.", session.getGuildId());
                    return;
                }
                freshTrack.setUserData(reResolvedContext);
                session.getScheduler().startFallbackTrack(freshTrack);
            }

            @Override
            public void playlistLoaded(AudioPlaylist playlist) {
                if (session.getPlaybackGeneration() != currentGen || session.isDestroyed()) return;
                if (!playlist.getTracks().isEmpty()) {
                    AudioTrack freshTrack = playlist.getTracks().get(0);
                    freshTrack.setUserData(reResolvedContext);
                    session.getScheduler().startFallbackTrack(freshTrack);
                } else {
                    noMatches();
                }
            }

            @Override
            public void noMatches() {
                if (session.getPlaybackGeneration() != currentGen || session.isDestroyed()) return;
                sendChannelMessage(session.getLastMessageChannel(), "❌ SoundCloud bu parçanın ses bağlantısını sağlayamadı. Sıradaki parçaya geçiliyor.");
                session.getScheduler().advanceQueueAfterException();
            }

            @Override
            public void loadFailed(FriendlyException ex) {
                if (session.getPlaybackGeneration() != currentGen || session.isDestroyed()) return;
                sendChannelMessage(session.getLastMessageChannel(), "❌ SoundCloud bu parçanın ses bağlantısını sağlayamadı. Sıradaki parçaya geçiliyor.");
                session.getScheduler().advanceQueueAfterException();
            }
        });
    }

    public static void logDetailedException(Logger logger, long guildId, AudioTrack track, FriendlyException exception) {
        TrackContext context = track != null ? (TrackContext) track.getUserData() : null;

        StringBuilder sb = new StringBuilder();
        sb.append("\n=================== ❌ DETAYLI SOUNDCLOUD HATASI ❌ ===================\n");
        sb.append("Sunucu (Guild ID)    : ").append(guildId).append("\n");
        sb.append("Parça Başlığı         : ").append(track != null ? track.getInfo().title : "Bilinmiyor").append("\n");
        sb.append("Kalıcı Page URI       : ").append(track != null ? sanitizeUri(track.getInfo().uri) : "Bilinmiyor").append("\n");
        sb.append("Hata Derecesi (Sev)   : ").append(exception != null ? exception.severity : "Bilinmiyor").append("\n");

        if (context != null) {
            sb.append("Kaynak Türü           : ").append(context.source()).append("\n");
            sb.append("Re-Resolved Durumu    : ").append(context.isReResolved()).append("\n");
            sb.append("Orijinal Sorgu        : ").append(context.originalQuery()).append("\n");
        }

        if (exception != null) {
            sb.append("Hata Mesajı           : ").append(exception.getMessage()).append("\n");
            sb.append("--- Exception Cause Zinciri ---\n");
            Throwable current = exception.getCause();
            int depth = 1;
            while (current != null) {
                sb.append("  [Cause #").append(depth++).append("] ")
                  .append(current.getClass().getName()).append(": ")
                  .append(current.getMessage()).append("\n");
                current = current.getCause();
            }
        }
        sb.append("====================================================================");

        logger.error("❌ [Guild: {}] SoundCloud yürütme hatası | title={} | uri={} | severity={}",
                guildId,
                track != null ? track.getInfo().title : "N/A",
                track != null ? sanitizeUri(track.getInfo().uri) : "N/A",
                exception != null ? exception.severity : "N/A",
                exception);

        logger.error(sb.toString());
    }

    private static String sanitizeUri(String uri) {
        if (uri == null) return "N/A";
        int queryIndex = uri.indexOf("?");
        if (queryIndex != -1) {
            return uri.substring(0, queryIndex);
        }
        return uri;
    }

    public static String getFriendlyErrorMessage(FriendlyException exception) {
        if (exception == null) return "❌ Bilinmeyen bir oynatma hatası oluştu.";

        String msg = exception.getMessage() != null ? exception.getMessage().toLowerCase() : "";
        Throwable cause = exception.getCause();
        String causeMsg = cause != null && cause.getMessage() != null ? cause.getMessage().toLowerCase() : "";

        if (msg.contains("404") || causeMsg.contains("404") || msg.contains("soundcloud stream")) {
            return "⚠️ SoundCloud bağlantısı geçersiz olduğu için parça tekrar deneniyor.";
        }
        if (msg.contains("403") || causeMsg.contains("403")) {
            return "❌ SoundCloud erişim engeli (403 Forbidden) verdi.";
        }
        if (msg.contains("429") || causeMsg.contains("429") || msg.contains("rate limit")) {
            return "❌ SoundCloud geçici olarak çok fazla istek aldığı için yanıt vermiyor.";
        }

        return "❌ SoundCloud bu parçayı şu anda oynatamadı.";
    }

    public static boolean isYouTubeUrlOrQuery(String query) {
        if (query == null) return false;
        String lower = query.toLowerCase();
        return lower.contains("youtube.com") || lower.contains("youtu.be") || lower.startsWith("ytsearch:");
    }

    private PlaybackSource determineSource(String query) {
        if (query.startsWith("scsearch:") || query.contains("soundcloud.com")) {
            return PlaybackSource.SOUNDCLOUD;
        } else if (query.contains("spotify.com")) {
            return PlaybackSource.SPOTIFY;
        } else if (query.startsWith("http://") || query.startsWith("https://")) {
            return PlaybackSource.HTTP;
        }
        return PlaybackSource.SOUNDCLOUD;
    }

    private void sendSearchPanel(InteractionHook hook, GuildMessageChannel messageChannel, String query, List<AudioTrack> tracks, long userId, long guildId, long channelId) {
        String customId = "song_select:" + UUID.randomUUID().toString().substring(0, 8);
        SearchPanelCache.put(customId, userId, guildId, channelId, tracks);

        EmbedBuilder eb = new EmbedBuilder()
                .setTitle("🔎 SoundCloud Arama Sonuçları Paneli")
                .setDescription("💡 **\"" + query + "\"** için SoundCloud üzerinde bulunan parçalar:\n\nAşağıdaki açılır menüden çalmak istediğin şarkıyı seç:")
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

    private void sendChannelMessage(GuildMessageChannel channel, String message) {
        if (channel != null) {
            channel.sendMessage(message).queue(null, err -> logger.warn("Kanal mesaj hatası: {}", err.getMessage()));
        }
    }
}
