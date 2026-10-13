package coint.mixin.minecraft;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.world.ChunkCoordIntPair;
import net.minecraft.world.World;
import net.minecraft.world.chunk.IChunkProvider;

import org.apache.logging.log4j.LogManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
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
        if (cointcore$dropUnloadedDisabled || ((World) (Object) this).isRemote) {
            return;
        }
        // Hodgepodge replaces the set with one whose iterator cannot remove (removeIf crashed the server), so collect
        // first and remove through Set.remove, which it does support. Iterated pairs are fresh objects, safe to keep.
        try {
            IChunkProvider provider = this.chunkProvider;
            // Forced chunks must stay: ForgeChunkManager.forceChunk does not load a chunk, it is loaded exactly here,
            // when the tick reaches it in the active set (ServerUtilities forces chunk-loaded bases after startup).
            Map<ChunkCoordIntPair, ?> forced = ((World) (Object) this).getPersistentChunks()
                .asMap();
            List<ChunkCoordIntPair> unloaded = null;
            for (ChunkCoordIntPair pos : this.activeChunkSet) {
                if (!provider.chunkExists(pos.chunkXPos, pos.chunkZPos) && !forced.containsKey(pos)) {
                    if (unloaded == null) {
                        unloaded = new ArrayList<>();
                    }
                    unloaded.add(pos);
                }
            }
            if (unloaded != null) {
                for (ChunkCoordIntPair pos : unloaded) {
                    this.activeChunkSet.remove(pos);
                }
            }
        } catch (RuntimeException e) {
            cointcore$dropUnloadedDisabled = true;
            LogManager.getLogger("cointcore")
                .warn(
                    "[Perf] Active chunk set of {} cannot be filtered, skipping unloaded-chunk filter",
                    this.activeChunkSet.getClass()
                        .getName(),
                    e);
        }
    }

    @Unique
    private static boolean cointcore$dropUnloadedDisabled;
}
