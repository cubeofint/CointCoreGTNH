package coint.mixin.minecraft;

import java.util.HashMap;

import net.minecraft.world.SpawnerAnimals;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import coint.CointConfig;

@Mixin(SpawnerAnimals.class)
public class MixinSpawnerAnimals {

    @Redirect(method = "findChunksForSpawning", at = @At(value = "INVOKE", target = "Ljava/util/HashMap;size()I"))
    private int cointcore$staticSpawnCap(HashMap map) {
        return CointConfig.limiter.staticVanillaCap ? 256 : map.size();
    }
}
