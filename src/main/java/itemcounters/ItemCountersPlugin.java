package itemcounters;

import itemcounters.api.ItemCountersApi;
import itemcounters.core.*;
import java.time.Instant;
import java.util.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

public final class ItemCountersPlugin extends JavaPlugin {
    private volatile Settings settings;
    private Settings startup;
    private Ledger ledger;
    private Storage storage;
    private CounterService counters;
    private ItemLocations locations;
    private CounterExpansion expansion;
    private volatile Map<String, List<Ledger.Score>> rankings = Map.of();
    private final java.util.concurrent.ExecutorService indexing = java.util.concurrent.Executors.newSingleThreadExecutor(task -> new Thread(task, "ItemCounters-index"));
    private final java.util.concurrent.atomic.AtomicBoolean indexingBusy = new java.util.concurrent.atomic.AtomicBoolean();
    private final LegacyComponentSerializer colors = LegacyComponentSerializer.legacyAmpersand();
    private boolean dirty = true;
    private boolean rewardsScheduled;
    private boolean loaded;

    @Override public void onEnable() {
        try {
            saveDefaultConfig();
            for (String locale : List.of("ru_RU", "en_US")) {
                if (!new java.io.File(getDataFolder(), "lang/" + locale + ".yml").exists()) saveResource("lang/" + locale + ".yml", false);
            }
            settings = Settings.load(this);
            startup = settings;
            ledger = new Ledger(startup.periods());
            storage = new Storage(startup.storageConfig(), getLogger());
            Ledger.State stored = storage.load();
            if (stored != null) ledger.restore(stored);
            loaded = true;
            prune();
            rankings = Ledger.rankings(ledger.snapshot(), settings.topSize());
            counters = new CounterService(this);
            locations = new ItemLocations(counters);
            CounterCommand command = new CounterCommand(this);
            Objects.requireNonNull(getCommand("counter")).setExecutor(command);
            Objects.requireNonNull(getCommand("counter")).setTabCompleter(command);
            getServer().getPluginManager().registerEvents(new CounterListener(this, locations), this);
            getServer().getServicesManager().register(ItemCountersApi.class, counters, this, ServicePriority.Normal);
            if (getServer().getPluginManager().isPluginEnabled("PlaceholderAPI") && settings.config().getBoolean("placeholderapi.enabled", true)) {
                expansion = new CounterExpansion(this);
                if (!expansion.register()) throw new IllegalStateException("PlaceholderAPI registration failed");
            }
            getServer().getScheduler().runTaskTimer(this, this::refreshRankings, startup.refreshTicks(), startup.refreshTicks());
            getServer().getScheduler().runTaskTimer(this, () -> { prune(); storage.save(ledger.snapshot()); locations.prune(); },
                    startup.flushSeconds() * 20L, startup.flushSeconds() * 20L);
            scheduleRewards();
            for (Player player : getServer().getOnlinePlayers()) { locations.inventory(player.getInventory()); locations.inventory(player.getEnderChest()); }
            storage.save(ledger.snapshot());
            getLogger().info("Enabled; item schema=1, storage ready.");
        } catch (Exception error) {
            getLogger().log(java.util.logging.Level.SEVERE, "Initialization failed", error);
            getServer().getPluginManager().disablePlugin(this);
        }
    }
    @Override public void onDisable() {
        getServer().getScheduler().cancelTasks(this);
        indexing.shutdown();
        try { if (!indexing.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) indexing.shutdownNow(); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); indexing.shutdownNow(); }
        if (expansion != null) expansion.unregister();
        getServer().getServicesManager().unregisterAll(this);
        if (storage != null) {
            if (ledger != null && loaded) storage.save(ledger.snapshot());
            storage.close();
        }
    }
    public Settings settings() { return settings; }
    public Ledger ledger() { return ledger; }
    public CounterService counters() { return counters; }
    public void credit(Player player, String category, Number value) {
        ledger.add(player.getUniqueId(), player.getName(), category, value, Instant.now());
        dirty = true;
    }
    private void prune() {
        ledger.prune(Instant.now(), settings.config().getInt("leaderboards.retention-daily-days", 31),
                settings.config().getInt("leaderboards.retention-weekly-weeks", 8));
        dirty = true;
    }
    private void refreshRankings() {
        if (dirty && indexingBusy.compareAndSet(false, true)) {
            Ledger.State snapshot = ledger.snapshot();
            int limit = settings.topSize();
            dirty = false;
            indexing.execute(() -> {
                try { rankings = Ledger.rankings(snapshot, limit); }
                finally { indexingBusy.set(false); }
            });
        }
    }
    public List<Ledger.Score> top(String category, String period) {
        return rankings.getOrDefault(Ledger.key(category, period, startup.periods().bucket(period, Instant.now())), List.of());
    }
    public String topValue(Ledger.Score score, String category) {
        if (category.equals("mixed")) category = score.category();
        String formatted = settings.numbers().format(score.value(), category.equals("damage") || category.equals("armor"));
        return CounterService.replace(settings.text("leaderboard.value"), Map.of("value", formatted, "unit", settings.unit(category, score.value())));
    }
    public boolean reloadSettings() {
        try {
            Settings updated = Settings.load(this);
            if (!startup.periods().equals(updated.periods()) || !startup.storageConfig().equals(updated.storageConfig())
                    || startup.flushSeconds() != updated.flushSeconds() || startup.refreshTicks() != updated.refreshTicks()
                    || settings.config().getBoolean("placeholderapi.enabled", true) != updated.config().getBoolean("placeholderapi.enabled", true))
                getLogger().warning("Storage, timezone/week-start, scheduler intervals and PAPI registration changes require a restart.");
            settings = updated;
            dirty = true;
            refreshRankings();
            return true;
        } catch (Exception error) { getLogger().log(java.util.logging.Level.WARNING, "Reload rejected; previous settings remain active", error); return false; }
    }
    public void send(org.bukkit.command.CommandSender sender, String key, Map<String, String> values) {
        sender.sendMessage(colors.deserialize(CounterService.replace(settings.text(key), values)));
    }
    public void reward(UUID actor, String name, String command) {
        ledger.reward(new Ledger.Reward(actor, name, command));
        scheduleRewards();
    }
    private void scheduleRewards() {
        if (rewardsScheduled || ledger.rewards().isEmpty()) return;
        rewardsScheduled = true;
        getServer().getScheduler().runTask(this, () -> { rewardsScheduled = false; drainRewards(); });
    }
    private void drainRewards() {
        for (Ledger.Reward reward : ledger.rewards()) {
            Player player = getServer().getPlayer(reward.actor());
            if (reward.command().startsWith("console:")) {
                ledger.removeReward(reward);
                getServer().dispatchCommand(getServer().getConsoleSender(), reward.command().substring(8));
            } else if (player != null && player.isOnline()) {
                ledger.removeReward(reward);
                player.performCommand(reward.command().substring(7));
            }
        }
    }
    public void deliverRewards(Player player) { dirty = true; scheduleRewards(); }
}
