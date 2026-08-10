package coint.mixin.bloodmagic;

import net.minecraft.entity.player.EntityPlayer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import WayofTime.alchemicalWizardry.api.sacrifice.PlayerSacrificeHandler;
import serverutils.lib.util.NBTUtils;

@Mixin(value = PlayerSacrificeHandler.class, remap = false)
public class MixinPlayerSacrificeHandler {

    @Redirect(
        method = "sacrificePlayerHealth",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/player/EntityPlayer;setHealth(F)V", remap = true),
        require = 1)
    private static void cointcore$skipHealthDrain(EntityPlayer player, float health) {
        if (!NBTUtils.getPersistedData(player, false)
            .getBoolean("god")) {
            player.setHealth(health);
        }
    }
}
