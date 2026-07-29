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
import java.nio.charset.StandardCharsets;
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
    private static final Duration ROOM_TTL = Duration.ofHours(6);
    private static final int MAX_ROOMS = 1_000;
    private static final int MAX_CONTROL_BODY = 2_048;

    private final String publicBaseUrl;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Room> rooms = new ConcurrentHashMap<>();
    private final Map<String, RequestWindow> requestWindows = new ConcurrentHashMap<>();
    private final String htmlTemplate;

    public WatchPartyService(String publicBaseUrl) {
        this.publicBaseUrl = publicBaseUrl == null ? "" : publicBaseUrl;
        this.htmlTemplate = loadTemplate();
    }

    public void register(HttpServer server) {
        server.createContext("/watch", this::handleWatchPage);
        server.createContext("/api/watch", this::handleApi);
    }

    public boolean isAvailable() {
        return !publicBaseUrl.isBlank();
    }

    public WatchRoom createRoom(String rawYouTubeUrl, long ownerUserId) {
        if (!isAvailable()) throw new IllegalStateException("PUBLIC_BASE_URL ayarlı değil.");
        String videoId = extractYouTubeId(rawYouTubeUrl);
        if (videoId == null) throw new IllegalArgumentException("Geçerli bir YouTube video bağlantısı gerekli.");
        cleanupExpiredRooms();
        if (rooms.size() >= MAX_ROOMS) throw new IllegalStateException("Ortak izleme oda sınırına ulaşıldı.");
        String roomId = randomId();
        Room room = new Room(roomId, videoId, ownerUserId);
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
        if (!validOrigin(exchange) || !allowControl(exchange, room.id)) {
            sendJson(exchange, 429, "{\"error\":\"request_rejected\"}");
            return;
        }
        byte[] body = exchange.getRequestBody().readNBytes(MAX_CONTROL_BODY + 1);
        if (body.length > MAX_CONTROL_BODY) {
            sendJson(exchange, 413, "{\"error\":\"body_too_large\"}");
            return;
        }
        Map<String, String> form = parseForm(new String(body, StandardCharsets.UTF_8));
        String action = form.getOrDefault("action", "");
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
            return Objects.equals(expected.getScheme(), actual.getScheme())
                    && Objects.equals(expected.getHost(), actual.getHost())
                    && effectivePort(expected) == effectivePort(actual);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private boolean allowControl(HttpExchange exchange, String roomId) {
        long now = System.currentTimeMillis();
        String key = exchange.getRemoteAddress().getAddress().getHostAddress() + ":" + roomId;
        RequestWindow window = requestWindows.compute(key, (ignored, old) ->
                old == null || now - old.startedAt > 10_000 ? new RequestWindow(now, 1) : new RequestWindow(old.startedAt, old.count + 1));
        return window.count <= 30;
    }

    private void cleanupExpiredRooms() {
        rooms.entrySet().removeIf(entry -> entry.getValue().expired());
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
        try (InputStream input = WatchPartyService.class.getResourceAsStream("/watch-party.html")) {
            if (input == null) throw new IllegalStateException("watch-party.html bulunamadı");
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Ortak izleme şablonu okunamadı", e);
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
                        + "font-src 'self'; base-uri 'none'; form-action 'self'; frame-ancestors https://discord.com https://*.discord.com");
        exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
        exchange.getResponseHeaders().set("Permissions-Policy", "camera=(), microphone=(), geolocation=()");
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
    private record RequestWindow(long startedAt, int count) {}

    private static final class Room {
        private final String id;
        private final String videoId;
        private final long ownerUserId;
        private final long expiresAt = System.currentTimeMillis() + ROOM_TTL.toMillis();
        private final CopyOnWriteArrayList<ArrayBlockingQueue<String>> subscribers = new CopyOnWriteArrayList<>();
        private boolean playing;
        private double position;
        private long stateChangedAt = System.currentTimeMillis();
        private long version;

        private Room(String id, String videoId, long ownerUserId) {
            this.id = id;
            this.videoId = videoId;
            this.ownerUserId = ownerUserId;
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
                    + ",\"participants\":" + subscribers.size() + ",\"owner\":\"" + ownerUserId + "\"}";
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
