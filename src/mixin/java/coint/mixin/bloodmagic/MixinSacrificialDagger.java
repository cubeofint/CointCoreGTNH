package coint.mixin.bloodmagic;

import net.minecraft.entity.player.EntityPlayer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import WayofTime.alchemicalWizardry.common.items.SacrificialDagger;
import serverutils.lib.util.NBTUtils;

@Mixin(value = SacrificialDagger.class, remap = false)
public class MixinSacrificialDagger {

    @Redirect(
        method = "onItemRightClick",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/player/EntityPlayer;setHealth(F)V"),
        require = 1)
    private void cointcore$skipHealthDrain(EntityPlayer player, float health) {
        if (!NBTUtils.getPersistedData(player, false)
            .getBoolean("god")) {
            player.setHealth(health);
        }
    }
}
