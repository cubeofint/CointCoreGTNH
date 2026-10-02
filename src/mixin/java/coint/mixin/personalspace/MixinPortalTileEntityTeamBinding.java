package coint.mixin.personalspace;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.tileentity.TileEntity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import coint.integration.personalspace.PersonalSpaceBinding;
import me.eigenraven.personalspace.block.PortalTileEntity;
import me.eigenraven.personalspace.world.DimensionConfig;

@Mixin(value = PortalTileEntity.class, remap = false)
public abstract class MixinPortalTileEntityTeamBinding extends TileEntity {

    @Inject(method = "updateSettings", at = @At("TAIL"))
    private void cointcore$bindPersonalSpace(EntityPlayerMP player, DimensionConfig config, CallbackInfo ci) {
        PersonalSpaceBinding.bindFromCreator(player, (PortalTileEntity) (Object) this);
    }

    @Inject(method = "transport", at = @At("HEAD"))
    private void cointcore$backfillPersonalSpace(EntityPlayerMP player, CallbackInfo ci) {
        PersonalSpaceBinding.bindFromPortalClaim((PortalTileEntity) (Object) this);
    }
}
