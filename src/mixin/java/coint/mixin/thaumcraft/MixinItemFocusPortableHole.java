package coint.mixin.thaumcraft;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
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
import thaumcraft.api.ThaumcraftApi;
import thaumcraft.common.config.ConfigBlocks;
import thaumcraft.common.items.wands.ItemWandCasting;
import thaumcraft.common.items.wands.foci.ItemFocusPortableHole;

/**
 * Blocks Portable Hole casts that would touch any protected block in the full
 * affected path (forward line + 3x3 expansion used by TileHole propagation).
 */
@Mixin(value = ItemFocusPortableHole.class, remap = false)
public class MixinItemFocusPortableHole {

    @Inject(method = "onFocusRightClick", at = @At("HEAD"), cancellable = true, remap = false)
    private void cointcore$guardPortableHole(ItemStack itemstack, World world, EntityPlayer player,
        MovingObjectPosition mop, CallbackInfoReturnable<ItemStack> cir) {

        if (world == null || player == null || !ClaimedChunks.isActive()) {
            return;
        }

        if (mop == null || mop.typeOfHit != MovingObjectType.BLOCK) {
            return;
        }

        if (cointcore$hitsClaimedArea(itemstack, world, player, mop)) {
            ClaimGuardNotifier.notifyDenied(player);
            cir.setReturnValue(itemstack);
        }
    }

    @Inject(method = "createHole", at = @At("HEAD"), cancellable = true, remap = false)
    private static void cointcore$cancelClientGhostHole(World world, int ii, int jj, int kk, int side, byte count,
        int max, CallbackInfoReturnable<Boolean> cir) {
        if (world != null && world.isRemote) {
            // Prevent client-side speculative hole placement (visual ghost blocks).
            cir.setReturnValue(false);
        }
    }

    @Unique
    private static boolean cointcore$hitsClaimedArea(ItemStack itemstack, World world, EntityPlayer player,
        MovingObjectPosition mop) {
        int side = mop.sideHit;

        if (cointcore$isBlocked(player, mop.blockX, mop.blockY, mop.blockZ, side)) {
            return true;
        }

        if (!(itemstack.getItem() instanceof ItemWandCasting)) {
            return false;
        }

        // getFocusEnlarge expects the wand itemstack; the focus is resolved internally.
        int enlarge = ((ItemWandCasting) itemstack.getItem()).getFocusEnlarge(itemstack);
        int maxDistance = 33 + enlarge * 8;

        // Also check the AOE of the starting block (the 3x3 ring at mop position)
        if (cointcore$isBlockedAoE(player, mop.blockX, mop.blockY, mop.blockZ, side)) {
            return true;
        }

        int x = mop.blockX;
        int y = mop.blockY;
        int z = mop.blockZ;

        for (int step = 0; step < maxDistance; step++) {
            Block block = world.getBlock(x, y, z);
            if (cointcore$stopPortableHolePath(world, x, y, z, block)) {
                break;
            }

            if (cointcore$isBlocked(player, x, y, z, side) || cointcore$isBlockedAoE(player, x, y, z, side)) {
                return true;
            }

            switch (side) {
                case 0:
                    y++;
                    break;
                case 1:
                    y--;
                    break;
                case 2:
                    z++;
                    break;
                case 3:
                    z--;
                    break;
                case 4:
                    x++;
                    break;
                case 5:
                    x--;
                    break;
                default:
                    return false;
            }
        }

        return false;
    }

    @Unique
    private static boolean cointcore$stopPortableHolePath(World world, int x, int y, int z, Block block) {
        if (block == null) {
            return true;
        }
        if (ThaumcraftApi.portableHoleBlackList.contains(block)) {
            return true;
        }
        if (block == Blocks.bedrock || block == ConfigBlocks.blockHole) {
            return true;
        }
        if (block.isAir(world, x, y, z)) {
            return true;
        }
        return block.getBlockHardness(world, x, y, z) == -1.0F;
    }

    @Unique
    private static boolean cointcore$isBlockedAoE(EntityPlayer player, int x, int y, int z, int side) {
        if (side == 0 || side == 1) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (cointcore$isBlocked(player, x + dx, y, z + dz, side)) {
                        return true;
                    }
                }
            }
            return false;
        }

        if (side == 2 || side == 3) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    if (cointcore$isBlocked(player, x + dx, y + dy, z, side)) {
                        return true;
                    }
                }
            }
            return false;
        }

        for (int dy = -1; dy <= 1; dy++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (cointcore$isBlocked(player, x, y + dy, z + dz, side)) {
                    return true;
                }
            }
        }
        return false;
    }

    @Unique
    private static boolean cointcore$isBlocked(EntityPlayer player, int x, int y, int z, int side) {
        if (ClaimedChunks.blockBlockEditing(player, x, y, z, side)) {
            return true;
        }

        // Portable Hole mutates existing blocks (not just face placement).
        // Check all sides as a conservative fallback to avoid side-based bypasses.
        for (int s = 0; s < 6; s++) {
            if (s != side && ClaimedChunks.blockBlockEditing(player, x, y, z, s)) {
                return true;
            }
        }

        return false;
    }
}

