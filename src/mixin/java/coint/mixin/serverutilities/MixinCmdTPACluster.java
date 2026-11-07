package coint.mixin.serverutilities;

import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import coint.cluster.ClusterTpa;
import serverutils.command.tp.CmdTPA;

/** /tpa to a player who is not here but on another server of the network. */
@Mixin(value = CmdTPA.class, remap = false)
public abstract class MixinCmdTPACluster {

    @Inject(method = "processCommand", at = @At("HEAD"), cancellable = true, remap = true)
    private void cointcore$tpaAcrossServers(ICommandSender sender, String[] args, CallbackInfo ci) {
        if (args.length < 1 || !(sender instanceof EntityPlayerMP player)) {
            return;
        }
        if (MinecraftServer.getServer()
            .getConfigurationManager()
            .func_152612_a(args[0]) != null) {
            return;
        }
        if (ClusterTpa.request(player, args[0])) {
            ci.cancel();
        }
    }
}
