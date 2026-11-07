package coint.mixin.serverutilities;

import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import coint.cluster.ClusterTpa;
import serverutils.command.tp.CmdTPAccept;

/** /tpaccept of a request that came from a player on another server of the network. */
@Mixin(value = CmdTPAccept.class, remap = false)
public abstract class MixinCmdTPAcceptCluster {

    @Inject(method = "processCommand", at = @At("HEAD"), cancellable = true, remap = true)
    private void cointcore$acceptAcrossServers(ICommandSender sender, String[] args, CallbackInfo ci) {
        if (args.length >= 1 && sender instanceof EntityPlayerMP player && ClusterTpa.accept(player, args[0])) {
            ci.cancel();
        }
    }
}
