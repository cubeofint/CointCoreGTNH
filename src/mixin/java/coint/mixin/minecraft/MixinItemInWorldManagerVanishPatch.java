package coint.mixin.minecraft;

import net.minecraft.server.management.ItemInWorldManager;

import org.spongepowered.asm.mixin.Mixin;

/**
 * Marker applied after ServerUtilities' vanish mixin (higher priority) so {@code CointMixinPlugin.postApply} can
 * point its block-activation check at {@code coint.vanish.VanishBridge}.
 */
@Mixin(value = ItemInWorldManager.class, priority = 1500)
public abstract class MixinItemInWorldManagerVanishPatch {
}
