package itemcounters.core;

import java.math.BigDecimal;
import java.util.*;

public final class MilestoneRules {
    private MilestoneRules() {}
    public static List<BigDecimal> crossed(Number before, Number after, Collection<BigDecimal> thresholds, Set<String> reached) {
        BigDecimal low = new BigDecimal(before.toString());
        BigDecimal high = new BigDecimal(after.toString());
        return thresholds.stream().filter(t -> t.compareTo(low) > 0 && t.compareTo(high) <= 0)
                .filter(t -> !reached.contains(key(t))).sorted().toList();
    }
    public static String key(BigDecimal threshold) { return threshold.stripTrailingZeros().toPlainString(); }
}
