package coint.trade;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraftforge.oredict.OreDictionary;

import coint.CointConfig;
import coint.CointCore;
import coint.epochsync.EpochEntry;
import coint.epochsync.EpochRegistry;
import coint.integration.serverutilities.RanksManager;
import cpw.mods.fml.common.registry.GameRegistry;

/**
 * Items a player may receive in a trade only from an epoch on ({@link CointConfig.Trade#epochItems}). The lines are
 * read again whenever the config changes.
 */
final class TradeLimits {

    private static String[] parsedFrom;
    private static List<Rule> rules = new ArrayList<>();

    private TradeLimits() {}

    /**
     * Why {@code receiver} may not get {@code stack}: the epoch it needs, for the player to read; null when he may.
     */
    static String blockReason(ItemStack stack, EntityPlayerMP receiver) {
        if (stack == null || stack.getItem() == null) {
            return null;
        }
        List<Rule> current = rules();
        if (current.isEmpty()) {
            return null;
        }
        EpochEntry needed = null;
        for (Rule rule : current) {
            EpochEntry epoch = epoch(rule.rank);
            if (epoch != null && (needed == null || epoch.priority > needed.priority) && rule.matches(stack)) {
                needed = epoch;
            }
        }
        if (needed == null) {
            return null;
        }
        EpochEntry has = RanksManager.get()
            .getPlayerEpoch(receiver.getUniqueID());
        if (has != null && has.priority >= needed.priority) {
            return null;
        }
        return needed.displayName == null || needed.displayName.isEmpty() ? needed.rankName : needed.displayName;
    }

    private static EpochEntry epoch(String rank) {
        return EpochRegistry.INST == null ? null : EpochRegistry.INST.getEpoch(rank);
    }

    private static synchronized List<Rule> rules() {
        String[] lines = CointConfig.trade.epochItems;
        if (lines == null) {
            lines = new String[0];
        }
        if (parsedFrom != null && Arrays.equals(parsedFrom, lines)) {
            return rules;
        }
        List<Rule> parsed = new ArrayList<>();
        for (String line : lines) {
            Rule rule = Rule.parse(line);
            if (rule != null) {
                parsed.add(rule);
            } else if (line != null && !line.trim()
                .isEmpty()) {
                    CointCore.LOG.warn("[Trade] Bad epoch item line, skipped: {}", line);
                }
        }
        parsedFrom = lines.clone();
        rules = parsed;
        return parsed;
    }

    private static final class Rule {

        final String rank;
        /** ore:name: the ore dictionary name; else null. */
        final String ore;
        /** modid:name with * as a wildcard, lower case. */
        final Pattern id;
        /** -1: any meta. */
        final int meta;

        private Rule(String rank, String ore, Pattern id, int meta) {
            this.rank = rank;
            this.ore = ore;
            this.id = id;
            this.meta = meta;
        }

        static Rule parse(String line) {
            if (line == null) {
                return null;
            }
            int bar = line.indexOf('|');
            if (bar <= 0 || bar == line.length() - 1) {
                return null;
            }
            String rank = line.substring(0, bar)
                .trim();
            String item = line.substring(bar + 1)
                .trim();
            if (rank.isEmpty() || item.isEmpty()) {
                return null;
            }
            if (item.regionMatches(true, 0, "ore:", 0, 4)) {
                String ore = item.substring(4)
                    .trim();
                return ore.isEmpty() ? null : new Rule(rank, ore, null, -1);
            }
            int meta = -1;
            int at = item.lastIndexOf('@');
            if (at > 0) {
                try {
                    meta = Integer.parseInt(
                        item.substring(at + 1)
                            .trim());
                } catch (NumberFormatException e) {
                    return null;
                }
                item = item.substring(0, at)
                    .trim();
            }
            if (item.indexOf(':') <= 0) {
                return null;
            }
            StringBuilder regex = new StringBuilder();
            for (String part : item.toLowerCase(Locale.ROOT)
                .split("\\*", -1)) {
                if (regex.length() > 0) {
                    regex.append(".*");
                }
                regex.append(Pattern.quote(part));
            }
            return new Rule(rank, null, Pattern.compile(regex.toString()), meta);
        }

        boolean matches(ItemStack stack) {
            if (ore != null) {
                int wanted = OreDictionary.getOreID(ore);
                for (int id : OreDictionary.getOreIDs(stack)) {
                    if (id == wanted) {
                        return true;
                    }
                }
                return false;
            }
            if (meta >= 0 && stack.getItemDamage() != meta) {
                return false;
            }
            GameRegistry.UniqueIdentifier uid = GameRegistry.findUniqueIdentifierFor(stack.getItem());
            return uid != null && id.matcher((uid.modId + ":" + uid.name).toLowerCase(Locale.ROOT))
                .matches();
        }
    }
}
