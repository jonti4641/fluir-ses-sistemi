package com.fluir.bot.audio;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Spotify oEmbed API ile Spotify bağlantılarını (track/album/playlist)
 * başlık ve sanatçı adına dönüştüren ultra hızlı ve bağımsız çözümleyici.
 * API anahtarı veya üçüncü taraf Maven bağımlılığı gerektirmez.
 */
public class SpotifyResolver {

    private static final Logger logger = LoggerFactory.getLogger(SpotifyResolver.class);
    private static final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private static final Pattern TITLE_PATTERN = Pattern.compile("\"title\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern AUTHOR_PATTERN = Pattern.compile("\"author_name\"\\s*:\\s*\"([^\"]+)\"");

    public static String resolveSpotifyUrl(String spotifyUrl) {
        if (spotifyUrl == null || !spotifyUrl.contains("spotify.com")) {
            return null;
        }

        try {
            String oembedUrl = "https://open.spotify.com/oembed?url=" + spotifyUrl;
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(oembedUrl))
                    .header("User-Agent", "Mozilla/5.0")
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                String json = response.body();
                Matcher titleMatcher = TITLE_PATTERN.matcher(json);
                Matcher authorMatcher = AUTHOR_PATTERN.matcher(json);

                String title = titleMatcher.find() ? titleMatcher.group(1) : null;
                String author = authorMatcher.find() ? authorMatcher.group(1) : null;

                if (title != null) {
                    title = unescapeJson(title);
                    if (author != null) author = unescapeJson(author);

                    String searchQuery = (author != null ? author + " - " : "") + title;
                    logger.info("🟢 Spotify URL çözümlendi: {} -> {}", spotifyUrl, searchQuery);
                    return searchQuery;
                }
            }
        } catch (Exception e) {
            logger.warn("⚠️ Spotify oEmbed çözme hatası: {}", e.getMessage());
        }
        return null;
    }

    private static String unescapeJson(String input) {
        if (input == null) return null;
        return input.replace("\\u0027", "'")
                    .replace("\\u0026", "&")
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\");
    }
}
