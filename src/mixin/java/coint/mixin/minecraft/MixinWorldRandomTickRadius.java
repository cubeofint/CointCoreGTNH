package coint.mixin.minecraft;

import java.util.Set;

import net.minecraft.world.ChunkCoordIntPair;
import net.minecraft.world.World;
import net.minecraft.world.chunk.IChunkProvider;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;

import coint.CointConfig;

/**
 * The active chunk set (random block ticks, snow/ice, lightning) is built from the view-distance around every
 * player. With many players on separate bases this is the bulk of the per-chunk tick cost, so allow a smaller radius
 * for it. Forced (chunk-loaded) chunks are added separately by Forge and are not affected.
 *
 * <p>
 * Chunks of the set that are not loaded yet are dropped: ticking them would load (or generate) them synchronously on
 * the server thread, ahead of the deferred chunk loading.
 */
@Mixin(World.class)
public abstract class MixinWorldRandomTickRadius {

    @Shadow
    protected Set<ChunkCoordIntPair> activeChunkSet;

    @Shadow
    protected IChunkProvider chunkProvider;

    @ModifyExpressionValue(
        method = "setActivePlayerChunksAndCheckLight",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/World;func_152379_p()I"),
        require = 1)
    private int cointcore$limitRandomTickRadius(int viewDistance) {
        int radius = CointConfig.general.randomTickChunkRadius;
        if (radius <= 0 || radius >= viewDistance || ((World) (Object) this).isRemote) {
            return viewDistance;
        }
        return radius;
    }

    @Inject(method = "setActivePlayerChunksAndCheckLight", at = @At("TAIL"), require = 1)
    private void cointcore$dropUnloadedActiveChunks(CallbackInfo ci) {
        if (((World) (Object) this).isRemote) {
            return;
        }
        IChunkProvider provider = this.chunkProvider;
        this.activeChunkSet.removeIf(pos -> !provider.chunkExists(pos.chunkXPos, pos.chunkZPos));
    }
}
