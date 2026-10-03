package coint.mixin.personalspace;

import net.minecraft.server.MinecraftServer;
import net.minecraftforge.common.DimensionManager;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import coint.player.TeamsManager;
import me.eigenraven.personalspace.world.DimensionConfig;

@Mixin(value = DimensionConfig.class, remap = false)
public abstract class MixinDimensionConfigRetiredIds {

    @Inject(method = "nextFreeDimId", at = @At("RETURN"), cancellable = true)
    private static void cointcore$skipRetiredIds(CallbackInfoReturnable<Integer> cir) {
        MinecraftServer server = MinecraftServer.getServer();
        if (server == null || server.worldServerForDimension(0) == null) {
            return;
        }

        TeamsManager manager = TeamsManager.get();
        int dimId = cir.getReturnValue();
        while (dimId < Integer.MAX_VALUE - 1
            && (manager.isRetiredPDim(dimId) || DimensionManager.isDimensionRegistered(dimId))) {
            dimId++;
        }
        cir.setReturnValue(dimId);
    }
}
