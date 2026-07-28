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
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import net.dv8tion.jda.api.managers.AudioManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Queue;

public class CommandManager extends ListenerAdapter {

    private static final Logger logger = LoggerFactory.getLogger(CommandManager.class);
    private static final String PREFIX = "!";
    private static final Color BOT_COLOR = new Color(88, 101, 242);

    private final AudioPlayerManager audioPlayerManager;

    public CommandManager(AudioPlayerManager audioPlayerManager) {
        this.audioPlayerManager = audioPlayerManager;
    }

    // ============================
    // SLASH KOMUT KAYDI
    // ============================
    public static void registerSlashCommands(JDA jda) {
        List<SlashCommandData> commands = new ArrayList<>();
        commands.add(Commands.slash("çal", "Bir parça veya çalma listesi çalar")
                .addOption(OptionType.STRING, "sorgu", "YouTube/SoundCloud URL veya arama terimi", true));
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

        switch (cmd) {
            case "çal", "cal", "play", "p" -> {
                if (args.isEmpty()) { channel.sendMessage("❌ Kullanım: `!çal <URL veya arama>`").queue(); return; }
                VoiceChannel vc = getVoiceChannel(member, channel);
                if (vc == null) return;
                loadAndPlayPrefix(guild, channel, vc, args.startsWith("http") ? args : "ytsearch:" + args);
            }
            case "dur", "pause"            -> {
                GuildAudioManager m = audioPlayerManager.getGuildAudioManager(guild);
                boolean p = !m.player.isPaused(); m.player.setPaused(p);
                channel.sendMessage(p ? "⏸️ Duraklatıldı." : "▶️ Devam ediyor.").queue();
            }
            case "atla", "skip", "s"       -> {
                audioPlayerManager.getGuildAudioManager(guild).scheduler.nextTrack();
                channel.sendMessage("⏭️ Sonraki parçaya geçildi.").queue();
            }
            case "durdur", "stop"          -> {
                audioPlayerManager.disconnect(guild);
                channel.sendMessage("⏹️ Durduruldu.").queue();
            }
            case "ses", "volume", "v"      -> {
                try {
                    int v = Integer.parseInt(args);
                    if (v < 0 || v > 150) { channel.sendMessage("❌ 0–150 arasında olmalı!").queue(); return; }
                    audioPlayerManager.getGuildAudioManager(guild).player.setVolume(v);
                    channel.sendMessage("🔊 Ses **" + v + "%** ayarlandı.").queue();
                } catch (NumberFormatException e) { channel.sendMessage("❌ Kullanım: `!ses <0-150>`").queue(); }
            }
            case "döngü", "loop"           -> {
                GuildAudioManager m = audioPlayerManager.getGuildAudioManager(guild);
                boolean l = !m.scheduler.isLoop(); m.scheduler.setLoop(l);
                channel.sendMessage(l ? "🔁 Döngü **açık**." : "➡️ Döngü **kapalı**.").queue();
            }
            case "temizle", "clear"        -> {
                audioPlayerManager.getGuildAudioManager(guild).scheduler.getQueue().clear();
                channel.sendMessage("🗑️ Kuyruk temizlendi.").queue();
            }
            case "yardim", "yardım", "help", "h" -> channel.sendMessageEmbeds(buildHelpEmbed().build()).queue();
        }
    }

    // ============================
    // SLASH KOMUT İŞLEYİCİLER
    // ============================

