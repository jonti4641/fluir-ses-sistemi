package com.fluir.bot.audio;

import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;

import java.awt.Color;

public final class NowPlayingPanel {
    private NowPlayingPanel() {}

    public static EmbedBuilder embed(AudioTrack track, GuildAudioSession session) {
        return new EmbedBuilder().setTitle("🎵 Şu An Çalıyor")
                .setDescription("**" + safe(track.getInfo().title, 200) + "**")
                .addField("Sanatçı", safe(track.getInfo().author, 100), true)
                .addField("Süre", TrackScheduler.formatDuration(track.getDuration()), true)
                .addField("Ses", session.getPlayer().getVolume() + "%", true)
                .setColor(new Color(88,101,242))
                .setUrl(MusicPlaybackService.isSafePublicUri(track.getInfo().uri) ? track.getInfo().uri : null);
    }

    public static ActionRow controls(GuildAudioSession session) {
        return ActionRow.of(
                session.getPlayer().isPaused() ? Button.success("music:pause", "▶ Devam") : Button.secondary("music:pause", "⏸ Dur"),
                Button.primary("music:skip", "⏭ Atla"),
                Button.danger("music:stop", "⏹ Durdur"),
                session.getScheduler().isLoop() ? Button.success("music:loop", "🔁 Döngü") : Button.secondary("music:loop", "🔁 Döngü"),
                Button.secondary("music:favorite", "❤ Favori")
        );
    }

    private static String safe(String value,int max){if(value==null)return "Bilinmiyor";String v=value.replaceAll("[\\r\\n\\p{Cntrl}]"," ");return v.length()>max?v.substring(0,max):v;}
}
