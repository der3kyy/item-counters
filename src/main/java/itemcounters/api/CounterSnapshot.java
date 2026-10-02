package itemcounters.api;

import java.util.UUID;
import itemcounters.core.CounterMath;

public record CounterSnapshot(UUID id, CounterType type, long blocks, long wood,
                              long mobKills, long playerKills, double damage) {
    public long totalKills() { return CounterMath.add(mobKills, playerKills); }
    public Number value() {
        return switch (type) {
            case BLOCKS -> blocks;
            case WOOD -> wood;
            case WEAPON -> totalKills();
            case ARMOR -> damage;
        };
    }
}
