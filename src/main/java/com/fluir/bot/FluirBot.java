package com.fluir.bot;

import club.minnced.discord.jdave.ffi.LibDave;
import club.minnced.discord.jdave.interop.JDaveSessionFactory;
import com.fluir.bot.audio.AudioPlayerManager;
import com.fluir.bot.audio.VoiceUpdateListener;
import com.fluir.bot.commands.CommandManager;
import com.fluir.bot.config.BotConfig;
import com.fluir.bot.monitoring.HealthServer;
import com.fluir.bot.monitoring.SecureWebhookNotifier;
import com.fluir.bot.persistence.PersistentStore;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.audio.AudioModuleConfig;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.MemberCachePolicy;
import net.dv8tion.jda.api.utils.cache.CacheFlag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.util.Collections;
import net.dv8tion.jda.api.utils.messages.MessageRequest;

public class FluirBot {
    private static final Logger logger=LoggerFactory.getLogger(FluirBot.class);
    public static JDA jda;
    public static AudioPlayerManager audioPlayerManager;
    private static PersistentStore store;
    private static HealthServer healthServer;

    public static void main(String[] args){
        BotConfig config=BotConfig.load();
        if(args.length>0&&"--smoke-test".equals(args[0])){smokeTest(config);return;}
        SecureWebhookNotifier notifier=new SecureWebhookNotifier(config.errorWebhookUrl());
        try{
            config.requireToken();
            short dave=LibDave.getMaxSupportedProtocolVersion();
            logger.info("DAVE native hazır. Protokol: {}",dave);
            store=new PersistentStore(config.dataDirectory());
            audioPlayerManager=new AudioPlayerManager(config,store,notifier);
            CommandManager commands=new CommandManager(audioPlayerManager);
            MessageRequest.setDefaultMentions(Collections.emptyList());
            MessageRequest.setDefaultMentionRepliedUser(false);
            jda=JDABuilder.createDefault(config.discordToken())
                    .setAudioModuleConfig(new AudioModuleConfig().withDaveSessionFactory(new JDaveSessionFactory()))
                    .setActivity(Activity.listening("🎵 /yardim"))
                    .enableIntents(GatewayIntent.GUILD_MESSAGES,GatewayIntent.MESSAGE_CONTENT,GatewayIntent.GUILD_VOICE_STATES)
                    .setMemberCachePolicy(MemberCachePolicy.VOICE).enableCache(CacheFlag.VOICE_STATE)
                    .addEventListeners(commands,new VoiceUpdateListener(audioPlayerManager)).build().awaitReady();
            audioPlayerManager.getWatchPartyService().configureApplication(jda.getSelfUser().getId());
            healthServer=new HealthServer(config.port(),store,()->jda,audioPlayerManager,config.healthMetricsToken());
            CommandManager.registerSlashCommands(jda);
            registerShutdownHook();
            logger.info("Fluir hazır: {} sunucu, health port {}",jda.getGuilds().size(),config.port());
        }catch(Throwable e){String id=notifier.report(0,"startup",e);logger.error("Bot başlatılamadı. Incident={}",id,e);shutdown();System.exit(1);}
    }

    private static void smokeTest(BotConfig config){
        try{LibDave.getMaxSupportedProtocolVersion();var temp=Files.createTempDirectory("fluir-smoke-");try(var db=new PersistentStore(temp)){if(!db.isHealthy())throw new IllegalStateException("DB unhealthy");}logger.info("Smoke test başarılı");}catch(Exception e){logger.error("Smoke test başarısız",e);System.exit(1);}
    }

    private static void registerShutdownHook(){Runtime.getRuntime().addShutdownHook(new Thread(FluirBot::shutdown,"FluirBot-ShutdownHook"));}
    static void shutdown(){try{if(healthServer!=null)healthServer.close();if(audioPlayerManager!=null)audioPlayerManager.shutdown();if(jda!=null)jda.shutdown();if(store!=null)store.close();logger.info("Kontrollü shutdown tamamlandı.");}catch(Exception e){logger.error("Shutdown hatası",e);}}
}
