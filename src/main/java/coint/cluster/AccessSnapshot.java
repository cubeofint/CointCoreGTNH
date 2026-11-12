package coint.cluster;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.google.gson.Gson;

/** Wire format: raw local rules include parent lists, wildcards, negative rules and arbitrary rank config values. */
final class AccessSnapshot {

    static final Gson JSON = new Gson();
    int format = 1;
    String authority;
    Map<String, Rules> definitions = new LinkedHashMap<>();
    Map<String, Rules> players = new LinkedHashMap<>();
    Set<String> epochRanks = new LinkedHashSet<>();
    Map<String, Map<String, Long>> parentExpiry = new LinkedHashMap<>();
    // Null means OP synchronization is disabled on the publisher, never "revoke everyone".
    Map<String, Operator> operators;

    void validate(String expectedAuthority) {
        if (format != 1 || !expectedAuthority.equals(authority)
            || definitions == null
            || definitions.isEmpty()
            || players == null
            || epochRanks == null
            || parentExpiry == null) {
            throw new IllegalArgumentException("Invalid or empty authoritative access snapshot");
        }
        definitions.forEach((id, rules) -> {
            if (!serverutils.ranks.Ranks.isValidName(id)) {
                throw new IllegalArgumentException("Invalid rank id: " + id);
            }
            validateRules(rules);
        });
        players.forEach((id, rules) -> {
            UUID.fromString(id);
            validateRules(rules);
        });
        parentExpiry.forEach((id, deadlines) -> {
            UUID.fromString(id);
            if (deadlines == null || deadlines.containsKey(null) || deadlines.containsValue(null)) {
                throw new IllegalArgumentException("Invalid temporary rank deadlines");
            }
        });
        if (operators != null) {
            operators.forEach((id, op) -> {
                UUID.fromString(id);
                if (op == null || op.name == null || op.name.isEmpty() || op.level < 0 || op.level > 4) {
                    throw new IllegalArgumentException("Invalid operator: " + id);
                }
            });
        }
    }

    private static void validateRules(Rules rules) {
        if (rules == null || rules.permissions == null || rules.comment == null) {
            throw new IllegalArgumentException("Missing access rules");
        }
        rules.permissions.forEach((node, rule) -> {
            if (node == null || node.isEmpty() || rule == null || rule.value == null || rule.comment == null) {
                throw new IllegalArgumentException("Invalid permission entry");
            }
        });
    }

    static final class Rules {

        String name = "";
        String comment = "";
        Map<String, Rule> permissions = new LinkedHashMap<>();
    }

    static final class Rule {

        String value;
        String comment;

        Rule(String value, String comment) {
            this.value = value;
            this.comment = comment;
        }
    }

    static final class Operator {

        String name;
        int level;

        Operator(String name, int level) {
            this.name = name;
            this.level = level;
        }
    }
}
