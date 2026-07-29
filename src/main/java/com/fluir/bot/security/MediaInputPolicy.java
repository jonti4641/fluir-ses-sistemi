package com.fluir.bot.security;

import java.net.IDN;
import java.net.URI;
import java.util.List;
import java.util.Locale;

/** SSRF, yerel dosya erişimi ve kontrol karakteri saldırılarını engeller. */
public final class MediaInputPolicy {
    private static final List<String> ALLOWED_HOSTS = List.of(
            "soundcloud.com", "on.soundcloud.com", "open.spotify.com",
            "bandcamp.com", "vimeo.com", "twitch.tv"
    );

    private MediaInputPolicy() {}

    public static Validation validate(String raw, int maxLength) {
        if (raw == null || raw.isBlank()) return new Validation(false, "Sorgu boş olamaz.");
        String query = raw.strip();
        if (query.length() > maxLength) return new Validation(false, "Sorgu çok uzun.");
        if (query.chars().anyMatch(c -> Character.isISOControl(c))) return new Validation(false, "Sorgu geçersiz karakter içeriyor.");
        if (!looksLikeUrl(query)) return new Validation(true, "");
        try {
            URI uri = URI.create(query);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getUserInfo() != null || uri.getFragment() != null) {
                return new Validation(false, "Yalnızca güvenli HTTPS medya bağlantıları kabul edilir.");
            }
            if (uri.getPort() != -1 && uri.getPort() != 443) return new Validation(false, "Özel bağlantı noktaları kabul edilmez.");
            String host = uri.getHost();
            if (host == null) return new Validation(false, "Bağlantı adresi geçersiz.");
            host = IDN.toASCII(host).toLowerCase(Locale.ROOT);
            if (isIpLiteral(host) || host.equals("localhost") || host.endsWith(".local")) {
                return new Validation(false, "Yerel veya IP tabanlı adresler kabul edilmez.");
            }
            String finalHost = host;
            boolean allowed = ALLOWED_HOSTS.stream().anyMatch(base -> finalHost.equals(base) || finalHost.endsWith("." + base));
            return allowed ? new Validation(true, "") : new Validation(false, "Bu medya alan adı desteklenmiyor.");
        } catch (IllegalArgumentException e) {
            return new Validation(false, "Bağlantı adresi geçersiz.");
        }
    }

    private static boolean looksLikeUrl(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.contains("://") || lower.startsWith("file:") || lower.startsWith("jar:");
    }

    private static boolean isIpLiteral(String host) {
        return host.matches("[0-9.]+") || host.contains(":") || host.startsWith("0x");
    }

    public record Validation(boolean allowed, String message) {}
}
