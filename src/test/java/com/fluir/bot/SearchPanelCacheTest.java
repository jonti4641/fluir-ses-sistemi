package com.fluir.bot;

import com.fluir.bot.audio.SearchPanelCache;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SearchPanelCacheTest {

    @BeforeEach
    void setUp() {
        SearchPanelCache.clear();
    }

    @Test
    @DisplayName("Giriş yapılmış arama paneli başarıyla getirilip tek seferde silinmelidir")
    void testSearchPanelPutAndGetSingleUse() {
        String customId = "song_select:test1234";
        long userId = 111L;
        long guildId = 222L;
        long channelId = 333L;

        SearchPanelCache.put(customId, userId, guildId, channelId, List.of());

        // Doğru kullanıcı çağırıyor
        SearchPanelCache.SearchPanelResult result = SearchPanelCache.getAndRemove(customId, userId);
        assertEquals(SearchPanelCache.SearchPanelStatus.SUCCESS, result.status());

        // İkinci kez çağrıldığında tek kullanımlık olduğu için EXPIRED_OR_NOT_FOUND dönmeli
        SearchPanelCache.SearchPanelResult result2 = SearchPanelCache.getAndRemove(customId, userId);
        assertEquals(SearchPanelCache.SearchPanelStatus.EXPIRED_OR_NOT_FOUND, result2.status());
    }

    @Test
    @DisplayName("Farklı bir kullanıcı aynı paneli seçmeye çalıştığında UNAUTHORIZED dönmelidir")
    void testSearchPanelUnauthorizedUser() {
        String customId = "song_select:test5678";
        long ownerUserId = 111L;
        long intruderUserId = 999L;

        SearchPanelCache.put(customId, ownerUserId, 222L, 333L, List.of());

        SearchPanelCache.SearchPanelResult result = SearchPanelCache.getAndRemove(customId, intruderUserId);
        assertEquals(SearchPanelCache.SearchPanelStatus.UNAUTHORIZED, result.status());
    }
}
