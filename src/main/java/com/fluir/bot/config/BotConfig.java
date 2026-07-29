package com.fluir.bot.config;

import io.github.cdimascio.dotenv.Dotenv;

import java.net.URI;
import java.nio.file.Path;
import java.util.Locale;

/** Güvenilir ortam değişkenlerini tek noktadan ve sınırlandırılmış olarak okur. */
public record BotConfig(
        String discordToken,
        Path dataDirectory,
        int port,
        String errorWebhookUrl,
        String healthMetricsToken,
        int maxQueryLength,
        String publicBaseUrl
) {
    public static BotConfig load() {
        Dotenv dotenv = Dotenv.configure().ignoreIfMissing().ignoreIfMalformed().load();
        String token = value("DISCORD_TOKEN", dotenv, "");
        String configuredDataDir = value("DATA_DIR", dotenv, "").trim();
        String railwayVolumePath = System.getenv("RAILWAY_VOLUME_MOUNT_PATH");
        String selectedDataDir = !configuredDataDir.isBlank() ? configuredDataDir
                : railwayVolumePath != null && !railwayVolumePath.isBlank()
                ? Path.of(railwayVolumePath, "fluir-data").toString()
                : "data";
        Path dataDir = Path.of(selectedDataDir).toAbsolutePath().normalize();
        int port = boundedInt(value("PORT", dotenv, "8080"), 1, 65535, 8080);
        int maxQuery = boundedInt(value("MAX_QUERY_LENGTH", dotenv, "300"), 32, 1000, 300);
        String webhook = value("ERROR_WEBHOOK_URL", dotenv, "").trim();
        if (!webhook.isEmpty() && !isAllowedDiscordWebhook(webhook)) {
            throw new IllegalArgumentException("ERROR_WEBHOOK_URL geçerli bir HTTPS Discord webhook adresi değil.");
        }
        String publicBaseUrl = resolvePublicBaseUrl(dotenv);
        return new BotConfig(token, dataDir, port, webhook,
                value("HEALTH_METRICS_TOKEN", dotenv, "").trim(), maxQuery, publicBaseUrl);
    }

    public void requireToken() {
        if (discordToken == null || discordToken.isBlank()) {
            throw new IllegalStateException("DISCORD_TOKEN ortam değişkeni gerekli.");
        }
    }

    private static String value(String key, Dotenv dotenv, String fallback) {
        String environment = System.getenv(key);
        if (environment != null && !environment.isBlank()) return environment;
        String local = dotenv.get(key);
        return local == null || local.isBlank() ? fallback : local;
    }

    private static int boundedInt(String raw, int min, int max, int fallback) {
        try {
            int value = Integer.parseInt(raw);
            return value >= min && value <= max ? value : fallback;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static boolean isAllowedDiscordWebhook(String raw) {
        try {
            URI uri = URI.create(raw);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            return "https".equalsIgnoreCase(uri.getScheme())
                    && (host.equals("discord.com") || host.equals("discordapp.com"))
                    && uri.getPath() != null && uri.getPath().startsWith("/api/webhooks/")
                    && uri.getUserInfo() == null && uri.getFragment() == null;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static String resolvePublicBaseUrl(Dotenv dotenv) {
        String configured = value("PUBLIC_BASE_URL", dotenv, "").trim();
        if (configured.isBlank()) {
            String railwayDomain = System.getenv("RAILWAY_PUBLIC_DOMAIN");
            if (railwayDomain != null && !railwayDomain.isBlank()) configured = "https://" + railwayDomain;
        }
        if (configured.isBlank()) return "";
        try {
            URI uri = URI.create(configured);
            boolean localDevelopment = "http".equalsIgnoreCase(uri.getScheme())
                    && ("localhost".equalsIgnoreCase(uri.getHost()) || "127.0.0.1".equals(uri.getHost()));
            if (!"https".equalsIgnoreCase(uri.getScheme()) && !localDevelopment) {
                throw new IllegalArgumentException("PUBLIC_BASE_URL HTTPS olmalıdır.");
            }
            if (uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException("PUBLIC_BASE_URL geçersiz.");
            }
            return configured.replaceAll("/+$", "");
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("PUBLIC_BASE_URL geçerli bir HTTPS adresi değil.", e);
        }
    }
}
