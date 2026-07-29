package com.fluir.bot.audio;

import net.dv8tion.jda.api.audio.AudioSendHandler;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.track.playback.MutableAudioFrame;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * JDA ile LavaPlayer arasındaki köprü.
 * JDA'nın ses gönderme sistemine LavaPlayer'ın ses verilerini sağlar.
 */
public class AudioPlayerSendHandler implements AudioSendHandler {

    private final AudioPlayer audioPlayer;
    private final ByteBuffer buffer;
    private final MutableAudioFrame frame;
    private final Runnable firstFrameCallback;
    private final AtomicBoolean awaitingFirstFrame = new AtomicBoolean();

    public AudioPlayerSendHandler(AudioPlayer audioPlayer) {
        this(audioPlayer, () -> {});
    }

    public AudioPlayerSendHandler(AudioPlayer audioPlayer, Runnable firstFrameCallback) {
        this.audioPlayer = audioPlayer;
        this.firstFrameCallback = firstFrameCallback;
        this.buffer = ByteBuffer.allocate(1024);
        this.frame = new MutableAudioFrame();
        this.frame.setBuffer(buffer);
    }

    @Override
    public boolean canProvide() {
        boolean provided = audioPlayer.provide(frame);
        if (provided && awaitingFirstFrame.compareAndSet(true, false)) {
            firstFrameCallback.run();
        }
        return provided;
    }

    public void armFirstFrameNotification() { awaitingFirstFrame.set(true); }

    @Override
    public ByteBuffer provide20MsAudio() {
        return (ByteBuffer) buffer.flip();
    }

    @Override
    public boolean isOpus() {
        return true;
    }
}
