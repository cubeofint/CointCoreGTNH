package coint.mixin.minecraft;

import java.util.HashMap;

import net.minecraft.world.SpawnerAnimals;
import net.minecraft.world.WorldServer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import coint.CointConfig;

@Mixin(SpawnerAnimals.class)
public class MixinSpawnerAnimals {

    @Inject(method = "findChunksForSpawning", at = @At("HEAD"), cancellable = true)
    private void cointcore$limitSpawnCheckRate(WorldServer world, boolean spawnHostileMobs, boolean spawnPeacefulMobs,
        boolean spawnAnimals, CallbackInfoReturnable<Integer> cir) {
        int interval = CointConfig.limiter.spawnCheckInterval;
        if (interval <= 1 || spawnAnimals) return;

        int offset = Math.floorMod(world.provider.dimensionId, interval);
        if ((world.getTotalWorldTime() + offset) % interval != 0L) {
            cir.setReturnValue(0);
        }
    }

    @Redirect(method = "findChunksForSpawning", at = @At(value = "INVOKE", target = "Ljava/util/HashMap;size()I"))
    private int cointcore$scaledSpawnCap(HashMap map) {
        int base = CointConfig.limiter.staticVanillaCap ? 256 : map.size();
        int percent = Math.max(0, Math.min(100, CointConfig.limiter.naturalSpawnCapPercent));
        if (percent >= 100) return base;
        return base * percent / 100;
    }
}
