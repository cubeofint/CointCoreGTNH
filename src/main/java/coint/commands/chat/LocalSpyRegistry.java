package coint.commands.chat;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;

import coint.config.CointConfig;

/**
 * Tracks which administrators have local-chat spy mode enabled and routes spy copies to them.
 *
 * <p>
 * Works analogously to {@link coint.commands.dm.SocialSpyRegistry} for DMs, but covers
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

    private static final Set<String> SPIES = ConcurrentHashMap.newKeySet();

    /** Prefix shown before every spy-copy message. */
    public static final String SPY_PREFIX = EnumChatFormatting.DARK_GRAY + "["
        + EnumChatFormatting.LIGHT_PURPLE
        + "ЛОКАЛ"
        + EnumChatFormatting.DARK_GRAY
        + "] ";

    private LocalSpyRegistry() {}

    /**
     * Toggles local-spy mode for {@code playerName}.
     *
     * @return {@code true} if spy mode is now <em>enabled</em>
     */
    public static boolean toggle(String playerName) {
        if (SPIES.remove(playerName)) {
            return false;
        }
        SPIES.add(playerName);
        return true;
    }

    /** Returns {@code true} if the player currently has local-spy mode on. */
    public static boolean isEnabled(String playerName) {
        return SPIES.contains(playerName);
    }

    /** Removes the entry; call on player disconnect to avoid stale state. */
    public static void remove(String playerName) {
        SPIES.remove(playerName);
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

        double radiusSq = CointConfig.localChatRadius * CointConfig.localChatRadius;
        int senderDim = sender.dimension;

        // Location suffix shown to spies — dimension + integer block coordinates.
        String location = EnumChatFormatting.DARK_GRAY + "(dim:"
            + senderDim
            + " x:"
            + (int) sender.posX
            + " z:"
            + (int) sender.posZ
            + ")";

        for (EntityPlayerMP spy : MinecraftServer.getServer()
            .getConfigurationManager().playerEntityList) {

            String spyName = spy.getCommandSenderName();
            if (!isEnabled(spyName)) continue;
            if (spy == sender) continue; // sender already sees their own text

            // Skip if the spy was already within range (they got the normal message).
            if (!CointConfig.sameDimensionOnly || spy.dimension == senderDim) {
                double dx = spy.posX - sender.posX;
                double dy = spy.posY - sender.posY;
                double dz = spy.posZ - sender.posZ;
                if (dx * dx + dy * dy + dz * dz <= radiusSq) {
                    continue;
                }
            }

            ChatComponentText msg = new ChatComponentText(
                SPY_PREFIX + EnumChatFormatting.AQUA
                    + senderDisplay
                    + " "
                    + location
                    + EnumChatFormatting.GRAY
                    + ": "
                    + EnumChatFormatting.WHITE
                    + text);
            spy.addChatMessage(msg);
        }
    }
}
