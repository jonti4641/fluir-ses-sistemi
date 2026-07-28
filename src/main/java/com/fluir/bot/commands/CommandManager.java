package com.fluir.bot.commands;

import com.fluir.bot.audio.AudioPlayerManager;
import com.fluir.bot.audio.GuildAudioManager;
import com.fluir.bot.audio.TrackScheduler;
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
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

/**
 * Slash komutlarını ve prefix komutlarını yöneten sınıf.
 * Prefix: ! (örn: !çal, !dur)
 * Slash: /çal, /dur vb.
 */
public class CommandManager extends ListenerAdapter {

    private static final Logger logger = LoggerFactory.getLogger(CommandManager.class);
    private static final String PREFIX = "!";

    private final AudioPlayerManager audioPlayerManager;

    public CommandManager(AudioPlayerManager audioPlayerManager) {
        this.audioPlayerManager = audioPlayerManager;
    }

    /**
     * Slash komutlarını Discord'a kaydeder.
     */
    public static void registerSlashCommands(JDA jda) {
        List<SlashCommandData> commands = new ArrayList<>();

        commands.add(Commands.slash("çal", "Bir parça veya çalma listesi çalar")
                .addOption(OptionType.STRING, "sorgu", "YouTube/SoundCloud URL veya arama terimi", true));

        commands.add(Commands.slash("dur", "Çalmayı duraklatır veya devam ettirir"));
        commands.add(Commands.slash("atla", "Mevcut parçayı atlar ve sıradakine geçer"));
        commands.add(Commands.slash("durdur", "Çalmayı durdurur ve botu ses kanalından çıkarır"));
        commands.add(Commands.slash("kuyruk", "Mevcut çalma kuyruğunu gösterir"));
        commands.add(Commands.slash("döngü", "Mevcut parçanın döngüsünü açar/kapatır"));
        commands.add(Commands.slash("ses", "Ses seviyesini ayarlar")
                .addOption(OptionType.INTEGER, "seviye", "Ses seviyesi (0-150)", true));
        commands.add(Commands.slash("şimdi", "Şu an çalan parçayı gösterir"));
        commands.add(Commands.slash("karıştır", "Kuyruğu karıştırır"));
        commands.add(Commands.slash("yardım", "Komut listesini gösterir"));
        commands.add(Commands.slash("temizle", "Kuyruğu temizler"));

        jda.updateCommands().addCommands(commands).queue(
                success -> logger.info("✅ {} slash komutu kaydedildi.", commands.size()),
                error -> logger.error("❌ Slash komutları kaydedilemedi: {}", error.getMessage())
        );
    }

    // ============================
    // SLASH KOMUT İŞLEYİCİ
    // ============================
    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (event.getGuild() == null) return;

