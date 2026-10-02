package itemcounters.api;

import org.bukkit.Material;

public enum CounterType {
    BLOCKS, WOOD, WEAPON, ARMOR;

    public static CounterType classify(Material material) {
        return material == null ? null : classify(material.name());
    }

    public static CounterType classify(String name) {
        if (name == null) return null;
        if (name.endsWith("_PICKAXE") || name.endsWith("_SHOVEL") || name.endsWith("_HOE")) return BLOCKS;
        if (name.endsWith("_AXE")) return WOOD;
        if (name.endsWith("_SWORD") || name.equals("BOW") || name.equals("CROSSBOW")
                || name.equals("TRIDENT") || name.equals("MACE")) return WEAPON;
        if (name.endsWith("_HELMET") || name.endsWith("_CHESTPLATE")
                || name.endsWith("_LEGGINGS") || name.endsWith("_BOOTS")) return ARMOR;
        return null;
    }

    public String category() {
        return switch (this) {
            case BLOCKS -> "blocks";
            case WOOD -> "wood";
            case WEAPON -> "kills";
            case ARMOR -> "damage";
        };
    }
}