    /** /çal — deferReply sonrası hook ile yanıtlar, her durumda hook tamamlanır */
    private void handlePlay(SlashCommandInteractionEvent event) {
        Member member = event.getMember();
        Guild  guild  = event.getGuild();

        VoiceChannel vc = getVoiceChannel(member, null);
        if (vc == null) {
            event.reply("❌ Bir ses kanalına bağlı olmalısın!").setEphemeral(true).queue();
            return;
        }

        String query = event.getOption("sorgu").getAsString();
        final String finalQuery = query.startsWith("http") ? query : "ytsearch:" + query;

        // Yanıtı ertele — Discord 3 sn içinde bir şey görmek ister
        event.deferReply().queue();
        final InteractionHook hook = event.getHook();

        try {
            // Ses kanalına bağlan
            AudioManager am = guild.getAudioManager();
            if (!am.isConnected()) am.openAudioConnection(vc);

            GuildAudioManager manager = audioPlayerManager.getGuildAudioManager(guild);

            audioPlayerManager.getPlayerManager().loadItemOrdered(manager, finalQuery, new AudioLoadResultHandler() {

                @Override
                public void trackLoaded(AudioTrack track) {
                    try {
                        manager.scheduler.queue(track);
                        hook.sendMessage("✅ Kuyruğa eklendi: **" + track.getInfo().title + "**\n" +
                                "⏱️ Süre: `" + TrackScheduler.formatDuration(track.getDuration()) + "`").queue();
                    } catch (Exception e) {
                        hook.sendMessage("✅ Parça kuyruğa eklendi.").queue();
                        logger.error("trackLoaded yanıt hatası: {}", e.getMessage());
                    }
                }

                @Override
                public void playlistLoaded(AudioPlaylist playlist) {
                    try {
                        if (playlist.isSearchResult()) {
                            AudioTrack track = playlist.getTracks().get(0);
                            manager.scheduler.queue(track);
                            hook.sendMessage("🎵 Çalınıyor: **" + track.getInfo().title + "**\n" +
                                    "⏱️ Süre: `" + TrackScheduler.formatDuration(track.getDuration()) + "`").queue();
                        } else {
                            for (AudioTrack t : playlist.getTracks()) manager.scheduler.queue(t);
                            hook.sendMessage("📃 **" + playlist.getName() + "** — `" +
                                    playlist.getTracks().size() + "` parça kuyruğa eklendi!").queue();
                        }
                    } catch (Exception e) {
                        hook.sendMessage("✅ Çalma listesi kuyruğa eklendi.").queue();
                        logger.error("playlistLoaded yanıt hatası: {}", e.getMessage());
                    }
                }

                @Override
                public void noMatches() {
                    hook.sendMessage("❌ **\"" + finalQuery.replace("ytsearch:", "") + "\"** için sonuç bulunamadı!").queue();
                    // Boştaysa ses kanalından çık — sürekli girip çıkmayı engelle
                    if (manager.player.getPlayingTrack() == null && manager.scheduler.getQueue().isEmpty()) {
                        guild.getAudioManager().closeAudioConnection();
                    }
                }

                @Override
                public void loadFailed(FriendlyException exception) {
                    logger.error("loadFailed: {}", exception.getMessage());
                    hook.sendMessage("❌ **Yükleme başarısız:** `" + exception.getMessage() + "`\n" +
                            "💡 Bu video yüklenemedi. SoundCloud URL veya farklı bir parça dene.").queue();
                    // Boştaysa ses kanalından çık — sürekli girip çıkmayı engelle
                    if (manager.player.getPlayingTrack() == null && manager.scheduler.getQueue().isEmpty()) {
                        guild.getAudioManager().closeAudioConnection();
                    }
                }
            });
        } catch (Exception e) {
            // Herhangi bir beklenmedik hata — hook mutlaka yanıtlanmalı
            logger.error("handlePlay beklenmedik hata: {}", e.getMessage(), e);
            hook.sendMessage("❌ Beklenmedik hata oluştu: `" + e.getMessage() + "`").queue();
        }
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
                .addField("Kanal",  cur.getInfo().author, true)
                .addField("Süre",   TrackScheduler.formatDuration(cur.getPosition()) + " / " +
                                    TrackScheduler.formatDuration(cur.getDuration()), true)
                .addField("Döngü",  m.scheduler.isLoop() ? "🔁 Açık" : "➡️ Kapalı", true)
                .addField("Ses",    "🔊 " + m.player.getVolume() + "%", true)
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

    // ============================
    // YARDIMCI METODLAR
    // ============================

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

    /** Prefix komutlar için ayrı load metodu */
    private void loadAndPlayPrefix(Guild guild, TextChannel channel, VoiceChannel vc, String query) {
        AudioManager am = guild.getAudioManager();
        if (!am.isConnected()) am.openAudioConnection(vc);

        GuildAudioManager manager = audioPlayerManager.getGuildAudioManager(guild);

        audioPlayerManager.getPlayerManager().loadItemOrdered(manager, query, new AudioLoadResultHandler() {
            @Override
            public void trackLoaded(AudioTrack track) {
                manager.scheduler.queue(track);
                channel.sendMessage("✅ Kuyruğa eklendi: **" + track.getInfo().title + "**").queue();
            }
            @Override
            public void playlistLoaded(AudioPlaylist playlist) {
                if (playlist.isSearchResult()) {
                    AudioTrack t = playlist.getTracks().get(0);
                    manager.scheduler.queue(t);
                    channel.sendMessage("🎵 Çalınıyor: **" + t.getInfo().title + "**").queue();
                } else {
                    for (AudioTrack t : playlist.getTracks()) manager.scheduler.queue(t);
                    channel.sendMessage("📃 **" + playlist.getName() + "** — `" +
                            playlist.getTracks().size() + "` parça eklendi!").queue();
                }
            }
            @Override
            public void noMatches() {
                channel.sendMessage("❌ Sonuç bulunamadı: **" + query.replace("ytsearch:", "") + "**").queue();
            }
            @Override
            public void loadFailed(FriendlyException ex) {
                channel.sendMessage("❌ Yükleme hatası: `" + ex.getMessage() + "`").queue();
            }
        });
    }

    private EmbedBuilder buildHelpEmbed() {
        return new EmbedBuilder()
                .setTitle("🎵 Fluir Ses Sistemi — Komutlar")
                .setDescription("Slash komutları (`/`) veya prefix (`!`) kullanabilirsin.")
                .addField("🎵 Çalma",
                        "`/çal <URL/arama>` • `!çal`\n`/dur` • `!dur`\n`/atla` • `!atla`\n`/durdur` • `!durdur`", false)
                .addField("📋 Kuyruk",
                        "`/kuyruk`\n`/karistir`\n`/temizle` • `!temizle`", false)
                .addField("⚙️ Ayarlar",
                        "`/ses <0-150>` • `!ses 80`\n`/döngü` • `!döngü`\n`/simdi`", false)
                .addField("🌐 Desteklenen Kaynaklar",
                        "YouTube • SoundCloud • Twitch • Vimeo • Bandcamp • HTTP ses", false)
                .setColor(BOT_COLOR)
                .setFooter("Fluir Ses Sistemi | JDA + LavaPlayer");
    }
}
