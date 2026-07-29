package com.fluir.bot.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/** Hazırlanmış sorgular kullanan, WAL etkin SQLite veri katmanı. */
public final class PersistentStore implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(PersistentStore.class);
    private final String jdbcUrl;
    private volatile boolean healthy;

    public PersistentStore(Path dataDirectory) {
        try {
            Files.createDirectories(dataDirectory);
            this.jdbcUrl = "jdbc:sqlite:" + dataDirectory.resolve("fluir.db").toAbsolutePath();
            migrate();
            healthy = true;
        } catch (Exception e) {
            throw new IllegalStateException("Veritabanı başlatılamadı", e);
        }
    }

    private Connection connection() throws SQLException {
        Connection connection = DriverManager.getConnection(jdbcUrl);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys=ON");
            statement.execute("PRAGMA busy_timeout=5000");
        }
        return connection;
    }

    private void migrate() throws SQLException {
        try (Connection c = connection(); Statement s = c.createStatement()) {
            s.execute("PRAGMA journal_mode=WAL");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS guild_settings (guild_id INTEGER PRIMARY KEY, default_volume INTEGER NOT NULL, idle_seconds INTEGER NOT NULL, max_queue_size INTEGER NOT NULL, autoplay INTEGER NOT NULL, announcements INTEGER NOT NULL, prefix_commands INTEGER NOT NULL, dj_role_id INTEGER NOT NULL, command_channel_id INTEGER NOT NULL)");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS favorites (id INTEGER PRIMARY KEY AUTOINCREMENT, guild_id INTEGER NOT NULL, user_id INTEGER NOT NULL, uri TEXT NOT NULL, title TEXT NOT NULL, author TEXT NOT NULL, duration_ms INTEGER NOT NULL, created_at INTEGER NOT NULL, UNIQUE(guild_id,user_id,uri))");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS history (id INTEGER PRIMARY KEY AUTOINCREMENT, guild_id INTEGER NOT NULL, uri TEXT NOT NULL, title TEXT NOT NULL, author TEXT NOT NULL, duration_ms INTEGER NOT NULL, requested_by INTEGER NOT NULL, played_at INTEGER NOT NULL)");
            s.executeUpdate("CREATE INDEX IF NOT EXISTS idx_history_guild ON history(guild_id, played_at DESC)");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS playlists (id INTEGER PRIMARY KEY AUTOINCREMENT, guild_id INTEGER NOT NULL, owner_id INTEGER NOT NULL, name TEXT NOT NULL COLLATE NOCASE, created_at INTEGER NOT NULL, UNIQUE(guild_id,name))");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS playlist_tracks (id INTEGER PRIMARY KEY AUTOINCREMENT, playlist_id INTEGER NOT NULL REFERENCES playlists(id) ON DELETE CASCADE, position INTEGER NOT NULL, uri TEXT NOT NULL, title TEXT NOT NULL, author TEXT NOT NULL, duration_ms INTEGER NOT NULL)");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS queue_snapshot (guild_id INTEGER NOT NULL, position INTEGER NOT NULL, uri TEXT NOT NULL, title TEXT NOT NULL, author TEXT NOT NULL, duration_ms INTEGER NOT NULL, original_query TEXT NOT NULL, requested_by INTEGER NOT NULL, message_channel_id INTEGER NOT NULL, PRIMARY KEY(guild_id,position))");
        }
    }

    public synchronized GuildSettings settings(long guildId) {
        String sql = "SELECT * FROM guild_settings WHERE guild_id=?";
        try (Connection c = connection(); PreparedStatement p = c.prepareStatement(sql)) {
            p.setLong(1, guildId);
            try (ResultSet r = p.executeQuery()) {
                if (r.next()) return readSettings(r);
            }
            GuildSettings defaults = GuildSettings.defaults(guildId);
            saveSettings(defaults);
            return defaults;
        } catch (SQLException e) {
            fail(e);
            return GuildSettings.defaults(guildId);
        }
    }

    public synchronized void saveSettings(GuildSettings value) {
        GuildSettings s = value.normalized();
        String sql = "INSERT INTO guild_settings VALUES(?,?,?,?,?,?,?,?,?) ON CONFLICT(guild_id) DO UPDATE SET default_volume=excluded.default_volume,idle_seconds=excluded.idle_seconds,max_queue_size=excluded.max_queue_size,autoplay=excluded.autoplay,announcements=excluded.announcements,prefix_commands=excluded.prefix_commands,dj_role_id=excluded.dj_role_id,command_channel_id=excluded.command_channel_id";
        try (Connection c = connection(); PreparedStatement p = c.prepareStatement(sql)) {
            p.setLong(1,s.guildId()); p.setInt(2,s.defaultVolume()); p.setInt(3,s.idleSeconds()); p.setInt(4,s.maxQueueSize());
            p.setBoolean(5,s.autoplay()); p.setBoolean(6,s.announcements()); p.setBoolean(7,s.prefixCommands()); p.setLong(8,s.djRoleId()); p.setLong(9,s.commandChannelId());
            p.executeUpdate(); healthy = true;
        } catch (SQLException e) { fail(e); }
    }

    public synchronized boolean addFavorite(long guildId, long userId, StoredTrack t) {
        String sql = "INSERT OR IGNORE INTO favorites(guild_id,user_id,uri,title,author,duration_ms,created_at) VALUES(?,?,?,?,?,?,?)";
        try (Connection c=connection(); PreparedStatement p=c.prepareStatement(sql)) {
            p.setLong(1,guildId); p.setLong(2,userId); bindTrack(p,3,t); p.setLong(7,System.currentTimeMillis());
            return p.executeUpdate()>0;
        } catch (SQLException e) { fail(e); return false; }
    }

    public synchronized List<StoredTrack> favorites(long guildId, long userId, int limit) {
        return readTracks("SELECT uri,title,author,duration_ms FROM favorites WHERE guild_id=? AND user_id=? ORDER BY id DESC LIMIT ?", guildId,userId,limit);
    }

    public synchronized boolean removeFavorite(long guildId, long userId, int oneBasedIndex) {
        List<Long> ids = ids("SELECT id FROM favorites WHERE guild_id=? AND user_id=? ORDER BY id DESC", guildId,userId,Math.max(1,oneBasedIndex));
        if (oneBasedIndex<1 || ids.size()<oneBasedIndex) return false;
        return deleteById("DELETE FROM favorites WHERE id=?", ids.get(oneBasedIndex-1));
    }

    public synchronized void addHistory(long guildId, StoredTrack t) {
        String sql="INSERT INTO history(guild_id,uri,title,author,duration_ms,requested_by,played_at) VALUES(?,?,?,?,?,?,?)";
        try(Connection c=connection(); PreparedStatement p=c.prepareStatement(sql)) {
            p.setLong(1,guildId); bindTrack(p,2,t); p.setLong(6,t.requestedBy()); p.setLong(7,System.currentTimeMillis()); p.executeUpdate();
            try(PreparedStatement trim=c.prepareStatement("DELETE FROM history WHERE guild_id=? AND id NOT IN (SELECT id FROM history WHERE guild_id=? ORDER BY played_at DESC LIMIT 200)")) { trim.setLong(1,guildId); trim.setLong(2,guildId); trim.executeUpdate(); }
        } catch(SQLException e){ fail(e); }
    }

    public synchronized List<StoredTrack> history(long guildId, int limit) {
        return readTracks("SELECT uri,title,author,duration_ms FROM history WHERE guild_id=? ORDER BY played_at DESC LIMIT ?", guildId,0,limit);
    }

    public synchronized boolean createPlaylist(long guildId,long ownerId,String name){
        try(Connection c=connection();PreparedStatement p=c.prepareStatement("INSERT OR IGNORE INTO playlists(guild_id,owner_id,name,created_at) VALUES(?,?,?,?)")){p.setLong(1,guildId);p.setLong(2,ownerId);p.setString(3,safeName(name));p.setLong(4,System.currentTimeMillis());return p.executeUpdate()>0;}catch(SQLException e){fail(e);return false;}
    }

    public synchronized boolean addPlaylistTrack(long guildId,String name,StoredTrack t){
        String sql="INSERT INTO playlist_tracks(playlist_id,position,uri,title,author,duration_ms) SELECT id,COALESCE((SELECT MAX(position)+1 FROM playlist_tracks WHERE playlist_id=playlists.id),0),?,?,?,? FROM playlists WHERE guild_id=? AND name=? COLLATE NOCASE";
        try(Connection c=connection();PreparedStatement p=c.prepareStatement(sql)){p.setString(1,t.uri());p.setString(2,t.title());p.setString(3,t.author());p.setLong(4,t.durationMs());p.setLong(5,guildId);p.setString(6,safeName(name));return p.executeUpdate()>0;}catch(SQLException e){fail(e);return false;}
    }

    public synchronized List<StoredTrack> playlist(long guildId,String name,int limit){
        String sql="SELECT pt.uri,pt.title,pt.author,pt.duration_ms FROM playlist_tracks pt JOIN playlists p ON p.id=pt.playlist_id WHERE p.guild_id=? AND p.name=? COLLATE NOCASE ORDER BY pt.position LIMIT ?";
        List<StoredTrack> out=new ArrayList<>();try(Connection c=connection();PreparedStatement p=c.prepareStatement(sql)){p.setLong(1,guildId);p.setString(2,safeName(name));p.setInt(3,Math.max(1,Math.min(limit,500)));try(ResultSet r=p.executeQuery()){while(r.next())out.add(track(r));}}catch(SQLException e){fail(e);}return out;
    }

    public synchronized List<String> playlists(long guildId,int limit){
        List<String> out=new ArrayList<>();try(Connection c=connection();PreparedStatement p=c.prepareStatement("SELECT name FROM playlists WHERE guild_id=? ORDER BY name LIMIT ?")){p.setLong(1,guildId);p.setInt(2,Math.max(1,Math.min(limit,100)));try(ResultSet r=p.executeQuery()){while(r.next())out.add(r.getString(1));}}catch(SQLException e){fail(e);}return out;
    }

    public synchronized void saveQueue(long guildId,List<StoredTrack> tracks){
        try(Connection c=connection()){c.setAutoCommit(false);try(PreparedStatement d=c.prepareStatement("DELETE FROM queue_snapshot WHERE guild_id=?")){d.setLong(1,guildId);d.executeUpdate();}try(PreparedStatement p=c.prepareStatement("INSERT INTO queue_snapshot VALUES(?,?,?,?,?,?,?,?,?)")){int i=0;for(StoredTrack t:tracks){p.setLong(1,guildId);p.setInt(2,i++);p.setString(3,t.uri());p.setString(4,t.title());p.setString(5,t.author());p.setLong(6,t.durationMs());p.setString(7,t.originalQuery()==null?"":t.originalQuery());p.setLong(8,t.requestedBy());p.setLong(9,t.messageChannelId());p.addBatch();}p.executeBatch();}c.commit();}catch(SQLException e){fail(e);}
    }

    public synchronized List<StoredTrack> queue(long guildId,int limit){
        String sql="SELECT uri,title,author,duration_ms,original_query,requested_by,message_channel_id FROM queue_snapshot WHERE guild_id=? ORDER BY position LIMIT ?";List<StoredTrack> out=new ArrayList<>();try(Connection c=connection();PreparedStatement p=c.prepareStatement(sql)){p.setLong(1,guildId);p.setInt(2,Math.max(1,Math.min(limit,500)));try(ResultSet r=p.executeQuery()){while(r.next())out.add(new StoredTrack(r.getString(1),r.getString(2),r.getString(3),r.getLong(4),r.getString(5),r.getLong(6),r.getLong(7)));}}catch(SQLException e){fail(e);}return out;
    }

    private List<StoredTrack> readTracks(String sql,long guildId,long userId,int limit){List<StoredTrack> out=new ArrayList<>();try(Connection c=connection();PreparedStatement p=c.prepareStatement(sql)){p.setLong(1,guildId);int index=2;if(userId!=0)p.setLong(index++,userId);p.setInt(index,Math.max(1,Math.min(limit,200)));try(ResultSet r=p.executeQuery()){while(r.next())out.add(track(r));}}catch(SQLException e){fail(e);}return out;}
    private static StoredTrack track(ResultSet r)throws SQLException{return new StoredTrack(r.getString(1),r.getString(2),r.getString(3),r.getLong(4),r.getString(2),0,0);}
    private static void bindTrack(PreparedStatement p,int start,StoredTrack t)throws SQLException{p.setString(start,t.uri());p.setString(start+1,t.title());p.setString(start+2,t.author());p.setLong(start+3,t.durationMs());}
    private List<Long> ids(String sql,long guildId,long userId,int limit){List<Long> out=new ArrayList<>();try(Connection c=connection();PreparedStatement p=c.prepareStatement(sql)){p.setLong(1,guildId);p.setLong(2,userId);try(ResultSet r=p.executeQuery()){while(r.next()&&out.size()<limit)out.add(r.getLong(1));}}catch(SQLException e){fail(e);}return out;}
    private boolean deleteById(String sql,long id){try(Connection c=connection();PreparedStatement p=c.prepareStatement(sql)){p.setLong(1,id);return p.executeUpdate()>0;}catch(SQLException e){fail(e);return false;}}
    private static String safeName(String value){String s=value==null?"":value.strip().replaceAll("[\\p{Cntrl}]","");return s.length()>40?s.substring(0,40):s;}
    private static GuildSettings readSettings(ResultSet r)throws SQLException{return new GuildSettings(r.getLong("guild_id"),r.getInt("default_volume"),r.getInt("idle_seconds"),r.getInt("max_queue_size"),r.getBoolean("autoplay"),r.getBoolean("announcements"),r.getBoolean("prefix_commands"),r.getLong("dj_role_id"),r.getLong("command_channel_id"));}
    private void fail(SQLException e){healthy=false;logger.error("Veritabanı işlemi başarısız: SQLState={}",e.getSQLState());}
    public boolean isHealthy(){return healthy;}
    @Override public void close(){healthy=false;}
}
