package itemcounters.core;

import java.time.*;
import java.time.temporal.TemporalAdjusters;

public record Periods(ZoneId zone, DayOfWeek weekStart) {
    public String bucket(String period, Instant time) {
        LocalDate date = time.atZone(zone).toLocalDate();
        return switch (period) {
            case "daily" -> date.toString();
            case "weekly" -> date.with(TemporalAdjusters.previousOrSame(weekStart)).toString();
            case "alltime" -> "alltime";
            default -> throw new IllegalArgumentException("Unknown period: " + period);
        };
    }
}
