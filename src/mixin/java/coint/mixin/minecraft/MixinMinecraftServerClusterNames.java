package coint.mixin.minecraft;

import java.util.List;

import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;

import org.spongepowered.asm.mixin.Mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import coint.cluster.ClusterCompletion;

/**
 * Marks the time a Tab completion is being made, so the online names asked for meanwhile include the players of the
 * other servers (see {@link MixinServerConfigurationManagerClusterNames}).
 */
@Mixin(MinecraftServer.class)
public abstract class MixinMinecraftServerClusterNames {

    @WrapMethod(method = "getPossibleCompletions")
    private List<String> cointcore$completion(ICommandSender sender, String input, Operation<List<String>> original) {
        ClusterCompletion.begin(input);
        try {
            return ClusterCompletion.complete(sender, input, original.call(sender, input));
        } finally {
            ClusterCompletion.end();
        }
    }
}
