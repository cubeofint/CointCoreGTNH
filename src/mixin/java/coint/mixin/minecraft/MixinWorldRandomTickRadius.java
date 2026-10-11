package coint.mixin.minecraft;

import net.minecraft.world.World;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;

import coint.CointConfig;

/**
 * The active chunk set (random block ticks, snow/ice, lightning) is built from the view-distance around every
 * player. With many players on separate bases this is the bulk of the per-chunk tick cost, so allow a smaller radius
 * for it. Forced (chunk-loaded) chunks are added separately by Forge and are not affected.
 */
@Mixin(World.class)
public abstract class MixinWorldRandomTickRadius {

    @ModifyExpressionValue(
        method = "setActivePlayerChunksAndCheckLight",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/World;func_152379_p()I", remap = false),
        remap = false)
    private int cointcore$limitRandomTickRadius(int viewDistance) {
        int radius = CointConfig.general.randomTickChunkRadius;
        if (radius <= 0 || radius >= viewDistance || ((World) (Object) this).isRemote) {
            return viewDistance;
        }
        return radius;
    }
}
