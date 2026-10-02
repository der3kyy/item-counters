package itemcounters;

import java.nio.file.Path;
import java.util.*;
import org.bukkit.configuration.file.YamlConfiguration;

public record StorageConfig(String type, Path sqliteFile, String host, int port, String database,
                            String username, String password, String sslMode, Path legacyFile) {
    public static StorageConfig sqlite(Path path) {
        return new StorageConfig("sqlite", path, "", 0, "", "", "", "disable", null);
    }
    public static StorageConfig read(YamlConfiguration config, Path directory) {
        String type = Objects.requireNonNull(config.getString("storage.type")).toLowerCase(Locale.ROOT);
        if (!Set.of("sqlite", "mysql").contains(type)) throw new IllegalArgumentException("storage.type must be sqlite or mysql");
        String filename = config.getString("storage.sqlite-file"), legacy = config.getString("storage.legacy-import");
        if (filename == null || !filename.matches("[A-Za-z0-9_-]+\\.(db|sqlite)")) throw new IllegalArgumentException("Invalid SQLite filename");
        if (legacy == null || !legacy.isEmpty() && !legacy.matches("[A-Za-z0-9_-]+\\.json")) throw new IllegalArgumentException("Invalid legacy import filename");
        String host = config.getString("storage.mysql.host"), database = config.getString("storage.mysql.database");
        int port = config.getInt("storage.mysql.port"); String ssl = config.getString("storage.mysql.ssl-mode");
        if (type.equals("mysql") && (host == null || !host.matches("[A-Za-z0-9_.:-]+") || database == null
                || !database.matches("[A-Za-z0-9_]+") || port < 1 || port > 65535
                || !Set.of("disable", "trust", "verify-ca", "verify-full").contains(ssl))) throw new IllegalArgumentException("Invalid MySQL settings");
        return new StorageConfig(type, directory.resolve(filename), host, port, database,
                config.getString("storage.mysql.username"), config.getString("storage.mysql.password"), ssl,
                legacy.isEmpty() ? null : directory.resolve(legacy));
    }
    @Override public String toString() { return "StorageConfig[type=" + type + "]"; }
}
