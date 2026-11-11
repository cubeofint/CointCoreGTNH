package coint.mixin.minecraft;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import coint.cluster.ClusterService;

/**
 * Tab completion offers the players of the other servers of the network too, in every command and in chat. Only while
 * a completion is being made: everything else that asks for the online names still gets the players of this server.
 */
@Mixin(MinecraftServer.class)
public abstract class MixinMinecraftServerClusterNames {

    @Unique
    private static int cointcore$completing;

    @Inject(method = "getPossibleCompletions", at = @At("HEAD"))
    private void cointcore$completionStart(ICommandSender sender, String input,
        CallbackInfoReturnable<List<String>> cir) {
        cointcore$completing++;
    }

    @Inject(method = "getPossibleCompletions", at = @At("RETURN"))
    private void cointcore$completionEnd(ICommandSender sender, String input,
        CallbackInfoReturnable<List<String>> cir) {
        cointcore$completing = Math.max(0, cointcore$completing - 1);
    }

    @Inject(method = "getAllUsernames", at = @At("RETURN"), cancellable = true)
    private void cointcore$networkNames(CallbackInfoReturnable<String[]> cir) {
        if (cointcore$completing == 0 || ClusterService.remoteTab()
            .isEmpty()) {
            return;
        }
        Set<String> names = new LinkedHashSet<>();
        for (String name : cir.getReturnValue()) {
            names.add(name);
        }
        names.addAll(
            ClusterService.remoteTab()
                .keySet());
        cir.setReturnValue(names.toArray(new String[0]));
    }
}
