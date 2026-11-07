package coint.mixin.appliedenergistics2;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.world.World;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import appeng.core.sync.GuiBridge;
import coint.cluster.RemoteMe;

/**
 * AE2's terminal window opened onto a network on another server gets our container, which shows that network's items
 * and sends the clicks through the cluster. The client opens AE2's window as always.
 */
@Mixin(value = GuiBridge.class, remap = false)
public abstract class MixinGuiBridgeRemote {

    @Inject(method = "getServerGuiElement", at = @At("HEAD"), cancellable = true)
    private void cointcore$remoteContainer(int ordinal, EntityPlayer player, World w, int x, int y, int z,
        CallbackInfoReturnable<Object> cir) {
        Object container = RemoteMe.nativeContainer(player, ordinal);
        if (container != null) {
            cir.setReturnValue(container == RemoteMe.BLOCK ? null : container);
        }
    }
}
