package coint.commands.spy;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IChatComponent;

/**
 * Tracks which administrators have DM-spy mode enabled and routes spy copies to them.
 *
 * <p>
 * State is in-memory only — resets on server restart. That is intentional: an admin who
 * logged off is no longer actively monitoring, so they should re-enable spy on next login.
 *
 * <p>
 * Thread-safe: uses a {@link ConcurrentHashMap}-backed set.
 */
public final class PersonalSpyRegistry {

    private static final Set<String> SPIES = ConcurrentHashMap.newKeySet();

    /** In-game prefix shown before every spy-copy message. */
    public static final String SPY_PREFIX = EnumChatFormatting.DARK_GRAY + "["
        + EnumChatFormatting.GOLD
        + "SPY"
        + EnumChatFormatting.DARK_GRAY
        + "] ";

    private PersonalSpyRegistry() {}

    /**
     * Toggles spy mode for {@code playerName}.
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

    public static ChatComponentText toggleWithMessage(String player) {
        return new ChatComponentText(
            PersonalSpyRegistry.SPY_PREFIX
                + (toggle(player) ? EnumChatFormatting.GREEN + "Включён — вы видите личную переписку игроков."
                    : EnumChatFormatting.RED + "Выключен."));
    }

    /** Returns {@code true} if the player currently has spy mode on. */
    public static boolean isEnabled(String playerName) {
        return SPIES.contains(playerName);
    }

    /**
     * Removes the spy entry (call on player disconnect to avoid stale state if the server
     * chooses to enforce that behaviour).
     */
    public static void remove(String playerName) {
        SPIES.remove(playerName);
    }

    /**
     * Sends a spy-copy of the message to all online admins who have spy mode enabled,
     * <em>excluding</em> the sender and target (who already received the real message).
     *
     * <p>
     * Format: {@code §8[§6SPY§8] §bSenderName §7→ §bTargetName§7: §ftext}
     *
     * @param senderDisplay unformatted display name of the sender
     * @param targetDisplay unformatted display name of the recipient
     * @param text          the original message component (will be deep-copied per recipient)
     */
    public static void notifySpies(String senderDisplay, String targetDisplay, IChatComponent text) {
        if (SPIES.isEmpty()) return;

        for (EntityPlayerMP spy : MinecraftServer.getServer()
            .getConfigurationManager().playerEntityList) {
            String spyName = spy.getCommandSenderName();
            if (!isEnabled(spyName)) continue;
            if (spyName.equals(senderDisplay) || spyName.equals(targetDisplay)) continue;

            // Build a fresh component per recipient so chat events don't share mutable state.
            ChatComponentText msg = new ChatComponentText(
                SPY_PREFIX + EnumChatFormatting.AQUA
                    + senderDisplay
                    + EnumChatFormatting.GRAY
                    + " → "
                    + EnumChatFormatting.AQUA
                    + targetDisplay
                    + EnumChatFormatting.GRAY
                    + ": "
                    + EnumChatFormatting.WHITE);
            msg.appendSibling(text.createCopy());
            spy.addChatMessage(msg);
        }
    }
}
