package itemcounters;

import itemcounters.core.NumberFormat;
import itemcounters.core.WordForms;
import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WordFormsTest {
    private static WordForms words(String rule) {
        return new WordForms(rule, Map.of("block", Map.of(
                "one", "блок", "few", "блока", "many", "блоков", "other", "блока")));
    }
    @Test void russianHandlesFinalDigitsTeensZeroAndHugeIntegers() {
        WordForms words = words("russian");
        for (long value : new long[]{1, 21, 101, 1001, -21}) assertEquals("блок", words.word("block", value));
        for (long value : new long[]{2, 3, 4, 22, 23, 24, 102, -24}) assertEquals("блока", words.word("block", value));
        for (long value : new long[]{0, 5, 10, 11, 12, 13, 14, 20, 25, 111, 112, 113, 114, Long.MAX_VALUE, Long.MIN_VALUE})
            assertEquals("блоков", words.word("block", value));
        assertEquals("блок", words.word("block", new BigDecimal("100000000000000000000000000000000000021")));
    }
    @Test void fractionsAndDisplayedRoundingUseTheCorrectForm() {
        WordForms words = words("russian");
        assertEquals("other", words.form(new BigDecimal("1.0")));
        assertEquals("other", words.form(new BigDecimal("1.5")));
        NumberFormat integerDisplay = new NumberFormat(" ", ",", 0);
        NumberFormat fractionalDisplay = new NumberFormat(" ", ",", 1);
        assertEquals("few", words.form(integerDisplay.displayed(new BigDecimal("1.95"), true)));
        assertEquals("other", words.form(fractionalDisplay.displayed(new BigDecimal("1.95"), true)));
        assertEquals("many", words.form(integerDisplay.displayed(new BigDecimal("10.95"), true)));
    }
    @Test void englishAndCustomWordsRenderWithoutChangingOtherPlaceholders() {
        WordForms words = new WordForms("english", Map.of("block", Map.of("one", "block", "other", "blocks")));
        assertEquals("{value} block with a pickaxe", words.render("{value} {block_word} with a pickaxe", 1L));
        for (Number value : List.of(0L, 2L, 11L, new BigDecimal("1.0"), new BigDecimal("1.5")))
            assertEquals("blocks", words.word("block", value));
        assertThrows(IllegalArgumentException.class, () -> new WordForms("unknown", Map.of()));
    }
    private static YamlConfiguration resource(String name) throws Exception {
        YamlConfiguration yaml = new YamlConfiguration(); yaml.options().parseComments(true);
        try (Reader reader = new InputStreamReader(Objects.requireNonNull(WordFormsTest.class.getResourceAsStream("/" + name)), StandardCharsets.UTF_8)) {
            yaml.load(reader);
        }
        return yaml;
    }
    @Test void legacyDefaultUnitsMigrateButCustomUnitsAndFormsSurviveRepeatedMerge() throws Exception {
        for (String locale : List.of("ru_RU", "en_US")) {
            YamlConfiguration defaults = resource("lang/" + locale + ".yml");
            YamlConfiguration oldUnits = resource("migration/" + locale + "-units-v2.yml");
            YamlConfiguration old = resource("migration/" + locale + "-units-v2.yml");
            old.set("units.shovel", "custom {block_word}"); old.set("forms.block.few", "custom few");
            old.set("messages.given", "custom reply");
            YamlConfiguration merged = YamlUpdater.merge(defaults, old, Set.of());
            YamlUpdater.migrateUnitDefaults(merged, old, oldUnits, resource("lang/" + locale + ".yml"));
            assertEquals("custom {block_word}", merged.getString("units.shovel"));
            assertEquals("custom few", merged.getString("forms.block.few"));
            assertEquals("custom reply", merged.getString("messages.given"));
            assertTrue(merged.getString("units.pickaxe").contains("{block_word}"));
            assertTrue(merged.getString("units.sword").contains("{kill_word}"));
            for (String noun : List.of("block", "kill", "mob", "player", "heart"))
                for (String form : List.of("one", "few", "many", "other")) assertNotNull(merged.getString("forms." + noun + "." + form));
            YamlConfiguration again = YamlUpdater.merge(resource("lang/" + locale + ".yml"), merged, Set.of());
            YamlUpdater.migrateUnitDefaults(again, merged, oldUnits, resource("lang/" + locale + ".yml"));
            assertEquals(merged.getKeys(true), again.getKeys(true));
            for (String key : merged.getKeys(true))
                if (!merged.isConfigurationSection(key)) assertEquals(merged.get(key), again.get(key), key);
        }
    }
}
