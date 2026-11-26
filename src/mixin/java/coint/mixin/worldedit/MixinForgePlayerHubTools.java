package coint.mixin.worldedit;

import net.minecraft.entity.player.EntityPlayerMP;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import coint.integration.serverutilities.HubToolPermissions;
import cpw.mods.fml.common.eventhandler.Event;

/** WorldEdit's tools have their own provider, independent of SU's command gate. */
@Pseudo
@Mixin(targets = "com.sk89q.worldedit.forge.ForgePlayer", remap = false)
public abstract class MixinForgePlayerHubTools {

    @Shadow
    @Final
    private EntityPlayerMP player;

    @Inject(method = "hasPermission", at = @At("HEAD"), cancellable = true)
    private void cointcore$hubTools(String node, CallbackInfoReturnable<Boolean> cir) {
        Event.Result scoped = HubToolPermissions.resolve(player.getGameProfile(), node, true);
        if (scoped != Event.Result.DEFAULT) cir.setReturnValue(scoped == Event.Result.ALLOW);
    }
}
