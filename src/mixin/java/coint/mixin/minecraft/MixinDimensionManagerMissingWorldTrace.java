package coint.mixin.minecraft;

import java.util.HashSet;
import java.util.Set;

import net.minecraftforge.common.DimensionManager;

import org.apache.logging.log4j.LogManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Records the caller, which Forge's failed hotload message does not include. */
@Mixin(value = DimensionManager.class, remap = false)
public abstract class MixinDimensionManagerMissingWorldTrace {

    @Unique
    private static Set<Integer> cointcore$missingWorlds;

    @Inject(method = "initDimension", at = @At("HEAD"), require = 1)
    private static void cointcore$traceMissingWorld(int dimension, CallbackInfo ci) {
        if (DimensionManager.isDimensionRegistered(dimension)) return;
        synchronized (DimensionManager.class) {
            if (cointcore$missingWorlds == null) cointcore$missingWorlds = new HashSet<>();
            // Bound memory and diagnostics even if a broken caller supplies arbitrary dimension IDs.
            if (cointcore$missingWorlds.size() >= 64 || !cointcore$missingWorlds.add(dimension)) return;
        }
        LogManager.getLogger("cointcore")
            .warn(
                "[MissingDimension] Requested unregistered dimension " + dimension
                    + "; caller trace is logged once per dimension per JVM",
                new IllegalStateException("Unregistered dimension hotload caller"));
    }
}
