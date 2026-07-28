package com.fluir.bot.commands;

import com.fluir.bot.audio.AudioPlayerManager;
import com.fluir.bot.audio.GuildAudioManager;
import com.fluir.bot.audio.TrackScheduler;
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.GuildVoiceState;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import net.dv8tion.jda.api.interactions.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.managers.AudioManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Color;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class CommandManager extends ListenerAdapter {

    private static final Logger logger = LoggerFactory.getLogger(CommandManager.class);
    private static final String PREFIX = "!";
    private static final Color BOT_COLOR = new Color(88, 101, 242);

    private final AudioPlayerManager audioPlayerManager;

    // Arama panelinden seçilen parçaları geçici tutan thread-safe cache
    // Key: selectMenuId, Value: Map<trackIndex, AudioTrack>
    private static final Map<String, List<AudioTrack>> searchPanelCache = new ConcurrentHashMap<>();

    public CommandManager(AudioPlayerManager audioPlayerManager) {
        this.audioPlayerManager = audioPlayerManager;
    }

    // ============================
    // SLASH KOMUT KAYDI
    // ============================
    public static void registerSlashCommands(JDA jda) {
        List<SlashCommandData> commands = new ArrayList<>();
        commands.add(Commands.slash("çal", "Spotify, SoundCloud veya YouTube'dan müzik çalar")
                .addOption(OptionType.STRING, "sorgu", "Şarkı adı veya Spotify / SoundCloud / YouTube URL'si", true));
        commands.add(Commands.slash("dur", "Çalmayı duraklatır veya devam ettirir"));
        commands.add(Commands.slash("atla", "Mevcut parçayı atlar"));
        commands.add(Commands.slash("durdur", "Çalmayı durdurur ve botu çıkarır"));
        commands.add(Commands.slash("kuyruk", "Mevcut çalma kuyruğunu gösterir"));
        commands.add(Commands.slash("döngü", "Döngü modunu açar/kapatır"));
        commands.add(Commands.slash("ses", "Ses seviyesini ayarlar")
                .addOption(OptionType.INTEGER, "seviye", "Ses seviyesi (0-150)", true));
        commands.add(Commands.slash("simdi", "Şu an çalan parçayı gösterir"));
        commands.add(Commands.slash("karistir", "Kuyruğu karıştırır"));
        commands.add(Commands.slash("yardim", "Komut listesini gösterir"));
        commands.add(Commands.slash("temizle", "Kuyruğu temizler"));

        jda.updateCommands().addCommands(commands).queue(
                ok -> logger.info("✅ {} slash komutu kaydedildi.", commands.size()),
                err -> logger.error("❌ Slash komutları kaydedilemedi: {}", err.getMessage())
        );
    }

    // ============================
    // SLASH KOMUT ROUTER
    // ============================
    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (event.getGuild() == null) {
            event.reply("❌ Bu komut sadece sunucularda kullanılabilir!").setEphemeral(true).queue();
            return;
        }

        try {
            switch (event.getName()) {
                case "çal"      -> handlePlay(event);
                case "dur"      -> handlePause(event);
                case "atla"     -> handleSkip(event);
                case "durdur"   -> handleStop(event);
                case "kuyruk"   -> handleQueue(event);
                case "döngü"    -> handleLoop(event);
                case "ses"      -> handleVolume(event);
                case "simdi"    -> handleNowPlaying(event);
                case "karistir" -> handleShuffle(event);
                case "yardim"   -> handleHelp(event);
                case "temizle"  -> handleClear(event);
                default         -> event.reply("❌ Bilinmeyen komut!").setEphemeral(true).queue();
            }
        } catch (Exception e) {
            logger.error("Slash komut hatası [{}]: {}", event.getName(), e.getMessage(), e);
            if (!event.isAcknowledged()) {
                event.reply("❌ İşlem sırasında bir hata oluştu: " + e.getMessage()).setEphemeral(true).queue();
            }
        }
    }

    // ============================
    // DROPDOWN (PANEL) SEÇİM İŞLEYİCİSİ
    // ============================
    @Override
    public void onStringSelectInteraction(StringSelectInteractionEvent event) {
        String menuId = event.getComponentId();
        if (!menuId.startsWith("song_select:")) return;

        try {
            Member member = event.getMember();
            Guild guild = event.getGuild();
            if (member == null || guild == null) return;

            VoiceChannel vc = getVoiceChannel(member, null);
            if (vc == null) {
                event.reply("❌ Bir ses kanalında olmalısın!").setEphemeral(true).queue();
                return;
            }

            List<AudioTrack> tracks = searchPanelCache.get(menuId);
            if (tracks == null || tracks.isEmpty()) {
                event.reply("❌ Seçim süresi doldu, lütfen tekrar arama yapın.").setEphemeral(true).queue();
                return;
            }

            String selectedValue = event.getValues().get(0);
            if (selectedValue.equals("cancel")) {
                searchPanelCache.remove(menuId);
                event.editMessage("❌ **Arama seçimi iptal edildi.**").setComponents().setEmbeds().queue();
                return;
            }

            int index = Integer.parseInt(selectedValue);
            if (index < 0 || index >= tracks.size()) {
                event.reply("❌ Geçersiz seçim.").setEphemeral(true).queue();
                return;
            }

            AudioTrack chosenTrack = tracks.get(index);

            // Ses kanalına bağlan ve çal
            AudioManager am = guild.getAudioManager();
            if (!am.isConnected()) am.openAudioConnection(vc);

            GuildAudioManager manager = audioPlayerManager.getGuildAudioManager(guild);
            manager.scheduler.queue(chosenTrack);

            searchPanelCache.remove(menuId);

            EmbedBuilder eb = new EmbedBuilder()
                    .setTitle("🎵 Parça Seçildi & Kuyruğa Eklendi")
                    .setDescription("**" + chosenTrack.getInfo().title + "**")
                    .addField("Sanatçı / Kanal", chosenTrack.getInfo().author, true)
                    .addField("Süre", TrackScheduler.formatDuration(chosenTrack.getDuration()), true)
                    .setColor(BOT_COLOR);

            event.editMessageEmbeds(eb.build()).setComponents().queue();

        } catch (Exception e) {
            logger.error("Panel seçim hatası: {}", e.getMessage(), e);
            event.reply("❌ Parça başlatılırken hata oluştu.").setEphemeral(true).queue();
        }
    }

    // ============================
    // PREFIX KOMUT ROUTER
    // ============================
    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        if (event.getAuthor().isBot() || !event.isFromGuild()) return;
        String msg = event.getMessage().getContentRaw();
        if (!msg.startsWith(PREFIX)) return;

        String[] parts = msg.substring(PREFIX.length()).trim().split("\\s+", 2);
        String cmd  = parts[0].toLowerCase();
        String args = parts.length > 1 ? parts[1] : "";

        Guild       guild   = event.getGuild();
        TextChannel channel = event.getChannel().asTextChannel();
        Member      member  = event.getMember();

        try {
            switch (cmd) {
                case "çal", "cal", "play", "p" -> {
                    if (args.isEmpty()) { channel.sendMessage("❌ Kullanım: `!çal <şarkı adı veya URL>`").queue(); return; }
                    VoiceChannel vc = getVoiceChannel(member, channel);
                    if (vc == null) return;
                    String query = args.startsWith("http") ? args : "scsearch:" + args;
                    audioPlayerManager.loadAndPlay(guild, channel, vc, query);
                }
                case "dur", "pause" -> {
                    GuildAudioManager m = audioPlayerManager.getGuildAudioManager(guild);
                    boolean p = !m.player.isPaused(); m.player.setPaused(p);
                    channel.sendMessage(p ? "⏸️ Duraklatıldı." : "▶️ Devam ediyor.").queue();
                }
                case "atla", "skip", "s" -> {
                    audioPlayerManager.getGuildAudioManager(guild).scheduler.nextTrack();
                    channel.sendMessage("⏭️ Sonraki parçaya geçildi.").queue();
                }
                case "durdur", "stop" -> {
                    audioPlayerManager.disconnect(guild);
                    channel.sendMessage("⏹️ Durduruldu.").queue();
                }
                case "ses", "volume", "v" -> {
                    try {
                        int v = Integer.parseInt(args);
                        if (v < 0 || v > 150) { channel.sendMessage("❌ 0–150 arasında olmalı!").queue(); return; }
                        audioPlayerManager.getGuildAudioManager(guild).player.setVolume(v);
                        channel.sendMessage("🔊 Ses **" + v + "%** ayarlandı.").queue();
                    } catch (NumberFormatException e) { channel.sendMessage("❌ Kullanım: `!ses <0-150>`").queue(); }
                }
                case "döngü", "loop" -> {
                    GuildAudioManager m = audioPlayerManager.getGuildAudioManager(guild);
                    boolean l = !m.scheduler.isLoop(); m.scheduler.setLoop(l);
                    channel.sendMessage(l ? "🔁 Döngü **açık**." : "➡️ Döngü **kapalı**.").queue();
                }
                case "temizle", "clear" -> {
                    audioPlayerManager.getGuildAudioManager(guild).scheduler.getQueue().clear();
                    channel.sendMessage("🗑️ Kuyruk temizlendi.").queue();
                }
                case "yardim", "yardım", "help", "h" -> channel.sendMessageEmbeds(buildHelpEmbed().build()).queue();
            }
        } catch (Exception e) {
            logger.error("Prefix komut hatası [!{}]: {}", cmd, e.getMessage());
        }
    }

    // ============================
    // /ÇAL İŞLEYİCİSİ (PANEL VE LİNK Desteği)
    // ============================
    private void handlePlay(SlashCommandInteractionEvent event) {
        Member member = event.getMember();
        Guild  guild  = event.getGuild();

        VoiceChannel vc = getVoiceChannel(member, null);
        if (vc == null) {
            event.reply("❌ Bir ses kanalında olmalısın!").setEphemeral(true).queue();
            return;
        }

        String rawQuery = event.getOption("sorgu").getAsString().trim();
        event.deferReply().queue();
        final InteractionHook hook = event.getHook();

        // 1. Eğer direkt URL verilmişse (Spotify, SoundCloud, YouTube vb.)
        if (rawQuery.startsWith("http://") || rawQuery.startsWith("https://")) {
            loadDirectUrl(guild, hook, vc, rawQuery);
            return;
        }

        // 2. Metin araması ise: Önce SoundCloud/YouTube üzerinden ara, panel olarak sun!
        String searchPrefixQuery = "scsearch:" + rawQuery;
        GuildAudioManager manager = audioPlayerManager.getGuildAudioManager(guild);

        audioPlayerManager.getPlayerManager().loadItemOrdered(manager, searchPrefixQuery, new AudioLoadResultHandler() {
            @Override
            public void trackLoaded(AudioTrack track) {
                // Tek sonuç döndü
                if (AudioPlayerManager.isUnwantedMedia(track.getInfo().title)) {
                    hook.sendMessage("⚠️ **\"" + track.getInfo().title + "\"** (film/dizi/fragman) olduğu için filtrelendi.").queue();
                    return;
                }
                playTrackImmediately(guild, hook, vc, track);
            }

            @Override
            public void playlistLoaded(AudioPlaylist playlist) {
                if (playlist.isSearchResult()) {
                    List<AudioTrack> validTracks = new ArrayList<>();
                    for (AudioTrack t : playlist.getTracks()) {
                        if (!AudioPlayerManager.isUnwantedMedia(t.getInfo().title)) {
                            validTracks.add(t);
                        }
                        if (validTracks.size() >= 5) break; // En iyi 5 şarkı
                    }

                    if (validTracks.isEmpty()) {
                        hook.sendMessage("⚠️ Arama sonuçlarındaki tüm içerikler film/dizi/fragman olduğu için filtrelendi.").queue();
                        return;
                    }

                    if (validTracks.size() == 1) {
                        playTrackImmediately(guild, hook, vc, validTracks.get(0));
                        return;
                    }

                    // ARAMA PANELİ (DROPDOWN SELECT MENU) OLUŞTUR
                    sendSearchSelectionPanel(hook, rawQuery, validTracks);

                } else {
                    // Normal Playlist
                    int added = 0;
                    for (AudioTrack t : playlist.getTracks()) {
                        if (!AudioPlayerManager.isUnwantedMedia(t.getInfo().title)) {
                            manager.scheduler.queue(t);
                            added++;
                        }
                    }
                    hook.sendMessage("📃 **" + playlist.getName() + "** — `" + added + "` parça kuyruğa eklendi.").queue();
                }
            }

            @Override
            public void noMatches() {
                // YouTube ile tekrar dene
                tryYtSearchFallback(guild, hook, vc, rawQuery);
            }

            @Override
            public void loadFailed(FriendlyException exception) {
                tryYtSearchFallback(guild, hook, vc, rawQuery);
            }
        });
    }

    private void tryYtSearchFallback(Guild guild, InteractionHook hook, VoiceChannel vc, String rawQuery) {
        String ytQuery = "ytsearch:" + rawQuery;
        GuildAudioManager manager = audioPlayerManager.getGuildAudioManager(guild);

        audioPlayerManager.getPlayerManager().loadItemOrdered(manager, ytQuery, new AudioLoadResultHandler() {
            @Override
            public void trackLoaded(AudioTrack track) {
                if (AudioPlayerManager.isUnwantedMedia(track.getInfo().title)) {
                    hook.sendMessage("⚠️ Film/dizi/fragman icerigi filtrelendi.").queue();
                    return;
                }
                playTrackImmediately(guild, hook, vc, track);
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
                        hook.sendMessage("⚠️ Sonuçlar film/dizi/fragman olduğu için filtrelendi.").queue();
                        return;
                    }
                    sendSearchSelectionPanel(hook, rawQuery, validTracks);
                } else {
                    for (AudioTrack t : playlist.getTracks()) manager.scheduler.queue(t);
                    hook.sendMessage("📃 Çalma listesi eklendi.").queue();
                }
            }

            @Override
            public void noMatches() {
                hook.sendMessage("❌ **\"" + rawQuery + "\"** için müzik bulunamadı.").queue();
            }

            @Override
            public void loadFailed(FriendlyException exception) {
                hook.sendMessage("❌ Arama sırasında bir sorun oluştu: `" + exception.getMessage() + "`").queue();
            }
        });
    }

    private void sendSearchSelectionPanel(InteractionHook hook, String query, List<AudioTrack> tracks) {
        String customId = "song_select:" + UUID.randomUUID().toString().substring(0, 8);
        searchPanelCache.put(customId, tracks);

        EmbedBuilder eb = new EmbedBuilder()
                .setTitle("🔎 Arama Sonuçları Panel")
                .setDescription("💡 **\"" + query + "\"** için bulunan parçalar (Film/Dizi/Fragman filtrelendi):\n\nAşağıdaki listeden çalmak istediğin şarkıyı seç:")
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

        hook.sendMessageEmbeds(eb.build())
                .addActionRow(menuBuilder.build())
                .queue();
    }

    private void loadDirectUrl(Guild guild, InteractionHook hook, VoiceChannel vc, String url) {
        if (url.contains("spotify.com")) {
            String resolvedQuery = com.fluir.bot.audio.SpotifyResolver.resolveSpotifyUrl(url);
            if (resolvedQuery != null) {
                url = "scsearch:" + resolvedQuery;
            }
        }

        GuildAudioManager manager = audioPlayerManager.getGuildAudioManager(guild);

        audioPlayerManager.getPlayerManager().loadItemOrdered(manager, url, new AudioLoadResultHandler() {
            @Override
            public void trackLoaded(AudioTrack track) {
                if (AudioPlayerManager.isUnwantedMedia(track.getInfo().title)) {
                    hook.sendMessage("⚠️ **\"" + track.getInfo().title + "\"** (film/dizi/fragman) filtrelendi.").queue();
                    return;
                }
                playTrackImmediately(guild, hook, vc, track);
            }

            @Override
            public void playlistLoaded(AudioPlaylist playlist) {
                if (playlist.isSearchResult()) {
                    AudioTrack t = playlist.getTracks().get(0);
                    playTrackImmediately(guild, hook, vc, t);
                } else {
                    int added = 0;
                    for (AudioTrack t : playlist.getTracks()) {
                        if (!AudioPlayerManager.isUnwantedMedia(t.getInfo().title)) {
                            manager.scheduler.queue(t);
                            added++;
                        }
                    }
                    hook.sendMessage("📃 **" + playlist.getName() + "** — `" + added + "` parça eklendi.").queue();
                }
            }

            @Override
            public void noMatches() {
                hook.sendMessage("❌ Bağlantıda çalınabilir ses bulunamadı.").queue();
            }

            @Override
            public void loadFailed(FriendlyException exception) {
                hook.sendMessage("❌ Link yüklenirken hata oluştu: `" + exception.getMessage() + "`").queue();
            }
        });
    }

    private void playTrackImmediately(Guild guild, InteractionHook hook, VoiceChannel vc, AudioTrack track) {
        AudioManager am = guild.getAudioManager();
        if (!am.isConnected()) am.openAudioConnection(vc);

        GuildAudioManager manager = audioPlayerManager.getGuildAudioManager(guild);
        manager.scheduler.queue(track);

        hook.sendMessage("✅ Kuyruğa eklendi: **" + track.getInfo().title + "**\n" +
                "👤 Sanatçı: `" + track.getInfo().author + "` | ⏱️ Süre: `" + TrackScheduler.formatDuration(track.getDuration()) + "`").queue();
    }

    private void handlePause(SlashCommandInteractionEvent event) {
        GuildAudioManager m = audioPlayerManager.getGuildAudioManager(event.getGuild());
        if (m.player.getPlayingTrack() == null) {
            event.reply("❌ Şu an hiçbir şey çalmıyor!").setEphemeral(true).queue();
            return;
        }
        boolean p = !m.player.isPaused();
        m.player.setPaused(p);
        event.reply(p ? "⏸️ **Duraklatıldı.**" : "▶️ **Devam ediyor.**").queue();
    }

    private void handleSkip(SlashCommandInteractionEvent event) {
        GuildAudioManager m = audioPlayerManager.getGuildAudioManager(event.getGuild());
        if (m.player.getPlayingTrack() == null) {
            event.reply("❌ Atlanacak parça yok!").setEphemeral(true).queue();
            return;
        }
        m.scheduler.nextTrack();
        event.reply("⏭️ **Sonraki parçaya geçildi.**").queue();
    }

    private void handleStop(SlashCommandInteractionEvent event) {
        audioPlayerManager.disconnect(event.getGuild());
        event.reply("⏹️ **Durduruldu ve ses kanalından çıkıldı.**").queue();
    }

    private void handleQueue(SlashCommandInteractionEvent event) {
        GuildAudioManager m     = audioPlayerManager.getGuildAudioManager(event.getGuild());
        Queue<AudioTrack> queue = m.scheduler.getQueue();
        AudioTrack        cur   = m.scheduler.getCurrentTrack();

        EmbedBuilder eb = new EmbedBuilder().setTitle("🎵 Çalma Kuyruğu").setColor(BOT_COLOR);
        StringBuilder sb = new StringBuilder();

        if (cur != null) {
            sb.append("**▶️ Şu an çalıyor:**\n`").append(cur.getInfo().title)
              .append("` — `").append(TrackScheduler.formatDuration(cur.getDuration())).append("`\n\n");
        } else {
            sb.append("*Şu an hiçbir şey çalmıyor.*\n\n");
        }

        if (queue.isEmpty()) {
            sb.append("*Kuyruk boş.*");
        } else {
            sb.append("**📋 Sıradakiler:**\n");
            int i = 1;
            for (AudioTrack t : queue) {
                if (i > 10) { sb.append("... ve `").append(queue.size() - 10).append("` parça daha."); break; }
                sb.append("`").append(i++).append(".` ").append(t.getInfo().title)
                  .append(" — `").append(TrackScheduler.formatDuration(t.getDuration())).append("`\n");
            }
        }

        eb.setDescription(sb.toString());
        if (m.scheduler.isLoop()) eb.setFooter("🔁 Döngü aktif");
        event.replyEmbeds(eb.build()).queue();
    }

    private void handleLoop(SlashCommandInteractionEvent event) {
        GuildAudioManager m = audioPlayerManager.getGuildAudioManager(event.getGuild());
        boolean l = !m.scheduler.isLoop();
        m.scheduler.setLoop(l);
        event.reply(l ? "🔁 **Döngü açık.**" : "➡️ **Döngü kapalı.**").queue();
    }

    private void handleVolume(SlashCommandInteractionEvent event) {
        int vol = (int) event.getOption("seviye").getAsLong();
        if (vol < 0 || vol > 150) {
            event.reply("❌ Ses 0–150 arasında olmalıdır!").setEphemeral(true).queue();
            return;
        }
        audioPlayerManager.getGuildAudioManager(event.getGuild()).player.setVolume(vol);
        event.reply("🔊 Ses seviyesi **" + vol + "%** olarak ayarlandı.").queue();
    }

    private void handleNowPlaying(SlashCommandInteractionEvent event) {
        GuildAudioManager m   = audioPlayerManager.getGuildAudioManager(event.getGuild());
        AudioTrack        cur = m.scheduler.getCurrentTrack();

        if (cur == null) {
            event.reply("❌ Şu an hiçbir şey çalmıyor!").setEphemeral(true).queue();
            return;
        }

        EmbedBuilder eb = new EmbedBuilder()
                .setTitle("🎵 Şu An Çalıyor")
                .setDescription("**" + cur.getInfo().title + "**")
                .addField("Kanal / Sanatçı", cur.getInfo().author, true)
                .addField("Süre", TrackScheduler.formatDuration(cur.getPosition()) + " / " +
                                    TrackScheduler.formatDuration(cur.getDuration()), true)
                .addField("Döngü", m.scheduler.isLoop() ? "🔁 Açık" : "➡️ Kapalı", true)
                .addField("Ses", "🔊 " + m.player.getVolume() + "%", true)
                .setColor(BOT_COLOR)
                .setUrl(cur.getInfo().uri);

        event.replyEmbeds(eb.build()).queue();
    }

    private void handleShuffle(SlashCommandInteractionEvent event) {
        GuildAudioManager m = audioPlayerManager.getGuildAudioManager(event.getGuild());
        List<AudioTrack> list = new ArrayList<>(m.scheduler.getQueue());
        Collections.shuffle(list);
        m.scheduler.getQueue().clear();
        m.scheduler.getQueue().addAll(list);
        event.reply("🔀 **Kuyruk karıştırıldı!** (`" + list.size() + "` parça)").queue();
    }

    private void handleClear(SlashCommandInteractionEvent event) {
        audioPlayerManager.getGuildAudioManager(event.getGuild()).scheduler.getQueue().clear();
        event.reply("🗑️ **Kuyruk temizlendi.**").queue();
    }

    private void handleHelp(SlashCommandInteractionEvent event) {
        event.replyEmbeds(buildHelpEmbed().build()).queue();
    }

    private VoiceChannel getVoiceChannel(Member member, TextChannel textChannel) {
        if (member == null) return null;
        GuildVoiceState vs = member.getVoiceState();
        if (vs == null || !vs.inAudioChannel()) {
            if (textChannel != null)
                textChannel.sendMessage("❌ Bir ses kanalına bağlı olmalısın!").queue();
            return null;
        }
        return (VoiceChannel) vs.getChannel();
    }

    private EmbedBuilder buildHelpEmbed() {
        return new EmbedBuilder()
                .setTitle("🎵 Fluir Ses Sistemi — Komutlar")
                .setDescription("Slash komutları (`/`) veya prefix (`!`) kullanabilirsin.")
                .addField("🎵 Çalma & Arama",
                        "`/çal <sorgu>` • Arama paneli açar, Spotify/SoundCloud/YouTube destekler\n`/dur` • Duraklat/Devam\n`/atla` • Sonraki parça\n`/durdur` • Çıkış yap", false)
                .addField("📋 Panel & Kuyruk",
                        "`/kuyruk` • Sıradakileri gör\n`/karistir` • Karıştır\n`/temizle` • Temizle", false)
                .addField("⚙️ Ayarlar",
                        "`/ses <0-150>` • Ses ayarla\n`/döngü` • Döngü\n`/simdi` • Şu an çalan", false)
                .addField("🛡️ Filtreler & Desteği",
                        "Film, dizi, fragman ve teaser videoları otomatik engellenir.", false)
                .setColor(BOT_COLOR)
                .setFooter("Fluir Ses Sistemi | Spotify + SoundCloud + JDA");
    }
}
