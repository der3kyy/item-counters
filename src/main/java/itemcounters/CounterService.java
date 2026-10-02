package itemcounters;

import itemcounters.api.*;
import itemcounters.core.*;
import java.math.BigDecimal;
import java.util.*;
import org.bukkit.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

public final class CounterService implements ItemCountersApi {
    private static final String LORE_MARKER = "itemcounters:managed-lore:v1";
    private final ItemCountersPlugin plugin;
    private final NamespacedKey tokenKey;
    private final Map<String, NamespacedKey> keys = new HashMap<>();
    private final LegacyComponentSerializer colors = LegacyComponentSerializer.legacyAmpersand();

    public CounterService(ItemCountersPlugin plugin) {
        this.plugin = plugin;
        tokenKey = new NamespacedKey(plugin, "counter_token");
        for (String key : List.of("schema", "id", "type", "blocks", "wood", "mob_kills", "player_kills", "damage", "milestones", "credit_seq"))
            keys.put(key, new NamespacedKey(plugin, key));
    }
    private void mainThread() {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("ItemCounters API requires the server thread");
    }
    @Override public boolean hasCounter(ItemStack item) { return getCounter(item) != null; }
    @Override public CounterType getCounterType(ItemStack item) {
        CounterSnapshot snapshot = getCounter(item);
        return snapshot == null ? null : snapshot.type();
    }
    @Override public CounterSnapshot getCounter(ItemStack item) {
        mainThread();
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) return null;
        var data = item.getItemMeta().getPersistentDataContainer();
        if (!Integer.valueOf(1).equals(data.get(keys.get("schema"), PersistentDataType.INTEGER))) return null;
        try {
            UUID id = UUID.fromString(Objects.requireNonNull(data.get(keys.get("id"), PersistentDataType.STRING)));
            CounterType type = CounterType.valueOf(Objects.requireNonNull(data.get(keys.get("type"), PersistentDataType.STRING)));
            if (CounterType.classify(item.getType()) != type || item.getAmount() != 1) return null;
            long blocks = data.getOrDefault(keys.get("blocks"), PersistentDataType.LONG, 0L);
            long wood = data.getOrDefault(keys.get("wood"), PersistentDataType.LONG, 0L);
            long mobs = data.getOrDefault(keys.get("mob_kills"), PersistentDataType.LONG, 0L);
            long players = data.getOrDefault(keys.get("player_kills"), PersistentDataType.LONG, 0L);
            double damage = data.getOrDefault(keys.get("damage"), PersistentDataType.DOUBLE, 0.0);
            if (blocks < 0 || wood < 0 || mobs < 0 || players < 0 || damage < 0 || !Double.isFinite(damage)) return null;
            return new CounterSnapshot(id, type, blocks, wood, mobs, players, damage);
        } catch (IllegalArgumentException | NullPointerException exception) { return null; }
    }
    @Override public boolean applyCounter(ItemStack item) {
        mainThread();
        if (item == null || item.getAmount() != 1 || hasCounter(item)) return false;
        CounterType type = CounterType.classify(item.getType());
        if (type == null || !plugin.settings().supported().contains(type)) return false;
        var existing = item.getItemMeta().getPersistentDataContainer();
        if (existing.has(keys.get("schema"))) return false;
        ItemMeta meta = item.getItemMeta();
        var data = meta.getPersistentDataContainer();
        data.set(keys.get("schema"), PersistentDataType.INTEGER, 1);
        data.set(keys.get("id"), PersistentDataType.STRING, UUID.randomUUID().toString());
        data.set(keys.get("type"), PersistentDataType.STRING, type.name());
        item.setItemMeta(meta);
        refreshDisplay(item);
        return true;
    }
    @Override public boolean removeCounter(ItemStack item) {
        mainThread();
        if (!hasCounter(item)) return false;
        ItemMeta meta = item.getItemMeta();
        keys.values().forEach(meta.getPersistentDataContainer()::remove);
        meta.lore(unmanaged(meta));
        item.setItemMeta(meta);
        return true;
    }
    public boolean enabled(CounterSnapshot snapshot) {
        return snapshot != null && plugin.settings().supported().contains(snapshot.type());
    }
    @Override public CounterSnapshot setValue(ItemStack item, String stat, Number value) { return edit(item, stat, value, "set", null, "", true); }
    @Override public CounterSnapshot addValue(ItemStack item, String stat, Number value) { return edit(item, stat, value, "add", null, "", true); }
    @Override public CounterSnapshot subtractValue(ItemStack item, String stat, Number value) { return edit(item, stat, value, "subtract", null, "", true); }
    public CounterSnapshot edit(ItemStack item, String stat, Number amount, String operation, UUID actor, String name, boolean admin) {
        mainThread();
        CounterSnapshot before = Objects.requireNonNull(getCounter(item), "Missing counter");
        if (stat.equals("value")) stat = before.type().category();
        boolean valid = switch (before.type()) {
            case BLOCKS -> stat.equals("blocks");
            case WOOD -> stat.equals("wood");
            case ARMOR -> stat.equals("damage");
            case WEAPON -> stat.equals("mob_kills") || stat.equals("player_kills");
        };
        if (!valid || !Set.of("set", "add", "subtract").contains(operation)) throw new IllegalArgumentException("Invalid stat/operation");
        BigDecimal decimal = new BigDecimal(amount.toString());
        if (decimal.signum() < 0) throw new IllegalArgumentException("Negative amount");
        ItemMeta meta = item.getItemMeta();
        var data = meta.getPersistentDataContainer();
        if (stat.equals("damage")) {
            double delta = decimal.doubleValue();
            if (!Double.isFinite(delta)) throw new IllegalArgumentException("Invalid damage amount");
            double old = before.damage();
            double value = switch (operation) {
                case "set" -> delta;
                case "add" -> CounterMath.addDamage(old, delta);
                default -> Math.max(0, old - delta);
            };
            data.set(keys.get(stat), PersistentDataType.DOUBLE, value);
        } else {
            long delta = decimal.longValueExact();
            long old = switch (stat) {
                case "blocks" -> before.blocks();
                case "wood" -> before.wood();
                case "mob_kills" -> before.mobKills();
                default -> before.playerKills();
            };
            long value = switch (operation) {
                case "set" -> delta;
                case "add" -> CounterMath.add(old, delta);
                default -> Math.max(0, old - delta);
            };
            data.set(keys.get(stat), PersistentDataType.LONG, value);
        }
        item.setItemMeta(meta);
        CounterSnapshot after = getCounter(item);
        List<String> commands = new ArrayList<>();
        if (actor != null && (!admin || plugin.settings().adminMilestones())) {
            meta = item.getItemMeta();
            Set<String> reached = new HashSet<>(Arrays.asList(meta.getPersistentDataContainer()
                    .getOrDefault(keys.get("milestones"), PersistentDataType.STRING, "").split(",")));
            var rules = plugin.settings().milestones().get(after.type().category());
            Set<String> seen = new HashSet<>();
            String prefix = after.type().category() + ":";
            for (String marker : reached) if (marker.startsWith(prefix)) seen.add(marker.substring(prefix.length()));
            for (BigDecimal threshold : MilestoneRules.crossed(before.value(), after.value(), rules.keySet(), seen)) {
                reached.add(prefix + MilestoneRules.key(threshold));
                Map<String, String> variables = values(after);
                variables.put("player", name);
                variables.put("uuid", actor == null ? "" : actor.toString());
                variables.put("item", item.getType().name());
                variables.put("threshold", MilestoneRules.key(threshold));
                for (String command : rules.get(threshold)) commands.add(replace(command, variables));
            }
            reached.remove("");
            meta.getPersistentDataContainer().set(keys.get("milestones"), PersistentDataType.STRING,
                    reached.stream().sorted().collect(java.util.stream.Collectors.joining(",")));
            item.setItemMeta(meta);
        }
        refreshDisplay(item);
        if (actor != null) for (String command : commands) plugin.reward(actor, name, command);
        return after;
    }
    public void reconcile(ItemStack item) {
        mainThread();
        CounterSnapshot snapshot = getCounter(item);
        if (snapshot == null || snapshot.type() != CounterType.WEAPON) return;
        long applied = item.getItemMeta().getPersistentDataContainer().getOrDefault(keys.get("credit_seq"), PersistentDataType.LONG, 0L);
        for (Ledger.Credit credit : plugin.ledger().pending(snapshot.id())) {
            if (credit.sequence() <= applied) continue;
            edit(item, credit.stat(), credit.amount(), "add", credit.actor(), credit.name(), false);
            applied = credit.sequence();
            ItemMeta meta = item.getItemMeta();
            meta.getPersistentDataContainer().set(keys.get("credit_seq"), PersistentDataType.LONG, applied);
            item.setItemMeta(meta);
        }
        plugin.ledger().acknowledge(snapshot.id(), applied);
    }
    public ItemStack createCounterItem() {
        mainThread();
        Settings settings = plugin.settings();
        ItemStack item = new ItemStack(settings.counterMaterial());
        item.editMeta(meta -> {
            meta.getPersistentDataContainer().set(tokenKey, PersistentDataType.BYTE, (byte) 1);
            meta.setEnchantmentGlintOverride(settings.counterGlow());
            meta.displayName(itemText(settings.text("counter-item.name")));
            meta.lore(settings.lang().getStringList("counter-item.lore").stream().map(this::itemText).toList());
        });
        return item;
    }
    public boolean isCounterItem(ItemStack item) {
        mainThread();
        return item != null && item.getAmount() > 0 && item.getType() == plugin.settings().counterMaterial()
                && item.hasItemMeta() && Byte.valueOf((byte) 1).equals(item.getItemMeta().getPersistentDataContainer().get(tokenKey, PersistentDataType.BYTE));
    }
    private Component itemText(String text) {
        // Parent supplies FALSE; explicit &o in a child can override it.
        return Component.empty().decoration(TextDecoration.ITALIC, false).append(colors.deserialize(text));
    }
    @Override public void refreshDisplay(ItemStack item) {
        mainThread();
        CounterSnapshot snapshot = getCounter(item);
        if (snapshot == null) return;
        ItemMeta meta = item.getItemMeta();
        List<Component> lore = unmanaged(meta);
        Settings settings = plugin.settings();
        Map<String, String> variables = values(snapshot);
        String category = ToolCategory.classify(item.getType().name());
        for (String template : settings.lang().getStringList("item-lore." + category)) {
            lore.add(itemText(replace(template, variables)).insertion(LORE_MARKER));
        }
        meta.lore(lore);
        item.setItemMeta(meta);
    }
    private List<Component> unmanaged(ItemMeta meta) {
        List<Component> lore = meta.lore();
        if (lore == null) return new ArrayList<>();
        return new ArrayList<>(lore.stream().filter(line -> !LORE_MARKER.equals(line.insertion())).toList());
    }
    public Map<String, String> values(CounterSnapshot snapshot) {
        Settings settings = plugin.settings();
        Map<String, String> values = new HashMap<>();
        values.put("value", settings.numbers().format(snapshot.value(), snapshot.type() == CounterType.ARMOR));
        values.put("raw_value", NumberFormat.raw(snapshot.value()));
        values.put("mob_kills", settings.numbers().format(snapshot.mobKills(), false));
        values.put("player_kills", settings.numbers().format(snapshot.playerKills(), false));
        values.put("total_kills", settings.numbers().format(snapshot.totalKills(), false));
        values.put("damage", settings.numbers().format(snapshot.damage(), true));
        values.put("unit", settings.unit(snapshot.type().category(), snapshot.value()));
        values.putAll(settings.wordForms().variables(settings.numbers().displayed(snapshot.value(), snapshot.type() == CounterType.ARMOR)));
        values.put("block_word", settings.wordForms().word("block", settings.numbers().displayed(
                snapshot.type() == CounterType.WOOD ? snapshot.wood() : snapshot.blocks(), false)));
        values.put("kill_word", settings.wordForms().word("kill", settings.numbers().displayed(snapshot.totalKills(), false)));
        values.put("mob_word", settings.wordForms().word("mob", settings.numbers().displayed(snapshot.mobKills(), false)));
        values.put("player_word", settings.wordForms().word("player", settings.numbers().displayed(snapshot.playerKills(), false)));
        values.put("heart_word", settings.wordForms().word("heart", settings.numbers().displayed(snapshot.damage(), true)));
        values.put("type", snapshot.type().name().toLowerCase(Locale.ROOT));
        values.put("id", snapshot.id().toString());
        return values;
    }
    public void copyCounter(ItemStack from, ItemStack to) {
        CounterSnapshot snapshot = getCounter(from);
        if (snapshot == null || to == null || CounterType.classify(to.getType()) != snapshot.type()) return;
        ItemMeta source = from.getItemMeta();
        ItemMeta result = to.getItemMeta();
        keys.values().forEach(result.getPersistentDataContainer()::remove);
        var fromData = source.getPersistentDataContainer();
        var toData = result.getPersistentDataContainer();
        for (var entry : keys.entrySet()) {
            String name = entry.getKey();
            NamespacedKey key = entry.getValue();
            if (name.equals("schema")) {
                Integer value = fromData.get(key, PersistentDataType.INTEGER);
                if (value != null) toData.set(key, PersistentDataType.INTEGER, value);
            } else if (name.equals("damage")) {
                Double value = fromData.get(key, PersistentDataType.DOUBLE);
                if (value != null) toData.set(key, PersistentDataType.DOUBLE, value);
            } else if (Set.of("id", "type", "milestones").contains(name)) {
                String value = fromData.get(key, PersistentDataType.STRING);
                if (value != null) toData.set(key, PersistentDataType.STRING, value);
            } else {
                Long value = fromData.get(key, PersistentDataType.LONG);
                if (value != null) toData.set(key, PersistentDataType.LONG, value);
            }
        }
        to.setItemMeta(result);
        refreshDisplay(to);
    }
    public static String replace(String text, Map<String, String> variables) {
        for (var entry : variables.entrySet()) text = text.replace("{" + entry.getKey() + "}", entry.getValue());
        return text;
    }
}
