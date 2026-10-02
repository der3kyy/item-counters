package itemcounters;

import itemcounters.api.*;
import itemcounters.core.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CoreTest {
    @Test void vanillaClassification() {
        for (String tier : List.of("WOODEN", "STONE", "COPPER", "IRON", "GOLDEN", "DIAMOND", "NETHERITE")) {
            assertEquals(CounterType.BLOCKS, CounterType.classify(tier + "_PICKAXE"));
            assertEquals(CounterType.BLOCKS, CounterType.classify(tier + "_SHOVEL"));
            assertEquals(CounterType.BLOCKS, CounterType.classify(tier + "_HOE"));
            assertEquals(CounterType.WOOD, CounterType.classify(tier + "_AXE"));
            assertEquals(CounterType.WEAPON, CounterType.classify(tier + "_SWORD"));
        }
        for (String name : List.of("BOW", "CROSSBOW", "TRIDENT", "MACE")) assertEquals(CounterType.WEAPON, CounterType.classify(name));
        for (String name : List.of("LEATHER_BOOTS", "TURTLE_HELMET", "CHAINMAIL_CHESTPLATE", "DIAMOND_LEGGINGS"))
            assertEquals(CounterType.ARMOR, CounterType.classify(name));
        for (String name : List.of("AIR", "STONE", "STICK", "ELYTRA", "SHEARS", "WOLF_ARMOR")) assertNull(CounterType.classify(name));
    }
    @Test void overflowSaturates() {
        assertEquals(Long.MAX_VALUE, CounterMath.add(Long.MAX_VALUE, 1));
        assertEquals(Long.MAX_VALUE, CounterMath.add(Long.MAX_VALUE - 5, 10));
        assertEquals(3L, CounterMath.add(1, 2));
        assertThrows(IllegalArgumentException.class, () -> CounterMath.add(-1, 1));
    }
    @Test void finalDamageClampedToHealth() {
        assertEquals(0, CounterMath.healthLoss(20, 0));
        assertEquals(3, CounterMath.healthLoss(3, 100));
        assertEquals(2.5, CounterMath.healthLoss(20, 2.5));
        assertEquals(0, CounterMath.healthLoss(20, -2));
        assertEquals(0, CounterMath.healthLoss(20, Double.NaN));
    }
    @Test void distributionConservesHearts() {
        assertArrayEquals(new double[]{4}, CounterMath.distribute(4, 1));
        assertArrayEquals(new double[]{2, 2}, CounterMath.distribute(4, 2));
        assertArrayEquals(new double[]{1, 1, 1, 1}, CounterMath.distribute(4, 4));
        for (double damage : new double[]{4, 0.1, 0.00001, 1234.567890123}) for (int pieces = 1; pieces <= 4; pieces++)
            assertEquals(damage, Arrays.stream(CounterMath.distribute(damage, pieces)).sum(), Math.ulp(damage) * 2);
        assertThrows(IllegalArgumentException.class, () -> CounterMath.distribute(4, 0));
        assertThrows(IllegalArgumentException.class, () -> CounterMath.distribute(Double.POSITIVE_INFINITY, 2));
    }
    @Test void damageStorageDoesNotRoundPerEvent() {
        double value = 0;
        for (int index = 0; index < 10000; index++) value = CounterMath.addDamage(value, 0.00001);
        assertEquals(0.1, value, 1e-12);
        assertEquals(Double.MAX_VALUE, CounterMath.addDamage(Double.MAX_VALUE, Double.MAX_VALUE));
    }
    @Test void formattingKeepsLongPrecision() {
        NumberFormat format = new NumberFormat(" ", ".", 1);
        assertEquals("9 223 372 036 854 775 807", format.format(Long.MAX_VALUE, false));
        assertEquals("1 234.6", format.format(1234.567, true));
        assertEquals("100000", NumberFormat.raw(100000L));
        assertEquals("0.125", NumberFormat.raw(0.125));
        assertEquals("12,50", new NumberFormat("", ",", 2).format(12.5, true));
    }
    @Test void weaponTotalCalculatedAndSaturated() {
        CounterSnapshot snapshot = new CounterSnapshot(UUID.randomUUID(), CounterType.WEAPON, 0, 0, 100, 20, 0);
        assertEquals(120L, snapshot.totalKills());
        assertEquals("120", NumberFormat.raw(snapshot.value()));
        assertEquals(Long.MAX_VALUE, new CounterSnapshot(snapshot.id(), snapshot.type(), 0, 0, Long.MAX_VALUE, 1, 0).totalKills());
    }
    @Test void dailyBucketUsesConfiguredTimezone() {
        Instant time = Instant.parse("2026-10-01T22:00:00Z");
        assertEquals("2026-10-02", new Periods(ZoneId.of("Europe/Kyiv"), DayOfWeek.MONDAY).bucket("daily", time));
        assertEquals("2026-10-01", new Periods(ZoneId.of("UTC"), DayOfWeek.MONDAY).bucket("daily", time));
    }
    @Test void weeklyRolloverDoesNotNeedMidnightTask() {
        Periods periods = new Periods(ZoneId.of("UTC"), DayOfWeek.MONDAY);
        assertEquals("2026-09-28", periods.bucket("weekly", Instant.parse("2026-10-04T23:59:59Z")));
        assertEquals("2026-10-05", periods.bucket("weekly", Instant.parse("2026-10-05T00:00:00Z")));
        assertEquals("2026-10-04", new Periods(ZoneId.of("UTC"), DayOfWeek.SUNDAY).bucket("weekly", Instant.parse("2026-10-05T12:00:00Z")));
        assertEquals("alltime", periods.bucket("alltime", Instant.EPOCH));
    }
    @Test void milestoneSkipAndMultipleThresholds() {
        List<BigDecimal> thresholds = List.of(new BigDecimal("1000"), new BigDecimal("100"), new BigDecimal("200"));
        assertEquals(List.of(new BigDecimal("100")), MilestoneRules.crossed(99, 101, thresholds, Set.of()));
        assertEquals(List.of(new BigDecimal("100"), new BigDecimal("200"), new BigDecimal("1000")), MilestoneRules.crossed(99, 1001, thresholds, Set.of()));
        assertEquals(List.of(new BigDecimal("200"), new BigDecimal("1000")), MilestoneRules.crossed(99, 1001, thresholds, Set.of("100")));
        assertTrue(MilestoneRules.crossed(200, 0, thresholds, Set.of()).isEmpty());
        assertEquals("100", MilestoneRules.key(new BigDecimal("100.0")));
    }
    @Test void invalidRegexAndTypesDoNotCrash() {
        List<String> warnings = new ArrayList<>();
        EntityExclusions rules = new EntityExclusions(List.of("ARMOR_STAND", "NONEXISTENT"), List.of(".*BOAT", "["), Set.of("ARMOR_STAND", "ZOMBIE"), warnings::add);
        assertTrue(rules.excludes("ARMOR_STAND"));
        assertTrue(rules.excludes("OAK_BOAT"));
        assertFalse(rules.excludes("ZOMBIE"));
        assertEquals(2, warnings.size());
    }
    @Test void aggregationBelongsToPlayerNotWeapon() {
        Ledger ledger = new Ledger(new Periods(ZoneId.of("UTC"), DayOfWeek.MONDAY));
        UUID a = UUID.fromString("00000000-0000-0000-0000-000000000001"), b = UUID.fromString("00000000-0000-0000-0000-000000000002");
        Instant time = Instant.parse("2026-10-01T00:00:00Z");
        ledger.add(a, "Alice", "kills", 100L, time); ledger.add(b, "Bob", "kills", 20L, time);
        ledger.add(a, "AliceRenamed", "damage", 4, time);
        var rankings = Ledger.rankings(ledger.snapshot(), 100);
        var kills = rankings.get("kills:alltime:alltime");
        assertEquals(a, kills.getFirst().player()); assertEquals("AliceRenamed", kills.getFirst().name());
        assertEquals(new BigDecimal("100"), kills.getFirst().value());
        assertEquals(new BigDecimal("20"), kills.get(1).value());
        assertEquals(new BigDecimal("4"), rankings.get("damage:alltime:alltime").getFirst().value());
        assertFalse(rankings.containsKey("wood:alltime:alltime"));
    }
    @Test void equalScoresHaveStableOrderAndBoundedCache() {
        Ledger ledger = new Ledger(new Periods(ZoneId.of("UTC"), DayOfWeek.MONDAY));
        UUID a = UUID.fromString("00000000-0000-0000-0000-000000000001"), b = UUID.fromString("00000000-0000-0000-0000-000000000002");
        ledger.add(b, "Bob", "blocks", Long.MAX_VALUE, Instant.EPOCH); ledger.add(a, "Alice", "blocks", Long.MAX_VALUE, Instant.EPOCH);
        var scores = Ledger.rankings(ledger.snapshot(), 1).get("blocks:alltime:alltime");
        assertEquals(1, scores.size()); assertEquals(a, scores.getFirst().player());
        assertEquals(new BigDecimal(Long.MAX_VALUE), scores.getFirst().value());
    }
    @Test void pendingProjectileCreditsSurviveRestoreAndAcknowledgement() {
        Periods periods = new Periods(ZoneId.of("UTC"), DayOfWeek.MONDAY);
        Ledger ledger = new Ledger(periods); UUID item = UUID.randomUUID(), actor = UUID.randomUUID();
        ledger.enqueue(item, actor, "Alice", "mob_kills"); ledger.enqueue(item, actor, "Alice", "player_kills");
        Ledger restored = new Ledger(periods); restored.restore(ledger.snapshot());
        assertEquals(2, restored.pending(item).size());
        restored.acknowledge(item, restored.pending(item).getFirst().sequence());
        assertEquals("player_kills", restored.pending(item).getFirst().stat());
        restored.acknowledge(item, Long.MAX_VALUE); assertTrue(restored.pending(item).isEmpty());
    }
    @Test void malformedRewardJournalIsRejectedBeforeStartup() {
        Periods periods = new Periods(ZoneId.of("UTC"), DayOfWeek.MONDAY);
        var bad = new Ledger.State(1, 0, Map.of(), Map.of(), Map.of(),
                List.of(new Ledger.Reward(UUID.randomUUID(), "Alice", "missing-sender-prefix")));
        assertThrows(IllegalArgumentException.class, () -> new Ledger(periods).restore(bad));
        var badBucket = new Ledger.State(1, 0, Map.of(), Map.of("kills:daily:invalid-date", Map.of()), Map.of(), List.of());
        assertThrows(java.time.DateTimeException.class, () -> new Ledger(periods).restore(badBucket));
    }
    @Test void retentionPreservesAlltimeAndPendingCredits() {
        Periods periods = new Periods(ZoneId.of("UTC"), DayOfWeek.MONDAY);
        Ledger ledger = new Ledger(periods); UUID actor = UUID.randomUUID(), item = UUID.randomUUID();
        ledger.add(actor, "Alice", "blocks", 3L, Instant.parse("2026-01-01T12:00:00Z"));
        ledger.add(actor, "Alice", "blocks", 2L, Instant.parse("2026-10-01T12:00:00Z"));
        ledger.enqueue(item, actor, "Alice", "mob_kills");
        ledger.prune(Instant.parse("2026-10-01T12:00:00Z"), 31, 8);
        var scores = Ledger.rankings(ledger.snapshot(), 100);
        assertEquals(new BigDecimal("5"), scores.get("blocks:alltime:alltime").getFirst().value());
        assertFalse(scores.containsKey("blocks:daily:2026-01-01"));
        assertFalse(scores.containsKey("blocks:weekly:2025-12-29"));
        assertEquals(1, ledger.pending(item).size());
    }
    @Test void restartDoesNotMoveOldScoresToNewPeriod() {
        Periods periods = new Periods(ZoneId.of("UTC"), DayOfWeek.MONDAY);
        Ledger ledger = new Ledger(periods); UUID actor = UUID.randomUUID();
        ledger.add(actor, "Alice", "blocks", 3L, Instant.parse("2026-10-04T12:00:00Z"));
        Ledger restored = new Ledger(periods); restored.restore(ledger.snapshot());
        restored.add(actor, "Alice", "blocks", 2L, Instant.parse("2026-10-05T12:00:00Z"));
        var scores = Ledger.rankings(restored.snapshot(), 100);
        assertEquals(new BigDecimal("5"), scores.get("blocks:alltime:alltime").getFirst().value());
        assertEquals(new BigDecimal("2"), scores.get("blocks:weekly:2026-10-05").getFirst().value());
        assertEquals(new BigDecimal("3"), scores.get("blocks:daily:2026-10-04").getFirst().value());
    }
}
