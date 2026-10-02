package itemcounters;

import itemcounters.core.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class StorageTest {
    @TempDir Path directory;
    @Test void finalFlushPersistsLatestSnapshotAndPendingCredits() throws Exception {
        Ledger ledger = new Ledger(new Periods(ZoneId.of("UTC"), DayOfWeek.MONDAY));
        UUID player = UUID.randomUUID(), item = UUID.randomUUID();
        Path file = directory.resolve("counters.db");
        try (Storage storage = new Storage(file, Logger.getAnonymousLogger())) {
            assertNull(storage.load());
            for (int index = 0; index < 100; index++) {
                ledger.add(player, "Alice", "blocks", 1L, Instant.EPOCH); storage.save(ledger.snapshot());
            }
            ledger.enqueue(item, player, "Alice", "mob_kills");
            ledger.reward(new Ledger.Reward(player, "Alice", "player:help"));
            storage.save(ledger.snapshot());
        }
        try (Storage storage = new Storage(file, Logger.getAnonymousLogger())) {
            Ledger restored = new Ledger(new Periods(ZoneId.of("UTC"), DayOfWeek.MONDAY)); restored.restore(storage.load());
            assertEquals("100", Ledger.rankings(restored.snapshot(), 100).get("blocks:alltime:alltime").getFirst().value().toPlainString());
            assertEquals(1, restored.pending(item).size()); assertEquals(1, restored.rewards().size());
        }
        assertFalse(Files.exists(directory.resolve("counters.db.tmp")));
    }
    @Test void corruptStorageNeverSilentlyResets() throws Exception {
        Path file = directory.resolve("counters.db"); Files.writeString(file, "invalid-sqlite");
        try (Storage storage = new Storage(file, Logger.getAnonymousLogger())) { assertThrows(java.io.IOException.class, storage::load); }
        assertEquals("invalid-sqlite", Files.readString(file));
    }
    @Test void legacyImportIsValidatedBackedUpAndNeverReplayed() throws Exception {
        Path legacy = directory.resolve("data.json"), db = directory.resolve("counters.db");
        var ledger = new Ledger(new Periods(ZoneId.of("UTC"), DayOfWeek.MONDAY)); UUID player = UUID.randomUUID();
        ledger.add(player, "Игрок", "kills", 7, Instant.EPOCH); ledger.add(player, "Игрок", "armor", new java.math.BigDecimal("0.125"), Instant.EPOCH);
        Files.writeString(legacy, new com.google.gson.Gson().toJson(ledger.snapshot()));
        var config = new StorageConfig("sqlite", db, "", 0, "", "", "", "disable", legacy);
        try (Storage storage = new Storage(config, Logger.getAnonymousLogger())) { assertEquals(ledger.snapshot(), storage.load()); }
        assertEquals(Files.readString(legacy), Files.readString(directory.resolve("data.json.pre-sql.bak")));
        Files.writeString(legacy, "corrupted after successful import");
        try (Storage storage = new Storage(config, Logger.getAnonymousLogger())) { assertEquals(ledger.snapshot(), storage.load()); }
    }
    @Test void invalidLegacyImportCannotCreateAResetSnapshot() throws Exception {
        Path legacy = directory.resolve("data.json"), db = directory.resolve("counters.db");
        Files.writeString(legacy, "{invalid");
        var config = new StorageConfig("sqlite", db, "", 0, "", "", "", "disable", legacy);
        try (Storage storage = new Storage(config, Logger.getAnonymousLogger())) { assertThrows(java.io.IOException.class, storage::load); }
        assertEquals("{invalid", Files.readString(legacy));
        try (Storage storage = new Storage(db, Logger.getAnonymousLogger())) { assertNull(storage.load()); }
    }

}
