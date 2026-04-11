package me.justindevb.replay.util.storage;

import com.google.gson.Gson;
import me.justindevb.replay.Replay;
import me.justindevb.replay.util.ReplayCompressor;
import me.justindevb.replay.util.VersionUtil;

import javax.sql.DataSource;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class MySQLReplayStorage implements ReplayStorage {

    private final DataSource dataSource;
    private final Gson gson = new Gson();
    private final Replay replay;

    public MySQLReplayStorage(DataSource dataSource, Replay replay) {
        this.dataSource = dataSource;
        this.replay = replay;
        init();
    }

    /** Returns true when the plugin config has compression enabled (default: true). */
    private boolean isCompressionEnabled() {
        return replay.getConfig().getBoolean("General.Compress-Replays", true);
    }

    private void init() {
        replay.getFoliaLib().getScheduler().runAsync(task -> {
            try (Connection conn = dataSource.getConnection();
                 Statement stmt = conn.createStatement()) {

                stmt.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS replays (
                        name VARCHAR(64) PRIMARY KEY,
                        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                        data MEDIUMBLOB NOT NULL
                    )
                """);

            } catch (SQLException e) {
                replay.getLogger().severe("Failed to init replay table");
                e.printStackTrace();
            }
        });
    }


    @Override
    public CompletableFuture<Void> saveReplay(String name, List<?> timeline) {
        return CompletableFuture.runAsync(() -> {
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement("""
                 INSERT INTO replays (name, data)
                 VALUES (?, ?)
                 ON DUPLICATE KEY UPDATE data = VALUES(data)
             """)) {

                String json = VersionUtil.wrapTimeline(gson, timeline, replay.getDescription().getVersion());
                byte[] data = isCompressionEnabled()
                        ? ReplayCompressor.compress(json)
                        : json.getBytes(StandardCharsets.UTF_8);

                ps.setString(1, name);
                ps.setBytes(2, data);
                ps.executeUpdate();

            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }


    @Override
    public CompletableFuture<List<?>> loadReplay(String name) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                         "SELECT data FROM replays WHERE name=?"
                 )) {

                ps.setString(1, name);

                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return null;
                    // Auto-detect compression so legacy uncompressed rows still load
                    String json = ReplayCompressor.decompressIfNeeded(rs.getBytes("data"));
                    return VersionUtil.parseReplayJson(gson, json, replay.getDescription().getVersion());
                }

            } catch (Exception e) {
                throw new RuntimeException("Failed to load replay: " + name, e);
            }
        });
    }


    @Override
    public CompletableFuture<Boolean> replayExists(String name) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                         "SELECT 1 FROM replays WHERE name=? LIMIT 1"
                 )) {

                ps.setString(1, name);

                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }

            } catch (SQLException e) {
                e.printStackTrace();
                return false;
            }
        });
    }


    @Override
    public CompletableFuture<Boolean> deleteReplay(String name) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                         "DELETE FROM replays WHERE name=?"
                 )) {

                ps.setString(1, name);
                int affected = ps.executeUpdate();
                return affected > 0;

            } catch (Exception e) {
                throw new RuntimeException("Failed to delete replay: " + name, e);
            }
        });
    }


    @Override
    public CompletableFuture<List<String>> listReplays() {
        return CompletableFuture.supplyAsync(() -> {
            List<String> names = new ArrayList<>();
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                         "SELECT name FROM replays ORDER BY created_at DESC"
                 );
                 ResultSet rs = ps.executeQuery()) {

                while (rs.next()) {
                    names.add(rs.getString("name"));
                }
                return names;

            } catch (Exception e) {
                throw new RuntimeException("Failed to list replays", e);
            }
        });
    }

    @Override
    public CompletableFuture<File> getReplayFile(String name) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement("SELECT data FROM replays WHERE name=?")) {

                ps.setString(1, name);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next())
                        return null;

                    // Auto-detect compression; works for both compressed and plain rows
                    String json = ReplayCompressor.decompressIfNeeded(rs.getBytes("data"));
                    List<?> timeline = VersionUtil.parseReplayJson(gson, json, replay.getDescription().getVersion());

                    File tempFile = File.createTempFile("replay_" + name + "_", ".json");
                    tempFile.deleteOnExit();
                    try (FileWriter writer = new FileWriter(tempFile)) {
                        gson.toJson(timeline, writer);
                    }
                    return tempFile;
                }
            } catch (Exception e) {
                e.printStackTrace();
                return null;
            }
        });
    }
}
