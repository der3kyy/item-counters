package itemcounters;

import itemcounters.api.CounterSnapshot;
import java.lang.ref.WeakReference;
import java.util.*;
import java.util.function.*;
import org.bukkit.entity.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.block.ShulkerBox;

public final class ItemLocations {
    private record Location(Supplier<ItemStack> read, Consumer<ItemStack> write, BooleanSupplier valid) {}
    private final Map<UUID, Location> locations = new HashMap<>();
    private final CounterService counters;
    public ItemLocations(CounterService counters) { this.counters = counters; }
    public void inventory(Inventory inventory) {
        WeakReference<Inventory> reference = new WeakReference<>(inventory);
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            if ((inventory instanceof AnvilInventory || inventory instanceof GrindstoneInventory) && slot == 2
                    || inventory instanceof SmithingInventory && slot == 3) continue;
            int index = slot;
            ItemStack item = inventory.getItem(index);
            touch(item, new Location(() -> reference.get() == null ? null : reference.get().getItem(index),
                    updated -> { if (reference.get() != null) reference.get().setItem(index, updated); },
                    () -> reference.get() != null && (!(reference.get().getHolder() instanceof Player player) || player.isOnline())), 0);
        }
    }
    public void entity(Item entity) {
        WeakReference<Item> reference = new WeakReference<>(entity);
        touch(entity.getItemStack(), new Location(() -> reference.get() == null ? null : reference.get().getItemStack(),
                updated -> { if (reference.get() != null && reference.get().isValid()) reference.get().setItemStack(updated); },
                () -> reference.get() != null && reference.get().isValid()), 0);
    }
    public void trident(Trident entity) {
        WeakReference<Trident> reference = new WeakReference<>(entity);
        touch(entity.getItemStack(), new Location(() -> reference.get() == null ? null : reference.get().getItemStack(),
                updated -> { if (reference.get() != null && reference.get().isValid()) reference.get().setItemStack(updated); },
                () -> reference.get() != null && reference.get().isValid()), 0);
    }
    public void resolve(UUID id, Player shooter) {
        Location location = locations.get(id);
        if (location != null && location.valid().getAsBoolean()) {
            ItemStack item = location.read().get();
            CounterSnapshot snapshot = counters.getCounter(item);
            if (snapshot != null && snapshot.id().equals(id)) {
                counters.reconcile(item);
                location.write().accept(item);
                return;
            }
        }
        locations.remove(id);
        if (shooter != null && shooter.isOnline()) {
            inventory(shooter.getInventory());
            inventory(shooter.getEnderChest());
        }
    }
    public void prune() { locations.entrySet().removeIf(entry -> !entry.getValue().valid().getAsBoolean()); }
    private void touch(ItemStack item, Location location, int depth) {
        if (item == null || item.getType().isAir()) return;
        CounterSnapshot snapshot = counters.getCounter(item);
        if (snapshot != null) {
            counters.reconcile(item);
            counters.refreshDisplay(item);
            locations.put(snapshot.id(), location);
            location.write().accept(item);
        }
        if (depth < 2 && item.getItemMeta() instanceof BlockStateMeta meta && meta.getBlockState() instanceof ShulkerBox box) {
            for (int slot = 0; slot < box.getInventory().getSize(); slot++) {
                ItemStack child = box.getInventory().getItem(slot);
                if (child != null) counters.reconcile(child);
                box.getInventory().setItem(slot, child);
            }
            meta.setBlockState(box);
            item.setItemMeta(meta);
            location.write().accept(item);
        }
    }
}
