package com.fluir.bot.watch;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Discord'un LAUNCH_ACTIVITY (type 12) interaction yanıtını gönderir.
 * JDA 6.5 bu yeni callback türünü henüz doğrudan modellemediği için resmi
 * interaction callback uç noktası kullanılır.
 */
public final class ActivityLaunchService {
    private static final String API = "https://discord.com/api/v10/interactions/";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(2);
    private final HttpClient httpClient;

    public ActivityLaunchService() {
        this(HttpClient.newBuilder().connectTimeout(REQUEST_TIMEOUT).build());
    }

    ActivityLaunchService(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    public CompletableFuture<Boolean> launch(long interactionId, String interactionToken) {
        HttpRequest request = callbackRequest(interactionId, interactionToken);
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                .orTimeout(REQUEST_TIMEOUT.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)
                .thenApply(response -> response.statusCode() == 200 || response.statusCode() == 204)
                .exceptionally(ignored -> false);
    }

    static HttpRequest callbackRequest(long interactionId, String interactionToken) {
        if (interactionId <= 0) throw new IllegalArgumentException("Geçersiz interaction ID");
        if (interactionToken == null || !interactionToken.matches("[A-Za-z0-9._-]{10,300}")) {
            throw new IllegalArgumentException("Geçersiz interaction token");
        }
        return HttpRequest.newBuilder(URI.create(API + interactionId + "/" + interactionToken + "/callback"))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .header("User-Agent", "FluirBot/1.0 DiscordActivity")
                .POST(HttpRequest.BodyPublishers.ofString("{\"type\":12}"))
                .build();
    }
}
