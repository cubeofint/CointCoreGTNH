package coint.mixin.minecraft;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import net.minecraft.command.ICommandSender;
import net.minecraft.command.server.CommandListPlayers;
import net.minecraft.util.ChatComponentText;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import coint.cluster.ClusterService;

/** /list also shows who plays on the other servers of the network, server by server. */
@Mixin(CommandListPlayers.class)
public abstract class MixinCommandListPlayersCluster {

    @Inject(method = "processCommand", at = @At("TAIL"))
    private void cointcore$listOtherServers(ICommandSender sender, String[] args, CallbackInfo ci) {
        Map<String, String> remote = ClusterService.remoteTab();
        if (remote.isEmpty()) {
            return;
        }
        Map<String, List<String>> byServer = new TreeMap<>();
        for (Map.Entry<String, String> e : remote.entrySet()) {
            byServer.computeIfAbsent(e.getValue(), k -> new ArrayList<>())
                .add(e.getKey());
        }
        for (Map.Entry<String, List<String>> e : byServer.entrySet()) {
            List<String> names = e.getValue();
            names.sort(String.CASE_INSENSITIVE_ORDER);
            sender.addChatMessage(
                new ChatComponentText(
                    "§6" + e.getKey() + " §7(" + names.size() + "): §f" + String.join("§7, §f", names)));
        }
    }
}
