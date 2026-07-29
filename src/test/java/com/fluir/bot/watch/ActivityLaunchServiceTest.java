package com.fluir.bot.watch;

import org.junit.jupiter.api.Test;

import java.net.http.HttpRequest;

import static org.junit.jupiter.api.Assertions.*;

class ActivityLaunchServiceTest {
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
}
