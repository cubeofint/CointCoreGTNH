package coint.mixin.minecraft;

import net.minecraft.server.management.ServerConfigurationManager;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import coint.cluster.ClusterCompletion;

/**
 * The online names, while a Tab completion is being made, include the players of the other servers. Both chat
 * completion (which asks this list) and commands (which ask the server, which asks this list) get them.
 */
@Mixin(ServerConfigurationManager.class)
public abstract class MixinServerConfigurationManagerClusterNames {

    @Inject(method = "getAllUsernames", at = @At("RETURN"), cancellable = true)
    private void cointcore$networkNames(CallbackInfoReturnable<String[]> cir) {
        String[] names = ClusterCompletion.names(cir.getReturnValue());
        if (names != null) {
            cir.setReturnValue(names);
        }
    }
}
