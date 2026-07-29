package com.fluir.bot.monitoring;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Sır, sorgu parametresi veya stack trace göndermeyen hata webhook'u. */
public final class SecureWebhookNotifier {
    private static final Logger logger = LoggerFactory.getLogger(SecureWebhookNotifier.class);
    private final String url;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final Map<String, Long> recentlySent = new ConcurrentHashMap<>();
    private volatile String lastIncidentId = "-";

    public SecureWebhookNotifier(String url) { this.url = url == null ? "" : url; }

    public String report(long guildId, String category, Throwable error) {
        String incident = UUID.randomUUID().toString().substring(0, 8);
        lastIncidentId = incident;
        if (url.isBlank()) return incident;
        String safeCategory = sanitize(category, 80);
        String fingerprint = guildId + ":" + safeCategory + ":" + (error == null ? "unknown" : error.getClass().getName());
        long now = System.currentTimeMillis();
        Long previous = recentlySent.put(fingerprint, now);
        if (previous != null && now - previous < 60_000) return incident;

        String content = "Fluir incident `" + incident + "` | guild `" + guildId + "` | " + safeCategory;
        String body = "{\"content\":\"" + json(content) + "\",\"allowed_mentions\":{\"parse\":[]}}";
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(8))
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
        client.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                .exceptionally(ex -> { logger.warn("Hata webhook'u gönderilemedi: {}", ex.getClass().getSimpleName()); return null; });
        return incident;
    }

    public String lastIncidentId() { return lastIncidentId; }
    private static String sanitize(String text,int max){String value=text==null?"hata":text.replaceAll("https?://\\S+","[url]").replaceAll("[\\r\\n\\p{Cntrl}]"," ").strip();return value.length()>max?value.substring(0,max):value;}
    private static String json(String text){return text.replace("\\","\\\\").replace("\"","\\\"");}
}
