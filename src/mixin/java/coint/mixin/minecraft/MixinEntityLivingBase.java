package coint.mixin.minecraft;

import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.DamageSource;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import serverutils.lib.util.NBTUtils;

/**
 * Second layer of god-mode protection on {@link EntityLivingBase}.
 *
 * <p>
 * {@link MixinEntityPlayer} already covers the override in {@link EntityPlayer}.
 * This mixin adds two more safety nets that together make god mode truly unconditional:
 *
 * <ol>
 * <li><b>attackEntityFrom (fallback)</b> — catches calls that land directly on
 * {@link EntityLivingBase#attackEntityFrom} without going through the
 * {@link EntityPlayer} override (e.g. some modded damage sources that call
 * super explicitly, or use raw casts).</li>
 * <li><b>setHealth (final barrier)</b> — prevents direct health reduction
 * regardless of how the damage pipeline reached it. This covers any mod that
 * bypasses {@code attackEntityFrom} entirely and just lowers HP directly
 * (e.g. some radiation, temperature or aura systems in GTNH).</li>
 * </ol>
 */
@Mixin(EntityLivingBase.class)
public class MixinEntityLivingBase {

    /**
     * Fallback cancellation at the {@link EntityLivingBase} level.
     * Fires when {@link EntityPlayer#attackEntityFrom} calls {@code super}, or when
     * any code calls {@code attackEntityFrom} via a raw {@link EntityLivingBase} reference.
     */
    @Inject(method = "attackEntityFrom(Lnet/minecraft/util/DamageSource;F)Z", at = @At("HEAD"), cancellable = true)
    @SuppressWarnings({ "unused", "ConstantConditions" })
    private void cointcore$godModeFallback(DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
        if (!((Object) this instanceof EntityPlayer)) {
            return;
        }
        EntityPlayer self = (EntityPlayer) (Object) this;
        if (self.worldObj == null || self.worldObj.isRemote) {
            return;
        }
        if (NBTUtils.getPersistedData(self, false)
            .getBoolean("god")) {
            cir.setReturnValue(false);
        }
    }

    /**
     * Final barrier: prevents any direct health reduction for god-mode players.
     * Only blocks decreases — healing (higher value) is allowed through unchanged.
     * Covers damage sources that skip {@code attackEntityFrom} entirely.
     */
    @Inject(method = "setHealth(F)V", at = @At("HEAD"), cancellable = true)
    @SuppressWarnings({ "unused", "ConstantConditions" })
    private void cointcore$godModeHealthSet(float health, CallbackInfo ci) {
        if (!((Object) this instanceof EntityPlayer)) {
            return;
        }
        EntityPlayer self = (EntityPlayer) (Object) this;
        if (self.worldObj == null || self.worldObj.isRemote) {
            return;
        }
        if (health < self.getHealth() && NBTUtils.getPersistedData(self, false)
            .getBoolean("god")) {
            ci.cancel();
        }
    }
}
