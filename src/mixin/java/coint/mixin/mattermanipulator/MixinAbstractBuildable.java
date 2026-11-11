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
        cointcore$denyIfClaimed(world, x, y, z, cir);
    }

    /** Newer MatterManipulator (0.1.38+): isEditable also says whether it is a placement. */
    @Dynamic("Target method exists in MatterManipulator 0.1.38+")
    @Inject(
        method = "isEditable(Lnet/minecraft/world/World;IIIZ)Z",
        at = @At("HEAD"),
        cancellable = true,
        remap = false,
        require = 0)
    private void cointcore$denyClaimedChunksPlacement(World world, int x, int y, int z, boolean isPlacement,
        CallbackInfoReturnable<Boolean> cir) {
        cointcore$denyIfClaimed(world, x, y, z, cir);
    }

    @Unique
    private void cointcore$denyIfClaimed(World world, int x, int y, int z, CallbackInfoReturnable<Boolean> cir) {
        if (world == null || world.isRemote || cointcore$player == null || !ClaimedChunks.isActive()) {
            return;
        }

        if (cointcore$isClaimDenied(cointcore$player, x, y, z)) {
            ClaimGuardNotifier.notifyDenied(cointcore$player);
            // Returning here skips the RETURN hook below, so the block log is switched back on now.
            coint.blocklog.BlockLog.skipping = false;
            cir.setReturnValue(false);
        }
    }

    /**
     * The events MM posts to ask whether it may edit a block are not the player's own work: the block log skips them.
     */
    @Inject(method = "isEditable", at = @At("HEAD"), remap = false, require = 0)
    private void cointcore$blockLogSkipStart(CallbackInfoReturnable<Boolean> cir) {
        coint.blocklog.BlockLog.skipping = true;
    }

    @Inject(method = "isEditable", at = @At("RETURN"), remap = false, require = 0)
    private void cointcore$blockLogSkipEnd(CallbackInfoReturnable<Boolean> cir) {
        coint.blocklog.BlockLog.skipping = false;
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
