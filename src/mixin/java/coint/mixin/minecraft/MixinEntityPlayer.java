package coint.mixin.minecraft;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.DamageSource;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import serverutils.lib.util.NBTUtils;

/**
 * Makes the god-mode flag (set by {@code /god}) truly reliable by intercepting
 * {@link EntityPlayer#attackEntityFrom} at the very beginning.
 *
 * <p>
 * ServerUtilities' {@code /god} command sets {@code capabilities.disableDamage = true},
 * but that field is reset to {@code false} on respawn because the new entity is created
 * with default survival capabilities. The NBT flag {@code "god"} survives respawn
 * (stored in {@code PERSISTED_NBT_TAG}, which Forge copies via {@code PlayerEvent.Clone}),
 * so we read it here directly instead of relying on the capability state.
 *
 * <p>
 * This also covers damage sources that vanilla's own {@code disableDamage} check
 * exempts, such as {@link DamageSource#outOfWorld} (void), making god mode truly
 * unconditional.
 */
@Mixin(EntityPlayer.class)
public class MixinEntityPlayer {

    /**
     * Cancels all incoming damage for players with the {@code "god"} NBT flag set.
     * Runs server-side only; client-side hits are skipped to avoid false cancellations.
     * Also resyncs {@code capabilities.disableDamage} if it drifted out of sync
     * (e.g. after respawn) — but only when the flag is found, so there is no
     * per-hit packet spam for non-god players.
     */
    @Inject(method = "attackEntityFrom(Lnet/minecraft/util/DamageSource;F)Z", at = @At("HEAD"), cancellable = true)
    @SuppressWarnings({ "unused", "ConstantConditions" })
    private void cointcore$godMode(DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
        EntityPlayer self = (EntityPlayer) (Object) this;
        if (self.worldObj == null || self.worldObj.isRemote) {
            return;
        }
        NBTTagCompound persisted = NBTUtils.getPersistedData(self, false);
        if (persisted.getBoolean("god")) {
            // Resync the capability if it was lost (e.g. after respawn) so that
            // vanilla code paths (inventory screen invulnerability etc.) also work.
            if (!self.capabilities.disableDamage) {
                self.capabilities.disableDamage = true;
                self.sendPlayerAbilities();
            }
            cir.setReturnValue(false);
        }
    }
}
