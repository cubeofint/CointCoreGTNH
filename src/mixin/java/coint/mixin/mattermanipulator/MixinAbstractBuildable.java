package coint.mixin.mattermanipulator;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.world.World;

import org.spongepowered.asm.mixin.Dynamic;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.recursive_pineapple.matter_manipulator.common.building.AbstractBuildable;
import com.recursive_pineapple.matter_manipulator.common.items.manipulator.ItemMatterManipulator;
import com.recursive_pineapple.matter_manipulator.common.items.manipulator.MMState;

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
@Mixin(value = AbstractBuildable.class, remap = false)
public abstract class MixinAbstractBuildable {

    @Unique
    protected EntityPlayer cointcore$player;

    @Inject(method = "<init>", remap = false, at = @At("RETURN"))
    private void cointcore$AbstractBuildable(EntityPlayer player, MMState state,
        ItemMatterManipulator.ManipulatorTier tier, CallbackInfo ci) {
        cointcore$player = player;
    }

    @Dynamic("Target method exists in MatterManipulator and is not available in this compile classpath")
    @Inject(
        method = "isEditable(Lnet/minecraft/world/World;III)Z",
        at = @At("HEAD"),
        cancellable = true,
        remap = false,
        require = 0)
    private void cointcore$denyClaimedChunks(World world, int x, int y, int z, CallbackInfoReturnable<Boolean> cir) {
        if (world == null || world.isRemote || cointcore$player == null || !ClaimedChunks.isActive()) {
            return;
        }

        if (cointcore$isClaimDenied(cointcore$player, x, y, z)) {
            ClaimGuardNotifier.notifyDenied(cointcore$player);
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
