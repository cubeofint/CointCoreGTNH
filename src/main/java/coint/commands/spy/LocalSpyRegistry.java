package coint.commands.spy;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;

import coint.CointConfig;
import coint.events.ChatSplitHandler;
import serverutils.lib.data.ForgePlayer;

/**
 * Tracks which administrators have local-chat spy mode enabled and routes spy copies to them.
 *
 * <p>
 * Works analogously to {@link PersonalSpyRegistry} for DMs, but covers
 * <em>local</em> chat messages sent via {@link ChatSplitHandler}.
 *
 * <p>
 * A spy copy is delivered only if the admin was <em>out of range</em> (or in a different
 * dimension) for the original message — if they were already in range they received it through
 * the normal local-chat pipeline and do not need a duplicate.
 *
 * <p>
 * The copy includes the sender's dimension and block coordinates so the admin has enough
 * context to understand where the conversation is taking place.
 *
 * <p>
 * State is in-memory only — resets on server restart by design (same rationale as DM spy).
 *
 * <p>
 * Thread-safe: backed by a {@link ConcurrentHashMap} key-set.
 */
public final class LocalSpyRegistry {

    private static final Set<ForgePlayer> SPIES = ConcurrentHashMap.newKeySet();

    /**
     * Prefix shown before every spy-copy message.
     */
    public static final String SPY_PREFIX = EnumChatFormatting.DARK_GRAY + "["
        + EnumChatFormatting.LIGHT_PURPLE
        + "ЛОКАЛ"
        + EnumChatFormatting.DARK_GRAY
        + "] ";

    private LocalSpyRegistry() {}

    /**
     * Toggles local-spy mode for {@code player}.
     *
     * @return {@code true} if spy mode is now <em>enabled</em>
     */
    public static boolean toggle(ForgePlayer player) {
        if (SPIES.remove(player)) {
            return false;
        }
        SPIES.add(player);
        return true;
    }

    public static ChatComponentText toggleWithMessage(ForgePlayer player) {
        return new ChatComponentText(
            LocalSpyRegistry.SPY_PREFIX
                + (toggle(player) ? EnumChatFormatting.GREEN + "Включён — вы видите локальные сообщения всех игроков."
                    : EnumChatFormatting.RED + "Выключен."));
    }

    public static void enable(ForgePlayer player) {
        SPIES.add(player);
    }

    public static void disable(ForgePlayer player) {
        SPIES.remove(player);
    }

    /**
     * Sends a spy-copy of the local message to all admins who have local-spy enabled
     * <em>and</em> were out of the original message's range (so they didn't see it normally).
     *
     * <p>
     * Format:
     *
     * <pre>
     * §8[§dЛОКАЛ§8] §bPlayerName §8(dim:0 x:-12 z:305)§7: §ftext
     * </pre>
     *
     * @param sender        the player who sent the message
     * @param senderDisplay rank-formatted display name
     * @param text          raw message text (no colour codes from the local format string)
     */
    public static void notifySpies(EntityPlayerMP sender, String senderDisplay, String text) {
        if (SPIES.isEmpty()) return;

        double radiusSq = CointConfig.chat.radius * CointConfig.chat.radius;
        String location = EnumChatFormatting.DARK_GRAY + "(dim:" + sender.dimension + ")";

        for (ForgePlayer spy : SPIES) {
            if (!spy.isOnline() || spy.equalsPlayer(sender)) continue;
            if (spy.getPlayer().dimension == sender.dimension
                && sender.getDistanceSqToEntity(spy.getPlayer()) <= radiusSq) continue;

            ChatComponentText msg = new ChatComponentText(
                SPY_PREFIX + EnumChatFormatting.AQUA
                    + senderDisplay
                    + " "
                    + location
                    + EnumChatFormatting.GRAY
                    + ": "
                    + EnumChatFormatting.WHITE
                    + text);
            spy.getPlayer()
                .addChatMessage(msg);
        }
    }
}
