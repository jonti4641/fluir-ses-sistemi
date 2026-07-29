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
    private static final String APPLICATIONS_API = "https://discord.com/api/v10/applications/";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration ENTRY_POINT_TIMEOUT = Duration.ofSeconds(5);
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

    /**
     * Activity'nin Discord Etkinlikler rafında görünmesini sağlayan birincil giriş
     * komutunu oluşturur. Slash komutlarının toplu güncellenmesinden sonra çağrılır.
     */
    public CompletableFuture<Boolean> ensureEntryPoint(String applicationId, String botToken) {
        if (applicationId == null || !applicationId.matches("[0-9]{15,22}")) {
            return CompletableFuture.completedFuture(false);
        }
        if (botToken == null || botToken.length() < 20 || botToken.contains("\r") || botToken.contains("\n")) {
            return CompletableFuture.completedFuture(false);
        }
        HttpRequest request = entryPointRequest(applicationId, botToken);
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                .orTimeout(ENTRY_POINT_TIMEOUT.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)
                .thenApply(response -> response.statusCode() == 200 || response.statusCode() == 201)
                .exceptionally(ignored -> false);
    }

    static HttpRequest entryPointRequest(String applicationId, String botToken) {
        String body = "{\"name\":\"watch together\",\"description\":\"Fluir ile birlikte video izle\","
                + "\"type\":4,\"handler\":2,\"integration_types\":[0],\"contexts\":[0]}";
        return HttpRequest.newBuilder(URI.create(APPLICATIONS_API + applicationId + "/commands"))
                .timeout(ENTRY_POINT_TIMEOUT)
                .header("Authorization", "Bot " + botToken)
                .header("Content-Type", "application/json")
                .header("User-Agent", "FluirBot/1.0 DiscordActivity")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
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

