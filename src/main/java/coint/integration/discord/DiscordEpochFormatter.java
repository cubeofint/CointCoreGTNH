package coint.integration.discord;

import net.minecraft.entity.player.EntityPlayerMP;

import coint.epochsync.EpochEntry;
import coint.epochsync.EpochRegistry;
import coint.integration.serverutilities.RanksManager;

/** Only the epoch, independent of donor/staff chat formatting. */
public final class DiscordEpochFormatter {

    private DiscordEpochFormatter() {}

    /** Call on the source server thread; the returned label no longer depends on mutable ranks or epoch config. */
    public static String capture(EntityPlayerMP sender) {
        if (sender == null || EpochRegistry.INST == null) return null;
        EpochEntry epoch = RanksManager.get()
            .getPlayerEpoch(sender.getUniqueID());
        if (epoch == null) return null;
        String display = label(epoch.displayName);
        return display == null ? label(epoch.rankName) : display;
    }

    static String prefix(String epoch) {
        String label = label(epoch);
        return label == null ? "" : "[" + label + "] ";
    }

    private static String label(String text) {
        String label = DiscordModerationWebhook.stripFormatting(text)
            .replaceAll("(?i)&[0-9a-fk-or]", "")
            .trim();
        while (label.startsWith("[") && label.endsWith("]")) {
            label = label.substring(1, label.length() - 1)
                .trim();
        }
        return label.isEmpty() ? null : label;
    }
}
