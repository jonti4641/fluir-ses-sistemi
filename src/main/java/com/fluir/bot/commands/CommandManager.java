package com.fluir.bot.commands;

import com.fluir.bot.audio.*;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Queue;

/**
 * Slash komutlar, prefix komutlar ve dropdown seçim etkileşimlerini yöneten listener.
 */
public class CommandManager extends ListenerAdapter {

    private static final Logger logger = LoggerFactory.getLogger(CommandManager.class);
    private static final String PREFIX = "!";
    private static final Color BOT_COLOR = new Color(88, 101, 242);

    private final AudioPlayerManager audioPlayerManager;

    public CommandManager(AudioPlayerManager audioPlayerManager) {
        this.audioPlayerManager = audioPlayerManager;
    }

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

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (event.getGuild() == null) {
            event.reply("❌ Bu komut sadece sunucularda kullanılabilir!").setEphemeral(true).queue();
            return;
        }

        try {
            switch (event.getName()) {
                case "çal"      -> handlePlaySlash(event);
                case "dur"      -> handlePauseSlash(event);
                case "atla"     -> handleSkipSlash(event);
                case "durdur"   -> handleStopSlash(event);
                case "kuyruk"   -> handleQueueSlash(event);
                case "döngü"    -> handleLoopSlash(event);
                case "ses"      -> handleVolumeSlash(event);
                case "simdi"    -> handleNowPlayingSlash(event);
                case "karistir" -> handleShuffleSlash(event);
                case "yardim"   -> handleHelpSlash(event);
                case "temizle"  -> handleClearSlash(event);
                default         -> event.reply("❌ Bilinmeyen komut!").setEphemeral(true).queue();
            }
        } catch (Exception e) {
            logger.error("Slash komut hatası [{}]: {}", event.getName(), e.getMessage(), e);
            if (!event.isAcknowledged()) {
                event.reply("❌ İşlem sırasında bir hata oluştu: " + e.getMessage()).setEphemeral(true).queue();
            }
        }
    }

    @Override
    public void onStringSelectInteraction(StringSelectInteractionEvent event) {
        String menuId = event.getComponentId();
        if (!menuId.startsWith("song_select:")) return;

        try {
            Member member = event.getMember();
            Guild guild = event.getGuild();
            if (member == null || guild == null) return;

            long userId = member.getUser().getIdLong();
            SearchPanelCache.SearchPanelResult cacheResult = SearchPanelCache.getAndRemove(menuId, userId);

            if (cacheResult.status() == SearchPanelCache.SearchPanelStatus.EXPIRED_OR_NOT_FOUND) {
                event.reply("❌ Bu arama panelinin süresi dolmuş veya panel bulunamadı.").setEphemeral(true).queue();
                return;
            }

            if (cacheResult.status() == SearchPanelCache.SearchPanelStatus.UNAUTHORIZED) {
                event.reply("❌ Bu paneli yalnızca aramayı başlatan kullanıcı kullanabilir!").setEphemeral(true).queue();
                return;
            }

            AudioChannel userChannel = getUserAudioChannel(member, null);
            if (userChannel == null) {
                event.reply("❌ Bir ses kanalına bağlı olmalısın!").setEphemeral(true).queue();
                return;
            }

            String selectedValue = event.getValues().get(0);
            if ("cancel".equals(selectedValue)) {
                event.editMessage("❌ **Arama seçimi iptal edildi.**").setComponents().setEmbeds().queue();
                return;
            }

            int index = Integer.parseInt(selectedValue);
            List<AudioTrack> tracks = cacheResult.tracks();
            if (index < 0 || index >= tracks.size()) {
                event.reply("❌ Geçersiz seçim.").setEphemeral(true).queue();
                return;
            }

            AudioTrack chosenTrack = tracks.get(index);
            GuildAudioSession session = audioPlayerManager.getOrCreateSession(guild);

            GuildAudioSession.ConnectionResult connResult = session.ensureConnected(guild, userChannel, event.getChannel().asTextChannel());
            if (!connResult.success()) {
                event.reply(connResult.message()).setEphemeral(true).queue();
                return;
            }

            session.getScheduler().queue(chosenTrack);

            EmbedBuilder eb = new EmbedBuilder()
                    .setTitle("🎵 Parça Seçildi & Kuyruğa Eklendi")
                    .setDescription("**" + chosenTrack.getInfo().title + "**")
                    .addField("Sanatçı / Kanal", chosenTrack.getInfo().author, true)
                    .addField("Süre", TrackScheduler.formatDuration(chosenTrack.getDuration()), true)
                    .setColor(BOT_COLOR);

            event.editMessageEmbeds(eb.build()).setComponents().queue();

        } catch (Exception e) {
            logger.error("Panel seçim hatası: {}", e.getMessage(), e);
            if (!event.isAcknowledged()) {
                event.reply("❌ Parça başlatılırken hata oluştu: " + e.getMessage()).setEphemeral(true).queue();
            }
        }
    }

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
                    AudioChannel vc = getUserAudioChannel(member, channel);
                    if (vc == null) return;
                    audioPlayerManager.getPlaybackService().processPlayRequest(guild, vc, channel, null, args, false);
                }
                case "dur", "pause" -> {
                    if (!validateControlPermissions(member, guild, channel, null)) return;
                    GuildAudioSession s = audioPlayerManager.getOrCreateSession(guild);
                    boolean p = !s.getPlayer().isPaused();
                    s.getPlayer().setPaused(p);
                    channel.sendMessage(p ? "⏸️ Duraklatıldı." : "▶️ Devam ediyor.").queue();
                }
                case "atla", "skip", "s" -> {
                    if (!validateControlPermissions(member, guild, channel, null)) return;
                    GuildAudioSession s = audioPlayerManager.getOrCreateSession(guild);
                    s.getScheduler().nextTrack();
                    channel.sendMessage("⏭️ Sonraki parçaya geçildi.").queue();
                }
                case "durdur", "stop" -> {
                    if (!validateControlPermissions(member, guild, channel, null)) return;
                    audioPlayerManager.disconnect(guild);
                    channel.sendMessage("⏹️ Durduruldu ve ses kanalından çıkıldı.").queue();
                }
                case "ses", "volume", "v" -> {
                    if (!validateControlPermissions(member, guild, channel, null)) return;
                    try {
                        int v = Integer.parseInt(args);
                        if (v < 0 || v > 150) { channel.sendMessage("❌ Ses 0–150 arasında olmalı!").queue(); return; }
                        GuildAudioSession s = audioPlayerManager.getOrCreateSession(guild);
                        s.getPlayer().setVolume(v);
                        channel.sendMessage("🔊 Ses **" + v + "%** ayarlandı.").queue();
                    } catch (NumberFormatException e) { channel.sendMessage("❌ Kullanım: `!ses <0-150>`").queue(); }
                }
                case "döngü", "loop" -> {
                    if (!validateControlPermissions(member, guild, channel, null)) return;
                    GuildAudioSession s = audioPlayerManager.getOrCreateSession(guild);
                    boolean l = !s.getScheduler().isLoop();
                    s.getScheduler().setLoop(l);
                    channel.sendMessage(l ? "🔁 Döngü **açık**." : "➡️ Döngü **kapalı**.").queue();
                }
                case "temizle", "clear" -> {
                    if (!validateControlPermissions(member, guild, channel, null)) return;
                    GuildAudioSession s = audioPlayerManager.getOrCreateSession(guild);
                    s.getScheduler().getQueue().clear();
                    channel.sendMessage("🗑️ Kuyruk temizlendi.").queue();
                }
                case "yardim", "yardım", "help", "h" -> channel.sendMessageEmbeds(buildHelpEmbed().build()).queue();
            }
        } catch (Exception e) {
            logger.error("Prefix komut hatası [!{}]: {}", cmd, e.getMessage(), e);
        }
    }

    private void handlePlaySlash(SlashCommandInteractionEvent event) {
        Member member = event.getMember();
        Guild guild = event.getGuild();
        TextChannel textChannel = event.getChannel().asTextChannel();

        AudioChannel vc = getUserAudioChannel(member, null);
        if (vc == null) {
            event.reply("❌ Bir ses kanalında olmalısın!").setEphemeral(true).queue();
            return;
        }

        event.deferReply().queue();
        String rawQuery = event.getOption("sorgu").getAsString().trim();
        audioPlayerManager.getPlaybackService().processPlayRequest(guild, vc, textChannel, event.getHook(), rawQuery, false);
    }

    private void handlePauseSlash(SlashCommandInteractionEvent event) {
        event.deferReply().queue();
        if (!validateControlPermissions(event.getMember(), event.getGuild(), null, event.getHook())) return;
        GuildAudioSession s = audioPlayerManager.getOrCreateSession(event.getGuild());
        if (s.getPlayer().getPlayingTrack() == null) {
            event.getHook().sendMessage("❌ Şu an hiçbir şey çalmıyor!").queue();
            return;
        }
        boolean p = !s.getPlayer().isPaused();
        s.getPlayer().setPaused(p);
        event.getHook().sendMessage(p ? "⏸️ **Duraklatıldı.**" : "▶️ **Devam ediyor.**").queue();
    }

    private void handleSkipSlash(SlashCommandInteractionEvent event) {
        event.deferReply().queue();
        if (!validateControlPermissions(event.getMember(), event.getGuild(), null, event.getHook())) return;
        GuildAudioSession s = audioPlayerManager.getOrCreateSession(event.getGuild());
        if (s.getPlayer().getPlayingTrack() == null) {
            event.getHook().sendMessage("❌ Atlanacak parça yok!").queue();
            return;
        }
        s.getScheduler().nextTrack();
        event.getHook().sendMessage("⏭️ **Sonraki parçaya geçildi.**").queue();
    }

    private void handleStopSlash(SlashCommandInteractionEvent event) {
        event.deferReply().queue();
        if (!validateControlPermissions(event.getMember(), event.getGuild(), null, event.getHook())) return;
        audioPlayerManager.disconnect(event.getGuild());
        event.getHook().sendMessage("⏹️ **Durduruldu ve ses kanalından çıkıldı.**").queue();
    }

    private void handleQueueSlash(SlashCommandInteractionEvent event) {
        event.deferReply().queue();
        GuildAudioSession s = audioPlayerManager.getOrCreateSession(event.getGuild());
        Queue<AudioTrack> queue = s.getScheduler().getQueue();
        AudioTrack cur = s.getScheduler().getCurrentTrack();

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
        if (s.getScheduler().isLoop()) eb.setFooter("🔁 Döngü aktif");
        event.getHook().sendMessageEmbeds(eb.build()).queue();
    }

    private void handleLoopSlash(SlashCommandInteractionEvent event) {
        event.deferReply().queue();
        if (!validateControlPermissions(event.getMember(), event.getGuild(), null, event.getHook())) return;
        GuildAudioSession s = audioPlayerManager.getOrCreateSession(event.getGuild());
        boolean l = !s.getScheduler().isLoop();
        s.getScheduler().setLoop(l);
        event.getHook().sendMessage(l ? "🔁 **Döngü açık.**" : "➡️ **Döngü kapalı.**").queue();
    }

    private void handleVolumeSlash(SlashCommandInteractionEvent event) {
        event.deferReply().queue();
        if (!validateControlPermissions(event.getMember(), event.getGuild(), null, event.getHook())) return;
        int vol = (int) event.getOption("seviye").getAsLong();
        if (vol < 0 || vol > 150) {
            event.getHook().sendMessage("❌ Ses 0–150 arasında olmalıdır!").queue();
            return;
        }
        GuildAudioSession s = audioPlayerManager.getOrCreateSession(event.getGuild());
        s.getPlayer().setVolume(vol);
        event.getHook().sendMessage("🔊 Ses seviyesi **" + vol + "%** olarak ayarlandı.").queue();
    }

    private void handleNowPlayingSlash(SlashCommandInteractionEvent event) {
        event.deferReply().queue();
        GuildAudioSession s = audioPlayerManager.getOrCreateSession(event.getGuild());
        AudioTrack cur = s.getScheduler().getCurrentTrack();

        if (cur == null) {
            event.getHook().sendMessage("❌ Şu an hiçbir şey çalmıyor!").queue();
            return;
        }

        EmbedBuilder eb = new EmbedBuilder()
                .setTitle("🎵 Şu An Çalıyor")
                .setDescription("**" + cur.getInfo().title + "**")
                .addField("Kanal / Sanatçı", cur.getInfo().author, true)
                .addField("Süre", TrackScheduler.formatDuration(cur.getPosition()) + " / " + TrackScheduler.formatDuration(cur.getDuration()), true)
                .addField("Döngü", s.getScheduler().isLoop() ? "🔁 Açık" : "➡️ Kapalı", true)
                .addField("Ses", "🔊 " + s.getPlayer().getVolume() + "%", true)
                .setColor(BOT_COLOR)
                .setUrl(cur.getInfo().uri);

        event.getHook().sendMessageEmbeds(eb.build()).queue();
    }

    private void handleShuffleSlash(SlashCommandInteractionEvent event) {
        event.deferReply().queue();
        if (!validateControlPermissions(event.getMember(), event.getGuild(), null, event.getHook())) return;
        GuildAudioSession s = audioPlayerManager.getOrCreateSession(event.getGuild());
        List<AudioTrack> list = new ArrayList<>(s.getScheduler().getQueue());
        Collections.shuffle(list);
        s.getScheduler().getQueue().clear();
        s.getScheduler().getQueue().addAll(list);
        event.getHook().sendMessage("🔀 **Kuyruk karıştırıldı!** (`" + list.size() + "` parça)").queue();
    }

    private void handleClearSlash(SlashCommandInteractionEvent event) {
        event.deferReply().queue();
        if (!validateControlPermissions(event.getMember(), event.getGuild(), null, event.getHook())) return;
        GuildAudioSession s = audioPlayerManager.getOrCreateSession(event.getGuild());
        s.getScheduler().getQueue().clear();
        event.getHook().sendMessage("🗑️ **Kuyruk temizlendi.**").queue();
    }

    private void handleHelpSlash(SlashCommandInteractionEvent event) {
        event.replyEmbeds(buildHelpEmbed().build()).queue();
    }

    /**
     * Kontrol komutları öncesinde üyenin botla aynı ses kanalında olup olmadığını doğrular.
     */
    public boolean validateControlPermissions(Member member, Guild guild, TextChannel textChannel, InteractionHook hook) {
        AudioChannel userChannel = getUserAudioChannel(member, textChannel);
        if (userChannel == null) return false;

        AudioChannel botChannel = guild.getAudioManager().getConnectedChannel();
        if (botChannel == null) {
            sendError(hook, textChannel, "❌ Bot şu an hiçbir ses kanalında değil!");
            return false;
        }

        if (userChannel.getIdLong() != botChannel.getIdLong()) {
            sendError(hook, textChannel, "❌ Bu komutu kullanabilmek için bot ile aynı ses kanalında (`" + botChannel.getName() + "`) olmalısın!");
            return false;
        }

        return true;
    }

    private void sendError(InteractionHook hook, TextChannel textChannel, String errorMsg) {
        if (hook != null) {
            hook.sendMessage(errorMsg).queue();
        } else if (textChannel != null) {
            textChannel.sendMessage(errorMsg).queue();
        }
    }

    private AudioChannel getUserAudioChannel(Member member, TextChannel textChannel) {
        if (member == null || member.getVoiceState() == null || !member.getVoiceState().inAudioChannel()) {
            if (textChannel != null) {
                textChannel.sendMessage("❌ Bir ses kanalına bağlı olmalısın!").queue();
            }
            return null;
        }
        return member.getVoiceState().getChannel();
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
                .addField("🛡️ Filtreler & Güvenlik",
                        "Film, dizi, fragman ve teaser videoları otomatik engellenir.", false)
                .setColor(BOT_COLOR)
                .setFooter("Fluir Ses Sistemi | JDA 5.2.1 + LavaPlayer");
    }
}
