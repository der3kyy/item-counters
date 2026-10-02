package itemcounters.core;

import java.util.*;
import java.util.function.Consumer;
import java.util.regex.*;

public final class EntityExclusions {
    private final Set<String> exact = new HashSet<>();
    private final List<Pattern> patterns = new ArrayList<>();
    public EntityExclusions(Collection<String> types, Collection<String> regex, Set<String> valid, Consumer<String> warn) {
        for (String type : types) {
            if (valid.contains(type)) exact.add(type);
            else warn.accept("Unknown entity type: " + type);
        }
        for (String expression : regex) {
            try { patterns.add(Pattern.compile(expression)); }
            catch (PatternSyntaxException exception) { warn.accept("Invalid entity regex: " + expression); }
        }
    }
    public boolean excludes(String type) {
        return exact.contains(type) || patterns.stream().anyMatch(pattern -> pattern.matcher(type).matches());
    }
}
