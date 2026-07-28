package com.fluir.bot;

import com.fluir.bot.audio.AudioPlayerManager;
import com.fluir.bot.commands.CommandManager;
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

    public static void main(String[] args) {
        logger.info("========================================");
        logger.info("   🎵 Fluir Ses Sistemi Başlatılıyor   ");
        logger.info("========================================");

        // Token yükle
        String token = loadToken();
        if (token == null) {
            logger.error("❌ DISCORD_TOKEN bulunamadı! .env veya ortam değişkeni gerekli.");
            System.exit(1);
        }

        try {
            // Ses motoru başlat
            logger.info("⚙️ Ses motoru başlatılıyor...");
            audioPlayerManager = new AudioPlayerManager();

            // JDA başlat
            logger.info("⚙️ JDA başlatılıyor...");
            CommandManager commandManager = new CommandManager(audioPlayerManager);

            jda = JDABuilder.createDefault(token)
                    .setActivity(Activity.listening("🎵 /yardim"))
                    .enableIntents(
                            GatewayIntent.GUILD_MESSAGES,
                            GatewayIntent.MESSAGE_CONTENT,
                            GatewayIntent.GUILD_VOICE_STATES,
                            GatewayIntent.GUILD_MEMBERS
                    )
                    .setMemberCachePolicy(MemberCachePolicy.VOICE)
                    .enableCache(CacheFlag.VOICE_STATE)
                    .addEventListeners(commandManager)
                    .build()
                    .awaitReady();

            logger.info("✅ JDA hazır! {} sunucuda aktif.", jda.getGuilds().size());

            // Slash komutlarını kaydet
            CommandManager.registerSlashCommands(jda);

            logger.info("========================================");
            logger.info("   ✅ Fluir Ses Sistemi HAZIR!          ");
            logger.info("========================================");

        } catch (Exception e) {
            logger.error("❌ Bot başlatılırken kritik hata: {}", e.getMessage(), e);
            System.exit(1);
        }
    }

    private static String loadToken() {
        // Önce ortam değişkeni (Railway, Docker)
        String token = System.getenv("DISCORD_TOKEN");
        if (token != null && !token.isBlank()) {
            logger.info("✅ Token ortam değişkeninden alındı.");
            return token;
        }

        // Yoksa .env dosyasından
        try {
            Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();
            token = dotenv.get("DISCORD_TOKEN");
            if (token != null && !token.isBlank()) {
                logger.info("✅ Token .env dosyasından alındı.");
                return token;
            }
        } catch (Exception e) {
            logger.warn(".env dosyası yüklenemedi: {}", e.getMessage());
        }

        return null;
    }
}
