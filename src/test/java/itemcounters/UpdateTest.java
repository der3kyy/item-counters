package itemcounters;

import itemcounters.core.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class UpdateTest {
    @TempDir Path directory;
    @Test void configMergePreservesValuesListsCustomRulesAndUsesNewComments() throws Exception {
        YamlConfiguration template = new YamlConfiguration(); template.options().parseComments(true);
        template.loadFromString("# New explanation\nconfig-version: 2\nanvil:\n  counter-material: COMPASS\n  level-cost: 0\nstorage:\n  type: sqlite\nmilestones:\n  kills: {}\n");
        YamlConfiguration old = new YamlConfiguration(); old.loadFromString("config-version: 1\nanvil:\n  counter-material: PAPER\n  level-cost: 7\nwood-blocks: [OAK_LOG]\nstorage:\n  file: old.json\nmilestones:\n  kills:\n    '100':\n      commands: ['console:give {player} diamond 2']\ncustom-key: preserved\n");
        var updated = YamlUpdater.merge(template, old, Set.of("wood-blocks", "storage.file"));
        assertEquals(2, updated.getInt("config-version")); assertEquals("PAPER", updated.getString("anvil.counter-material"));
        assertEquals(7, updated.getInt("anvil.level-cost")); assertEquals("sqlite", updated.getString("storage.type"));
        assertFalse(updated.contains("wood-blocks")); assertFalse(updated.contains("storage.file"));
        assertEquals(List.of("console:give {player} diamond 2"), updated.getStringList("milestones.kills.100.commands"));
        assertEquals("preserved", updated.getString("custom-key")); assertTrue(updated.saveToString().contains("New explanation"));
        Path file = directory.resolve("config.yml"); Files.writeString(file, old.saveToString()); YamlUpdater.write(file, updated);
        assertEquals(old.saveToString(), Files.readString(directory.resolve("config.yml.pre-v2.bak")));
        String first = Files.readString(file); YamlUpdater.write(file, updated); assertEquals(first, Files.readString(file));
    }
    @Test void mixedChoosesOneBestToolPerPlayerWithoutAggregateDoubleCounting() {
        var ledger = new Ledger(new Periods(ZoneId.of("UTC"), DayOfWeek.MONDAY));
        UUID a = new UUID(0, 1), b = new UUID(0, 2), c = new UUID(0, 3);
        ledger.add(a, "Alice", "pickaxe", 800, Instant.EPOCH); ledger.add(a, "Alice", "sword", 50, Instant.EPOCH);
        ledger.add(a, "Alice", "blocks", 10000, Instant.EPOCH); ledger.add(b, "Bob", "axe", 700, Instant.EPOCH);
        ledger.add(c, "Carol", "armor", new BigDecimal("12.25"), Instant.EPOCH);
        var rankings = Ledger.rankings(ledger.snapshot(), 100); var mixed = rankings.get("mixed:alltime:alltime");
        assertEquals(3, mixed.size()); assertEquals("pickaxe", mixed.get(0).category()); assertEquals(new BigDecimal("800"), mixed.get(0).value());
        assertEquals("axe", mixed.get(1).category()); assertEquals("armor", mixed.get(2).category());
        assertEquals(3, rankings.get("mixed:daily:1970-01-01").size());
        var restored = new Ledger(new Periods(ZoneId.of("UTC"), DayOfWeek.MONDAY)); restored.restore(ledger.snapshot());
        assertEquals(rankings, Ledger.rankings(restored.snapshot(), 100));
    }
    @Test void mixedTieOrderIsStableAndLimitIsAppliedAfterBestSelection() {
        var ledger = new Ledger(new Periods(ZoneId.of("UTC"), DayOfWeek.MONDAY)); UUID a = new UUID(0,1), b = new UUID(0,2);
        ledger.add(a,"A","sword",20,Instant.EPOCH); ledger.add(a,"A","axe",20,Instant.EPOCH); ledger.add(b,"B","pickaxe",20,Instant.EPOCH);
        var mixed = Ledger.rankings(ledger.snapshot(),1).get("mixed:alltime:alltime");
        assertEquals(1,mixed.size()); assertEquals(a,mixed.getFirst().player()); assertEquals("axe",mixed.getFirst().category());
    }
    @Test void everySupportedToolMapsToExpectedCategory() {
        assertEquals("pickaxe",ToolCategory.classify("DIAMOND_PICKAXE")); assertEquals("axe",ToolCategory.classify("COPPER_AXE"));
        assertEquals("shovel",ToolCategory.classify("WOODEN_SHOVEL")); assertEquals("hoe",ToolCategory.classify("IRON_HOE"));
        assertEquals("crossbow",ToolCategory.classify("CROSSBOW")); assertEquals("armor",ToolCategory.classify("TURTLE_HELMET"));
        assertNull(ToolCategory.classify("STONE"));
    }
}
