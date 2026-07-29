package com.fluir.bot.commands;

import com.fluir.bot.audio.*;
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;
import net.dv8tion.jda.api.Permission;
import com.fluir.bot.persistence.GuildSettings;
import com.fluir.bot.persistence.StoredTrack;
import com.fluir.bot.security.CommandRateLimiter;
import com.fluir.bot.watch.ActivityLaunchService;
import com.fluir.bot.watch.WatchPartyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Queue;

/**
 * Slash komutlar, prefix komutlar ve dropdown seçim etkileşimlerini yöneten listener.
 * Hem metin kanallarını hem de ses kanalı yerleşik yazı sohbetlerini (GuildMessageChannel) destekler.
 * Birincil müzik kaynağı SoundCloud olarak yapılandırılmıştır.
 */
public class CommandManager extends ListenerAdapter {

    private static final Logger logger = LoggerFactory.getLogger(CommandManager.class);
    private static final String PREFIX = "!";
    private static final Color BOT_COLOR = new Color(88, 101, 242);

    private final AudioPlayerManager audioPlayerManager;
    private final ActivityLaunchService activityLauncher = new ActivityLaunchService();
    private final CommandRateLimiter rateLimiter = new CommandRateLimiter(8, 10_000);
    private final long startedAt = System.currentTimeMillis();

    public CommandManager(AudioPlayerManager audioPlayerManager) {
        this.audioPlayerManager = audioPlayerManager;
    }

