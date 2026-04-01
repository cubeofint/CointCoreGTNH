package coint.mixin.thaumcraft;

import java.util.ArrayList;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.MovingObjectPosition.MovingObjectType;
import net.minecraft.world.World;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import coint.util.ClaimGuardNotifier;
import serverutils.data.ClaimedChunks;
import thaumcraft.api.BlockCoordinates;
import thaumcraft.common.items.wands.foci.ItemFocusWarding;

/**
 * Blocks Focus Warding from replacing blocks in claimed chunks where the player
 * has no edit rights.
 */
@Mixin(value = ItemFocusWarding.class, remap = false)
public class MixinItemFocusWarding {

    @Inject(method = "onFocusRightClick", at = @At("HEAD"), cancellable = true, remap = false)
    private void cointcore$guardWardingFocus(ItemStack itemstack, World world, EntityPlayer player,
        MovingObjectPosition mop, CallbackInfoReturnable<ItemStack> cir) {
        if (world == null || world.isRemote || player == null || !ClaimedChunks.isActive()) {
            return;
        }
        if (mop == null || mop.typeOfHit != MovingObjectType.BLOCK) {
            return;
        }

        ItemFocusWarding self = (ItemFocusWarding) (Object) this;
        ArrayList<BlockCoordinates> blocks = self
            .getArchitectBlocks(itemstack, world, mop.blockX, mop.blockY, mop.blockZ, mop.sideHit, player);

        if (blocks == null || blocks.isEmpty()) {
            if (cointcore$isBlocked(player, mop.blockX, mop.blockY, mop.blockZ, mop.sideHit)) {
                cir.setReturnValue(itemstack);
            }
            return;
        }

        for (BlockCoordinates c : blocks) {
            if (cointcore$isBlocked(player, c.x, c.y, c.z, mop.sideHit)) {
                cir.setReturnValue(itemstack);
                return;
            }
        }
    }

    @Unique
    private static boolean cointcore$isBlocked(EntityPlayer player, int x, int y, int z, int side) {
        if (ClaimedChunks.blockBlockEditing(player, x, y, z, side)) {
            ClaimGuardNotifier.notifyDenied(player);
            return true;
        }
        return false;
    }
}
