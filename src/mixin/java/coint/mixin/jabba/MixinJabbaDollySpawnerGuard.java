package coint.mixin.jabba;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntityMobSpawner;
import net.minecraft.util.ChatComponentText;
import net.minecraft.world.World;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import coint.events.SpawnerGuard;
import mcp.mobius.betterbarrels.common.items.dolly.ItemBarrelMover;

/**
 * The JABBA diamond dolly can pick up mob spawners and put them down elsewhere (bypassing the block place event).
 * Keep vanilla spawners where they are, and refuse to unload a dolly that already carries one.
 */
@Mixin(value = ItemBarrelMover.class, remap = false)
public abstract class MixinJabbaDollySpawnerGuard {

    @Inject(method = "pickupContainer", at = @At("HEAD"), cancellable = true, require = 0)
    private void cointcore$keepVanillaSpawners(ItemStack stack, EntityPlayer player, World world, int x, int y, int z,
        CallbackInfoReturnable<Boolean> cir) {
        if (world.getTileEntity(x, y, z) instanceof TileEntityMobSpawner) {
            cointcore$deny(world, player, cir);
        }
    }

    @Inject(method = "placeContainer", at = @At("HEAD"), cancellable = true, require = 0)
    private void cointcore$noCarriedSpawnerPlacement(ItemStack stack, EntityPlayer player, World world, int x, int y,
        int z, int side, CallbackInfoReturnable<Boolean> cir) {
        NBTTagCompound tag = stack == null ? null : stack.getTagCompound();
        if (tag == null || !tag.hasKey("Container")) {
            return;
        }
        String teClass = tag.getCompoundTag("Container")
            .getString("TEClass");
        if (TileEntityMobSpawner.class.getName()
            .equals(teClass)) {
            cointcore$deny(world, player, cir);
        }
    }

    @Unique
    private static void cointcore$deny(World world, EntityPlayer player, CallbackInfoReturnable<Boolean> cir) {
        if (world.isRemote) {
            cir.setReturnValue(false);
            return;
        }
        if (SpawnerGuard.canHandleSpawners(player)) {
            return;
        }
        if (player != null) {
            player.addChatMessage(new ChatComponentText(SpawnerGuard.DENY_MESSAGE));
        }
        cir.setReturnValue(false);
    }
}
