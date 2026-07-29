package com.fluir.bot.watch;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * YouTube videosunu bot üzerinden yeniden yayınlamadan, resmi IFrame Player kullanan
 * yetenek-URL tabanlı senkron ortak izleme odaları sağlar.
 */
public final class WatchPartyService {
    private static final Logger logger = LoggerFactory.getLogger(WatchPartyService.class);
    private static final Pattern VIDEO_ID = Pattern.compile("^[A-Za-z0-9_-]{11}$");
    private static final Pattern WATCH_URL = Pattern.compile("(?:youtu\\.be/|youtube\\.com/(?:watch\\?(?:[^#]*&)?v=|shorts/|embed/))([A-Za-z0-9_-]{11})", Pattern.CASE_INSENSITIVE);
    private static final Pattern INSTANCE_ID = Pattern.compile("^[A-Za-z0-9_-]{10,200}$");
    private static final Pattern SNOWFLAKE = Pattern.compile("^[0-9]{15,22}$");
    private static final Duration ROOM_TTL = Duration.ofHours(6);
    private static final Duration PENDING_TTL = Duration.ofMinutes(2);
    private static final int MAX_ROOMS = 1_000;
    private static final int MAX_CONTROL_BODY = 2_048;
    private static final int MAX_YOUTUBE_EMBED_BYTES = 2 * 1024 * 1024;
    private static final long MAX_YOUTUBE_ASSET_BYTES = 8L * 1024 * 1024;
    private static final Pattern HTML_NONCE_ATTRIBUTE = Pattern.compile("(?i)\\snonce=(?:\"[^\"]*\"|'[^']*')");
    private static final String CONTROL_COOKIE = "__Host-FluirControl";

    private final String publicBaseUrl;
    private final String botToken;
    private final HttpClient discordApi;
    private final HttpClient youtubeAssets;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Room> rooms = new ConcurrentHashMap<>();
    private final Map<Long, PendingLaunch> pendingLaunches = new ConcurrentHashMap<>();
    private final Map<String, String> instanceRooms = new ConcurrentHashMap<>();
    private final Map<String, RequestWindow> requestWindows = new ConcurrentHashMap<>();
    private final String htmlTemplate;
    private final String activityTemplate;
    private final byte[] discordSdk;
    private volatile String applicationId = "";

    public WatchPartyService(String publicBaseUrl) {
        this(publicBaseUrl, "");
    }

