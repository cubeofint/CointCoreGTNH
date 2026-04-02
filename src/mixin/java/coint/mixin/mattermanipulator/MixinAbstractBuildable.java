package coint.mixin.mattermanipulator;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.world.World;

import org.spongepowered.asm.mixin.Dynamic;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import coint.util.ClaimGuardNotifier;
import serverutils.data.ClaimedChunks;

/**
 * Integrates MatterManipulator block-edit checks with ServerUtilities claims.
 *
 * <p>
 * This hooks the central MM edit gate so both build and move pipelines are
 * blocked consistently in foreign claims.
 */
@Pseudo
@Mixin(targets = "com.recursive_pineapple.matter_manipulator.common.building.AbstractBuildable", remap = false)
public abstract class MixinAbstractBuildable {

    @Shadow(remap = false)
    protected EntityPlayer player;

    @Dynamic("Target method exists in MatterManipulator and is not available in this compile classpath")
    @Inject(
        method = "isEditable(Lnet/minecraft/world/World;III)Z",
        at = @At("HEAD"),
        cancellable = true,
        remap = false,
        require = 0)
    private void cointcore$denyClaimedChunks(World world, int x, int y, int z, CallbackInfoReturnable<Boolean> cir) {
        if (world == null || world.isRemote || player == null || !ClaimedChunks.isActive()) {
            return;
        }

        if (cointcore$isClaimDenied(player, x, y, z)) {
            ClaimGuardNotifier.notifyDenied(player);
            cir.setReturnValue(false);
        }
    }

    @Unique
    private static boolean cointcore$isClaimDenied(EntityPlayer player, int x, int y, int z) {
        for (int side = 0; side < 6; side++) {
            if (ClaimedChunks.blockBlockEditing(player, x, y, z, side)) {
                return true;
            }
        }
        return false;
    }
}
