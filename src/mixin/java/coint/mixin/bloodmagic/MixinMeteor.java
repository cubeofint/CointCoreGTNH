package coint.mixin.bloodmagic;

import java.util.List;

import net.minecraft.world.World;
import net.minecraft.world.chunk.IChunkProvider;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import WayofTime.alchemicalWizardry.api.alchemy.energy.Reagent;
import WayofTime.alchemicalWizardry.common.summoning.meteor.Meteor;

@Mixin(value = Meteor.class, remap = false)
public class MixinMeteor {

    @Inject(method = "createMeteorImpact", at = @At("HEAD"), cancellable = true)
    private void cointcore$requireChunksLoaded(World world, int x, int y, int z, List<Reagent> reagents,
        CallbackInfo ci) {
        var meteor = (Meteor) (Object) this;
        int checkRadius = Meteor.getNewRadius(meteor.radius, reagents) * 4;

        IChunkProvider provider = world.getChunkProvider();
        int minChunkX = (x - checkRadius) >> 4;
        int maxChunkX = (x + checkRadius) >> 4;
        int minChunkZ = (z - checkRadius) >> 4;
        int maxChunkZ = (z + checkRadius) >> 4;

        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                if (!provider.chunkExists(cx, cz)) {
                    ci.cancel();
                    return;
                }
            }
        }
    }
}
