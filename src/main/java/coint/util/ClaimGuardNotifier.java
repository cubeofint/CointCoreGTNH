package coint.util;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;

/**
 * Sends claim-deny chat messages with a short cooldown to avoid duplicates from
 * overlapping guards on the same action.
 */
public final class ClaimGuardNotifier {

    public static final String DENY_MESSAGE = "Действие запрещено в чужом привате!";

    private static final String LAST_NOTIFY_TICK_KEY = "cointcore.claimDenyLastTick";
    private static final long NOTIFY_COOLDOWN_TICKS = 4L;

    private ClaimGuardNotifier() {}

    public static void notifyDenied(EntityPlayer player) {
        if (player == null || player.worldObj == null || player.worldObj.isRemote) {
            return;
        }

        long now = player.worldObj.getTotalWorldTime();
        NBTTagCompound data = player.getEntityData();
        long last = data.getLong(LAST_NOTIFY_TICK_KEY);
        if (now - last < NOTIFY_COOLDOWN_TICKS) {
            return;
        }

        data.setLong(LAST_NOTIFY_TICK_KEY, now);
        player.addChatMessage(new ChatComponentText(EnumChatFormatting.RED + DENY_MESSAGE));
    }
}
