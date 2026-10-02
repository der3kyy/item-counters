package itemcounters.api;

import org.bukkit.inventory.ItemStack;

public interface ItemCountersApi {
    boolean hasCounter(ItemStack item);
    CounterType getCounterType(ItemStack item);
    CounterSnapshot getCounter(ItemStack item);
    boolean applyCounter(ItemStack item);
    boolean removeCounter(ItemStack item);
    CounterSnapshot setValue(ItemStack item, String stat, Number value);
    CounterSnapshot addValue(ItemStack item, String stat, Number value);
    CounterSnapshot subtractValue(ItemStack item, String stat, Number value);
    void refreshDisplay(ItemStack item);
}
