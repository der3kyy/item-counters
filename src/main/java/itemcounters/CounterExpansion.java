package itemcounters;

import itemcounters.api.CounterSnapshot;
import itemcounters.core.*;
import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;

public final class CounterExpansion extends PlaceholderExpansion {
    private final ItemCountersPlugin plugin;
    public CounterExpansion(ItemCountersPlugin plugin) { this.plugin = plugin; }
    @Override public String getIdentifier() { return "counter"; }
    @Override public String getAuthor() { return "ItemCounters contributors"; }
    @Override public String getVersion() { return plugin.getPluginMeta().getVersion(); }
    @Override public boolean persist() { return true; }
    @Override public String onPlaceholderRequest(Player player, String params) {
        if (params.startsWith("top_")) return top(params.substring(4));
        if (!Bukkit.isPrimaryThread()) return plugin.settings().config().getString("placeholderapi.empty-item", "0");
        CounterSnapshot snapshot = player == null ? null : plugin.counters().getCounter(player.getInventory().getItemInMainHand());
        if (params.equals("has")) return snapshot == null ? "false" : "true";
        if (snapshot == null) return plugin.settings().config().getString("placeholderapi.empty-item", "0");
        return plugin.counters().values(snapshot).get(params);
    }
    private String top(String params) {
        String[] parts = params.split("_");
        String category;
        String period;
        String position;
        String field;
        if (parts.length == 2) {
            category = plugin.settings().config().getString("leaderboards.default-category", "kills");
            period = plugin.settings().config().getString("leaderboards.default-period", "alltime");
            position = parts[0]; field = parts[1];
        } else if (parts.length == 4) { category = parts[0]; period = parts[1]; position = parts[2]; field = parts[3]; }
        else return null;
        if (!ToolCategory.ALL.contains(category) || !Set.of("daily", "weekly", "alltime").contains(period)
                || !Set.of("name", "value", "raw", "position", "category", "action", "line").contains(field)) return null;
        int index;
        try { index = Integer.parseInt(position) - 1; } catch (NumberFormatException error) { return null; }
        List<Ledger.Score> scores = plugin.top(category, period);
        if (index < 0 || index >= plugin.settings().topSize() || index >= scores.size())
            return plugin.settings().config().getString("leaderboards.empty-placeholder", "—");
        Ledger.Score score = scores.get(index);
        String actualCategory = category.equals("mixed") ? score.category() : category;
        return switch (field) {
            case "name" -> score.name();
            case "raw" -> NumberFormat.raw(score.value());
            case "position" -> String.valueOf(index + 1);
            case "category" -> actualCategory;
            case "action" -> plugin.settings().unit(actualCategory, score.value());
            case "line" -> CounterService.replace(plugin.settings().text("leaderboard.row"), Map.of(
                    "position", String.valueOf(index + 1), "player", score.name(), "value", plugin.topValue(score, category)));
            default -> plugin.topValue(score, category);
        };
    }
}
