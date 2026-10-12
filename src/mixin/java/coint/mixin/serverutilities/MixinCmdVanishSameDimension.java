package coint.mixin.serverutilities;

import net.minecraft.entity.EntityTrackerEntry;
import net.minecraft.entity.player.EntityPlayerMP;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import serverutils.command.CmdVanish;

/**
 * Leaving vanish makes ServerUtilities start tracking the admin for every online player. The tracker only checks the
 * distance, not the world, so players in other dimensions near the same coordinates (every personal world sits around
 * 0,0) got the admin spawned into their world. Only offer the admin to players in the same world.
 */
@Mixin(value = CmdVanish.class, remap = false)
public abstract class MixinCmdVanishSameDimension {

    @Redirect(
        method = "updateVanishStatus",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/entity/EntityTrackerEntry;tryStartWachingThis(Lnet/minecraft/entity/player/EntityPlayerMP;)V",
            remap = true),
        require = 0)
    private static void cointcore$sameWorldOnly(EntityTrackerEntry entry, EntityPlayerMP player) {
        if (entry.myEntity != null && entry.myEntity.worldObj == player.worldObj) {
            entry.tryStartWachingThis(player);
        }
    }
}
