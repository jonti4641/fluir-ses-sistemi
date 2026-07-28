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
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

/**
 * Slash, Prefix ve Panel işlemlerinin tümünün kullandığı merkezi oynatma servisi.
 * Varsayılan arama birincil olarak YouTube (ytsearch:) üzerinden gerçekleştirilir.
 */
public class MusicPlaybackService {

    private static final Logger logger = LoggerFactory.getLogger(MusicPlaybackService.class);
    private static final Color BOT_COLOR = new Color(88, 101, 242);
    public static final int MAX_PLAYLIST_TRACKS = 100;
    public static final int MAX_PLAYBACK_FALLBACK_ATTEMPTS = 1;

    private final AudioPlayerManager audioPlayerManager;

    public MusicPlaybackService(AudioPlayerManager audioPlayerManager) {
        this.audioPlayerManager = audioPlayerManager;
    }

    /**
     * Slash veya doğrudan yanıt ortamı için müzik arama ve oynatma.
     */
    public void processPlayRequest(Guild guild, AudioChannel targetChannel, GuildMessageChannel messageChannel, InteractionHook hook, String rawQuery, boolean isFallback) {
        String processedQuery = rawQuery.trim();

        // 1. Spotify URL kontrolü -> YouTube aramasına dönüştürülür
        if (processedQuery.contains("spotify.com")) {
            String resolved = SpotifyResolver.resolveSpotifyUrl(processedQuery);
            if (resolved != null) {
                processedQuery = "ytsearch:" + resolved + " official audio";
            }
        }

        // 2. Eğer URL veya ön ek değilse varsayılan birincil kaynak YouTube (ytsearch:) olarak belirlenir
        final boolean isUrl = processedQuery.startsWith("http://") || processedQuery.startsWith("https://");
        if (!isUrl && !processedQuery.startsWith("scsearch:") && !processedQuery.startsWith("ytsearch:")) {
            // SoundCloud devre kesici açık mı kontrol et
            if (SoundCloudCircuitBreaker.isOpen()) {
                logger.info("⚡ SoundCloud devresi açık olduğu için arama doğrudan YouTube'dan yapılıyor.");
                processedQuery = "ytsearch:" + processedQuery;
            } else {
                processedQuery = "ytsearch:" + processedQuery;
            }
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

                // TrackContext ataması
                TrackContext context = TrackContext.create(rawQuery, track.getInfo().title, track.getInfo().author, source, userId, channelId);
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
                            TrackContext context = TrackContext.create(rawQuery, t.getInfo().title, t.getInfo().author, source, userId, channelId);
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
                            TrackContext context = TrackContext.create(playlist.getName(), t.getInfo().title, t.getInfo().author, source, userId, channelId);
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
                // Birincil (YouTube) arama başarısız olduysa SoundCloud fallback denenir (eğer devre açık değilse)
                if (!isFallback && finalQuery.startsWith("ytsearch:") && !SoundCloudCircuitBreaker.isOpen()) {
                    String fallbackQuery = "scsearch:" + rawQuery;
                    logger.info("🔄 [Guild: {}] YouTube eşleşmedi. SoundCloud fallback deneniyor: {}", guild.getId(), fallbackQuery);
                    processPlayRequest(guild, targetChannel, messageChannel, hook, fallbackQuery, true);
                    return;
                }

                sendResponse(hook, messageChannel, "❌ **\"" + rawQuery + "\"** için sonuç bulunamadı.");
            }

            @Override
            public void loadFailed(FriendlyException exception) {
                logger.error("❌ [Guild: {}] Yükleme hatası [{}]: {}", guild.getId(), finalQuery, exception.getMessage());
                // Fallback (Tek Seferlik: YouTube -> SoundCloud)
                if (!isFallback && finalQuery.startsWith("ytsearch:") && !SoundCloudCircuitBreaker.isOpen()) {
                    String fallbackQuery = "scsearch:" + rawQuery;
                    logger.info("🔄 [Guild: {}] YouTube hatası. SoundCloud fallback deneniyor: {}", guild.getId(), fallbackQuery);
                    processPlayRequest(guild, targetChannel, messageChannel, hook, fallbackQuery, true);
                    return;
                }

                sendResponse(hook, messageChannel, getFriendlyErrorMessage(exception));
            }
        });
    }

    /**
     * Oynatma esnasında (onTrackException) oluşan SoundCloud / Stream 404 hataları için çalışma zamanı (runtime) fallback işlemi.
     */
    public void handleRuntimePlaybackFallback(GuildAudioSession session, AudioTrack failedTrack, FriendlyException exception) {
        TrackContext context = (TrackContext) failedTrack.getUserData();
        long currentGen = session.getPlaybackGeneration();

        if (context == null) {
            context = TrackContext.create(failedTrack.getInfo().title, failedTrack.getInfo().title, failedTrack.getInfo().author, determineSourceFromUri(failedTrack.getInfo().uri), 0L, 0L);
        }

        // SoundCloud hatası kaydı (Circuit Breaker)
        if (context.source() == PlaybackSource.SOUNDCLOUD || isSoundCloud404(exception)) {
            SoundCloudCircuitBreaker.recordFailure();
        }

        // Fallback deneme sınırı ve tekrar eden kaynak kontrolü
        PlaybackSource targetSource = (context.source() == PlaybackSource.SOUNDCLOUD || isSoundCloud404(exception))
                ? PlaybackSource.YOUTUBE
                : PlaybackSource.SOUNDCLOUD;

        if (context.fallbackAttempt() >= MAX_PLAYBACK_FALLBACK_ATTEMPTS || context.attemptedSources().contains(targetSource) || (targetSource == PlaybackSource.SOUNDCLOUD && SoundCloudCircuitBreaker.isOpen())) {
            logger.warn("⚠️ [Guild: {}] Fallback sınırı aşıldı veya alternatif kaynak devre dışı. Sıradaki parçaya geçiliyor.", session.getGuildId());
            sendChannelMessage(session.getLastMessageChannel(), getFriendlyErrorMessage(exception));
            session.getScheduler().advanceQueueAfterException();
            return;
        }

        // Alternatif sorgu hazırlığı
        String author = failedTrack.getInfo().author != null ? failedTrack.getInfo().author : "";
        String title = failedTrack.getInfo().title != null ? failedTrack.getInfo().title : "";
        String searchQuery = targetSource == PlaybackSource.YOUTUBE
                ? "ytsearch:" + author + " " + title + " official audio"
                : "scsearch:" + author + " " + title;

        final TrackContext nextContext = context.withAttempt(targetSource);

        sendChannelMessage(session.getLastMessageChannel(), "⚠️ **" + (context.source() == PlaybackSource.SOUNDCLOUD ? "SoundCloud" : "YouTube") + "** bağlantısı çalışmadığı için parça alternatif kaynak üzerinden yeniden başlatılıyor...");

        audioPlayerManager.getPlayerManager().loadItemOrdered(session, searchQuery, new AudioLoadResultHandler() {
            @Override
            public void trackLoaded(AudioTrack fallbackTrack) {
                if (session.getPlaybackGeneration() != currentGen || session.isDestroyed()) {
                    logger.info("ℹ️ [Guild: {}] Manuel skip/stop yapıldığı için fallback iptal edildi.", session.getGuildId());
                    return;
                }
                fallbackTrack.setUserData(nextContext);
                session.getScheduler().startFallbackTrack(fallbackTrack);
            }

            @Override
            public void playlistLoaded(AudioPlaylist playlist) {
                if (session.getPlaybackGeneration() != currentGen || session.isDestroyed()) return;
                if (!playlist.getTracks().isEmpty()) {
                    AudioTrack fallbackTrack = playlist.getTracks().get(0);
                    fallbackTrack.setUserData(nextContext);
                    session.getScheduler().startFallbackTrack(fallbackTrack);
                } else {
                    noMatches();
                }
            }

            @Override
            public void noMatches() {
                if (session.getPlaybackGeneration() != currentGen || session.isDestroyed()) return;
                sendChannelMessage(session.getLastMessageChannel(), "❌ Bu parça şu anda kullanılabilir kaynaklardan oynatılamıyor.");
                session.getScheduler().advanceQueueAfterException();
            }

            @Override
            public void loadFailed(FriendlyException ex) {
                if (session.getPlaybackGeneration() != currentGen || session.isDestroyed()) return;
                sendChannelMessage(session.getLastMessageChannel(), "❌ Bu parça şu anda kullanılabilir kaynaklardan oynatılamıyor.");
                session.getScheduler().advanceQueueAfterException();
            }
        });
    }

    public static String getFriendlyErrorMessage(FriendlyException exception) {
        if (exception == null) return "❌ Bilinmeyen bir oynatma hatası oluştu.";

        String msg = exception.getMessage() != null ? exception.getMessage().toLowerCase() : "";
        Throwable cause = exception.getCause();
        String causeMsg = cause != null && cause.getMessage() != null ? cause.getMessage().toLowerCase() : "";

        if (msg.contains("404") || causeMsg.contains("404") || msg.contains("soundcloud stream")) {
            return "⚠️ SoundCloud bağlantısı geçersiz olduğu için alternatif kaynak deneniyor.";
        }
        if (msg.contains("429") || causeMsg.contains("429") || msg.contains("rate limit")) {
            return "❌ Müzik kaynağı geçici olarak çok fazla istek aldığı için yanıt vermiyor.";
        }
        if (msg.contains("age") || msg.contains("restricted") || msg.contains("private") || msg.contains("region")) {
            return "❌ Bu içerik özel, yaş kısıtlı veya bölgesel olarak engellenmiş olabilir.";
        }

        return "❌ Bu parça şu anda kullanılabilir kaynaklardan oynatılamıyor.";
    }

    public static boolean isSoundCloud404(FriendlyException exception) {
        if (exception == null) return false;
        String msg = exception.getMessage() != null ? exception.getMessage() : "";
        Throwable cause = exception.getCause();
        String causeMsg = cause != null && cause.getMessage() != null ? cause.getMessage() : "";
        return msg.contains("soundcloud") || causeMsg.contains("soundcloud") || msg.contains("404") || causeMsg.contains("404");
    }

    private PlaybackSource determineSource(String query) {
        if (query.startsWith("ytsearch:") || query.contains("youtube.com") || query.contains("youtu.be")) {
            return PlaybackSource.YOUTUBE;
        } else if (query.startsWith("scsearch:") || query.contains("soundcloud.com")) {
            return PlaybackSource.SOUNDCLOUD;
        } else if (query.startsWith("http://") || query.startsWith("https://")) {
            return PlaybackSource.HTTP;
        }
        return PlaybackSource.YOUTUBE;
    }

    private PlaybackSource determineSourceFromUri(String uri) {
        if (uri == null) return PlaybackSource.YOUTUBE;
        if (uri.contains("soundcloud.com")) return PlaybackSource.SOUNDCLOUD;
        if (uri.contains("youtube.com") || uri.contains("youtu.be")) return PlaybackSource.YOUTUBE;
        return PlaybackSource.OTHER;
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

    private void sendChannelMessage(GuildMessageChannel channel, String message) {
        if (channel != null) {
            channel.sendMessage(message).queue(null, err -> logger.warn("Kanal mesaj hatası: {}", err.getMessage()));
        }
    }
}
