package itemcounters.core;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

public final class Ledger {
    public record Score(UUID player, String name, BigDecimal value, String category) {
        public Score(UUID player, String name, BigDecimal value) { this(player, name, value, ""); }
    }
    public record Credit(long sequence, UUID actor, String name, String stat, long amount) {}
    public record Reward(UUID actor, String name, String command) {}
    public record State(int schema, long sequence, Map<String, String> names,
                        Map<String, Map<String, BigDecimal>> scores, Map<String, List<Credit>> pending, List<Reward> rewards) {}
    private final Map<String, String> names = new HashMap<>();
    private final Map<String, Map<String, BigDecimal>> scores = new HashMap<>();
    private final Map<String, List<Credit>> pending = new HashMap<>();
    private long sequence;
    private final List<Reward> rewards = new ArrayList<>();
    private final Periods periods;

    public Ledger(Periods periods) { this.periods = periods; }
    public void restore(State state) {
        if (state == null || state.schema() != 1 || state.names() == null || state.scores() == null || state.pending() == null)
            throw new IllegalArgumentException("Unsupported or malformed storage schema");
        if (state.sequence() < 0) throw new IllegalArgumentException("Invalid credit sequence");
        state.names().forEach((id, name) -> {
            UUID.fromString(id);
            if (name == null) throw new IllegalArgumentException("Invalid stored player name");
            names.put(id, name);
        });
        state.scores().forEach((key, values) -> {
            if (key == null || !key.matches("[a-z]+:(daily|weekly|alltime):.+")
                    || !ToolCategory.ALL.contains(key.split(":", 3)[0]) || key.startsWith("mixed:"))
                throw new IllegalArgumentException("Invalid stored score bucket");
            String[] parts = key.split(":", 3);
            if (parts[1].equals("alltime")) {
                if (!parts[2].equals("alltime")) throw new IllegalArgumentException("Invalid alltime bucket");
            } else java.time.LocalDate.parse(parts[2]);
            Map<String, BigDecimal> copy = new HashMap<>();
            values.forEach((id, value) -> {
                UUID.fromString(id);
                if (value == null || value.signum() < 0) throw new IllegalArgumentException("Invalid stored score");
                copy.put(id, value);
            });
            scores.put(key, copy);
        });
        state.pending().forEach((id, credits) -> {
            UUID.fromString(id);
            long last = 0;
            for (Credit credit : credits) {
                if (credit == null || credit.sequence() <= last || credit.sequence() > state.sequence()
                        || credit.amount() < 0 || credit.actor() == null || credit.name() == null
                        || !Set.of("mob_kills", "player_kills").contains(credit.stat()))
                    throw new IllegalArgumentException("Invalid pending credit");
                last = credit.sequence();
            }
            pending.put(id, new ArrayList<>(credits));
        });
        sequence = state.sequence();
        if (state.rewards() != null) for (Reward reward : state.rewards()) {
            if (reward == null || reward.actor() == null || reward.name() == null || reward.command() == null
                    || !(reward.command().startsWith("console:") || reward.command().startsWith("player:")))
                throw new IllegalArgumentException("Invalid stored reward");
            rewards.add(reward);
        }
    }
    public void name(UUID player, String name) { names.put(player.toString(), name); }
    public void add(UUID player, String name, String category, Number amount, Instant time) {
        if (!ToolCategory.ALL.contains(category) || category.equals("mixed")) throw new IllegalArgumentException("Unknown score category");
        BigDecimal delta = new BigDecimal(amount.toString());
        if (delta.signum() < 0) throw new IllegalArgumentException("Negative leaderboard credit");
        name(player, name);
        for (String period : List.of("daily", "weekly", "alltime")) {
            String key = key(category, period, periods.bucket(period, time));
            scores.computeIfAbsent(key, unused -> new HashMap<>()).merge(player.toString(), delta, BigDecimal::add);
        }
    }
    public void enqueue(UUID item, UUID actor, String name, String stat) {
        if (sequence == Long.MAX_VALUE) throw new IllegalStateException("Credit sequence exhausted");
        pending.computeIfAbsent(item.toString(), unused -> new ArrayList<>())
                .add(new Credit(++sequence, actor, name, stat, 1));
    }
    public List<Credit> pending(UUID item) { return List.copyOf(pending.getOrDefault(item.toString(), List.of())); }
    public void acknowledge(UUID item, long applied) {
        List<Credit> list = pending.get(item.toString());
        if (list == null) return;
        list.removeIf(credit -> credit.sequence() <= applied);
        if (list.isEmpty()) pending.remove(item.toString());
    }
    public void prune(Instant now, int dailyDays, int weeklyWeeks) {
        java.time.LocalDate daily = java.time.LocalDate.parse(periods.bucket("daily", now)).minusDays(dailyDays - 1L);
        java.time.LocalDate weekly = java.time.LocalDate.parse(periods.bucket("weekly", now)).minusWeeks(weeklyWeeks - 1L);
        scores.keySet().removeIf(key -> {
            String[] parts = key.split(":", 3);
            if (parts.length != 3 || parts[1].equals("alltime")) return false;
            try {
                java.time.LocalDate date = java.time.LocalDate.parse(parts[2]);
                return date.isBefore(parts[1].equals("daily") ? daily : weekly);
            } catch (java.time.DateTimeException error) { return false; }
        });
    }
    public State snapshot() {
        Map<String, Map<String, BigDecimal>> copied = new HashMap<>();
        scores.forEach((key, value) -> copied.put(key, Map.copyOf(value)));
        Map<String, List<Credit>> queued = new HashMap<>();
        pending.forEach((key, value) -> queued.put(key, List.copyOf(value)));
        return new State(1, sequence, Map.copyOf(names), Map.copyOf(copied), Map.copyOf(queued), List.copyOf(rewards));
    }
    public void reward(Reward reward) { rewards.add(reward); }
    public List<Reward> rewards() { return List.copyOf(rewards); }
    public void removeReward(Reward reward) { rewards.remove(reward); }
    public static String key(String category, String period, String bucket) { return category + ":" + period + ":" + bucket; }
    public static Map<String, List<Score>> rankings(State state, int maximum) {
        Map<String, List<Score>> result = new HashMap<>();
        state.scores().forEach((key, values) -> {
            List<Score> sorted = values.entrySet().stream().filter(entry -> entry.getValue().signum() > 0)
                    .map(entry -> new Score(UUID.fromString(entry.getKey()), state.names().getOrDefault(entry.getKey(), entry.getKey()), entry.getValue(), key.split(":", 3)[0]))
                    .sorted(Comparator.comparing(Score::value).reversed().thenComparing(score -> score.player().toString()))
                    .limit(maximum).toList();
            result.put(key, sorted);
        });
        // Build mixed from full buckets, never from individually truncated category tops.
        Map<String, Map<String, Score>> mixed = new HashMap<>();
        state.scores().forEach((key, values) -> {
            String[] parts = key.split(":", 3);
            if (!ToolCategory.TOOLS.contains(parts[0])) return;
            Map<String, Score> bucket = mixed.computeIfAbsent(key("mixed", parts[1], parts[2]), unused -> new HashMap<>());
            values.forEach((id, value) -> {
                if (value.signum() <= 0) return;
                Score score = new Score(UUID.fromString(id), state.names().getOrDefault(id, id), value, parts[0]);
                bucket.merge(id, score, (left, right) -> left.value().compareTo(right.value()) > 0
                        || left.value().compareTo(right.value()) == 0 && left.category().compareTo(right.category()) <= 0 ? left : right);
            });
        });
        mixed.forEach((key, values) -> result.put(key, values.values().stream()
                .sorted(Comparator.comparing(Score::value).reversed().thenComparing(score -> score.player().toString()))
                .limit(maximum).toList()));
        return Map.copyOf(result);
    }
}
