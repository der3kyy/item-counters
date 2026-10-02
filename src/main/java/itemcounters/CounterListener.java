package itemcounters;

import itemcounters.api.*;
import itemcounters.core.CounterMath;
import itemcounters.core.ToolCategory;
import java.util.*;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

public final class CounterListener implements Listener {
    private final ItemCountersPlugin plugin;
    private final CounterService counters;
    private final ItemLocations locations;
    private final NamespacedKey weapon;
    private final NamespacedKey actor;
    private final NamespacedKey sourceCategory;
    public CounterListener(ItemCountersPlugin plugin, ItemLocations locations) {
        this.plugin = plugin;
        this.counters = plugin.counters();
        this.locations = locations;
        weapon = new NamespacedKey(plugin, "source_weapon");
        actor = new NamespacedKey(plugin, "source_player");
        sourceCategory = new NamespacedKey(plugin, "source_category");
    }
    private boolean counts(Player player) {
        return player.getGameMode() != GameMode.SPECTATOR && (plugin.settings().creative() || player.getGameMode() != GameMode.CREATIVE);
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void block(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (!counts(player)) return;
        ItemStack item = player.getInventory().getItemInMainHand();
        CounterSnapshot snapshot = counters.getCounter(item);
        if (!counters.enabled(snapshot)) return;
        boolean wood = snapshot.type() == CounterType.WOOD;
        if (snapshot.type() != CounterType.BLOCKS && !wood) return;
        String stat = wood ? "wood" : "blocks";
        counters.edit(item, stat, 1L, "add", player.getUniqueId(), player.getName(), false);
        player.getInventory().setItemInMainHand(item);
        plugin.credit(player, stat, 1L);
        if (wood) plugin.credit(player, "blocks", 1L);
        plugin.credit(player, ToolCategory.classify(item.getType().name()), 1L);
    }
    private void source(PersistentDataContainer data, UUID weaponId, UUID actorId, String category) {
        data.remove(weapon);
        data.remove(actor);
        data.remove(sourceCategory);
        if (weaponId != null && actorId != null) {
            data.set(weapon, PersistentDataType.STRING, weaponId.toString());
            data.set(actor, PersistentDataType.STRING, actorId.toString());
            if (category != null) data.set(sourceCategory, PersistentDataType.STRING, category);
        }
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void bow(EntityShootBowEvent event) {
        if (!(event.getEntity() instanceof Player player) || !counts(player)) return;
        CounterSnapshot snapshot = counters.getCounter(event.getBow());
        source(event.getProjectile().getPersistentDataContainer(), counters.enabled(snapshot) && snapshot.type() == CounterType.WEAPON ? snapshot.id() : null,
                player.getUniqueId(), event.getBow() == null ? null : ToolCategory.classify(event.getBow().getType().name()));
        locations.inventory(player.getInventory());
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void launch(ProjectileLaunchEvent event) {
        if (!(event.getEntity() instanceof Trident trident) || !(trident.getShooter() instanceof Player player) || !counts(player)) return;
        CounterSnapshot snapshot = counters.getCounter(trident.getItemStack());
        source(trident.getPersistentDataContainer(), counters.enabled(snapshot) ? snapshot.id() : null, player.getUniqueId(), "trident");
        locations.trident(trident);
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void hit(EntityDamageEvent event) {
        if (event.getEntity() instanceof LivingEntity victim && event instanceof EntityDamageByEntityEvent attack) {
            Entity damager = attack.getDamager();
            Player player = damager instanceof Player direct ? direct
                    : damager instanceof Projectile projectile && projectile.getShooter() instanceof Player shooter ? shooter : null;
            if (player != null) {
                UUID sourceWeapon = null;
                String category = null;
                if (counts(player)) {
                    if (damager instanceof Projectile projectile) {
                        String id = projectile.getPersistentDataContainer().get(weapon, PersistentDataType.STRING);
                        String owner = projectile.getPersistentDataContainer().get(actor, PersistentDataType.STRING);
                        if (player.getUniqueId().toString().equals(owner)) sourceWeapon = parse(id);
                        category = projectile.getPersistentDataContainer().get(sourceCategory, PersistentDataType.STRING);
                        if (projectile instanceof Trident trident) locations.trident(trident);
                    } else {
                        CounterSnapshot held = counters.getCounter(player.getInventory().getItemInMainHand());
                        if (counters.enabled(held) && held.type() == CounterType.WEAPON) sourceWeapon = held.id();
                        category = ToolCategory.classify(player.getInventory().getItemInMainHand().getType().name());
                        locations.inventory(player.getInventory());
                    }
                }
                source(victim.getPersistentDataContainer(), sourceWeapon, player.getUniqueId(), category);
            }
        }
        if (!(event.getEntity() instanceof Player player) || !counts(player)) return;
        double damage = CounterMath.healthLoss(player.getHealth(), event.getFinalDamage()) / 2.0;
        if (damage <= 0) return;
        ItemStack[] armor = player.getInventory().getArmorContents();
        List<Integer> slots = new ArrayList<>();
        for (int index = 0; index < armor.length; index++) {
            CounterSnapshot snapshot = counters.getCounter(armor[index]);
            if (counters.enabled(snapshot) && snapshot.type() == CounterType.ARMOR) slots.add(index);
        }
        if (slots.isEmpty()) return;
        double[] shares = CounterMath.distribute(damage, slots.size());
        for (int index = 0; index < slots.size(); index++)
            counters.edit(armor[slots.get(index)], "damage", shares[index], "add", player.getUniqueId(), player.getName(), false);
        player.getInventory().setArmorContents(armor);
        plugin.credit(player, "damage", damage);
        plugin.credit(player, "armor", damage);
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void death(EntityDeathEvent event) {
        LivingEntity victim = event.getEntity();
        Player killer = victim.getKiller();
        if (killer == null || !counts(killer) || plugin.settings().exclusions().excludes(victim.getType().name())) return;
        UUID id = parse(victim.getPersistentDataContainer().get(weapon, PersistentDataType.STRING));
        String owner = victim.getPersistentDataContainer().get(actor, PersistentDataType.STRING);
        if (id == null || !killer.getUniqueId().toString().equals(owner)) return;
        String stat = victim instanceof Player ? "player_kills" : "mob_kills";
        plugin.ledger().enqueue(id, killer.getUniqueId(), killer.getName(), stat);
        locations.resolve(id, killer);
        plugin.credit(killer, "kills", 1L);
        String category = victim.getPersistentDataContainer().get(sourceCategory, PersistentDataType.STRING);
        if (category != null && ToolCategory.TOOLS.contains(category)) plugin.credit(killer, category, 1L);
        if (event instanceof PlayerDeathEvent death) for (ItemStack dropped : death.getDrops()) counters.reconcile(dropped);
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void anvil(PrepareAnvilEvent event) {
        ItemStack result = event.getResult();
        ItemStack main = event.getInventory().getItem(0);
        ItemStack token = event.getInventory().getItem(1);
        Settings settings = plugin.settings();
        if (settings.anvilEnabled() && counters.isCounterItem(token) && main != null && !counters.hasCounter(main)) {
            ItemStack applied = main.clone();
            if (counters.applyCounter(applied)) {
                event.setResult(applied);
                event.getView().setRepairItemCountCost(1);
                event.getView().setRepairCost(settings.anvilLevelCost());
                return;
            }
        }
        if (counters.isCounterItem(token)) { event.setResult(null); return; }
        if (token != null && token.getType() == settings.counterMaterial()
                && main != null && CounterType.classify(main.getType()) != null
                && !counters.hasCounter(main)) {
            event.setResult(null);
            return;
        }
        if (result != null && counters.hasCounter(main)) {
            counters.reconcile(main);
            counters.copyCounter(main, result);
            event.setResult(result);
        }
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void takeAnvil(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory() instanceof AnvilInventory inventory) || event.getRawSlot() != 2) return;
        ItemStack main = inventory.getItem(0), result = event.getCurrentItem();
        if (result == null || !counters.hasCounter(result) || counters.hasCounter(main)) return;
        if (!plugin.settings().anvilEnabled() || !counters.isCounterItem(inventory.getItem(1))) {
            event.setCancelled(true); inventory.setItem(2, null);
        }
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void smithing(PrepareSmithingEvent event) {
        ItemStack result = event.getResult();
        ItemStack main = event.getInventory().getInputEquipment();
        if (result != null && counters.hasCounter(main)) {
            counters.reconcile(main);
            counters.copyCounter(main, result);
            event.setResult(result);
        }
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void grindstone(PrepareGrindstoneEvent event) {
        ItemStack result = event.getResult();
        ItemStack main = event.getInventory().getItem(0);
        if (result != null && counters.hasCounter(main)) { counters.copyCounter(main, result); event.setResult(result); }
    }
    @EventHandler(priority = EventPriority.MONITOR)
    public void join(PlayerJoinEvent event) {
        plugin.ledger().name(event.getPlayer().getUniqueId(), event.getPlayer().getName());
        locations.inventory(event.getPlayer().getInventory());
        locations.inventory(event.getPlayer().getEnderChest());
        plugin.deliverRewards(event.getPlayer());
    }
    @EventHandler(priority = EventPriority.MONITOR)
    public void quit(PlayerQuitEvent event) { locations.inventory(event.getPlayer().getInventory()); locations.inventory(event.getPlayer().getEnderChest()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void open(InventoryOpenEvent event) { locations.inventory(event.getInventory()); locations.inventory(event.getPlayer().getInventory()); }
    @EventHandler(priority = EventPriority.MONITOR)
    public void close(InventoryCloseEvent event) { locations.inventory(event.getInventory()); locations.inventory(event.getPlayer().getInventory()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void click(InventoryClickEvent event) {
        if (event.getSlotType() != InventoryType.SlotType.RESULT) counters.reconcile(event.getCurrentItem());
        counters.reconcile(event.getCursor());
        next(() -> { locations.inventory(event.getInventory()); if (event.getWhoClicked() instanceof Player player && player.isOnline()) locations.inventory(player.getInventory()); });
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void drag(InventoryDragEvent event) { next(() -> { locations.inventory(event.getInventory()); locations.inventory(event.getWhoClicked().getInventory()); }); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void move(InventoryMoveItemEvent event) { next(() -> { locations.inventory(event.getSource()); locations.inventory(event.getDestination()); }); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void hopper(InventoryPickupItemEvent event) { locations.entity(event.getItem()); next(() -> locations.inventory(event.getInventory())); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void pickup(EntityPickupItemEvent event) { locations.entity(event.getItem()); if (event.getEntity() instanceof Player player) next(() -> locations.inventory(player.getInventory())); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void arrow(PlayerPickupArrowEvent event) {
        if (event.getArrow() instanceof Trident trident) {
            locations.trident(trident);
            event.getItem().setItemStack(trident.getItemStack());
        }
        locations.entity(event.getItem());
        next(() -> locations.inventory(event.getPlayer().getInventory()));
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void drop(PlayerDropItemEvent event) { locations.entity(event.getItemDrop()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void spawn(ItemSpawnEvent event) { locations.entity(event.getEntity()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void held(PlayerItemHeldEvent event) { counters.reconcile(event.getPlayer().getInventory().getItem(event.getNewSlot())); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void swap(PlayerSwapHandItemsEvent event) { counters.reconcile(event.getMainHandItem()); counters.reconcile(event.getOffHandItem()); }
    private void next(Runnable task) { Bukkit.getScheduler().runTask(plugin, task); }
    private UUID parse(String value) { try { return value == null ? null : UUID.fromString(value); } catch (IllegalArgumentException error) { return null; } }
}
