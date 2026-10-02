package itemcounters;

import itemcounters.api.CounterType;
import itemcounters.core.*;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.plugin.java.JavaPlugin;
import java.io.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

public record Settings(YamlConfiguration config, YamlConfiguration lang, boolean creative, Set<CounterType> supported,
                       Set<Material> wood, EntityExclusions exclusions, NumberFormat numbers, WordForms wordForms, Periods periods,
                       int topSize, int pageSize, int refreshTicks, int flushSeconds, StorageConfig storageConfig,
                       boolean adminMilestones, Map<String, NavigableMap<BigDecimal, List<String>>> milestones,
                       boolean anvilEnabled, Material counterMaterial, boolean counterGlow, int anvilLevelCost) {
    public static Settings load(JavaPlugin plugin) throws Exception {
        YamlConfiguration config = YamlUpdater.update(plugin);
        String language = config.getString("general.language", "ru_RU");
        if (!language.matches("[A-Za-z0-9_-]+")) throw new IllegalArgumentException("Invalid language filename");
        File languageFile = new File(plugin.getDataFolder(), "lang/" + language + ".yml");
        YamlConfiguration lang = new YamlConfiguration();
        if (languageFile.isFile()) lang.load(languageFile);
        else throw new IllegalArgumentException("Missing locale: " + languageFile);
        YamlConfiguration fallback = new YamlConfiguration();
        try (Reader reader = new InputStreamReader(Objects.requireNonNull(plugin.getResource("lang/en_US.yml")), java.nio.charset.StandardCharsets.UTF_8)) {
            fallback.load(reader);
        }
        InputStream bundledLocale = plugin.getResource("lang/" + language + ".yml");
        if (bundledLocale != null) {
            YamlConfiguration localizedDefaults = new YamlConfiguration();
            try (Reader reader = new InputStreamReader(bundledLocale, java.nio.charset.StandardCharsets.UTF_8)) { localizedDefaults.load(reader); }
            localizedDefaults.setDefaults(fallback);
            lang.setDefaults(localizedDefaults);
        } else lang.setDefaults(fallback);
        Map<String, Map<String, String>> words = new LinkedHashMap<>();
        var forms = lang.getConfigurationSection("forms");
        if (forms != null) for (String name : forms.getKeys(false)) {
            var options = forms.getConfigurationSection(name);
            if (options == null) continue;
            Map<String, String> values = new HashMap<>();
            for (String form : List.of("one", "few", "many", "other")) {
                String value = lang.getString("forms." + name + "." + form);
                if (value != null) values.put(form, value);
            }
            words.put(name, values);
        }
        WordForms wordForms = new WordForms(lang.getString("forms.rule", "english"), words);
        Set<CounterType> supported = EnumSet.noneOf(CounterType.class);
        for (CounterType type : CounterType.values()) if (config.getBoolean("supported-items." + type.category(), true)) supported.add(type);
        Set<Material> wood = EnumSet.noneOf(Material.class);
        for (String value : config.getStringList("wood-blocks")) {
            Material material = Material.matchMaterial(value);
            if (material != null && material.isBlock()) wood.add(material);
            else plugin.getLogger().warning("Invalid wood material ignored: " + value);
        }
        Set<String> validEntities = new HashSet<>();
        for (EntityType type : EntityType.values()) validEntities.add(type.name());
        EntityExclusions exclusions = new EntityExclusions(config.getStringList("entity-exclusions.types"),
                config.getStringList("entity-exclusions.patterns"), validEntities, plugin.getLogger()::warning);
        String timezone = config.getString("leaderboards.timezone", "system");
        ZoneId zone = timezone.equals("system") ? ZoneId.systemDefault() : ZoneId.of(timezone);
        Periods periods = new Periods(zone, DayOfWeek.valueOf(config.getString("leaderboards.week-start", "MONDAY")));
        Map<String, NavigableMap<BigDecimal, List<String>>> milestones = new HashMap<>();
        for (String category : List.of("blocks", "wood", "kills", "damage")) {
            NavigableMap<BigDecimal, List<String>> rules = new TreeMap<>();
            var section = config.getConfigurationSection("milestones." + category);
            if (section != null) for (String key : section.getKeys(false)) {
                try {
                    BigDecimal threshold = new BigDecimal(key.replace('_', '.'));
                    if (threshold.signum() <= 0) throw new IllegalArgumentException("Nonpositive threshold");
                    List<String> commands = section.getStringList(key + ".commands");
                    if (commands.stream().anyMatch(command -> !(command.startsWith("console:") || command.startsWith("player:"))))
                        throw new IllegalArgumentException("Missing console:/player: prefix");
                    rules.put(threshold, List.copyOf(commands));
                } catch (IllegalArgumentException error) { plugin.getLogger().warning("Invalid milestone ignored: " + category + "/" + key); }
            }
            milestones.put(category, Collections.unmodifiableNavigableMap(rules));
        }
        bounded(config, "leaderboards.retention-daily-days", 1, 3650);
        bounded(config, "leaderboards.retention-weekly-weeks", 1, 520);
        StorageConfig storageConfig = StorageConfig.read(config, plugin.getDataFolder().toPath());
        Material counterMaterial = Material.matchMaterial(Objects.requireNonNull(config.getString("anvil.counter-material")));
        if (counterMaterial == null || counterMaterial.isAir() || !counterMaterial.isItem())
            throw new IllegalArgumentException("Invalid anvil counter material");
        NumberFormat numbers = new NumberFormat(config.getString("number-format.thousands-separator", " "),
                config.getString("number-format.decimal-separator", "."), bounded(config, "number-format.damage-decimals", 0, 12));
        if (!ToolCategory.ALL.contains(config.getString("leaderboards.default-category"))
                || !List.of("daily", "weekly", "alltime").contains(config.getString("leaderboards.default-period")))
            throw new IllegalArgumentException("Invalid default leaderboard category/period");
        return new Settings(config, lang, config.getBoolean("counting.creative", false), supported, Set.copyOf(wood), exclusions,
                numbers, wordForms, periods, bounded(config, "leaderboards.max-cached-top", 1, 10000),
                bounded(config, "leaderboards.page-size", 1, 100), bounded(config, "leaderboards.refresh-ticks", 20, 1200),
                bounded(config, "storage.flush-seconds", 1, 3600), storageConfig,
                config.getBoolean("milestones.trigger-on-admin-change", false), Map.copyOf(milestones),
                config.getBoolean("anvil.enabled", true), counterMaterial, config.getBoolean("anvil.counter-glow", false), bounded(config, "anvil.level-cost", 0, 39));
    }
    private static int bounded(YamlConfiguration config, String key, int min, int max) {
        Object raw = config.get(key);
        if (!(raw instanceof Number number) || number.doubleValue() != number.intValue()
                || number.intValue() < min || number.intValue() > max) throw new IllegalArgumentException("Invalid setting: " + key);
        return number.intValue();
    }
    public String text(String key) {
        String value = lang.getString(key);
        return value == null ? key : value;
    }
    public String unit(String category) { return unit(category, 0L); }
    public String unit(String category, Number value) {
        return wordForms.render(text("units." + category), numbers.displayed(value,
                category.equals("damage") || category.equals("armor")));
    }
}
