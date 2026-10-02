package itemcounters;

import itemcounters.api.*;
import itemcounters.core.Ledger;
import java.math.BigDecimal;
import java.util.*;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

public final class CounterCommand implements CommandExecutor, TabCompleter {
    private static final List<String> CATEGORIES = itemcounters.core.ToolCategory.ALL;
    private static final List<String> PERIODS = List.of("daily", "weekly", "alltime");
    private static final Set<String> EDIT_ACTIONS = Set.of("set", "add", "subtract");
    private static final Set<String> ITEM_ACTIONS = Set.of("apply", "remove", "inspect");
    private final ItemCountersPlugin plugin;
    public CounterCommand(ItemCountersPlugin plugin) { this.plugin = plugin; }
    private void send(CommandSender sender, String key) { plugin.send(sender, key, Map.of()); }
    private boolean usage(CommandSender sender, String action, String reason) {
        plugin.send(sender, "messages." + reason, Map.of("usage", plugin.settings().text("usage." + action)));
        return true;
    }
    private boolean permitted(CommandSender sender, String permission) {
        if (sender.hasPermission(permission)) return true;
        send(sender, "messages.no-permission"); return false;
    }
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            if (!permitted(sender, "itemcounters.use")) return true;
            if (args.length > 1) return usage(sender, "help", "invalid-arguments");
            plugin.send(sender, "help.header", Map.of());
            for (String line : plugin.settings().lang().getStringList("help.public")) sender.sendMessage(net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacyAmpersand().deserialize(line));
            if (sender.hasPermission("itemcounters.admin")) for (String line : plugin.settings().lang().getStringList("help.admin")) sender.sendMessage(net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacyAmpersand().deserialize(line));
            return true;
        }
        String action = args[0].toLowerCase(Locale.ROOT);
        if (action.equals("top")) return top(sender, args);
        if (action.equals("give")) return give(sender, args);
        if (action.equals("reload")) {
            if (!permitted(sender, "itemcounters.admin.reload")) return true;
            if (args.length != 1) return usage(sender, action, "invalid-arguments");
            send(sender, plugin.reloadSettings() ? "messages.reloaded" : "messages.reload-failed"); return true;
        }
        if (!ITEM_ACTIONS.contains(action) && !EDIT_ACTIONS.contains(action)) { send(sender, "messages.syntax"); return true; }
        String permission = "itemcounters.admin." + (EDIT_ACTIONS.contains(action) ? "edit" : action);
        if (!permitted(sender, permission)) return true;
        boolean edit = EDIT_ACTIONS.contains(action);
        if (edit && args.length < 2) return usage(sender, action, "missing-stat");
        if (edit && args.length < 3) return usage(sender, action, "missing-value");
        if ((edit && args.length > 4) || (!edit && args.length > 2)) return usage(sender, action, "invalid-arguments");
        String targetName = edit ? (args.length == 4 ? args[3] : null) : (args.length == 2 ? args[1] : null);
        Player target = targetName == null ? (sender instanceof Player player ? player : null) : plugin.getServer().getPlayerExact(targetName);
        if (target == null) {
            if (targetName == null) return usage(sender, action, "player-required");
            plugin.send(sender, "messages.player-not-found", Map.of("player", targetName)); return true;
        }
        ItemStack item = target.getInventory().getItemInMainHand();
        CounterService service = plugin.counters();
        service.reconcile(item);
        CounterSnapshot snapshot = service.getCounter(item);
        if (action.equals("apply")) {
            if (snapshot != null) send(sender, "messages.already-counted");
            else if (!service.applyCounter(item)) send(sender, "messages.unsupported");
            else { target.getInventory().setItemInMainHand(item); plugin.send(sender, "messages.applied", Map.of("player", target.getName())); }
            return true;
        }
        if (snapshot == null) { send(sender, "messages.no-counter"); return true; }
        if (action.equals("remove")) {
            service.removeCounter(item); target.getInventory().setItemInMainHand(item);
            plugin.send(sender, "messages.removed", Map.of("player", target.getName())); return true;
        }
        if (action.equals("inspect")) {
            Map<String, String> values = service.values(snapshot); values.put("player", target.getName());
            for (String line : plugin.settings().lang().getStringList("inspect." + snapshot.type().category()))
                sender.sendMessage(net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacyAmpersand().deserialize(CounterService.replace(line, values)));
            return true;
        }
        try {
            BigDecimal value = new BigDecimal(args[2]);
            CounterSnapshot changed = service.edit(item, args[1].toLowerCase(Locale.ROOT), value, action, target.getUniqueId(), target.getName(), true);
            target.getInventory().setItemInMainHand(item);
            Map<String, String> values = service.values(changed); values.put("player", target.getName());
            plugin.send(sender, "messages.edited", values);
        } catch (IllegalArgumentException | ArithmeticException error) { send(sender, "messages.invalid-stat-value"); }
        return true;
    }
    private boolean give(CommandSender sender, String[] args) {
        if (!permitted(sender, "itemcounters.admin.give")) return true;
        if (args.length < 2) return usage(sender, "give", "missing-player");
        if (args.length > 2) return usage(sender, "give", "invalid-arguments");
        Player target = plugin.getServer().getPlayerExact(args[1]);
        if (target == null) { plugin.send(sender, "messages.player-not-found", Map.of("player", args[1])); return true; }
        if (!target.getInventory().addItem(plugin.counters().createCounterItem()).isEmpty()) {
            plugin.send(sender, "messages.inventory-full", Map.of("player", target.getName())); return true;
        }
        plugin.send(sender, "messages.given", Map.of("player", target.getName()));
        if (!sender.equals(target)) send(target, "messages.received");
        return true;
    }
    private boolean top(CommandSender sender, String[] args) {
        if (!permitted(sender, "itemcounters.top")) return true;
        if (args.length < 2) return usage(sender, "top", "missing-category");
        if (args.length < 3) return usage(sender, "top", "missing-period");
        if (args.length > 4) return usage(sender, "top", "invalid-arguments");
        String category = args[1].toLowerCase(Locale.ROOT), period = args[2].toLowerCase(Locale.ROOT);
        if (!CATEGORIES.contains(category)) return usage(sender, "top", "invalid-category");
        if (!PERIODS.contains(period)) return usage(sender, "top", "invalid-period");
        int page;
        try { page = args.length == 4 ? Integer.parseInt(args[3]) : 1; } catch (NumberFormatException error) { send(sender, "messages.invalid-page"); return true; }
        int size = plugin.settings().pageSize();
        if (page < 1 || page > (plugin.settings().topSize() + size - 1) / size) { send(sender, "messages.invalid-page"); return true; }
        List<Ledger.Score> top = plugin.top(category, period);
        plugin.send(sender, "leaderboard.header", Map.of("category", plugin.settings().text("categories." + category),
                "period", plugin.settings().text("periods." + period), "page", String.valueOf(page)));
        int start = (page - 1) * size;
        if (start >= top.size()) { send(sender, "leaderboard.empty"); return true; }
        for (int index = start; index < Math.min(start + size, top.size()); index++) {
            Ledger.Score score = top.get(index);
            plugin.send(sender, "leaderboard.row", Map.of("position", String.valueOf(index + 1), "player", score.name(), "value", plugin.topValue(score, category)));
        }
        return true;
    }
    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> options = new ArrayList<>();
        if (args.length == 0) return options;
        String action = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 1) {
            options.add("help"); if (sender.hasPermission("itemcounters.top")) options.add("top");
            for (String option : List.of("give", "apply", "remove", "inspect", "reload", "set", "add", "subtract"))
                if (sender.hasPermission("itemcounters.admin." + (EDIT_ACTIONS.contains(option) ? "edit" : option))) options.add(option);
        } else if (action.equals("top") && sender.hasPermission("itemcounters.top")) options.addAll(args.length == 2 ? CATEGORIES : args.length == 3 ? PERIODS : args.length == 4 ? List.of("1") : List.of());
        else if (EDIT_ACTIONS.contains(action) && args.length == 2 && sender.hasPermission("itemcounters.admin.edit")) options.addAll(List.of("blocks", "wood", "mob_kills", "player_kills", "damage"));
        else if (((ITEM_ACTIONS.contains(action) || action.equals("give")) && args.length == 2 || EDIT_ACTIONS.contains(action) && args.length == 4)
                && sender.hasPermission("itemcounters.admin." + (EDIT_ACTIONS.contains(action) ? "edit" : action)))
            plugin.getServer().getOnlinePlayers().forEach(player -> options.add(player.getName()));
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        return options.stream().filter(option -> option.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
    }
}
