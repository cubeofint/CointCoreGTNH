package coint.vanish;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;

import serverutils.data.ServerUtilitiesPlayerData;
import serverutils.lib.util.ServerUtils;

/**
 * Called from the ServerUtilities vanish block-activation wrapper in place of {@code ServerUtils.isVanished} (patched
 * in {@code CointMixinPlugin}). That wrapper refuses every right-click on a non-container block while vanished, even
 * with the "interact" vanish option on, so doors, levers and GregTech machines could not be used. With "interact" on
 * the block is activated normally.
 */
public final class VanishBridge {

    private VanishBridge() {}

    public static boolean restrictsBlockActivation(Entity entity) {
        if (!ServerUtils.isVanished(entity) || !(entity instanceof EntityPlayerMP player)) {
            return false;
        }
        return !ServerUtilitiesPlayerData.get(player)
            .getVanishData().interaction;
    }
}
