package com.fluir.bot.watch;

import org.junit.jupiter.api.Test;

import java.net.http.HttpRequest;

import static org.junit.jupiter.api.Assertions.*;

class ActivityLaunchServiceTest {
    @Test
    void youtubeEmbedNormalizerInstallsOnlyFixedDiscordMappings() {
        String html = "<html><head></head><body><script nonce=\"old\" src=\"/s/player/base.js\"></script></body></html>";

        String normalized = WatchPartyService.normalizeYouTubeEmbedHtml(html);

        assertFalse(normalized.contains("nonce=\"old\""));
        assertTrue(normalized.contains("/s/player/base.js?fluir=proxy-route-4"));
        assertTrue(normalized.contains("location.origin+\"/googlevideo/\"+subdomain"));
        assertTrue(normalized.contains("window.fetch=async(input,init)"));
        assertFalse(normalized.contains("target:\"{subdomain}.googlevideo.com\""));
        assertTrue(normalized.contains("patchUrlMappings"));
        assertFalse(normalized.contains("http://"));
    }

    @Test
    void googleVideoRelayAcceptsOnlySignedFixedMediaTargets() {
        assertNotNull(WatchPartyService.googleVideoTarget(java.net.URI.create(
                "/googlevideo/rr1---sn-example/videoplayback?expire=123&id=o-test&sig=abc")));
        assertNotNull(WatchPartyService.googleVideoTarget(java.net.URI.create(
                "/googlevideo/rr1---sn-example/videoplayback?expire=123&id=o-test&n=abc")));
        assertNotNull(WatchPartyService.googleVideoTarget(java.net.URI.create(
                "/googlevideo/test/generate_204")));

        assertNull(WatchPartyService.googleVideoTarget(java.net.URI.create(
                "/googlevideo/rr1---sn-example/videoplayback?id=o-test")));
        assertNull(WatchPartyService.googleVideoTarget(java.net.URI.create(
                "/googlevideo/evil.example/videoplayback?expire=123&id=o-test&sig=abc")));
        assertNull(WatchPartyService.googleVideoTarget(java.net.URI.create(
                "/googlevideo/test/../admin?expire=123&id=o-test&sig=abc")));
    }

    @Test
    void launchCallbackUsesOfficialInteractionEndpointAndPost() {
        HttpRequest request = ActivityLaunchService.callbackRequest(
                123456789012345678L, "abcdefghij.ABCDEFGHIJ_1234567890");

        assertEquals("POST", request.method());
        assertEquals("https", request.uri().getScheme());
        assertEquals("discord.com", request.uri().getHost());
        assertEquals("/api/v10/interactions/123456789012345678/abcdefghij.ABCDEFGHIJ_1234567890/callback",
                request.uri().getPath());
        assertEquals("application/json", request.headers().firstValue("Content-Type").orElseThrow());
        assertTrue(request.bodyPublisher().isPresent());
        assertEquals(11, request.bodyPublisher().orElseThrow().contentLength());
    }

    @Test
    void launchCallbackRejectsInvalidIdentifiers() {
        assertThrows(IllegalArgumentException.class,
                () -> ActivityLaunchService.callbackRequest(0, "abcdefghij"));
        assertThrows(IllegalArgumentException.class,
                () -> ActivityLaunchService.callbackRequest(123, "bad/token"));
    }

    @Test
    void entryPointCommandUsesDiscordManagedActivityLaunchEndpoint() {
        HttpRequest request = ActivityLaunchService.entryPointRequest(
                "123456789012345678", "abcdefghijklmnopqrstuvwxyz.1234567890");

        assertEquals("POST", request.method());
        assertEquals("https://discord.com/api/v10/applications/123456789012345678/commands",
                request.uri().toString());
        assertEquals("Bot abcdefghijklmnopqrstuvwxyz.1234567890",
                request.headers().firstValue("Authorization").orElseThrow());
        assertTrue(request.bodyPublisher().orElseThrow().contentLength() > 80);
    }
}
