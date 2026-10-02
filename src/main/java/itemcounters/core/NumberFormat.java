package itemcounters.core;

import java.math.BigDecimal;
import java.math.RoundingMode;

public record NumberFormat(String thousands, String decimal, int damageDecimals) {
    public NumberFormat {
        if (damageDecimals < 0 || damageDecimals > 12) throw new IllegalArgumentException("Invalid decimals");
    }
    public String format(Number value, boolean damage) {
        String raw = displayed(value, damage).toPlainString();
        String[] parts = raw.split("\\.");
        StringBuilder grouped = new StringBuilder();
        for (int i = 0; i < parts[0].length(); i++) {
            if (i > 0 && (parts[0].length() - i) % 3 == 0) grouped.append(thousands);
            grouped.append(parts[0].charAt(i));
        }
        if (parts.length == 2) grouped.append(decimal).append(parts[1]);
        return grouped.toString();
    }
    public BigDecimal displayed(Number value, boolean damage) {
        return new BigDecimal(value.toString()).setScale(damage ? damageDecimals : 0, RoundingMode.HALF_UP);
    }
    public static String raw(Number value) { return new BigDecimal(value.toString()).stripTrailingZeros().toPlainString(); }
}