    public WatchPartyService(String publicBaseUrl, String botToken) {
        this.publicBaseUrl = publicBaseUrl == null ? "" : publicBaseUrl;
        this.botToken = botToken == null ? "" : botToken;
        this.discordApi = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        this.youtubeAssets = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).followRedirects(HttpClient.Redirect.NORMAL).build();
        this.htmlTemplate = loadTemplate();
        this.activityTemplate = loadTextResource("/activity.html");
        this.discordSdk = loadBytesResource("/discord-sdk.js");
    }

    public void register(HttpServer server) {
        server.createContext("/watch", this::handleWatchPage);
        server.createContext("/api/watch", this::handleApi);
        server.createContext("/api/activity", this::handleActivityApi);
        server.createContext("/assets/discord-sdk.js", this::handleDiscordSdk);
        server.createContext("/youtube-embed/", this::handleYouTubeEmbed);
        server.createContext("/s/", this::handleYouTubeAsset);
        server.createContext("/yts/", this::handleYouTubeAsset);
        server.createContext("/", this::handleActivityPage);
    }

    /**
     * YouTube'un resmi embed HTML'ini sabit kaynaktan alır. YouTube'un CSP nonce
     * değerleri Discord'un Activity proxy'sinde yeniden üretilen nonce ile çakıştığı
     * için yalnızca nonce öznitelikleri temizlenir; video verisi burada aktarılmaz.
     */
    private void handleYouTubeEmbed(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod()) && !"HEAD".equals(exchange.getRequestMethod())) {
            sendJson(exchange, 405, "{\"error\":\"method_not_allowed\"}");
            return;
        }
        String path = exchange.getRequestURI().getPath();
        String videoId = pathPart(path, 2);
        if (videoId == null || !VIDEO_ID.matcher(videoId).matches()
                || !path.equals("/youtube-embed/" + videoId)) {
            sendJson(exchange, 400, "{\"error\":\"invalid_youtube_source\"}");
            return;
        }
        URI target;
        try {
            target = new URI("https", "www.youtube.com", "/embed/" + videoId,
                    exchange.getRequestURI().getRawQuery(), null);
        } catch (Exception e) {
            sendJson(exchange, 400, "{\"error\":\"invalid_youtube_source\"}");
            return;
        }
        HttpRequest request = HttpRequest.newBuilder(target)
                .timeout(Duration.ofSeconds(12))
                .header("Accept", "text/html,application/xhtml+xml")
                .header("Accept-Language", "tr-TR,tr;q=0.9,en;q=0.7")
                .header("User-Agent", "Mozilla/5.0 FluirDiscordActivity/1.0")
                .GET().build();
        try {
            HttpResponse<InputStream> response = youtubeAssets.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                response.body().close();
                sendJson(exchange, 502, "{\"error\":\"youtube_embed_unavailable\"}");
                return;
            }
            byte[] raw;
            try (InputStream input = response.body()) {
                raw = input.readNBytes(MAX_YOUTUBE_EMBED_BYTES + 1);
            }
            if (raw.length > MAX_YOUTUBE_EMBED_BYTES) {
                sendJson(exchange, 502, "{\"error\":\"youtube_embed_too_large\"}");
                return;
            }
            String normalized = HTML_NONCE_ATTRIBUTE.matcher(new String(raw, StandardCharsets.UTF_8)).replaceAll("");
            byte[] body = normalized.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.getResponseHeaders().set("Cache-Control", "private, no-store");
            exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            exchange.getResponseHeaders().set("Referrer-Policy", "strict-origin-when-cross-origin");
            if ("HEAD".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(200, -1);
                return;
            }
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(body);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            sendJson(exchange, 503, "{\"error\":\"youtube_embed_interrupted\"}");
        } catch (IOException e) {
            logger.warn("YouTube embed sayfası alınamadı: {}", e.getClass().getSimpleName());
            if (exchange.getResponseCode() < 0) sendJson(exchange, 502, "{\"error\":\"youtube_embed_unavailable\"}");
        }
    }

    public void configureApplication(String applicationId) {
        if (applicationId == null || !SNOWFLAKE.matcher(applicationId).matches()) {
            throw new IllegalArgumentException("Geçersiz Discord application ID");
        }
        this.applicationId = applicationId;
    }

    public boolean isAvailable() {
        return !publicBaseUrl.isBlank();
    }

    public boolean isActivityAvailable() {
        return isAvailable() && !botToken.isBlank() && !applicationId.isBlank();
    }

    public PendingLaunch prepareActivity(long channelId, String rawYouTubeUrl, long ownerUserId) {
        if (!isActivityAvailable()) throw new IllegalStateException("Discord Activity henüz hazır değil.");
        String videoId = extractYouTubeId(rawYouTubeUrl);
        if (videoId == null) throw new IllegalArgumentException("Geçerli bir YouTube video bağlantısı gerekli.");
        cleanupExpiredRooms();
        PendingLaunch pending = new PendingLaunch(channelId, videoId, ownerUserId,
                System.currentTimeMillis() + PENDING_TTL.toMillis());
        pendingLaunches.put(channelId, pending);
        return pending;
    }

    public void cancelPrepared(PendingLaunch pending) {
        if (pending != null) pendingLaunches.remove(pending.channelId(), pending);
    }

    public WatchRoom createRoom(String rawYouTubeUrl, long ownerUserId) {
        if (!isAvailable()) throw new IllegalStateException("PUBLIC_BASE_URL ayarlı değil.");
        String videoId = extractYouTubeId(rawYouTubeUrl);
        if (videoId == null) throw new IllegalArgumentException("Geçerli bir YouTube video bağlantısı gerekli.");
        cleanupExpiredRooms();
        if (rooms.size() >= MAX_ROOMS) throw new IllegalStateException("Ortak izleme oda sınırına ulaşıldı.");
        String roomId = randomId();
        Room room = new Room(roomId, videoId, randomId());
        rooms.put(roomId, room);
        return new WatchRoom(roomId, videoId, publicBaseUrl + "/watch/" + roomId, room.expiresAt);
    }

    public static String extractYouTubeId(String raw) {
        if (raw == null || raw.isBlank() || raw.length() > 500) return null;
        String value = raw.strip();
        if (VIDEO_ID.matcher(value).matches()) return value;
        Matcher matcher = WATCH_URL.matcher(value);
        return matcher.find() ? matcher.group(1) : null;
    }

    private void handleActivityPage(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (!("/".equals(path) || "/activity".equals(path))) {
            sendJson(exchange, 404, "{\"error\":\"not_found\"}");
            return;
        }
        if (!"GET".equals(exchange.getRequestMethod())) {
            sendJson(exchange, 405, "{\"error\":\"method_not_allowed\"}");
            return;
        }
        if (!isActivityAvailable()) {
            sendHtml(exchange, 503, "<!doctype html><meta charset=\"utf-8\"><title>Activity hazırlanıyor</title><body>Activity henüz yapılandırılmadı.</body>");
            return;
        }
        String nonce = randomId();
        String html = activityTemplate
                .replace("__DISCORD_CLIENT_ID__", applicationId)
                .replace("__NONCE__", nonce);
        setActivitySecurityHeaders(exchange, nonce);
        sendHtml(exchange, 200, html);
    }

    private void handleDiscordSdk(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            sendJson(exchange, 405, "{\"error\":\"method_not_allowed\"}");
            return;
        }
        exchange.getResponseHeaders().set("Content-Type", "text/javascript; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "public, max-age=86400, immutable");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(200, discordSdk.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(discordSdk);
        }
    }

    /**
     * Discord URL eşlemesiyle gelen YouTube embed sayfasının kök-bağıl statik
     * oynatıcı dosyalarını sabit www.youtube.com kaynağından geçirir. İstek hedefi
     * kullanıcı tarafından seçilemez; video akışı veya keyfi URL proxy'lenmez.
     */
    private void handleYouTubeAsset(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod()) && !"HEAD".equals(exchange.getRequestMethod())) {
            sendJson(exchange, 405, "{\"error\":\"method_not_allowed\"}");
            return;
        }
        String path = exchange.getRequestURI().getRawPath();
        if (path == null || (!path.startsWith("/s/") && !path.startsWith("/yts/"))
                || path.contains("..") || path.length() > 1_500) {
            sendJson(exchange, 400, "{\"error\":\"invalid_asset_path\"}");
            return;
        }
        URI target;
        try {
            target = new URI("https", "www.youtube.com", path, exchange.getRequestURI().getRawQuery(), null);
        } catch (Exception e) {
            sendJson(exchange, 400, "{\"error\":\"invalid_asset_path\"}");
            return;
        }
        HttpRequest request = HttpRequest.newBuilder(target)
                .timeout(Duration.ofSeconds(12))
                .header("Accept", "*/*")
                .header("User-Agent", "Mozilla/5.0 FluirDiscordActivity/1.0")
                .GET().build();
        try {
            HttpResponse<InputStream> response = youtubeAssets.send(request, HttpResponse.BodyHandlers.ofInputStream());
            long declaredLength = response.headers().firstValueAsLong("Content-Length").orElse(-1);
            if (response.statusCode() != 200 || declaredLength > MAX_YOUTUBE_ASSET_BYTES) {
                response.body().close();
                sendJson(exchange, 502, "{\"error\":\"youtube_asset_unavailable\"}");
                return;
            }
            exchange.getResponseHeaders().set("Content-Type", response.headers().firstValue("Content-Type").orElse("application/octet-stream"));
            exchange.getResponseHeaders().set("Cache-Control", "public, max-age=3600");
            exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            if ("HEAD".equals(exchange.getRequestMethod())) {
                response.body().close();
                exchange.sendResponseHeaders(200, -1);
                return;
            }
            exchange.sendResponseHeaders(200, declaredLength >= 0 ? declaredLength : 0);
            try (InputStream input = response.body(); OutputStream output = exchange.getResponseBody()) {
                byte[] buffer = new byte[16_384];
                long total = 0;
                for (int read; (read = input.read(buffer)) >= 0;) {
                    total += read;
                    if (total > MAX_YOUTUBE_ASSET_BYTES) throw new IOException("YouTube asset boyut sınırını aştı");
                    output.write(buffer, 0, read);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            sendJson(exchange, 503, "{\"error\":\"youtube_asset_interrupted\"}");
        } catch (IOException e) {
            logger.warn("YouTube oynatıcı varlığı alınamadı: {}", e.getClass().getSimpleName());
            if (exchange.getResponseCode() < 0) sendJson(exchange, 502, "{\"error\":\"youtube_asset_unavailable\"}");
        }
    }

    private void handleActivityApi(HttpExchange exchange) throws IOException {
        if (!"/api/activity/bootstrap".equals(exchange.getRequestURI().getPath())) {
            sendJson(exchange, 404, "{\"error\":\"not_found\"}");
            return;
        }
        if (!"POST".equals(exchange.getRequestMethod())) {
            sendJson(exchange, 405, "{\"error\":\"method_not_allowed\"}");
            return;
        }
        if (!validOrigin(exchange)) {
            sendJson(exchange, 429, "{\"error\":\"request_rejected\"}");
            return;
        }
        byte[] body = exchange.getRequestBody().readNBytes(MAX_CONTROL_BODY + 1);
        if (body.length > MAX_CONTROL_BODY) {
            sendJson(exchange, 413, "{\"error\":\"body_too_large\"}");
            return;
        }
        Map<String, String> form = parseForm(new String(body, StandardCharsets.UTF_8));
        String instanceId = form.getOrDefault("instanceId", "");
        String channelValue = form.getOrDefault("channelId", "");
        if (!INSTANCE_ID.matcher(instanceId).matches() || !SNOWFLAKE.matcher(channelValue).matches()) {
            sendJson(exchange, 400, "{\"error\":\"invalid_activity_context\"}");
            return;
        }
        if (!allowControl("activity-bootstrap:" + instanceId)) {
            sendJson(exchange, 429, "{\"error\":\"request_rejected\"}");
            return;
        }
        long channelId;
        try {
            channelId = Long.parseUnsignedLong(channelValue);
        } catch (NumberFormatException e) {
            sendJson(exchange, 400, "{\"error\":\"invalid_channel\"}");
            return;
        }
        if (!verifyActivityInstance(instanceId, channelValue)) {
            sendJson(exchange, 403, "{\"error\":\"activity_instance_rejected\"}");
            return;
        }
        ActivityRoom activityRoom = roomForActivity(instanceId, channelId);
        if (activityRoom == null) {
            sendJson(exchange, 409, "{\"error\":\"room_limit_reached\"}");
            return;
        }
        if (activityRoom.created()) setControllerCookie(exchange, activityRoom.room().controllerToken);
        boolean controller = activityRoom.created() || activityRoom.room().hasControllerCookie(exchange);
        sendJson(exchange, 200, activityRoom.room().bootstrapSnapshot(controller));
    }

    private synchronized ActivityRoom roomForActivity(String instanceId, long channelId) {
        cleanupExpiredRooms();
        String existingId = instanceRooms.get(instanceId);
        Room existing = existingId == null ? null : rooms.get(existingId);
        if (existing != null && !existing.expired()) return new ActivityRoom(existing, false);

        PendingLaunch pending = pendingLaunches.get(channelId);
        if (pending != null && pending.expired()) pending = null;
        if (rooms.size() >= MAX_ROOMS) return null;
        String roomId = randomId();
        String videoId = pending == null ? "" : pending.videoId();
        Room room = new Room(roomId, videoId, randomId());
        rooms.put(roomId, room);
        instanceRooms.put(instanceId, roomId);
        if (pending != null) pendingLaunches.remove(channelId, pending);
        return new ActivityRoom(room, true);
    }

    private boolean verifyActivityInstance(String instanceId, String channelId) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create("https://discord.com/api/v10/applications/"
                            + applicationId + "/activity-instances/" + instanceId))
                    .timeout(Duration.ofSeconds(2))
                    .header("Authorization", "Bot " + botToken)
                    .header("User-Agent", "FluirBot/1.0 DiscordActivity")
                    .GET().build();
            HttpResponse<String> response = discordApi.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) return false;
            String json = response.body();
            return jsonFieldEquals(json, "application_id", applicationId)
                    && jsonFieldEquals(json, "instance_id", instanceId)
                    && jsonFieldEquals(json, "channel_id", channelId);
        } catch (IOException e) {
            logger.warn("Discord Activity instance doğrulaması başarısız: {}", e.getClass().getSimpleName());
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static boolean jsonFieldEquals(String json, String field, String expected) {
        return Pattern.compile("\\\"" + Pattern.quote(field) + "\\\"\\s*:\\s*\\\""
                + Pattern.quote(expected) + "\\\"").matcher(json).find();
    }

    private void handleWatchPage(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            sendJson(exchange, 405, "{\"error\":\"method_not_allowed\"}");
            return;
        }
        String roomId = pathPart(exchange.getRequestURI().getPath(), 2);
        Room room = roomId == null ? null : rooms.get(roomId);
        if (room == null || room.expired()) {
            sendHtml(exchange, 404, expiredPage());
            return;
        }
        String nonce = randomId();
        String html = htmlTemplate
                .replace("__ROOM_ID__", room.id)
                .replace("__VIDEO_ID__", room.videoId)
                .replace("__NONCE__", nonce);
        setSecurityHeaders(exchange, nonce);
        sendHtml(exchange, 200, html);
    }

    private void handleApi(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String roomId = pathPart(path, 3);
        String operation = pathPart(path, 4);
        Room room = roomId == null ? null : rooms.get(roomId);
        if (room == null || room.expired()) {
            sendJson(exchange, 404, "{\"error\":\"room_not_found\"}");
            return;
        }
        if ("events".equals(operation)) {
            handleEvents(exchange, room);
        } else if ("state".equals(operation) && "GET".equals(exchange.getRequestMethod())) {
            sendJson(exchange, 200, room.snapshot());
        } else if ("control".equals(operation)) {
            handleControl(exchange, room);
        } else {
            sendJson(exchange, 404, "{\"error\":\"not_found\"}");
        }
    }

    private void handleControl(HttpExchange exchange, Room room) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            sendJson(exchange, 405, "{\"error\":\"method_not_allowed\"}");
            return;
        }
        if (!validOrigin(exchange)) {
            sendJson(exchange, 429, "{\"error\":\"request_rejected\"}");
            return;
        }
        if (!room.hasControllerCookie(exchange)) {
            sendJson(exchange, 403, "{\"error\":\"controller_only\"}");
            return;
        }
        if (!allowControl("room-control:" + room.id)) {
            sendJson(exchange, 429, "{\"error\":\"rate_limited\"}");
            return;
        }
        byte[] body = exchange.getRequestBody().readNBytes(MAX_CONTROL_BODY + 1);
        if (body.length > MAX_CONTROL_BODY) {
            sendJson(exchange, 413, "{\"error\":\"body_too_large\"}");
            return;
        }
        Map<String, String> form = parseForm(new String(body, StandardCharsets.UTF_8));
        String action = form.getOrDefault("action", "");
        if ("source".equals(action)) {
            if (!room.setSource(form.getOrDefault("source", ""))) {
                sendJson(exchange, 400, "{\"error\":\"invalid_youtube_source\"}");
                return;
            }
            room.broadcast();
            sendJson(exchange, 200, room.snapshot());
            return;
        }
        double position;
        try {
            position = Double.parseDouble(form.getOrDefault("position", "0"));
        } catch (NumberFormatException e) {
            sendJson(exchange, 400, "{\"error\":\"invalid_position\"}");
            return;
        }
        if (!Double.isFinite(position) || position < 0 || position > 86_400) {
            sendJson(exchange, 400, "{\"error\":\"invalid_position\"}");
            return;
        }
        if (!room.control(action, position)) {
            sendJson(exchange, 400, "{\"error\":\"invalid_action\"}");
            return;
        }
        room.broadcast();
        sendJson(exchange, 200, room.snapshot());
    }

    private void handleEvents(HttpExchange exchange, Room room) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            sendJson(exchange, 405, "{\"error\":\"method_not_allowed\"}");
            return;
        }
        if (room.subscribers.size() >= 50) {
            sendJson(exchange, 429, "{\"error\":\"room_full\"}");
            return;
        }
        ArrayBlockingQueue<String> subscriber = new ArrayBlockingQueue<>(20);
        room.subscribers.add(subscriber);
        room.broadcast();
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("Connection", "keep-alive");
        exchange.getResponseHeaders().set("X-Accel-Buffering", "no");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(200, 0);
        try (OutputStream output = exchange.getResponseBody()) {
            while (!room.expired()) {
                String state = subscriber.poll(20, TimeUnit.SECONDS);
                String event = state == null ? ": heartbeat\n\n" : "event: state\ndata: " + state + "\n\n";
                output.write(event.getBytes(StandardCharsets.UTF_8));
                output.flush();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException ignored) {
            // Tarayıcı bağlantıyı kapattı.
        } finally {
            room.subscribers.remove(subscriber);
            room.broadcast();
        }
    }

    private boolean validOrigin(HttpExchange exchange) {
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        if (origin == null || origin.isBlank()) return false;
        try {
            URI expected = URI.create(publicBaseUrl);
            URI actual = URI.create(origin);
            boolean publicOrigin = Objects.equals(expected.getScheme(), actual.getScheme())
                    && Objects.equals(expected.getHost(), actual.getHost())
                    && effectivePort(expected) == effectivePort(actual);
            boolean activityOrigin = "https".equalsIgnoreCase(actual.getScheme())
                    && actual.getPort() < 0
                    && (applicationId + ".discordsays.com").equalsIgnoreCase(actual.getHost());
            return publicOrigin || activityOrigin;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private boolean allowControl(String key) {
        long now = System.currentTimeMillis();
        RequestWindow window = requestWindows.compute(key, (ignored, old) ->
                old == null || now - old.startedAt > 10_000 ? new RequestWindow(now, 1) : new RequestWindow(old.startedAt, old.count + 1));
        return window.count <= 30;
    }

    private static void setControllerCookie(HttpExchange exchange, String token) {
        exchange.getResponseHeaders().add("Set-Cookie", CONTROL_COOKIE + "=" + token
                + "; Path=/; Max-Age=" + ROOM_TTL.toSeconds()
                + "; Secure; HttpOnly; SameSite=None; Partitioned");
    }

    private void cleanupExpiredRooms() {
        rooms.entrySet().removeIf(entry -> entry.getValue().expired());
        instanceRooms.entrySet().removeIf(entry -> !rooms.containsKey(entry.getValue()));
        pendingLaunches.entrySet().removeIf(entry -> entry.getValue().expired());
        if (requestWindows.size() > 10_000) {
            long cutoff = System.currentTimeMillis() - 60_000;
            requestWindows.entrySet().removeIf(entry -> entry.getValue().startedAt < cutoff);
        }
    }

    private String randomId() {
        byte[] bytes = new byte[18];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static Map<String, String> parseForm(String body) {
        Map<String, String> values = new ConcurrentHashMap<>();
        for (String pair : body.split("&", 10)) {
            String[] parts = pair.split("=", 2);
            if (parts.length == 2) {
                values.put(URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
                        URLDecoder.decode(parts[1], StandardCharsets.UTF_8));
            }
        }
        return values;
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() >= 0) return uri.getPort();
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private static String pathPart(String path, int index) {
        String[] parts = path.split("/");
        return parts.length > index ? parts[index] : null;
    }

    private static String loadTemplate() {
        return loadTextResource("/watch-party.html");
    }

    private static String loadTextResource(String path) {
        return new String(loadBytesResource(path), StandardCharsets.UTF_8);
    }

    private static byte[] loadBytesResource(String path) {
        try (InputStream input = WatchPartyService.class.getResourceAsStream(path)) {
            if (input == null) throw new IllegalStateException(path + " bulunamadı");
            return input.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException(path + " okunamadı", e);
        }
    }

    private static String expiredPage() {
        return "<!doctype html><html lang=\"tr\"><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width\"><title>Oda kapandı</title><body style=\"background:#080b14;color:#fff;font-family:sans-serif;padding:3rem\"><h1>Bu ortak izleme odası kapandı.</h1><p>Discord içinde /izle komutuyla yeni oda oluştur.</p></body></html>";
    }

    private static void setSecurityHeaders(HttpExchange exchange, String nonce) {
        exchange.getResponseHeaders().set("Content-Security-Policy",
                "default-src 'none'; script-src 'nonce-" + nonce + "' https://www.youtube.com https://s.ytimg.com; "
                        + "style-src 'nonce-" + nonce + "'; img-src 'self' data: https://i.ytimg.com; "
                        + "frame-src https://www.youtube.com https://www.youtube-nocookie.com; connect-src 'self'; "
                        + "font-src 'self'; base-uri 'none'; form-action 'self'; frame-ancestors https://discord.com https://*.discord.com https://*.discordsays.com");
        exchange.getResponseHeaders().set("Referrer-Policy", "strict-origin-when-cross-origin");
        exchange.getResponseHeaders().set("Permissions-Policy", "camera=(), microphone=(), geolocation=(), display-capture=(), payment=(), usb=(), serial=(), bluetooth=(), accelerometer=(), gyroscope=(), magnetometer=(), clipboard-read=(), clipboard-write=(), fullscreen=(self)");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
    }

    private static void setActivitySecurityHeaders(HttpExchange exchange, String nonce) {
        exchange.getResponseHeaders().set("Content-Security-Policy",
                "default-src 'self'; script-src 'self' 'nonce-" + nonce + "'; style-src 'nonce-" + nonce + "'; "
                        + "img-src 'self' data: https://i.ytimg.com; frame-src 'self' https://www.youtube.com https://www.youtube-nocookie.com; "
                        + "connect-src 'self'; media-src 'none'; object-src 'none'; base-uri 'none'; form-action 'self'; "
                        + "frame-ancestors https://discord.com https://*.discord.com https://*.discordsays.com");
        exchange.getResponseHeaders().set("Referrer-Policy", "strict-origin-when-cross-origin");
        exchange.getResponseHeaders().set("Permissions-Policy", "camera=(), microphone=(), geolocation=(), display-capture=(), payment=(), usb=(), serial=(), bluetooth=(), accelerometer=(), gyroscope=(), magnetometer=(), clipboard-read=(), clipboard-write=(), fullscreen=(self)");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
    }

    private static void sendJson(HttpExchange exchange, int status, String body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        send(exchange, status, body);
    }

    private static void sendHtml(HttpExchange exchange, int status, String body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        send(exchange, status, body);
    }

    private static void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    public record WatchRoom(String id, String videoId, String url, long expiresAt) {}
    public record PendingLaunch(long channelId, String videoId, long ownerUserId, long expiresAt) {
        private boolean expired() { return System.currentTimeMillis() >= expiresAt; }
    }
    private record RequestWindow(long startedAt, int count) {}
    private record ActivityRoom(Room room, boolean created) {}

    private static final class Room {
        private final String id;
        private String videoId;
        private final String controllerToken;
        private final long expiresAt = System.currentTimeMillis() + ROOM_TTL.toMillis();
        private final CopyOnWriteArrayList<ArrayBlockingQueue<String>> subscribers = new CopyOnWriteArrayList<>();
        private boolean playing = true;
        private double position;
        private long stateChangedAt = System.currentTimeMillis();
        private long version;

        private Room(String id, String videoId, String controllerToken) {
            this.id = id;
            this.videoId = videoId;
            this.controllerToken = controllerToken;
        }

        private synchronized boolean setSource(String rawSource) {
            String parsedVideoId = extractYouTubeId(rawSource);
            if (parsedVideoId == null) return false;
            videoId = parsedVideoId;
            position = 0;
            playing = true;
            stateChangedAt = System.currentTimeMillis();
            version++;
            return true;
        }

        private synchronized boolean control(String action, double requestedPosition) {
            if (!action.equals("play") && !action.equals("pause") && !action.equals("seek")) return false;
            position = requestedPosition;
            if (action.equals("play")) playing = true;
            if (action.equals("pause")) playing = false;
            stateChangedAt = System.currentTimeMillis();
            version++;
            return true;
        }

        private synchronized String snapshot() {
            long now = System.currentTimeMillis();
            double current = playing ? position + (now - stateChangedAt) / 1000.0 : position;
            return "{\"videoId\":\"" + videoId + "\",\"playing\":" + playing
                    + ",\"position\":" + String.format(java.util.Locale.ROOT, "%.3f", current)
                    + ",\"serverTime\":" + now + ",\"version\":" + version
                    + ",\"participants\":" + subscribers.size() + "}";
        }

        private synchronized String bootstrapSnapshot(boolean controller) {
            return "{\"roomId\":\"" + id + "\",\"controller\":" + controller + "," + snapshot().substring(1);
        }

        private boolean hasControllerCookie(HttpExchange exchange) {
            String cookie = exchange.getRequestHeaders().getFirst("Cookie");
            if (cookie == null || cookie.isBlank()) return false;
            for (String pair : cookie.split(";")) {
                String[] parts = pair.strip().split("=", 2);
                if (parts.length == 2 && CONTROL_COOKIE.equals(parts[0])) {
                    return MessageDigest.isEqual(controllerToken.getBytes(StandardCharsets.UTF_8),
                            parts[1].getBytes(StandardCharsets.UTF_8));
                }
            }
            return false;
        }

        private void broadcast() {
            String state = snapshot();
            for (ArrayBlockingQueue<String> subscriber : subscribers) {
                if (!subscriber.offer(state)) {
                    subscriber.poll();
                    subscriber.offer(state);
                }
            }
        }

        private boolean expired() {
            return System.currentTimeMillis() >= expiresAt;
        }
    }
}

