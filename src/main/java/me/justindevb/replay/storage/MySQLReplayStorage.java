package me.justindevb.replay.storage;

import me.justindevb.replay.Replay;
import me.justindevb.replay.api.ReplayExportQuery;
import me.justindevb.replay.debug.ReplayDumpQuery;
import me.justindevb.replay.recording.TimelineEvent;
import me.justindevb.replay.storage.binary.BinaryReplayStorageCodec;
import me.justindevb.replay.util.io.ReplayCompressor;

import javax.sql.DataSource;
import java.io.*;
import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public class MySQLReplayStorage implements ReplayStorage {

    private final DataSource dataSource;
    private final Replay replay;
    private final ReplayStorageCodec saveCodec;
    private final ReplayFormatDetector formatDetector;
    private final ReplayExporter replayExporter;
    private final ReplayDumpWriter replayDumpWriter;

    public MySQLReplayStorage(DataSource dataSource, Replay replay) {
        this(dataSource, replay, new BinaryReplayStorageCodec(), defaultFormatDetector());
    }

    private static ReplayFormatDetector defaultFormatDetector() {
        return new DefaultReplayFormatDetector(List.of(new JsonReplayStorageCodec(), new BinaryReplayStorageCodec()));
    }

    MySQLReplayStorage(DataSource dataSource, Replay replay, ReplayStorageCodec saveCodec, ReplayFormatDetector formatDetector) {
        this.dataSource = dataSource;
        this.replay = replay;
        this.saveCodec = saveCodec;
        this.formatDetector = formatDetector;
        this.replayExporter = new ReplayExporter(new File(replay.getDataFolder(), "exports"));
        this.replayDumpWriter = new ReplayDumpWriter(new File(replay.getDataFolder(), "dumps"));
        init();
    }

    private boolean usesCodecCompression() {
        return saveCodec.supportsCompression();
    }

    private byte[] encodeForStorage(String name, ReplaySaveRequest request) throws IOException {
        byte[] payload = saveCodec.finalizeReplay(name, request, replay.getPluginMeta().getVersion());
        return usesCodecCompression() ? ReplayCompressor.compress(new String(payload, java.nio.charset.StandardCharsets.UTF_8)) : payload;
    }

    private void init() {
        replay.getFoliaLib().getScheduler().runAsync(task -> {
            try (Connection conn = dataSource.getConnection();
                 Statement stmt = conn.createStatement()) {

                stmt.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS replays (
                        name VARCHAR(64) PRIMARY KEY,
                        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                        is_protected BOOLEAN NOT NULL DEFAULT FALSE,
                        protected_at TIMESTAMP NULL,
                        protected_by VARCHAR(64) NULL,
                        data LONGBLOB NOT NULL
                    )
                """);

                ensureColumnExists(conn, stmt, "is_protected", "BOOLEAN NOT NULL DEFAULT FALSE");
                ensureColumnExists(conn, stmt, "protected_at", "TIMESTAMP NULL");
                ensureColumnExists(conn, stmt, "protected_by", "VARCHAR(64) NULL");
                stmt.executeUpdate("ALTER TABLE replays MODIFY COLUMN data LONGBLOB NOT NULL");

            } catch (SQLException e) {
                replay.getLogger().log(java.util.logging.Level.SEVERE, "Failed to init replay table", e);
            }
        });
    }

    private void ensureColumnExists(Connection conn, Statement stmt, String columnName, String definition) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SHOW COLUMNS FROM replays LIKE ?")) {
            ps.setString(1, columnName);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    stmt.executeUpdate("ALTER TABLE replays ADD COLUMN " + columnName + " " + definition);
                }
            }
        }
    }

    private Optional<Boolean> getProtectionState(Connection conn, String name) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT is_protected FROM replays WHERE name=? LIMIT 1"
        )) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(rs.getBoolean("is_protected"));
            }
        }
    }


    @Override
    public CompletableFuture<Void> saveReplay(String name, List<TimelineEvent> timeline) {
        return saveReplay(name, new ReplaySaveRequest(timeline));
    }

    @Override
    public CompletableFuture<Void> saveReplay(String name, ReplaySaveRequest request) {
        return CompletableFuture.runAsync(() -> {
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement("""
                 INSERT INTO replays (name, data)
                 VALUES (?, ?)
                 ON DUPLICATE KEY UPDATE data = VALUES(data)
             """)) {

                byte[] data = encodeForStorage(name, request);

                ps.setString(1, name);
                ps.setBytes(2, data);
                ps.executeUpdate();

            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }


    @Override
    public CompletableFuture<List<TimelineEvent>> loadReplay(String name) {
        return loadReplayData(name).thenApply(data -> data == null ? null : data.timeline());
    }

    @Override
    public CompletableFuture<ReplayPlaybackData> loadReplayData(String name) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                         "SELECT data FROM replays WHERE name=?"
                 )) {

                ps.setString(1, name);

                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return null;
                    byte[] data = rs.getBytes("data");
                    ReplayStorageCodec codec = formatDetector.detectCodec(name, data);
                    return codec.decodeReplayData(data, replay.getPluginMeta().getVersion());
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
                replay.getLogger().log(java.util.logging.Level.SEVERE, "Failed to check replay existence: " + name, e);
                return false;
            }
        });
    }


    @Override
    public CompletableFuture<ReplayDeleteResult> deleteReplay(String name) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                         "DELETE FROM replays WHERE name=?"
                 )) {

                Optional<Boolean> protectionState = getProtectionState(conn, name);
                if (protectionState.isEmpty()) {
                    return ReplayDeleteResult.NOT_FOUND;
                }
                if (protectionState.get()) {
                    return ReplayDeleteResult.PROTECTED;
                }

                ps.setString(1, name);
                int affected = ps.executeUpdate();
                return affected > 0 ? ReplayDeleteResult.DELETED : ReplayDeleteResult.NOT_FOUND;

            } catch (Exception e) {
                throw new RuntimeException("Failed to delete replay: " + name, e);
            }
        });
    }

    @Override
    public CompletableFuture<List<ReplaySummary>> listReplaySummaries() {
        return CompletableFuture.supplyAsync(() -> {
            List<ReplaySummary> summaries = new ArrayList<>();
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                 "SELECT name, created_at, OCTET_LENGTH(data) AS data_size, is_protected, protected_at, protected_by "
                     + "FROM replays ORDER BY created_at DESC"
                 );
                 ResultSet rs = ps.executeQuery()) {

                while (rs.next()) {
                    Timestamp createdAt = rs.getTimestamp("created_at");
                Timestamp protectedAt = rs.getTimestamp("protected_at");
                    summaries.add(new ReplaySummary(
                            rs.getString("name"),
                            createdAt != null ? createdAt.toInstant() : Instant.EPOCH,
                            rs.getLong("data_size"),
                    rs.getBoolean("is_protected"),
                    protectedAt != null ? protectedAt.toInstant() : null,
                    rs.getString("protected_by"),
                            ReplayStorageType.MYSQL));
                }
                return summaries;

            } catch (Exception e) {
                throw new RuntimeException("Failed to list replay summaries", e);
            }
        });
    }

    @Override
    public CompletableFuture<ReplayProtectionResult> protectReplay(String name, Instant protectedAt, String protectedBy) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection conn = dataSource.getConnection()) {
                Optional<Boolean> protectionState = getProtectionState(conn, name);
                if (protectionState.isEmpty()) {
                    return ReplayProtectionResult.NOT_FOUND;
                }
                if (protectionState.get()) {
                    return ReplayProtectionResult.ALREADY_PROTECTED;
                }

                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE replays SET is_protected = TRUE, protected_at = ?, protected_by = ? WHERE name=?"
                )) {
                    ps.setTimestamp(1, Timestamp.from(protectedAt));
                    ps.setString(2, protectedBy);
                    ps.setString(3, name);
                    ps.executeUpdate();
                    return ReplayProtectionResult.UPDATED;
                }
            } catch (Exception e) {
                throw new RuntimeException("Failed to protect replay: " + name, e);
            }
        });
    }

    @Override
    public CompletableFuture<ReplayProtectionResult> unprotectReplay(String name) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection conn = dataSource.getConnection()) {
                Optional<Boolean> protectionState = getProtectionState(conn, name);
                if (protectionState.isEmpty()) {
                    return ReplayProtectionResult.NOT_FOUND;
                }
                if (!protectionState.get()) {
                    return ReplayProtectionResult.ALREADY_UNPROTECTED;
                }

                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE replays SET is_protected = FALSE WHERE name=?"
                )) {
                    ps.setString(1, name);
                    ps.executeUpdate();
                    return ReplayProtectionResult.UPDATED;
                }
            } catch (Exception e) {
                throw new RuntimeException("Failed to unprotect replay: " + name, e);
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

                    byte[] data = rs.getBytes("data");
                    ReplayStorageCodec codec = formatDetector.detectCodec(name, data);
                    return codec.writeReplayFile(name, data, replay.getPluginMeta().getVersion());
                }
            } catch (Exception e) {
                replay.getLogger().log(java.util.logging.Level.SEVERE, "Failed to get replay file: " + name, e);
                return null;
            }
        });
    }

    @Override
    public CompletableFuture<File> getReplayFile(String name, ReplayExportQuery query) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement("SELECT data FROM replays WHERE name=?")) {

                ps.setString(1, name);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return null;
                    }

                    byte[] data = rs.getBytes("data");
                    ReplayStorageCodec codec = formatDetector.detectCodec(name, data);
                    return replayExporter.exportReplay(name, codec.decodeTimeline(data, replay.getPluginMeta().getVersion()), query,
                            replay.getPluginMeta().getVersion());
                }
            } catch (Exception e) {
                replay.getLogger().log(java.util.logging.Level.SEVERE, "Failed to export replay file: " + name, e);
                return null;
            }
        });
    }

    @Override
    public CompletableFuture<ReplayInspection> getReplayInfo(String name) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement("SELECT data FROM replays WHERE name=?")) {

                ps.setString(1, name);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return null;
                    }

                    byte[] data = rs.getBytes("data");
                    ReplayStorageCodec codec = formatDetector.detectCodec(name, data);
                    return codec.inspectReplay(name, data, replay.getPluginMeta().getVersion());
                }
            } catch (Exception e) {
                replay.getLogger().log(java.util.logging.Level.SEVERE, "Failed to inspect replay file: " + name, e);
                return null;
            }
        });
    }

    @Override
    public CompletableFuture<File> getReplayDumpFile(String name, ReplayDumpQuery query) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement("SELECT data FROM replays WHERE name=?")) {

                ps.setString(1, name);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return null;
                    }

                    byte[] data = rs.getBytes("data");
                    ReplayStorageCodec codec = formatDetector.detectCodec(name, data);
                    return replayDumpWriter.writeDump(name, codec.decodeTimeline(data, replay.getPluginMeta().getVersion()), query);
                }
            } catch (Exception e) {
                replay.getLogger().log(java.util.logging.Level.SEVERE, "Failed to dump replay file: " + name, e);
                return null;
            }
        });
    }
}
