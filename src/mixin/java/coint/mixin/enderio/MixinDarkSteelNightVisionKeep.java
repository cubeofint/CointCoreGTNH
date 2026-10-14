package coint.mixin.enderio;

import net.minecraft.entity.player.EntityPlayer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import coint.events.NightVisionHandler;
import crazypants.enderio.item.darksteel.DarkSteelController;

/**
 * EnderIO handles the Night Vision helmet upgrade and the Thaumic Exploration Night Vision enchantment. While such a
 * helmet is worn with the feature toggled off, it removes every ambient night vision effect each tick — including the
 * one given by /nv. Keep night vision for players who turned it on with /nv.
 */
@Mixin(value = DarkSteelController.class, remap = false)
public abstract class MixinDarkSteelNightVisionKeep {

    @Redirect(
        method = "updateNightvision",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/entity/player/EntityPlayer;removePotionEffect(I)V",
            remap = true),
        require = 0)
    private void cointcore$keepCommandNightVision(EntityPlayer player, int potionId) {
        if (!NightVisionHandler.isEnabled(player)) {
            player.removePotionEffect(potionId);
        }
    }
}