    public static void registerSlashCommands(JDA jda, String botToken) {
        List<SlashCommandData> commands = new ArrayList<>();
        commands.add(Commands.slash("çal", "SoundCloud veya Spotify metadata ile müzik çalar")
                .addOption(OptionType.STRING, "sorgu", "Şarkı adı veya SoundCloud / Spotify URL'si", true));
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
        commands.add(Commands.slash("panel", "Gelişmiş müzik kontrol panelini gösterir"));
        commands.add(Commands.slash("durum", "Bot, veritabanı ve SoundCloud durumunu gösterir"));
        commands.add(Commands.slash("gecmis", "Son çalınan parçaları gösterir"));
        commands.add(Commands.slash("kuyruk-yukle", "Kaydedilmiş kuyruğu geri yükler"));
        commands.add(Commands.slash("izle", "Discord Watch Together ile YouTube izleme odası açar")
                .addOption(OptionType.STRING, "youtube", "YouTube video bağlantısı", true));
        commands.add(Commands.slash("favori", "Kişisel favorileri yönetir")
                .addSubcommands(new SubcommandData("ekle","Çalan parçayı favorilere ekler"),new SubcommandData("liste","Favorileri gösterir"),new SubcommandData("sil","Sıra numarasıyla siler").addOption(OptionType.INTEGER,"sira","Favori sıra numarası",true),new SubcommandData("cal","Favoriyi çalar").addOption(OptionType.INTEGER,"sira","Favori sıra numarası",true)));
        commands.add(Commands.slash("liste", "Sunucu çalma listelerini yönetir")
                .addSubcommands(new SubcommandData("olustur","Liste oluşturur").addOption(OptionType.STRING,"ad","Liste adı",true),new SubcommandData("ekle","Çalan parçayı listeye ekler").addOption(OptionType.STRING,"ad","Liste adı",true),new SubcommandData("goster","Listeyi gösterir").addOption(OptionType.STRING,"ad","Liste adı",false),new SubcommandData("cal","Listeyi kuyruğa yükler").addOption(OptionType.STRING,"ad","Liste adı",true)));
        commands.add(Commands.slash("ayar", "Sunucu müzik ayarlarını yönetir")
                .addSubcommands(new SubcommandData("goster","Ayarları gösterir"),new SubcommandData("ses","Varsayılan ses").addOption(OptionType.INTEGER,"deger","0-150",true),new SubcommandData("bos-kalma","Ayrılma süresi").addOption(OptionType.INTEGER,"saniye","30-900",true),new SubcommandData("max-kuyruk","Kuyruk sınırı").addOption(OptionType.INTEGER,"deger","10-500",true),new SubcommandData("otomatik-cal","Otomatik çalma").addOption(OptionType.BOOLEAN,"aktif","Aç/kapat",true),new SubcommandData("duyurular","Çalıyor duyuruları").addOption(OptionType.BOOLEAN,"aktif","Aç/kapat",true),new SubcommandData("prefix","Prefix komutları").addOption(OptionType.BOOLEAN,"aktif","Aç/kapat",true),new SubcommandData("dj-rol","DJ rolü").addOption(OptionType.ROLE,"rol","Kontrol yetkili rol",true),new SubcommandData("dj-rol-sil","DJ rolü sınırlamasını kaldırır"),new SubcommandData("komut-kanal","Komut kanalı").addOption(OptionType.CHANNEL,"kanal","İzin verilen kanal",true),new SubcommandData("komut-kanal-sil","Komut kanalı sınırlamasını kaldırır")));

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
        if (!rateLimiter.allow(event.getGuild().getIdLong(), event.getUser().getIdLong())) { event.reply("⏳ Çok hızlı komut gönderiyorsun; birkaç saniye bekle.").setEphemeral(true).queue(); return; }
        GuildSettings policy=audioPlayerManager.getStore().settings(event.getGuild().getIdLong());
        if(policy.commandChannelId()!=0&&event.getChannel().getIdLong()!=policy.commandChannelId()&&!isAdmin(event.getMember())){event.reply("❌ Müzik komutları bu sunucuda ayarlanan komut kanalında kullanılabilir.").setEphemeral(true).queue();return;}

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
                case "panel"    -> handlePanelSlash(event);
                case "durum"    -> handleStatusSlash(event);
                case "gecmis"   -> handleHistorySlash(event);
                case "kuyruk-yukle" -> handleRestoreQueue(event);
                case "izle"     -> handleWatchParty(event);
                case "favori"   -> handleFavorite(event);
                case "liste"    -> handlePlaylist(event);
                case "ayar"     -> handleSettings(event);
                default         -> event.reply("❌ Bilinmeyen komut!").setEphemeral(true).queue();
            }
        } catch (Exception e) {
            logger.error("Slash komut hatası [{}], type={}", event.getName(), e.getClass().getSimpleName());
            if (!event.isAcknowledged()) {
                event.reply("❌ İşlem tamamlanamadı. Hata yöneticilere kaydedildi.").setEphemeral(true).queue();
            } else {
                event.getHook().sendMessage("❌ İşlem tamamlanamadı. Hata yöneticilere kaydedildi.").queue();
            }
            audioPlayerManager.getNotifier().report(event.getGuild().getIdLong(),"slash-"+event.getName(),e);
        }
    }

    @Override public void onButtonInteraction(ButtonInteractionEvent event){
        if(!event.getComponentId().startsWith("music:")||event.getGuild()==null)return;
        GuildMessageChannel channel=event.getChannel() instanceof GuildMessageChannel g?g:null;
        if(!validateControlPermissions(event.getMember(),event.getGuild(),channel,null)){event.reply("❌ Bu kontrol için botla aynı ses kanalında olmalısın.").setEphemeral(true).queue();return;}
        GuildAudioSession s=audioPlayerManager.getOrCreateSession(event.getGuild());AudioTrack track=s.getScheduler().getCurrentTrack();
        switch(event.getComponentId()){
            case "music:pause"->{s.getPlayer().setPaused(!s.getPlayer().isPaused());s.refreshPanelNow();event.reply(s.getPlayer().isPaused()?"⏸️ Duraklatıldı.":"▶️ Devam ediyor.").setEphemeral(true).queue();}
            case "music:skip"->{s.getScheduler().nextTrack();event.reply("⏭️ Atlandı.").setEphemeral(true).queue();}
            case "music:stop"->{audioPlayerManager.disconnect(event.getGuild());event.reply("⏹️ Durduruldu.").setEphemeral(true).queue();}
            case "music:loop"->{s.getScheduler().setLoop(!s.getScheduler().isLoop());s.refreshPanelNow();event.reply("🔁 Döngü "+(s.getScheduler().isLoop()?"açık.":"kapalı.")).setEphemeral(true).queue();}
            case "music:favorite"->{if(track==null){event.reply("❌ Çalan parça yok.").setEphemeral(true).queue();return;}boolean added=audioPlayerManager.getStore().addFavorite(event.getGuild().getIdLong(),event.getUser().getIdLong(),StoredTrack.from(track));event.reply(added?"❤ Favorilere eklendi.":"ℹ️ Zaten favorilerinde.").setEphemeral(true).queue();}
            case "music:volume_down"->{int volume=Math.max(0,s.getPlayer().getVolume()-10);s.getPlayer().setVolume(volume);s.refreshPanelNow();event.reply("🔉 Ses **%"+volume+"**.").setEphemeral(true).queue();}
            case "music:volume_up"->{int volume=Math.min(150,s.getPlayer().getVolume()+10);s.getPlayer().setVolume(volume);s.refreshPanelNow();event.reply("🔊 Ses **%"+volume+"**.").setEphemeral(true).queue();}
            case "music:rewind"->{if(track==null||!track.isSeekable()){event.reply("❌ Bu parçada geri sarma desteklenmiyor.").setEphemeral(true).queue();return;}track.setPosition(Math.max(0,track.getPosition()-10_000));s.refreshPanelNow();event.reply("⏪ 10 saniye geri sarıldı.").setEphemeral(true).queue();}
            case "music:forward"->{if(track==null||!track.isSeekable()){event.reply("❌ Bu parçada ileri sarma desteklenmiyor.").setEphemeral(true).queue();return;}track.setPosition(Math.min(track.getDuration(),track.getPosition()+10_000));s.refreshPanelNow();event.reply("⏩ 10 saniye ileri sarıldı.").setEphemeral(true).queue();}
            case "music:shuffle"->{int count=s.getScheduler().shuffleQueue();s.refreshPanelNow();event.reply("🔀 Kuyruktaki **"+count+"** parça karıştırıldı.").setEphemeral(true).queue();}
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

            GuildMessageChannel messageChannel = event.getChannel() instanceof GuildMessageChannel gmc ? gmc : null;
            AudioChannel userChannel = getUserAudioChannel(member, messageChannel);
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
            List<SearchPanelCache.SoundCloudTrackMetadata> metaList = cacheResult.metadataList();
            if (index < 0 || index >= metaList.size()) {
                event.reply("❌ Geçersiz seçim.").setEphemeral(true).queue();
                return;
            }

            SearchPanelCache.SoundCloudTrackMetadata chosenMeta = metaList.get(index);
            GuildAudioSession session = audioPlayerManager.getOrCreateSession(guild);

            GuildAudioSession.ConnectionResult connResult = session.ensureConnected(guild, userChannel, messageChannel);
            if (!connResult.success()) {
                event.reply(connResult.message()).setEphemeral(true).queue();
                return;
            }

            event.deferEdit().queue();

            // Panelden seçilen kalıcı SoundCloud URI'sini oynatma anında re-resolve ederek taze AudioTrack nesnesi yüklüyoruz
            audioPlayerManager.getPlayerManager().loadItemOrdered(session, chosenMeta.uri(), new AudioLoadResultHandler() {
                @Override
                public void trackLoaded(AudioTrack freshTrack) {
                    TrackContext context = TrackContext.create(chosenMeta.title(), freshTrack.getInfo().title, freshTrack.getInfo().author, freshTrack.getInfo().uri, PlaybackSource.SOUNDCLOUD, userId, messageChannel != null ? messageChannel.getIdLong() : 0L);
                    freshTrack.setUserData(context);

                    session.getScheduler().queue(freshTrack);

                    EmbedBuilder eb = new EmbedBuilder()
                            .setTitle("🎵 Parça Seçildi & Kuyruğa Eklendi")
                            .setDescription("**" + freshTrack.getInfo().title + "**")
                            .addField("Sanatçı", freshTrack.getInfo().author, true)
                            .addField("Süre", TrackScheduler.formatDuration(freshTrack.getDuration()), true)
                            .setColor(BOT_COLOR);

                    event.getHook().editOriginalEmbeds(eb.build()).setComponents().queue();
                }

                @Override
                public void playlistLoaded(AudioPlaylist playlist) {
                    if (!playlist.getTracks().isEmpty()) {
                        trackLoaded(playlist.getTracks().get(0));
                    } else {
                        noMatches();
                    }
                }

                @Override
                public void noMatches() {
                    event.getHook().editOriginal("❌ Seçilen SoundCloud parçası yüklenemedi.").setComponents().setEmbeds().queue();
                }

                @Override
                public void loadFailed(FriendlyException exception) {
                    event.getHook().editOriginal(MusicPlaybackService.getFriendlyErrorMessage(exception)).setComponents().setEmbeds().queue();
                }
            });

        } catch (Exception e) {
            logger.error("Panel seçim hatası, type={}", e.getClass().getSimpleName());
            if (event.getGuild() != null) audioPlayerManager.getNotifier().report(event.getGuild().getIdLong(),"search-panel",e);
            if (!event.isAcknowledged()) {
                event.reply("❌ Parça başlatılamadı. Hata güvenli şekilde kaydedildi.").setEphemeral(true).queue();
            }
        }
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        if (event.getAuthor().isBot() || !event.isFromGuild()) return;
        String msg = event.getMessage().getContentRaw();
        if (!msg.startsWith(PREFIX)) return;

        if (!(event.getChannel() instanceof GuildMessageChannel messageChannel)) {
            return;
        }

        String[] parts = msg.substring(PREFIX.length()).trim().split("\\s+", 2);
        String cmd  = parts[0].toLowerCase();
        String args = parts.length > 1 ? parts[1] : "";

        Guild  guild  = event.getGuild();
        Member member = event.getMember();
        GuildSettings policy=audioPlayerManager.getStore().settings(guild.getIdLong());
        if(!policy.prefixCommands()||(policy.commandChannelId()!=0&&messageChannel.getIdLong()!=policy.commandChannelId()&&!isAdmin(member))||!rateLimiter.allow(guild.getIdLong(),event.getAuthor().getIdLong()))return;

        try {
            switch (cmd) {
                case "çal", "cal", "play", "p" -> {
                    if (args.isEmpty()) { messageChannel.sendMessage("❌ Kullanım: `!çal <şarkı adı veya SoundCloud URL>`").queue(); return; }
                    AudioChannel vc = getUserAudioChannel(member, messageChannel);
                    if (vc == null) return;
                    audioPlayerManager.getPlaybackService().processPlayRequest(guild, vc, messageChannel, null, args, false);
                }
                case "dur", "pause" -> {
                    if (!validateControlPermissions(member, guild, messageChannel, null)) return;
                    GuildAudioSession s = audioPlayerManager.getOrCreateSession(guild);
                    boolean p = !s.getPlayer().isPaused();
                    s.getPlayer().setPaused(p);
                    messageChannel.sendMessage(p ? "⏸️ Duraklatıldı." : "▶️ Devam ediyor.").queue();
                }
                case "atla", "skip", "s" -> {
                    if (!validateControlPermissions(member, guild, messageChannel, null)) return;
                    GuildAudioSession s = audioPlayerManager.getOrCreateSession(guild);
                    s.getScheduler().nextTrack();
                    messageChannel.sendMessage("⏭️ Sonraki parçaya geçildi.").queue();
                }
                case "durdur", "stop" -> {
                    if (!validateControlPermissions(member, guild, messageChannel, null)) return;
                    audioPlayerManager.disconnect(guild);
                    messageChannel.sendMessage("⏹️ Durduruldu ve ses kanalından çıkıldı.").queue();
                }
                case "ses", "volume", "v" -> {
                    if (!validateControlPermissions(member, guild, messageChannel, null)) return;
                    try {
                        int v = Integer.parseInt(args);
                        if (v < 0 || v > 150) { messageChannel.sendMessage("❌ Ses 0–150 arasında olmalı!").queue(); return; }
                        GuildAudioSession s = audioPlayerManager.getOrCreateSession(guild);
                        s.getPlayer().setVolume(v);
                        messageChannel.sendMessage("🔊 Ses **" + v + "%** ayarlandı.").queue();
                    } catch (NumberFormatException e) { messageChannel.sendMessage("❌ Kullanım: `!ses <0-150>`").queue(); }
                }
                case "döngü", "loop" -> {
                    if (!validateControlPermissions(member, guild, messageChannel, null)) return;
                    GuildAudioSession s = audioPlayerManager.getOrCreateSession(guild);
                    boolean l = !s.getScheduler().isLoop();
                    s.getScheduler().setLoop(l);
                    messageChannel.sendMessage(l ? "🔁 Döngü **açık**." : "➡️ Döngü **kapalı**.").queue();
                }
                case "temizle", "clear" -> {
                    if (!validateControlPermissions(member, guild, messageChannel, null)) return;
                    GuildAudioSession s = audioPlayerManager.getOrCreateSession(guild);
                    s.getScheduler().clearQueue();
                    messageChannel.sendMessage("🗑️ Kuyruk temizlendi.").queue();
                }
                case "yardim", "yardım", "help", "h" -> messageChannel.sendMessageEmbeds(buildHelpEmbed().build()).queue();
            }
        } catch (Exception e) {
            logger.error("Prefix komut hatası [!{}], type={}", cmd, e.getClass().getSimpleName());
            audioPlayerManager.getNotifier().report(guild.getIdLong(),"prefix-"+cmd,e);
        }
    }

    private void handlePlaySlash(SlashCommandInteractionEvent event) {
        Member member = event.getMember();
        Guild guild = event.getGuild();

        if (!(event.getChannel() instanceof GuildMessageChannel messageChannel)) {
            event.reply("❌ Bu kanal üzerinden komut yanıtı gönderilemiyor.").setEphemeral(true).queue();
            return;
        }

        AudioChannel vc = getUserAudioChannel(member, null);
        if (vc == null) {
            event.reply("❌ Bir ses kanalında olmalısın!").setEphemeral(true).queue();
            return;
        }

        event.deferReply().queue();
        String rawQuery = event.getOption("sorgu").getAsString().trim();
        audioPlayerManager.getPlaybackService().processPlayRequest(guild, vc, messageChannel, event.getHook(), rawQuery, false);
    }

    private void handlePauseSlash(SlashCommandInteractionEvent event) {
        event.deferReply().queue();
        GuildMessageChannel messageChannel = event.getChannel() instanceof GuildMessageChannel gmc ? gmc : null;
        if (!validateControlPermissions(event.getMember(), event.getGuild(), messageChannel, event.getHook())) return;
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
        GuildMessageChannel messageChannel = event.getChannel() instanceof GuildMessageChannel gmc ? gmc : null;
        if (!validateControlPermissions(event.getMember(), event.getGuild(), messageChannel, event.getHook())) return;
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
        GuildMessageChannel messageChannel = event.getChannel() instanceof GuildMessageChannel gmc ? gmc : null;
        if (!validateControlPermissions(event.getMember(), event.getGuild(), messageChannel, event.getHook())) return;
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
        GuildMessageChannel messageChannel = event.getChannel() instanceof GuildMessageChannel gmc ? gmc : null;
        if (!validateControlPermissions(event.getMember(), event.getGuild(), messageChannel, event.getHook())) return;
        GuildAudioSession s = audioPlayerManager.getOrCreateSession(event.getGuild());
        boolean l = !s.getScheduler().isLoop();
        s.getScheduler().setLoop(l);
        event.getHook().sendMessage(l ? "🔁 **Döngü açık.**" : "➡️ **Döngü kapalı.**").queue();
    }

    private void handleVolumeSlash(SlashCommandInteractionEvent event) {
        event.deferReply().queue();
        GuildMessageChannel messageChannel = event.getChannel() instanceof GuildMessageChannel gmc ? gmc : null;
        if (!validateControlPermissions(event.getMember(), event.getGuild(), messageChannel, event.getHook())) return;
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
        GuildMessageChannel messageChannel = event.getChannel() instanceof GuildMessageChannel gmc ? gmc : null;
        if (!validateControlPermissions(event.getMember(), event.getGuild(), messageChannel, event.getHook())) return;
        GuildAudioSession s = audioPlayerManager.getOrCreateSession(event.getGuild());
        int count=s.getScheduler().shuffleQueue();
        event.getHook().sendMessage("🔀 **Kuyruk karıştırıldı!** (`" + count + "` parça)").queue();
    }

    private void handleClearSlash(SlashCommandInteractionEvent event) {
        event.deferReply().queue();
        GuildMessageChannel messageChannel = event.getChannel() instanceof GuildMessageChannel gmc ? gmc : null;
        if (!validateControlPermissions(event.getMember(), event.getGuild(), messageChannel, event.getHook())) return;
        GuildAudioSession s = audioPlayerManager.getOrCreateSession(event.getGuild());
        s.getScheduler().clearQueue();
        event.getHook().sendMessage("🗑️ **Kuyruk temizlendi.**").queue();
    }

    private void handleHelpSlash(SlashCommandInteractionEvent event) {
        event.replyEmbeds(buildHelpEmbed().build()).queue();
    }

    private void handlePanelSlash(SlashCommandInteractionEvent event){
        GuildAudioSession s=audioPlayerManager.getOrCreateSession(event.getGuild());AudioTrack track=s.getScheduler().getCurrentTrack();
        if(track==null){event.reply("❌ Şu an çalan parça yok.").setEphemeral(true).queue();return;}
        event.replyEmbeds(NowPlayingPanel.embed(track,s).build()).addComponents(NowPlayingPanel.components(s)).queue();
    }

    private void handleWatchParty(SlashCommandInteractionEvent event) {
        Member member = event.getMember();
        AudioChannel voiceChannel = getUserAudioChannel(member, null);
        if (voiceChannel == null) {
            event.reply("❌ Önce ortak izleme yapılacak ses kanalına katıl.").setEphemeral(true).queue();
            return;
        }
        if (event.getChannelIdLong() != voiceChannel.getIdLong()) {
            event.reply("❌ `/izle` komutunu bulunduğun ses kanalının yazılı sohbetinde çalıştır.")
                    .setEphemeral(true).queue();
            return;
        }
        if (!member.hasPermission(voiceChannel, Permission.USE_EMBEDDED_ACTIVITIES)) {
            event.reply("❌ Bu kanalda **Etkinlikleri Kullan** iznin yok.").setEphemeral(true).queue();
            return;
        }
        String youtubeUrl = event.getOption("youtube").getAsString();
        String videoId = WatchPartyService.extractYouTubeId(youtubeUrl);
        if (videoId == null) {
            event.reply("❌ Geçerli bir YouTube video bağlantısı gir.").setEphemeral(true).queue();
            return;
        }
        if (!event.getGuild().getSelfMember().hasPermission(voiceChannel, Permission.CREATE_INSTANT_INVITE)) {
            event.reply("❌ Botun bu ses kanalında **Davet Oluştur** izni yok.").setEphemeral(true).queue();
            return;
        }
        event.deferReply().queue(ignored -> activityLauncher.createOfficialWatchTogetherInvite(
                        voiceChannel.getIdLong(), audioPlayerManager.getConfig().discordToken())
                .thenAccept(invite -> {
                    if (invite.isEmpty()) {
                        event.getHook().editOriginal("❌ Discord Watch Together daveti oluşturulamadı. Bot izinlerini kontrol et.").queue();
                        return;
                    }
                    String selectedVideo = "https://www.youtube.com/watch?v=" + videoId;
                    event.getHook().editOriginal("🎬 **Discord Watch Together hazır!**\n"
                            + invite.get() + "\n\n📺 Etkinlik açılınca bu videoyu ekle:\n<" + selectedVideo + ">").queue();
                    logger.info("Resmi Watch Together daveti oluşturuldu [Guild: {}, Channel: {}, Video: {}]",
                            event.getGuild().getIdLong(), voiceChannel.getIdLong(), videoId);
                }), error -> logger.warn("İzle komutu ertelenemedi: {}", error.getMessage()));
    }

    private void handleStatusSlash(SlashCommandInteractionEvent event){
        GuildAudioSession s=audioPlayerManager.getOrCreateSession(event.getGuild());long used=Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory();
        EmbedBuilder e=new EmbedBuilder().setTitle("🩺 Fluir Durumu").setColor(audioPlayerManager.getStore().isHealthy()?Color.GREEN:Color.RED)
                .addField("Discord gecikmesi",event.getJDA().getGatewayPing()+" ms",true).addField("Aktif oturum",String.valueOf(audioPlayerManager.getSessionCount()),true)
                .addField("Kuyruk",String.valueOf(s.getScheduler().getQueue().size()),true).addField("SoundCloud",SoundCloudCircuitBreaker.status(event.getGuild().getIdLong()),true)
                .addField("Veritabanı",audioPlayerManager.getStore().isHealthy()?"✅ Sağlıklı":"❌ Sorunlu",true).addField("Bellek",(used/1024/1024)+" MiB",true)
                .addField("Çalışma süresi",TrackScheduler.formatDuration(System.currentTimeMillis()-startedAt),true).addField("Son incident",audioPlayerManager.getNotifier().lastIncidentId(),true);
        event.replyEmbeds(e.build()).setEphemeral(true).queue();
    }

    private void handleHistorySlash(SlashCommandInteractionEvent event){
        List<StoredTrack> tracks=audioPlayerManager.getStore().history(event.getGuild().getIdLong(),10);event.reply(renderTracks("🕘 Son Çalınanlar",tracks)).setEphemeral(true).queue();
    }

    private void handleRestoreQueue(SlashCommandInteractionEvent event){
        Member member=event.getMember();AudioChannel vc=getUserAudioChannel(member,null);if(vc==null){event.reply("❌ Bir ses kanalında olmalısın.").setEphemeral(true).queue();return;}
        List<StoredTrack> tracks=audioPlayerManager.getStore().queue(event.getGuild().getIdLong(),audioPlayerManager.getStore().settings(event.getGuild().getIdLong()).maxQueueSize());
        if(tracks.isEmpty()){event.reply("ℹ️ Kaydedilmiş kuyruk yok.").setEphemeral(true).queue();return;}event.deferReply(true).queue();
        GuildMessageChannel channel=event.getChannel() instanceof GuildMessageChannel g?g:null;for(StoredTrack t:tracks)audioPlayerManager.getPlaybackService().processPlayRequest(event.getGuild(),vc,channel,null,t.uri(),false);
        event.getHook().sendMessage("✅ `"+tracks.size()+"` kayıt geri yüklenmek üzere işlendi.").queue();
    }

    private void handleFavorite(SlashCommandInteractionEvent event){
        String sub=event.getSubcommandName();long guild=event.getGuild().getIdLong(),user=event.getUser().getIdLong();GuildAudioSession s=audioPlayerManager.getOrCreateSession(event.getGuild());
        switch(sub==null?"":sub){
            case "ekle"->{AudioTrack t=s.getScheduler().getCurrentTrack();if(t==null){event.reply("❌ Çalan parça yok.").setEphemeral(true).queue();return;}event.reply(audioPlayerManager.getStore().addFavorite(guild,user,StoredTrack.from(t))?"❤ Favorilere eklendi.":"ℹ️ Zaten favorilerinde.").setEphemeral(true).queue();}
            case "liste"->event.reply(renderTracks("❤ Favoriler",audioPlayerManager.getStore().favorites(guild,user,20))).setEphemeral(true).queue();
            case "sil"->{int i=(int)event.getOption("sira").getAsLong();event.reply(audioPlayerManager.getStore().removeFavorite(guild,user,i)?"✅ Favori silindi.":"❌ Geçersiz sıra.").setEphemeral(true).queue();}
            case "cal"->{int i=(int)event.getOption("sira").getAsLong();List<StoredTrack> f=audioPlayerManager.getStore().favorites(guild,user,100);if(i<1||i>f.size()){event.reply("❌ Geçersiz sıra.").setEphemeral(true).queue();return;}AudioChannel vc=getUserAudioChannel(event.getMember(),null);if(vc==null){event.reply("❌ Bir ses kanalında olmalısın.").setEphemeral(true).queue();return;}event.deferReply().queue();audioPlayerManager.getPlaybackService().processPlayRequest(event.getGuild(),vc,event.getChannel() instanceof GuildMessageChannel g?g:null,event.getHook(),f.get(i-1).uri(),false);}
        }
    }

    private void handlePlaylist(SlashCommandInteractionEvent event){
        String sub=event.getSubcommandName();String name=event.getOption("ad")==null?"":event.getOption("ad").getAsString();long guild=event.getGuild().getIdLong();
        switch(sub==null?"":sub){
            case "olustur"->event.reply(audioPlayerManager.getStore().createPlaylist(guild,event.getUser().getIdLong(),name)?"✅ Liste oluşturuldu.":"ℹ️ Bu ad zaten kullanılıyor.").setEphemeral(true).queue();
            case "ekle"->{AudioTrack t=audioPlayerManager.getOrCreateSession(event.getGuild()).getScheduler().getCurrentTrack();if(t==null){event.reply("❌ Çalan parça yok.").setEphemeral(true).queue();return;}event.reply(audioPlayerManager.getStore().addPlaylistTrack(guild,name,StoredTrack.from(t))?"✅ Listeye eklendi.":"❌ Liste bulunamadı.").setEphemeral(true).queue();}
            case "goster"->{if(name.isBlank()){List<String> names=audioPlayerManager.getStore().playlists(guild,50);event.reply(names.isEmpty()?"ℹ️ Liste yok.":"📚 **Listeler:** "+String.join(", ",names)).setEphemeral(true).queue();}else event.reply(renderTracks("📚 "+name,audioPlayerManager.getStore().playlist(guild,name,50))).setEphemeral(true).queue();}
            case "cal"->{List<StoredTrack> tracks=audioPlayerManager.getStore().playlist(guild,name,100);AudioChannel vc=getUserAudioChannel(event.getMember(),null);if(tracks.isEmpty()||vc==null){event.reply("❌ Liste boş/bulunamadı veya ses kanalında değilsin.").setEphemeral(true).queue();return;}event.deferReply(true).queue();GuildMessageChannel c=event.getChannel() instanceof GuildMessageChannel g?g:null;for(StoredTrack t:tracks)audioPlayerManager.getPlaybackService().processPlayRequest(event.getGuild(),vc,c,null,t.uri(),false);event.getHook().sendMessage("✅ Liste kuyruğa yükleniyor.").queue();}
        }
    }

    private void handleSettings(SlashCommandInteractionEvent event){
        GuildSettings old=audioPlayerManager.getStore().settings(event.getGuild().getIdLong());String sub=event.getSubcommandName();
        if("goster".equals(sub)){event.reply("⚙️ Ses: `"+old.defaultVolume()+"` • Boş kalma: `"+old.idleSeconds()+"s` • Maks. kuyruk: `"+old.maxQueueSize()+"` • Otomatik: `"+old.autoplay()+"` • Duyuru: `"+old.announcements()+"` • Prefix: `"+old.prefixCommands()+"` • DJ rol: `"+old.djRoleId()+"` • Kanal: `"+old.commandChannelId()+"`").setEphemeral(true).queue();return;}
        if(!isAdmin(event.getMember())){event.reply("❌ Ayarları değiştirmek için Sunucuyu Yönet izni gerekli.").setEphemeral(true).queue();return;}
        GuildSettings n=switch(sub==null?"":sub){
            case "ses"->new GuildSettings(old.guildId(),(int)event.getOption("deger").getAsLong(),old.idleSeconds(),old.maxQueueSize(),old.autoplay(),old.announcements(),old.prefixCommands(),old.djRoleId(),old.commandChannelId());
            case "bos-kalma"->new GuildSettings(old.guildId(),old.defaultVolume(),(int)event.getOption("saniye").getAsLong(),old.maxQueueSize(),old.autoplay(),old.announcements(),old.prefixCommands(),old.djRoleId(),old.commandChannelId());
            case "max-kuyruk"->new GuildSettings(old.guildId(),old.defaultVolume(),old.idleSeconds(),(int)event.getOption("deger").getAsLong(),old.autoplay(),old.announcements(),old.prefixCommands(),old.djRoleId(),old.commandChannelId());
            case "otomatik-cal"->new GuildSettings(old.guildId(),old.defaultVolume(),old.idleSeconds(),old.maxQueueSize(),event.getOption("aktif").getAsBoolean(),old.announcements(),old.prefixCommands(),old.djRoleId(),old.commandChannelId());
            case "duyurular"->new GuildSettings(old.guildId(),old.defaultVolume(),old.idleSeconds(),old.maxQueueSize(),old.autoplay(),event.getOption("aktif").getAsBoolean(),old.prefixCommands(),old.djRoleId(),old.commandChannelId());
            case "prefix"->new GuildSettings(old.guildId(),old.defaultVolume(),old.idleSeconds(),old.maxQueueSize(),old.autoplay(),old.announcements(),event.getOption("aktif").getAsBoolean(),old.djRoleId(),old.commandChannelId());
            case "dj-rol"->new GuildSettings(old.guildId(),old.defaultVolume(),old.idleSeconds(),old.maxQueueSize(),old.autoplay(),old.announcements(),old.prefixCommands(),event.getOption("rol").getAsRole().getIdLong(),old.commandChannelId());
            case "dj-rol-sil"->new GuildSettings(old.guildId(),old.defaultVolume(),old.idleSeconds(),old.maxQueueSize(),old.autoplay(),old.announcements(),old.prefixCommands(),0,old.commandChannelId());
            case "komut-kanal"->new GuildSettings(old.guildId(),old.defaultVolume(),old.idleSeconds(),old.maxQueueSize(),old.autoplay(),old.announcements(),old.prefixCommands(),old.djRoleId(),event.getOption("kanal").getAsChannel().getIdLong());
            case "komut-kanal-sil"->new GuildSettings(old.guildId(),old.defaultVolume(),old.idleSeconds(),old.maxQueueSize(),old.autoplay(),old.announcements(),old.prefixCommands(),old.djRoleId(),0);
            default->old;};audioPlayerManager.getStore().saveSettings(n);audioPlayerManager.getOrCreateSession(event.getGuild()).getPlayer().setVolume(n.normalized().defaultVolume());event.reply("✅ Sunucu ayarı kaydedildi.").setEphemeral(true).queue();
    }

    private static String renderTracks(String title,List<StoredTrack> tracks){if(tracks.isEmpty())return "ℹ️ "+title+" boş.";StringBuilder b=new StringBuilder("**").append(title).append("**\n");int i=1;for(StoredTrack t:tracks)b.append('`').append(i++).append(".` ").append(t.title()).append(" — ").append(t.author()).append('\n');return b.length()>1900?b.substring(0,1900):b.toString();}
    private static boolean isAdmin(Member member){return member!=null&&member.hasPermission(Permission.MANAGE_SERVER);}

    public boolean validateControlPermissions(Member member, Guild guild, GuildMessageChannel messageChannel, InteractionHook hook) {
        GuildSettings settings=audioPlayerManager.getStore().settings(guild.getIdLong());
        if(settings.djRoleId()!=0&&!isAdmin(member)&&(member==null||member.getRoles().stream().noneMatch(r->r.getIdLong()==settings.djRoleId()))){sendError(hook,messageChannel,"❌ Bu kontrol için ayarlanmış DJ rolü gerekli.");return false;}
        AudioChannel userChannel = getUserAudioChannel(member, messageChannel);
        if (userChannel == null) return false;

        AudioChannel botChannel = guild.getAudioManager().getConnectedChannel();
        if (botChannel == null) {
            sendError(hook, messageChannel, "❌ Bot şu an hiçbir ses kanalında değil!");
            return false;
        }

        if (userChannel.getIdLong() != botChannel.getIdLong()) {
            sendError(hook, messageChannel, "❌ Bu komutu kullanabilmek için bot ile aynı ses kanalında (`" + botChannel.getName() + "`) olmalısın!");
            return false;
        }

        return true;
    }

    private void sendError(InteractionHook hook, GuildMessageChannel messageChannel, String errorMsg) {
        if (hook != null) {
            hook.sendMessage(errorMsg).queue();
        } else if (messageChannel != null) {
            messageChannel.sendMessage(errorMsg).queue();
        }
    }

    private AudioChannel getUserAudioChannel(Member member, GuildMessageChannel messageChannel) {
        if (member == null || member.getVoiceState() == null || !member.getVoiceState().inAudioChannel()) {
            if (messageChannel != null) {
                messageChannel.sendMessage("❌ Bir ses kanalına bağlı olmalısın!").queue();
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
                        "`/çal <sorgu>` • SoundCloud arama paneli açar (SoundCloud & Spotify metadata)\n`/dur` • Duraklat/Devam\n`/atla` • Sonraki parça\n`/durdur` • Çıkış yap", false)
                .addField("📋 Panel & Kuyruk",
                        "`/kuyruk` • Sıradakileri gör\n`/karistir` • Karıştır\n`/temizle` • Temizle", false)
                .addField("⚙️ Ayarlar",
                        "`/ses <0-150>` • Ses ayarla\n`/döngü` • Döngü\n`/simdi` • Şu an çalan", false)
                .addField("⚠️ Kaynak Notu",
                        "YouTube bağlantıları desteklenmez. Spotify bağlantıları metadata olarak çözümlenip SoundCloud'da aratılır.", false)
                .setColor(BOT_COLOR)
                .setFooter("Fluir Ses Sistemi | JDA 6.5 + DAVE + LavaPlayer 2.2.7");
    }
}