        switch (event.getName()) {
            case "çal" -> handlePlay(event);
            case "dur" -> handlePause(event);
            case "atla" -> handleSkip(event);
            case "durdur" -> handleStop(event);
            case "kuyruk" -> handleQueue(event);
            case "döngü" -> handleLoop(event);
            case "ses" -> handleVolume(event);
            case "şimdi" -> handleNowPlaying(event);
            case "karıştır" -> handleShuffle(event);
            case "yardım" -> handleHelp(event);
            case "temizle" -> handleClear(event);
            default -> event.reply("❌ Bilinmeyen komut!").setEphemeral(true).queue();
        }
    }

    // ============================
    // PREFIX KOMUT İŞLEYİCİ
    // ============================
    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        if (event.getAuthor().isBot()) return;
        if (!event.isFromGuild()) return;

        String message = event.getMessage().getContentRaw();
        if (!message.startsWith(PREFIX)) return;

        String[] parts = message.substring(PREFIX.length()).trim().split("\\s+", 2);
        String command = parts[0].toLowerCase();
        String args = parts.length > 1 ? parts[1] : "";

        Guild guild = event.getGuild();
        TextChannel textChannel = event.getChannel().asTextChannel();
        Member member = event.getMember();

        switch (command) {
            case "çal", "cal", "play", "p" -> {
                if (args.isEmpty()) {
                    textChannel.sendMessage("❌ Kullanım: `!çal <URL veya arama terimi>`").queue();
                    return;
                }
                VoiceChannel voiceChannel = getVoiceChannel(member, textChannel);
                if (voiceChannel == null) return;
                String query = args.startsWith("http") ? args : "ytsearch:" + args;
                audioPlayerManager.loadAndPlay(guild, textChannel, voiceChannel, query);
            }
            case "dur", "pause" -> {
                GuildAudioManager mgr = audioPlayerManager.getGuildAudioManager(guild);
                boolean paused = !mgr.player.isPaused();
                mgr.player.setPaused(paused);
                textChannel.sendMessage(paused ? "⏸️ Duraklatıldı." : "▶️ Devam ediyor.").queue();
            }
            case "atla", "skip", "s" -> {
                GuildAudioManager mgr = audioPlayerManager.getGuildAudioManager(guild);
                mgr.scheduler.nextTrack();
                textChannel.sendMessage("⏭️ Sonraki parçaya geçildi.").queue();
            }
            case "durdur", "stop" -> {
                audioPlayerManager.disconnect(guild);
                textChannel.sendMessage("⏹️ Durduruldu ve ses kanalından çıkıldı.").queue();
            }
            case "ses", "volume", "v" -> {
                try {
                    int vol = Integer.parseInt(args);
                    if (vol < 0 || vol > 150) {
                        textChannel.sendMessage("❌ Ses seviyesi 0-150 arasında olmalıdır!").queue();
                        return;
                    }
                    GuildAudioManager mgr = audioPlayerManager.getGuildAudioManager(guild);
                    mgr.player.setVolume(vol);
                    textChannel.sendMessage("🔊 Ses seviyesi **" + vol + "%** olarak ayarlandı.").queue();
                } catch (NumberFormatException e) {
                    textChannel.sendMessage("❌ Kullanım: `!ses <0-150>`").queue();
                }
            }
            case "döngü", "loop" -> {
                GuildAudioManager mgr = audioPlayerManager.getGuildAudioManager(guild);
                boolean loop = !mgr.scheduler.isLoop();
                mgr.scheduler.setLoop(loop);
                textChannel.sendMessage(loop ? "🔁 Döngü **açık**." : "➡️ Döngü **kapalı**.").queue();
            }
            case "temizle", "clear" -> {
                GuildAudioManager mgr = audioPlayerManager.getGuildAudioManager(guild);
                mgr.scheduler.getQueue().clear();
                textChannel.sendMessage("🗑️ Kuyruk temizlendi.").queue();
            }
            case "yardım", "help", "h" -> {
                sendHelpEmbed(textChannel);
            }
        }
    }

    // ============================
    // KOMUT İŞLEYİCİ METODları
    // ============================

    private void handlePlay(SlashCommandInteractionEvent event) {
        Member member = event.getMember();
        Guild guild = event.getGuild();
        TextChannel textChannel = event.getChannel().asTextChannel();

        VoiceChannel voiceChannel = getVoiceChannel(member, null);
        if (voiceChannel == null) {
            event.reply("❌ Bir ses kanalına bağlı olmalısın!").setEphemeral(true).queue();
            return;
        }

        String query = event.getOption("sorgu").getAsString();
        if (!query.startsWith("http")) {
            query = "ytsearch:" + query;
        }

        event.deferReply().queue();
        audioPlayerManager.loadAndPlay(guild, textChannel, voiceChannel, query);
    }

    private void handlePause(SlashCommandInteractionEvent event) {
        GuildAudioManager mgr = audioPlayerManager.getGuildAudioManager(event.getGuild());
        boolean paused = !mgr.player.isPaused();
        mgr.player.setPaused(paused);
        event.reply(paused ? "⏸️ **Duraklatıldı.**" : "▶️ **Devam ediyor.**").queue();
    }

    private void handleSkip(SlashCommandInteractionEvent event) {
        GuildAudioManager mgr = audioPlayerManager.getGuildAudioManager(event.getGuild());
        mgr.scheduler.nextTrack();
        event.reply("⏭️ **Sonraki parçaya geçildi.**").queue();
    }

    private void handleStop(SlashCommandInteractionEvent event) {
        audioPlayerManager.disconnect(event.getGuild());
        event.reply("⏹️ **Durduruldu ve ses kanalından çıkıldı.**").queue();
    }

    private void handleQueue(SlashCommandInteractionEvent event) {
        GuildAudioManager mgr = audioPlayerManager.getGuildAudioManager(event.getGuild());
        Queue<AudioTrack> queue = mgr.scheduler.getQueue();
        AudioTrack current = mgr.scheduler.getCurrentTrack();

        EmbedBuilder embed = new EmbedBuilder()
                .setTitle("🎵 Çalma Kuyruğu")
                .setColor(new Color(88, 101, 242));

        StringBuilder sb = new StringBuilder();

        if (current != null) {
            sb.append("**▶️ Şu an çalıyor:**\n`").append(current.getInfo().title)
              .append("` (").append(TrackScheduler.formatDuration(current.getDuration())).append(")\n\n");
        }

        if (queue.isEmpty()) {
            sb.append("*Kuyruk boş.*");
        } else {
            sb.append("**📋 Sıradaki parçalar:**\n");
            int i = 1;
            for (AudioTrack track : queue) {
                if (i > 10) {
                    sb.append("... ve ").append(queue.size() - 10).append(" parça daha.");
                    break;
                }
                sb.append("`").append(i++).append(".` ").append(track.getInfo().title)
                  .append(" (").append(TrackScheduler.formatDuration(track.getDuration())).append(")\n");
            }
        }

        embed.setDescription(sb.toString());
        if (mgr.scheduler.isLoop()) embed.setFooter("🔁 Döngü aktif");

        event.replyEmbeds(embed.build()).queue();
    }

    private void handleLoop(SlashCommandInteractionEvent event) {
        GuildAudioManager mgr = audioPlayerManager.getGuildAudioManager(event.getGuild());
        boolean loop = !mgr.scheduler.isLoop();
        mgr.scheduler.setLoop(loop);
        event.reply(loop ? "🔁 **Döngü açık.**" : "➡️ **Döngü kapalı.**").queue();
    }

    private void handleVolume(SlashCommandInteractionEvent event) {
        int vol = event.getOption("seviye").getAsInt();
        if (vol < 0 || vol > 150) {
            event.reply("❌ Ses seviyesi 0-150 arasında olmalıdır!").setEphemeral(true).queue();
            return;
        }
        GuildAudioManager mgr = audioPlayerManager.getGuildAudioManager(event.getGuild());
        mgr.player.setVolume(vol);
        event.reply("🔊 Ses seviyesi **" + vol + "%** olarak ayarlandı.").queue();
    }

    private void handleNowPlaying(SlashCommandInteractionEvent event) {
        GuildAudioManager mgr = audioPlayerManager.getGuildAudioManager(event.getGuild());
        AudioTrack current = mgr.scheduler.getCurrentTrack();

        if (current == null) {
            event.reply("❌ Şu an hiçbir şey çalmıyor!").setEphemeral(true).queue();
            return;
        }

        long pos = current.getPosition();
        long dur = current.getDuration();

        EmbedBuilder embed = new EmbedBuilder()
                .setTitle("🎵 Şu An Çalıyor")
                .setDescription("**" + current.getInfo().title + "**")
                .addField("Kanal", current.getInfo().author, true)
                .addField("Süre", TrackScheduler.formatDuration(pos) + " / " + TrackScheduler.formatDuration(dur), true)
                .addField("Döngü", mgr.scheduler.isLoop() ? "🔁 Açık" : "➡️ Kapalı", true)
                .addField("Ses", "🔊 " + mgr.player.getVolume() + "%", true)
                .setColor(new Color(88, 101, 242))
                .setUrl(current.getInfo().uri);

        event.replyEmbeds(embed.build()).queue();
    }

    private void handleShuffle(SlashCommandInteractionEvent event) {
        GuildAudioManager mgr = audioPlayerManager.getGuildAudioManager(event.getGuild());
        List<AudioTrack> tracks = new ArrayList<>(mgr.scheduler.getQueue());
        java.util.Collections.shuffle(tracks);
        mgr.scheduler.getQueue().clear();
        mgr.scheduler.getQueue().addAll(tracks);
        event.reply("🔀 **Kuyruk karıştırıldı!**").queue();
    }

    private void handleClear(SlashCommandInteractionEvent event) {
        GuildAudioManager mgr = audioPlayerManager.getGuildAudioManager(event.getGuild());
        mgr.scheduler.getQueue().clear();
        event.reply("🗑️ **Kuyruk temizlendi.**").queue();
    }

    private void handleHelp(SlashCommandInteractionEvent event) {
        EmbedBuilder embed = buildHelpEmbed();
        event.replyEmbeds(embed.build()).queue();
    }

    // ============================
    // YARDIMCI METODlar
    // ============================

    private VoiceChannel getVoiceChannel(Member member, TextChannel textChannel) {
        if (member == null) return null;
        GuildVoiceState voiceState = member.getVoiceState();
        if (voiceState == null || !voiceState.inAudioChannel()) {
            if (textChannel != null) {
                textChannel.sendMessage("❌ Bir ses kanalına bağlı olmalısın!").queue();
            }
            return null;
        }
        return (VoiceChannel) voiceState.getChannel();
    }

    private void sendHelpEmbed(TextChannel channel) {
        channel.sendMessageEmbeds(buildHelpEmbed().build()).queue();
    }

    private EmbedBuilder buildHelpEmbed() {
        return new EmbedBuilder()
                .setTitle("🎵 Fluir Ses Sistemi - Komutlar")
                .setDescription("Hem `/komut` (slash) hem `!komut` (prefix) kullanabilirsin.")
                .addField("🎵 Çalma",
                        "`/çal <URL/arama>` - Parça veya çalma listesi çal\n" +
                        "`/dur` - Duraklatma/Devam\n" +
                        "`/atla` - Sonraki parçaya geç\n" +
                        "`/durdur` - Durdur ve çık", false)
                .addField("📋 Kuyruk",
                        "`/kuyruk` - Kuyruğu gör\n" +
                        "`/karıştır` - Kuyruğu karıştır\n" +
                        "`/temizle` - Kuyruğu temizle", false)
                .addField("⚙️ Ayarlar",
                        "`/ses <0-150>` - Ses seviyesi\n" +
                        "`/döngü` - Döngü aç/kapat\n" +
                        "`/şimdi` - Şu an çalanı gör", false)
                .addField("📌 Desteklenen Kaynaklar",
                        "YouTube, SoundCloud, Twitch, Vimeo, Bandcamp, HTTP Ses Dosyaları", false)
                .setColor(new Color(88, 101, 242))
                .setFooter("Fluir Ses Sistemi | JDA + LavaPlayer");
    }
}
