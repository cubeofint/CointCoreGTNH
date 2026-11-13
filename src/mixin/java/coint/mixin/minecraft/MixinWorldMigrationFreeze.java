package coint.mixin.minecraft;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import coint.migration.MigrationGuard;

@Mixin(World.class)
public abstract class MixinWorldMigrationFreeze {

    @Inject(method = "setBlock(IIILnet/minecraft/block/Block;II)Z", at = @At("HEAD"), cancellable = true)
    private void cointcore$freezeBlock(int x, int y, int z, net.minecraft.block.Block block, int meta, int flags,
        CallbackInfoReturnable<Boolean> cir) {
        if (MigrationGuard.block((World) (Object) this, x, z)) cir.setReturnValue(false);
    }

    @Inject(method = "setBlockMetadataWithNotify", at = @At("HEAD"), cancellable = true)
    private void cointcore$freezeMetadata(int x, int y, int z, int meta, int flags,
        CallbackInfoReturnable<Boolean> cir) {
        if (MigrationGuard.block((World) (Object) this, x, z)) cir.setReturnValue(false);
    }

    @Inject(method = "updateEntityWithOptionalForce", at = @At("HEAD"), cancellable = true)
    private void cointcore$freezeEntity(Entity entity, boolean force, CallbackInfo ci) {
        if (!(entity instanceof EntityPlayer) && MigrationGuard
            .block((World) (Object) this, (int) Math.floor(entity.posX), (int) Math.floor(entity.posZ))) ci.cancel();
    }

    @WrapOperation(
        method = "updateEntities",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/tileentity/TileEntity;updateEntity()V"))
    private void cointcore$freezeTile(TileEntity tile, Operation<Void> original) {
        if (!MigrationGuard.block(tile.getWorldObj(), tile.xCoord, tile.zCoord)) original.call(tile);
    }
}
