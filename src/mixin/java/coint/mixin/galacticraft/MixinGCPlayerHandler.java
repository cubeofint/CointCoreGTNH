package coint.mixin.galacticraft;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.potion.PotionEffect;
import net.minecraft.util.DamageSource;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import micdoodle8.mods.galacticraft.core.entities.player.GCPlayerHandler;
import serverutils.lib.util.NBTUtils;

/**
 * Extends god-mode protection to Galacticraft's thermal system, which has no Forge event
 * and therefore cannot be intercepted from {@link coint.mixin.minecraft.MixinEntityPlayer}.
 *
 * <p>
 * When a player's {@code thermalLevel} reaches ±22 inside
 * {@code GCPlayerHandler.checkThermalStatus()}, two distinct effects are applied:
 * <ol>
 * <li>A 1.5 HP absolute hit via {@code attackEntityFrom(DamageSourceGC.thermal, 1.5F)}</li>
 * <li>Side-effect potions — Slowness III or Nausea II — applied directly via
 * {@code addPotionEffect}, completely bypassing any damage-level check</li>
 * </ol>
 *
 * <p>
 * The HP hit is already cancelled by {@code MixinEntityPlayer}, but
 * the potions are not. This mixin adds a second gate on the HP hit (earlier in the call
 * stack, so GC state isn't disturbed) and suppresses both potions.
 *
 * <p>
 * Oxygen suffocation is handled via the Forge event
 * {@code GCCoreOxygenSuffocationEvent.Pre}
 * in {@link coint.integration.galacticraft.GalacticraftGodHandler}.
 */
@Mixin(value = GCPlayerHandler.class, remap = false)
public class MixinGCPlayerHandler {

    @Unique
    private static boolean cointcore$isGodMode(EntityPlayerMP player) {
        return NBTUtils.getPersistedData(player, false)
            .getBoolean("god");
    }

    // ── Thermal: HP damage ───────────────────────────────────────────────────

    /**
     * Suppresses the thermal damage hit for god-mode players, preventing the hit from
     * reaching {@link coint.mixin.minecraft.MixinEntityPlayer} at all (cleaner state).
     */
    @Redirect(
        method = "checkThermalStatus(Lnet/minecraft/entity/player/EntityPlayerMP;Lmicdoodle8/mods/galacticraft/core/entities/player/GCPlayerStats;)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/entity/player/EntityPlayerMP;attackEntityFrom(Lnet/minecraft/util/DamageSource;F)Z"))
    @SuppressWarnings("unused")
    private boolean cointcore$suppressThermalDamage(EntityPlayerMP player, DamageSource source, float amount) {
        if (cointcore$isGodMode(player)) return false;
        return player.attackEntityFrom(source, amount);
    }

    // ── Thermal: side-effect potions ────────────────────────────────────────

    /**
     * Suppresses the Slowness III and Nausea II potions that are applied as thermal
     * side-effects ({@code thermalLevel < -15} and {@code thermalLevel > 15} respectively)
     * for god-mode players.
     *
     * <p>
     * A single {@code @Redirect} covers both {@code addPotionEffect} call sites.
     */
    @Redirect(
        method = "checkThermalStatus(Lnet/minecraft/entity/player/EntityPlayerMP;Lmicdoodle8/mods/galacticraft/core/entities/player/GCPlayerStats;)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/entity/player/EntityPlayerMP;addPotionEffect(Lnet/minecraft/potion/PotionEffect;)V"))
    @SuppressWarnings("unused")
    private void cointcore$suppressThermalPotions(EntityPlayerMP player, PotionEffect effect) {
        if (!cointcore$isGodMode(player)) {
            player.addPotionEffect(effect);
        }
    }
}
