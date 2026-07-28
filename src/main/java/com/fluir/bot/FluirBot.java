package com.fluir.bot;

import com.fluir.bot.commands.CommandManager;
import com.fluir.bot.audio.AudioPlayerManager;
import io.github.cdimascio.dotenv.Dotenv;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.MemberCachePolicy;
import net.dv8tion.jda.api.utils.cache.CacheFlag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FluirBot {

    private static final Logger logger = LoggerFactory.getLogger(FluirBot.class);
    public static JDA jda;
    public static AudioPlayerManager audioPlayerManager;

    public static void main(String[] args) throws Exception {
        logger.info("🎵 Fluir Ses Sistemi başlatılıyor...");

        // Token yükle - önce env var, yoksa .env dosyası
        String token = System.getenv("DISCORD_TOKEN");
        if (token == null || token.isEmpty()) {
            try {
                Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();
                token = dotenv.get("DISCORD_TOKEN");
            } catch (Exception e) {
                logger.warn(".env dosyası bulunamadı, ortam değişkeni kullanılıyor.");
            }
        }

        if (token == null || token.isEmpty()) {
            logger.error("❌ DISCORD_TOKEN bulunamadı! Lütfen ortam değişkenini veya .env dosyasını ayarlayın.");
            System.exit(1);
        }

        // Ses yöneticisini başlat
        audioPlayerManager = new AudioPlayerManager();

        // JDA bot başlat
        jda = JDABuilder.createDefault(token)
                .setActivity(Activity.listening("🎵 /yardım"))
                .enableIntents(
                        GatewayIntent.GUILD_MESSAGES,
                        GatewayIntent.MESSAGE_CONTENT,
                        GatewayIntent.GUILD_VOICE_STATES,
                        GatewayIntent.GUILD_MEMBERS
                )
                .setMemberCachePolicy(MemberCachePolicy.VOICE)
                .enableCache(CacheFlag.VOICE_STATE)
                .addEventListeners(new CommandManager(audioPlayerManager))
                .build()
                .awaitReady();

        logger.info("✅ Fluir Ses Sistemi hazır! {} sunucuda aktif.", jda.getGuilds().size());

        // Slash komutlarını kaydet
        CommandManager.registerSlashCommands(jda);
        logger.info("📋 Slash komutları kaydedildi.");
    }
}
