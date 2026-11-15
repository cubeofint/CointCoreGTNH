package coint.mixin.minecraft;

import java.util.List;

import net.minecraft.entity.player.EntityPlayerMP;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Avoid loading an unwatched chunk just to build packets with no recipients. */
@Mixin(targets = "net.minecraft.server.management.PlayerManager$PlayerInstance")
public abstract class MixinPlayerInstanceEmptyUpdates {

    @Shadow
    @Final
    private List<EntityPlayerMP> playersWatchingChunk;

    @Shadow
    private int numberOfTilesToUpdate;

    @Shadow
    private int flagsYAreasToUpdate;

    @Inject(method = "sendChunkUpdate", at = @At("HEAD"), cancellable = true, require = 1)
    private void cointcore$skipUnwatchedUpdates(CallbackInfo ci) {
        if (playersWatchingChunk.isEmpty()) {
            // A future watcher receives a complete chunk, not these obsolete deltas.
            numberOfTilesToUpdate = 0;
            flagsYAreasToUpdate = 0;
            ci.cancel();
        }
    }
}
