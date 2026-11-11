package coint.prefix;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumChatFormatting;

import coint.util.ChatUtil;
import serverutils.ServerUtilitiesPermissions;
import serverutils.data.ServerUtilitiesPlayerData;
import serverutils.lib.data.ForgePlayer;
import serverutils.lib.data.Universe;
import serverutils.lib.util.NBTUtils;
import serverutils.ranks.Rank;
import serverutils.ranks.Ranks;

/**
 * Which prefix a player wears and the color of his name.
 * <ul>
 * <li>Prefix: by default the one of his highest rank (as ServerUtilities does). He may pick the prefix of any other
 * rank he has (an epoch reached, a donation rank) with /prefix.</li>
 * <li>Name color: one of the 16 colors with /namecolor, for those with the permission.</li>
 * </ul>
 * Both are kept with the player and change the name in chat and in the tab list on every server of the network.
 */
public final class PrefixService {

    private static final String TAG_PREFIX = "cointcore_prefix", TAG_COLOR = "cointcore_namecolor";

    private PrefixService() {}

    /** A prefix a player may wear: the rank it comes from and its name format. */
    public static final class Option {

        public final String rank, format;

        Option(String rank, String format) {
            this.rank = rank;
            this.format = format;
        }

        /** The prefix alone, as it shows before the name. */
        public String prefix() {
            return prefixOf(format);
        }
    }

    /** The ranks of the player that have a prefix, highest first. */
    public static List<Option> available(EntityPlayerMP player) {
        Map<String, Option> result = new LinkedHashMap<>();
        if (!Ranks.isActive()) {
            return new ArrayList<>();
        }
        Rank own = Ranks.INSTANCE.getPlayerRank(player);
        Deque<Rank> queue = new ArrayDeque<>(own.getParents());
        if (queue.isEmpty()) {
            Rank fallback = Ranks.INSTANCE.getDefaultPlayerRank();
            if (fallback != null) {
                queue.add(fallback);
            }
        }
        Set<String> seen = new HashSet<>();
        List<Rank> ranks = new ArrayList<>();
        while (!queue.isEmpty()) {
            Rank rank = queue.poll();
            if (rank == null || !seen.add(rank.getId())) {
                continue;
            }
            ranks.add(rank);
            queue.addAll(rank.getParents());
        }
        ranks.sort(null);
        for (Rank rank : ranks) {
            String format = rank.getLocalPermission(ServerUtilitiesPermissions.CHAT_NAME_FORMAT);
            if (format != null && !format.isEmpty() && format.contains("{name}") && !prefixOf(format).isEmpty()) {
                result.putIfAbsent(rank.getId(), new Option(rank.getId(), format));
            }
        }
        return new ArrayList<>(result.values());
    }

    /** The rank whose prefix the player picked, or null for his highest. */
    public static String selected(EntityPlayerMP player) {
        String s = NBTUtils.getPersistedData(player, false)
            .getString(TAG_PREFIX);
        return s.isEmpty() ? null : s;
    }

    /** Picks the prefix of {@code rank}, or the default for null. False when he has no such rank. */
    public static boolean select(EntityPlayerMP player, String rank) {
        NBTTagCompound data = NBTUtils.getPersistedData(player, true);
        if (rank == null) {
            data.removeTag(TAG_PREFIX);
        } else {
            String id = rank.toLowerCase(Locale.ROOT);
            if (available(player).stream()
                .noneMatch(o -> o.rank.equals(id))) {
                return false;
            }
            data.setString(TAG_PREFIX, id);
        }
        refresh(player);
        return true;
    }

    /** The color of the name, or null for the rank's own. */
    public static EnumChatFormatting color(EntityPlayerMP player) {
        String s = NBTUtils.getPersistedData(player, false)
            .getString(TAG_COLOR);
        return s.isEmpty() ? null : EnumChatFormatting.getValueByName(s);
    }

    public static void setColor(EntityPlayerMP player, EnumChatFormatting color) {
        NBTTagCompound data = NBTUtils.getPersistedData(player, true);
        if (color == null) {
            data.removeTag(TAG_COLOR);
        } else {
            data.setString(TAG_COLOR, color.getFriendlyName());
        }
        refresh(player);
    }

    /**
     * The name format to use for this player: the picked prefix (while he still has that rank) or {@code fallback},
     * with the name in his color.
     */
    public static String format(EntityPlayerMP player, String fallback) {
        String format = fallback;
        String picked = selected(player);
        if (picked != null) {
            for (Option option : available(player)) {
                if (option.rank.equals(picked)) {
                    format = option.format;
                    break;
                }
            }
        }
        EnumChatFormatting color = color(player);
        if (color != null && format != null && format.contains("{name}")) {
            format = format.replace("{name}", color + "{name}" + EnumChatFormatting.RESET);
        }
        return format;
    }

    /** The name is built again, in chat and in the tab list. */
    public static void refresh(EntityPlayerMP player) {
        ChatUtil.refreshRFN(player);
        Universe universe = Universe.loaded() ? Universe.get() : null;
        ForgePlayer forgePlayer = universe == null ? null : universe.getPlayer(player);
        if (forgePlayer != null) {
            ServerUtilitiesPlayerData data = ServerUtilitiesPlayerData.get(forgePlayer);
            data.clearCache();
            // Built again now, which also sends the new tab name to everyone.
            data.getNameForChat();
        }
    }

    /** "<§3[ZPM]§r {name}>" → "§3[ZPM]§r". */
    static String prefixOf(String format) {
        int at = format.indexOf("{name}");
        String before = at < 0 ? format : format.substring(0, at);
        return before.replace("<", "")
            .replaceAll("&([0-9a-fk-orA-FK-OR])", "§$1")
            .trim();
    }
}
