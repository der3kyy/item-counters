package itemcounters.core;

import java.util.List;

/** Tool-specific player rankings; old aggregate buckets remain readable. */
public final class ToolCategory {
    private ToolCategory() {}
    public static final List<String> TOOLS = List.of("pickaxe", "shovel", "hoe", "axe", "sword", "mace", "bow", "crossbow", "trident", "armor");
    public static final List<String> ALL = java.util.stream.Stream.concat(TOOLS.stream(),
            java.util.stream.Stream.of("mixed", "blocks", "wood", "kills", "damage")).toList();
    public static String classify(String material) {
        if (material == null) return null;
        for (String tool : List.of("pickaxe", "shovel", "hoe", "axe", "sword"))
            if (material.endsWith("_" + tool.toUpperCase(java.util.Locale.ROOT))) return tool;
        if (List.of("MACE", "BOW", "CROSSBOW", "TRIDENT").contains(material)) return material.toLowerCase(java.util.Locale.ROOT);
        if (material.endsWith("_HELMET") || material.endsWith("_CHESTPLATE") || material.endsWith("_LEGGINGS") || material.endsWith("_BOOTS")) return "armor";
        return null;
    }
}
