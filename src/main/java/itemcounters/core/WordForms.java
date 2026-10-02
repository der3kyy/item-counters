package itemcounters.core;

import java.math.BigDecimal;
import java.util.*;

/** Immutable locale words, selected from the number actually displayed. */
public final class WordForms {
    private final String rule;
    private final Map<String, Map<String, String>> words;

    public WordForms(String rule, Map<String, Map<String, String>> words) {
        this.rule = Objects.requireNonNull(rule).toLowerCase(Locale.ROOT);
        if (!Set.of("russian", "english").contains(this.rule))
            throw new IllegalArgumentException("Invalid forms.rule: " + rule);
        Map<String, Map<String, String>> copy = new LinkedHashMap<>();
        words.forEach((name, forms) -> copy.put(name, Map.copyOf(forms)));
        this.words = Collections.unmodifiableMap(copy);
    }

    public String form(Number value) {
        BigDecimal number = new BigDecimal(value.toString()).abs();
        // A visible fractional part, including 1.0, uses the fractional form.
        if (number.scale() > 0) return "other";
        if (rule.equals("english")) return number.compareTo(BigDecimal.ONE) == 0 ? "one" : "other";
        int lastHundred = number.remainder(BigDecimal.valueOf(100)).intValue();
        if (lastHundred >= 11 && lastHundred <= 14) return "many";
        return switch (lastHundred % 10) {
            case 1 -> "one";
            case 2, 3, 4 -> "few";
            default -> "many";
        };
    }

    public String word(String name, Number value) {
        Map<String, String> options = words.get(name);
        if (options == null) return name;
        return options.getOrDefault(form(value), options.getOrDefault("other",
                options.getOrDefault("many", options.getOrDefault("one", name))));
    }

    public Map<String, String> variables(Number value) {
        Map<String, String> result = new HashMap<>();
        words.keySet().forEach(name -> result.put(name + "_word", word(name, value)));
        return result;
    }

    public String render(String template, Number value) {
        for (var entry : variables(value).entrySet())
            template = template.replace("{" + entry.getKey() + "}", entry.getValue());
        return template;
    }
}
