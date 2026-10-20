package dev.smallfish.hooklab.protocol;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Bounded, explicit method rules; no wildcards and no arbitrary code evaluation.
 * A rule is: fully.qualified.Class#method(type1,type2) .
 */
public final class RuleWire {
    public static final String PREF_GROUP = "hooklab.rules.v2";
    public static final String SELECTED_TARGET = "selected_target";
    public static final String RULES_PREFIX = "rules.";
    public static final String BROWSE_PREFIX = "browse.";
    public static final String EVENT_AUTHORITY = "dev.smallfish.hooklab.trace";
    public static final int MAX_RULES = 12;
    public static final int MAX_CATALOG = 100;
    private static final Pattern NAME = Pattern.compile(
            "[a-zA-Z_$][a-zA-Z0-9_$]*(\\.[a-zA-Z_$][a-zA-Z0-9_$]*)*");
    private static final Pattern METHOD = Pattern.compile("[a-zA-Z_$][a-zA-Z0-9_$]*");
    private RuleWire() {}

    public static boolean packageName(String name) {
        return name != null && name.length() < 160 && name.contains(".")
                && NAME.matcher(name).matches();
    }

    public static boolean classForTarget(String pkg, String cls) {
        return packageName(pkg) && cls != null && cls.length() <= 220
                && cls.startsWith(pkg + ".") && NAME.matcher(cls).matches();
    }

    public static boolean typeName(String value) {
        if (value == null || value.length() > 160) return false;
        String n = value;
        while (n.endsWith("[]")) n = n.substring(0, n.length() - 2);
        return NAME.matcher(n).matches();
    }

    public static boolean valid(String pkg, String rule) {
        if (rule == null || rule.length() > 350) return false;
        int hash = rule.indexOf('#');
        int open = rule.indexOf('(', hash + 1);
        if (hash < 1 || open <= hash + 1 || !rule.endsWith(")")) return false;
        if (!classForTarget(pkg, rule.substring(0, hash))
                || !METHOD.matcher(rule.substring(hash + 1, open)).matches()) return false;
        String types = rule.substring(open + 1, rule.length() - 1);
        if (types.isEmpty()) return true;
        String[] args = types.split(",", -1);
        if (args.length > 12) return false;
        for (String arg : args) if (!typeName(arg)) return false;
        return true;
    }

    public static List<String> decode(String pkg, String wire) {
        if (wire == null || wire.isEmpty()) return new ArrayList<>();
        if (wire.length() > MAX_RULES * 352) throw new IllegalArgumentException("Rules oversized");
        String[] lines = wire.split("\\n", -1);
        if (lines.length > MAX_RULES) throw new IllegalArgumentException("Too many rules");
        Set<String> unique = new LinkedHashSet<>();
        for (String item : lines) {
            if (!valid(pkg, item)) throw new IllegalArgumentException("Invalid rule");
            if (!unique.add(item)) throw new IllegalArgumentException("Duplicate rule");
        }
        return new ArrayList<>(unique);
    }

    public static String encode(String pkg, List<String> items) {
        if (items == null || items.size() > MAX_RULES) throw new IllegalArgumentException("Too many rules");
        Set<String> unique = new LinkedHashSet<>();
        for (String item : items) {
            if (!valid(pkg, item) || !unique.add(item)) throw new IllegalArgumentException("Invalid rule");
        }
        return String.join("\n", unique);
    }
}
