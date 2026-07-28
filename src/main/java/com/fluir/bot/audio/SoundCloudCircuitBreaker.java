package com.fluir.bot.audio;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * SoundCloud oynatma hatalarını izleyen ve üst üste gelen hatalarda
 * SoundCloud kullanımını geçici olarak devreden çıkaran Devre Kesici (Circuit Breaker).
 */
public class SoundCloudCircuitBreaker {

    private static final Logger logger = LoggerFactory.getLogger(SoundCloudCircuitBreaker.class);

    private static final int FAILURE_THRESHOLD = 3;
    private static final long TIMEFRAME_MS = 120_000; // 2 dakika (120 saniye)
    private static final long OPEN_DURATION_MS = 120_000; // 2 dakika kapalı tutma süresi

    private static final Queue<Long> failureTimestamps = new ConcurrentLinkedQueue<>();
    private static final AtomicLong circuitOpenUntil = new AtomicLong(0);

    /**
     * SoundCloud hatası oluştuğunda çağrılır.
     */
    public static void recordFailure() {
        long now = System.currentTimeMillis();
        failureTimestamps.add(now);

        // 2 dakikadan eski kayıtlardan temizle
        failureTimestamps.removeIf(timestamp -> (now - timestamp) > TIMEFRAME_MS);

        if (failureTimestamps.size() >= FAILURE_THRESHOLD) {
            long openUntil = now + OPEN_DURATION_MS;
            circuitOpenUntil.set(openUntil);
            failureTimestamps.clear();
            logger.warn("⚠️ SoundCloud playback circuit opened for 120 seconds after 3 failures");
        }
    }

    /**
     * Devrenin açık (SoundCloud'un geçici olarak devre dışı) olup olmadığını kontrol eder.
     */
    public static boolean isOpen() {
        long now = System.currentTimeMillis();
        long openUntil = circuitOpenUntil.get();
        if (now < openUntil) {
            return true;
        }
        return false;
    }

    public static void reset() {
        failureTimestamps.clear();
        circuitOpenUntil.set(0);
    }
}
