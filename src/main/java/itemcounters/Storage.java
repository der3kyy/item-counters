package itemcounters;

import com.google.gson.Gson;
import itemcounters.core.*;
import java.io.*;
import java.nio.file.*;
import java.math.BigDecimal;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.logging.Logger;

/** Single SQL writer. Snapshots replace five owned tables in one transaction. */
public final class Storage implements AutoCloseable {
    private final StorageConfig config;
    private final Logger logger;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(task -> new Thread(task, "ItemCounters-storage"));
    private final AtomicReference<Ledger.State> pending = new AtomicReference<>();
    private final AtomicBoolean scheduled = new AtomicBoolean();
    private volatile boolean closed;
    public Storage(Path file, Logger logger) { this(StorageConfig.sqlite(file), logger); }
    public Storage(StorageConfig config, Logger logger) { this.config = config; this.logger = logger; }
    private Connection connect() throws Exception {
        if (config.type().equals("sqlite")) {
            Files.createDirectories(config.sqliteFile().toAbsolutePath().getParent());
            Class.forName("org.sqlite.JDBC");
            Connection connection = DriverManager.getConnection("jdbc:sqlite:" + config.sqliteFile().toAbsolutePath());
            try (Statement sql = connection.createStatement()) {
                sql.execute("PRAGMA busy_timeout=10000"); sql.execute("PRAGMA journal_mode=WAL"); sql.execute("PRAGMA synchronous=FULL");
            } catch (Exception error) { connection.close(); throw error; }
            return connection;
        }
        Class.forName("org.mariadb.jdbc.Driver");
        Properties properties = new Properties();
        properties.setProperty("user", config.username()); properties.setProperty("password", config.password());
        properties.setProperty("sslMode", config.sslMode()); properties.setProperty("connectTimeout", "10000"); properties.setProperty("socketTimeout", "30000");
        String host = config.host().contains(":") ? "[" + config.host() + "]" : config.host();
        return DriverManager.getConnection("jdbc:mariadb://" + host + ":" + config.port() + "/" + config.database(), properties);
    }
    private void schema(Connection connection) throws SQLException {
        String engine = config.type().equals("mysql") ? " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4" : "";
        try (Statement sql = connection.createStatement()) {
            sql.execute("CREATE TABLE IF NOT EXISTS ic_meta (id INTEGER PRIMARY KEY, schema_version INTEGER NOT NULL, sequence_value BIGINT NOT NULL)" + engine);
            sql.execute("CREATE TABLE IF NOT EXISTS ic_players (uuid VARCHAR(36) PRIMARY KEY, player_name VARCHAR(64) NOT NULL)" + engine);
            sql.execute("CREATE TABLE IF NOT EXISTS ic_scores (bucket VARCHAR(100) NOT NULL, uuid VARCHAR(36) NOT NULL, score TEXT NOT NULL, PRIMARY KEY (bucket, uuid))" + engine);
            sql.execute("CREATE TABLE IF NOT EXISTS ic_pending (item_uuid VARCHAR(36) NOT NULL, sequence_value BIGINT NOT NULL PRIMARY KEY, actor_uuid VARCHAR(36) NOT NULL, player_name VARCHAR(64) NOT NULL, stat VARCHAR(32) NOT NULL, amount BIGINT NOT NULL)" + engine);
            sql.execute("CREATE TABLE IF NOT EXISTS ic_rewards (position_value BIGINT PRIMARY KEY, actor_uuid VARCHAR(36) NOT NULL, player_name VARCHAR(64) NOT NULL, command_value TEXT NOT NULL)" + engine);
        }
    }
    public Ledger.State load() throws IOException {
        try (Connection connection = connect()) {
            schema(connection); Ledger.State result = read(connection);
            if (result != null || config.legacyFile() == null || !Files.exists(config.legacyFile())) return result;
            Ledger.State legacy;
            try (Reader reader = Files.newBufferedReader(config.legacyFile())) { legacy = new Gson().fromJson(reader, Ledger.State.class); }
            Ledger validator = new Ledger(new Periods(java.time.ZoneId.of("UTC"), java.time.DayOfWeek.MONDAY));
            validator.restore(legacy);
            Path backup = config.legacyFile().resolveSibling(config.legacyFile().getFileName() + ".pre-sql.bak");
            if (!Files.exists(backup)) Files.copy(config.legacyFile(), backup);
            write(connection, validator.snapshot()); Ledger.State verified = read(connection);
            if (!validator.snapshot().equals(verified)) throw new IOException("SQL import verification failed");
            logger.info("Imported legacy statistics into " + config.type() + "; original and backup retained.");
            return verified;
        } catch (Exception error) { throw new IOException("Could not load " + config.type() + " statistics", error); }
    }
    private Ledger.State read(Connection connection) throws SQLException {
        int version; long sequence;
        try (Statement sql = connection.createStatement(); ResultSet rows = sql.executeQuery("SELECT schema_version, sequence_value FROM ic_meta WHERE id=1")) {
            if (!rows.next()) return null; version = rows.getInt(1); sequence = rows.getLong(2);
        }
        Map<String, String> names = new HashMap<>(); Map<String, Map<String, BigDecimal>> scores = new HashMap<>();
        Map<String, List<Ledger.Credit>> credits = new HashMap<>(); List<Ledger.Reward> rewards = new ArrayList<>();
        try (Statement sql = connection.createStatement()) {
            try (ResultSet rows = sql.executeQuery("SELECT uuid, player_name FROM ic_players")) { while (rows.next()) names.put(rows.getString(1), rows.getString(2)); }
            try (ResultSet rows = sql.executeQuery("SELECT bucket, uuid, score FROM ic_scores")) { while (rows.next()) scores.computeIfAbsent(rows.getString(1), unused -> new HashMap<>()).put(rows.getString(2), new BigDecimal(rows.getString(3))); }
            try (ResultSet rows = sql.executeQuery("SELECT item_uuid, sequence_value, actor_uuid, player_name, stat, amount FROM ic_pending ORDER BY sequence_value")) {
                while (rows.next()) credits.computeIfAbsent(rows.getString(1), unused -> new ArrayList<>()).add(new Ledger.Credit(rows.getLong(2), UUID.fromString(rows.getString(3)), rows.getString(4), rows.getString(5), rows.getLong(6)));
            }
            try (ResultSet rows = sql.executeQuery("SELECT actor_uuid, player_name, command_value FROM ic_rewards ORDER BY position_value")) {
                while (rows.next()) rewards.add(new Ledger.Reward(UUID.fromString(rows.getString(1)), rows.getString(2), rows.getString(3)));
            }
        }
        return new Ledger.State(version, sequence, names, scores, credits, rewards);
    }
    public void save(Ledger.State snapshot) {
        if (closed) throw new IllegalStateException("Storage is closed"); pending.set(snapshot); schedule();
    }
    private void schedule() {
        if (!scheduled.compareAndSet(false, true)) return;
        executor.execute(() -> {
            boolean failed = false;
            try {
                Ledger.State snapshot;
                while ((snapshot = pending.getAndSet(null)) != null) {
                    try (Connection connection = connect()) { write(connection, snapshot); }
                    catch (Exception error) { pending.compareAndSet(null, snapshot); failed = true;
                        logger.log(java.util.logging.Level.SEVERE, "SQL write failed; snapshot retained for next flush", error); break; }
                }
            } finally {
                scheduled.set(false);
                if (!failed && pending.get() != null && !closed) schedule();
            }
        });
    }
    private void write(Connection connection, Ledger.State snapshot) throws SQLException {
        connection.setAutoCommit(false);
        try {
            try (Statement sql = connection.createStatement()) {
                for (String table : List.of("ic_scores", "ic_players", "ic_pending", "ic_rewards", "ic_meta")) sql.executeUpdate("DELETE FROM " + table);
            }
            try (PreparedStatement sql = connection.prepareStatement("INSERT INTO ic_meta VALUES (1, ?, ?)")) { sql.setInt(1, snapshot.schema()); sql.setLong(2, snapshot.sequence()); sql.executeUpdate(); }
            try (PreparedStatement sql = connection.prepareStatement("INSERT INTO ic_players VALUES (?, ?)")) {
                for (var name : snapshot.names().entrySet()) { sql.setString(1, name.getKey()); sql.setString(2, name.getValue()); sql.addBatch(); } sql.executeBatch();
            }
            try (PreparedStatement sql = connection.prepareStatement("INSERT INTO ic_scores VALUES (?, ?, ?)")) {
                for (var bucket : snapshot.scores().entrySet()) for (var score : bucket.getValue().entrySet()) { sql.setString(1, bucket.getKey()); sql.setString(2, score.getKey()); sql.setString(3, score.getValue().toString()); sql.addBatch(); } sql.executeBatch();
            }
            try (PreparedStatement sql = connection.prepareStatement("INSERT INTO ic_pending VALUES (?, ?, ?, ?, ?, ?)")) {
                for (var item : snapshot.pending().entrySet()) for (var credit : item.getValue()) {
                    sql.setString(1, item.getKey()); sql.setLong(2, credit.sequence()); sql.setString(3, credit.actor().toString()); sql.setString(4, credit.name()); sql.setString(5, credit.stat()); sql.setLong(6, credit.amount()); sql.addBatch();
                } sql.executeBatch();
            }
            try (PreparedStatement sql = connection.prepareStatement("INSERT INTO ic_rewards VALUES (?, ?, ?, ?)")) {
                long position = 0;
                for (var reward : snapshot.rewards()) { sql.setLong(1, ++position); sql.setString(2, reward.actor().toString()); sql.setString(3, reward.name()); sql.setString(4, reward.command()); sql.addBatch(); } sql.executeBatch();
            }
            connection.commit();
        } catch (SQLException error) { connection.rollback(); throw error; }
        finally { connection.setAutoCommit(true); }
    }
    @Override public void close() {
        closed = true;
        executor.execute(() -> {
            Ledger.State snapshot = pending.getAndSet(null);
            if (snapshot != null) try (Connection connection = connect()) { write(connection, snapshot); }
            catch (Exception error) { pending.set(snapshot); logger.log(java.util.logging.Level.SEVERE, "Final SQL flush failed", error); }
        });
        executor.shutdown();
        try {
            if (!executor.awaitTermination(40, TimeUnit.SECONDS)) { logger.severe("SQL flush timed out"); executor.shutdownNow(); }
            if (pending.get() != null) logger.severe("Storage shutdown could not persist the last snapshot");
        } catch (InterruptedException error) { Thread.currentThread().interrupt(); executor.shutdownNow(); }
    }
}
