package coint.mixin.thaumcraft;

import java.util.ArrayList;
import java.util.HashMap;

import net.minecraft.world.World;
import net.minecraftforge.common.util.ForgeDirection;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import coint.CointConfig;
import coint.CointCore;
import coint.integration.thaumcraft.EssentiaSourceSearch;
import thaumcraft.api.WorldCoordinates;
import thaumcraft.common.lib.events.EssentiaHandler;

@Mixin(value = EssentiaHandler.class, remap = false)
public abstract class MixinEssentiaHandlerSearch {

    private static final long COINTCORE$MISS_DELAY_MS = 5000L;

    @Shadow(remap = false)
    private static HashMap<WorldCoordinates, ArrayList<WorldCoordinates>> sources;

    @Shadow(remap = false)
    private static HashMap<WorldCoordinates, Long> sourcesDelay;

    @Unique
    private static boolean cointcore$searchFailed;

    @Inject(method = "getSources", at = @At("HEAD"), cancellable = true, remap = false)
    private static void cointcore$searchLoadedTiles(World world, WorldCoordinates tileLoc, ForgeDirection direction,
        int range, CallbackInfo ci) {
        if (!CointConfig.infusion.fastEssentiaSearch || world == null || tileLoc == null) {
            return;
        }
        ci.cancel();
        if (sourcesDelay.containsKey(tileLoc)) {
            Long blockedUntil = sourcesDelay.get(tileLoc);
            if (blockedUntil != null && blockedUntil > System.currentTimeMillis()) {
                return;
            }
            sourcesDelay.remove(tileLoc);
        }

        try {
            ArrayList<WorldCoordinates> found = EssentiaSourceSearch.find(world, tileLoc, direction, range);
            if (found.isEmpty()) {
                sourcesDelay.put(tileLoc, System.currentTimeMillis() + COINTCORE$MISS_DELAY_MS);
            } else {
                sources.put(tileLoc, found);
            }
        } catch (Exception ex) {
            sourcesDelay.put(tileLoc, System.currentTimeMillis() + COINTCORE$MISS_DELAY_MS);
            if (!cointcore$searchFailed) {
                cointcore$searchFailed = true;
                CointCore.LOG.warn(
                    "[CointCore] Essentia source search failed at {}, {}, {}",
                    tileLoc.x,
                    tileLoc.y,
                    tileLoc.z,
                    ex);
            }
        }
    }
}
