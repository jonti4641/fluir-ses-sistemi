package com.fluir.bot;

import com.fluir.bot.persistence.GuildSettings;
import com.fluir.bot.persistence.PersistentStore;
import com.fluir.bot.persistence.StoredTrack;
import com.fluir.bot.security.CommandRateLimiter;
import com.fluir.bot.security.MediaInputPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class SecurityAndPersistenceTest {
    @TempDir Path temp;

    @Test void blocksSsrfAndLocalFiles(){
        assertFalse(MediaInputPolicy.validate("http://127.0.0.1/admin",300).allowed());
        assertFalse(MediaInputPolicy.validate("file:///etc/passwd",300).allowed());
        assertFalse(MediaInputPolicy.validate("https://169.254.169.254/latest/meta-data",300).allowed());
        assertTrue(MediaInputPolicy.validate("https://soundcloud.com/artist/track",300).allowed());
        assertTrue(MediaInputPolicy.validate("şarkı adı",300).allowed());
    }

    @Test void rateLimiterIsolatedByGuildAndUser(){
        CommandRateLimiter limiter=new CommandRateLimiter(2,60_000);
        assertTrue(limiter.allow(1,1));assertTrue(limiter.allow(1,1));assertFalse(limiter.allow(1,1));
        assertTrue(limiter.allow(2,1));assertTrue(limiter.allow(1,2));
    }

    @Test void persistsSettingsFavoritesHistoryAndQueue(){
        try(PersistentStore db=new PersistentStore(temp)){
            GuildSettings settings=new GuildSettings(7,120,45,25,true,false,false,9,11);db.saveSettings(settings);assertEquals(settings,db.settings(7));
            StoredTrack t=new StoredTrack("https://soundcloud.com/a/b","B","A",120000,"B",42,10);
            assertTrue(db.addFavorite(7,42,t));assertFalse(db.addFavorite(7,42,t));assertEquals(1,db.favorites(7,42,10).size());
            db.addHistory(7,t);assertEquals("B",db.history(7,10).getFirst().title());
            db.saveQueue(7,java.util.List.of(t));assertEquals(t,db.queue(7,10).getFirst());
            assertTrue(db.createPlaylist(7,42,"Gece"));assertTrue(db.addPlaylistTrack(7,"Gece",t));assertEquals(1,db.playlist(7,"Gece",10).size());
        }
    }
}
