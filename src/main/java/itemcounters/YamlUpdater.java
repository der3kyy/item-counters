package itemcounters;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/** Rebuilds comments from the bundled template while retaining user values. */
public final class YamlUpdater {
    private YamlUpdater() {}
    public static YamlConfiguration bundled(JavaPlugin plugin, String name) throws Exception {
        try (Reader reader = new InputStreamReader(Objects.requireNonNull(plugin.getResource(name)), StandardCharsets.UTF_8)) {
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.options().parseComments(true);
            yaml.load(reader);
            return yaml;
        }
    }
    public static YamlConfiguration load(Path file) throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().parseComments(true);
        if (Files.exists(file)) yaml.load(file.toFile());
        return yaml;
    }
    public static YamlConfiguration merge(YamlConfiguration template, YamlConfiguration previous, Set<String> obsolete) {
        for (String key : previous.getKeys(true)) {
            if (previous.isConfigurationSection(key) || key.equals("config-version")
                    || obsolete.stream().anyMatch(old -> key.equals(old) || key.startsWith(old + "."))) continue;
            template.set(key, previous.get(key));
        }
        return template;
    }
    public static void write(Path file, YamlConfiguration yaml) throws IOException {
        Files.createDirectories(file.getParent());
        String text = yaml.saveToString();
        if (Files.exists(file) && Files.readString(file).equals(text)) return;
        if (Files.exists(file)) {
            Path backup = file.resolveSibling(file.getFileName() + ".pre-v2.bak");
            if (!Files.exists(backup)) Files.copy(file, backup);
        }
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, text, StandardCharsets.UTF_8);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
    static void migrateUnitDefaults(YamlConfiguration lang, YamlConfiguration old,
                                    YamlConfiguration oldUnits, YamlConfiguration newLocale) {
        for (String key : oldUnits.getKeys(true)) {
            if (!oldUnits.isConfigurationSection(key) && old.contains(key)
                    && Objects.equals(old.get(key), oldUnits.get(key))) lang.set(key, newLocale.get(key));
        }
    }
    public static YamlConfiguration update(JavaPlugin plugin) throws Exception {
        Path root = plugin.getDataFolder().toPath(), file = root.resolve("config.yml");
        YamlConfiguration previous = load(file);
        YamlConfiguration config = merge(bundled(plugin, "config.yml"), previous,
                Set.of("display", "wood-blocks", "storage.file"));
        if (previous.contains("storage.file")) config.set("storage.legacy-import", previous.getString("storage.file"));
        String selected = config.getString("general.language");
        if (selected == null || !selected.matches("[A-Za-z0-9_-]+")) throw new IllegalArgumentException("Invalid language filename");
        for (String locale : new LinkedHashSet<>(List.of("ru_RU", "en_US", selected))) {
            Path langFile = root.resolve("lang/" + locale + ".yml");
            InputStream resource = plugin.getResource("lang/" + locale + ".yml");
            boolean bundled = resource != null;
            if (resource != null) resource.close();
            if (!bundled && !Files.exists(langFile)) throw new IllegalArgumentException("Missing locale: " + locale);
            YamlConfiguration old = load(langFile);
            YamlConfiguration lang = merge(bundled(plugin, "lang/" + (bundled ? locale : "en_US") + ".yml"), old, Set.of("labels"));
            // Only known old default units adopt word placeholders; custom phrases remain intact.
            YamlConfiguration oldUnits = bundled(plugin, "migration/" + (bundled ? locale : "en_US") + "-units-v2.yml");
            YamlConfiguration newLocale = bundled(plugin, "lang/" + (bundled ? locale : "en_US") + ".yml");
            migrateUnitDefaults(lang, old, oldUnits, newLocale);
            YamlConfiguration oldDefaults = bundled(plugin, "migration/" + (bundled ? locale : "en_US") + "-v1.yml");
            if (previous.getInt("config-version", 1) < 2) {
                YamlConfiguration newDefaults = bundled(plugin, "lang/" + (bundled ? locale : "en_US") + ".yml");
                for (String key : oldDefaults.getKeys(true)) {
                    if (!oldDefaults.isConfigurationSection(key) && old.contains(key) && newDefaults.contains(key)
                            && Objects.equals(old.get(key), oldDefaults.get(key))) lang.set(key, newDefaults.get(key));
                }
            }
            // Migrate old custom lore only into the selected locale. Built-in defaults use clear complete text.
            if (locale.equals(selected)) for (String category : List.of("blocks", "wood", "kills", "damage")) {
                String path = "display." + category + ".lore";
                if (!previous.contains(path)) continue;
                List<String> lines = previous.getStringList(path);
                boolean changedLabels = !Objects.equals(old.getString("labels." + category), oldDefaults.getString("labels." + category))
                        || category.equals("kills") && (!Objects.equals(old.getString("labels.mob_kills"), oldDefaults.getString("labels.mob_kills"))
                        || !Objects.equals(old.getString("labels.player_kills"), oldDefaults.getString("labels.player_kills")));
                if (!lines.equals(bundled(plugin, "migration/display-v1.yml").getStringList(path)) || changedLabels) {
                    List<String> migrated = lines.stream().map(line -> line.replace("{label}", old.getString("labels." + category, lang.getString("categories." + category, category)))
                            .replace("{mob_label}", old.getString("labels.mob_kills", "mob_kills"))
                            .replace("{player_label}", old.getString("labels.player_kills", "player_kills"))).toList();
                    for (String tool : switch (category) {
                        case "blocks" -> List.of("pickaxe", "shovel", "hoe");
                        case "wood" -> List.of("axe");
                        case "kills" -> List.of("sword", "mace", "bow", "crossbow", "trident");
                        default -> List.of("armor");
                    }) if (!old.contains("item-lore." + tool)) lang.set("item-lore." + tool, migrated);
                }
            }
            write(langFile, lang);
        }
        write(file, config);
        return config;
    }
}
